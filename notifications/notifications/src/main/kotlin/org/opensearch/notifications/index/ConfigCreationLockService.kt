/*
 * Copyright (C) 2026, Wazuh Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.opensearch.notifications.index

import kotlinx.coroutines.delay
import org.opensearch.ResourceAlreadyExistsException
import org.opensearch.action.admin.indices.create.CreateIndexRequest
import org.opensearch.action.admin.indices.create.CreateIndexResponse
import org.opensearch.action.delete.DeleteRequest
import org.opensearch.action.delete.DeleteResponse
import org.opensearch.action.get.GetRequest
import org.opensearch.action.get.GetResponse
import org.opensearch.action.index.IndexRequest
import org.opensearch.action.index.IndexResponse
import org.opensearch.action.support.WriteRequest
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.xcontent.XContentHelper
import org.opensearch.common.xcontent.XContentType
import org.opensearch.commons.utils.logger
import org.opensearch.index.engine.VersionConflictEngineException
import org.opensearch.index.seqno.SequenceNumbers.UNASSIGNED_PRIMARY_TERM
import org.opensearch.index.seqno.SequenceNumbers.UNASSIGNED_SEQ_NO
import org.opensearch.notifications.NotificationPlugin.Companion.LOG_PREFIX
import org.opensearch.notifications.settings.PluginSettings
import org.opensearch.notifications.util.SecureIndexClient
import org.opensearch.notifications.util.SuspendUtils.Companion.suspendUntilTimeout
import org.opensearch.transport.client.Client
import java.time.Instant

/**
 * Serializes the notification-config-creation limit check-then-act sequence (count existing
 * configs, then create if under the configured max) with a single short-lived mutex document.
 *
 * A single global lock is used -- rather than one per config type -- because
 * [PluginSettings.maxNotificationConfigs] is a total cap spanning every config type, so any two
 * concurrent creates (regardless of type) must be serialized to keep that count consistent. The
 * type-specific limits (groups, senders, active responses) are checked while the same lock is
 * held, so they are covered for free.
 *
 * The mutex is a document with a fixed ID, created via [IndexRequest.create] so only one caller
 * can hold it at a time -- the same atomic-guard technique used by [DefaultChannelInitializer] to
 * create default channels idempotently. The resource counts themselves remain live search
 * queries; the lock only prevents two requests from evaluating those counts concurrently with a
 * create.
 */
internal object ConfigCreationLockService {
    private val log by logger(ConfigCreationLockService::class.java)

    const val INDEX_NAME = ".opensearch-notifications-config-locks"
    private const val MAPPING_FILE_NAME = "notifications-config-locks-mapping.yml"
    private const val SETTINGS_FILE_NAME = "notifications-config-locks-settings.yml"
    private const val LOCK_ID = "notification-config-creation"
    private const val ACQUIRED_AT_FIELD = "acquired_at"
    private const val MAX_ACQUIRE_RETRIES = 20
    private const val ACQUIRE_RETRY_BACKOFF_MS = 100L
    private const val STALE_THRESHOLD_MS = 30_000L

    private lateinit var client: Client
    private lateinit var clusterService: ClusterService

    /**
     * Identifies the exact lock document revision a caller created, so [release] can only delete
     * its own lock.
     *
     * [seqNo] and [primaryTerm] are the document's optimistic-concurrency coordinates. A lock that
     * [stealIfStale] removed and another caller then re-acquired is a different revision, so the
     * original holder's conditional delete is rejected instead of silently unlocking the new
     * owner's critical section.
     */
    data class LockHandle(val seqNo: Long, val primaryTerm: Long)

    /**
     * Initializes the service with the client and cluster service used for lock-index operations.
     */
    fun initialize(client: Client, clusterService: ClusterService) {
        ConfigCreationLockService.client = SecureIndexClient(client)
        ConfigCreationLockService.clusterService = clusterService
    }

    /**
     * Acquires the global notification-config-creation mutex, blocking (with bounded retries)
     * until it becomes available.
     *
     * @return the handle identifying the acquired lock document, which must be passed to [release].
     * @throws IllegalStateException if the lock could not be acquired after [MAX_ACQUIRE_RETRIES] attempts.
     */
    suspend fun acquire(): LockHandle {
        ensureIndexExists()
        for (attempt in 1..MAX_ACQUIRE_RETRIES) {
            try {
                val request = IndexRequest(INDEX_NAME)
                    .id(LOCK_ID)
                    .source(mapOf(ACQUIRED_AT_FIELD to Instant.now().toEpochMilli()))
                    .create(true)
                    .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                val response: IndexResponse = client.suspendUntilTimeout(PluginSettings.operationTimeoutMs) {
                    index(request, it)
                }
                log.debug("$LOG_PREFIX:Acquired notification-config-creation lock: $response")
                return LockHandle(response.seqNo, response.primaryTerm)
            } catch (e: VersionConflictEngineException) {
                if (stealIfStale()) {
                    continue
                }
                delay(ACQUIRE_RETRY_BACKOFF_MS)
            }
        }
        throw IllegalStateException("$LOG_PREFIX:Timed out waiting for the notification-config-creation lock.")
    }

    /**
     * Releases the lock previously taken by [acquire].
     *
     * The delete is conditioned on [handle], so it only ever removes the lock document this caller
     * created. Without that condition a caller whose lock had been stolen by [stealIfStale] --
     * which happens to a live holder whenever its critical section outlives [STALE_THRESHOLD_MS] --
     * would delete the lock another caller has since acquired, leaving two callers inside the
     * critical section and defeating the serialization altogether.
     *
     * Failures are logged and swallowed so a release problem never surfaces as a config-creation
     * failure; a lock older than [STALE_THRESHOLD_MS] is stolen by the next caller regardless.
     */
    suspend fun release(handle: LockHandle) {
        try {
            val request = DeleteRequest(INDEX_NAME, LOCK_ID)
                .setIfSeqNo(handle.seqNo)
                .setIfPrimaryTerm(handle.primaryTerm)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
            val response: DeleteResponse = client.suspendUntilTimeout(PluginSettings.operationTimeoutMs) {
                delete(request, it)
            }
            log.debug("$LOG_PREFIX:Released notification-config-creation lock: $response")
        } catch (e: VersionConflictEngineException) {
            // Our lock was stolen, or stolen and re-acquired, while we were still running. The
            // document now present is not ours to delete, so leave it to its owner.
            log.warn(
                "$LOG_PREFIX:Skipped releasing the notification-config-creation lock, it is no longer the one " +
                    "we acquired: ${e.message}"
            )
        } catch (e: Exception) {
            log.warn("$LOG_PREFIX:Failed to release notification-config-creation lock: ${e.message}")
        }
    }

    /**
     * Deletes the lock document if it was acquired more than [STALE_THRESHOLD_MS] ago, guarding
     * against a lock orphaned by a crashed node.
     *
     * The delete is conditioned on the revision the staleness decision was made on, so a lock that
     * was released and re-acquired between the read and the delete is left to its new owner rather
     * than being stolen from it on the strength of the previous holder's timestamp.
     *
     * @return true when the lock is known to be free and the caller should retry at once -- we
     * stole it, or it had already been released. False to back off first, which includes losing the
     * race to a caller that acquired the lock while we were deciding to steal it: whoever holds it
     * now holds it with a fresh timestamp, so retrying immediately could only conflict again.
     */
    private suspend fun stealIfStale(): Boolean {
        return try {
            val response: GetResponse = client.suspendUntilTimeout(PluginSettings.operationTimeoutMs) {
                get(GetRequest(INDEX_NAME, LOCK_ID), it)
            }
            if (!response.isExists) {
                // Released concurrently between our failed acquire and this check; retry immediately.
                return true
            }
            val acquiredAt = (response.sourceAsMap?.get(ACQUIRED_AT_FIELD) as? Number)?.toLong() ?: 0L
            if (Instant.now().toEpochMilli() - acquiredAt <= STALE_THRESHOLD_MS) {
                return false
            }
            if (response.seqNo == UNASSIGNED_SEQ_NO || response.primaryTerm == UNASSIGNED_PRIMARY_TERM) {
                // Without the document's revision the delete cannot be made conditional, and an
                // unconditional one risks dropping a lock that has since changed hands.
                log.warn("$LOG_PREFIX:Stale notification-config-creation lock carries no revision info, not stealing.")
                return false
            }
            log.warn("$LOG_PREFIX:Stealing stale notification-config-creation lock.")
            val deleteRequest = DeleteRequest(INDEX_NAME, LOCK_ID)
                .setIfSeqNo(response.seqNo)
                .setIfPrimaryTerm(response.primaryTerm)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
            val deleteResponse: DeleteResponse = client.suspendUntilTimeout(PluginSettings.operationTimeoutMs) {
                delete(deleteRequest, it)
            }
            log.debug("$LOG_PREFIX:Stole stale notification-config-creation lock: $deleteResponse")
            true
        } catch (e: VersionConflictEngineException) {
            // The revision we aimed at is gone: the lock was released and re-acquired between the
            // staleness read and the delete, so it is not the orphan we decided to steal. Back off
            // instead of retrying at once, since its new holder's timestamp is fresh.
            log.debug("$LOG_PREFIX:Stale-lock steal skipped, the lock changed concurrently: ${e.message}")
            false
        } catch (e: Exception) {
            log.warn("$LOG_PREFIX:Failed to check staleness of notification-config-creation lock: ${e.message}")
            false
        }
    }

    private fun isIndexExists(): Boolean {
        return clusterService.state().routingTable.hasIndex(INDEX_NAME)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun ensureIndexExists() {
        if (isIndexExists()) {
            return
        }
        val classLoader = ConfigCreationLockService::class.java.classLoader
        val mapping = XContentHelper.convertToMap(
            XContentType.YAML.xContent(),
            classLoader.getResource(MAPPING_FILE_NAME)?.readText()!!,
            false
        )
        val indexSettingsSource = classLoader.getResource(SETTINGS_FILE_NAME)?.readText()!!
        val request = CreateIndexRequest(INDEX_NAME)
            .mapping(mapping)
            .settings(indexSettingsSource, XContentType.YAML)
        val storedContext = client.threadPool().threadContext.stashContext()
        try {
            val response: CreateIndexResponse = client.suspendUntilTimeout(PluginSettings.operationTimeoutMs) {
                admin().indices().create(request, it)
            }
            if (!response.isAcknowledged) {
                log.warn("$LOG_PREFIX:Index $INDEX_NAME creation not Acknowledged")
            }
        } catch (exception: Exception) {
            if (exception !is ResourceAlreadyExistsException && exception.cause !is ResourceAlreadyExistsException) {
                throw exception
            }
        } finally {
            storedContext.close()
        }
    }
}
