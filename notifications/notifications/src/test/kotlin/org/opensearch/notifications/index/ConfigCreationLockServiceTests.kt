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

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opensearch.action.DocWriteResponse
import org.opensearch.action.delete.DeleteRequest
import org.opensearch.action.delete.DeleteResponse
import org.opensearch.action.get.GetResponse
import org.opensearch.action.index.IndexRequest
import org.opensearch.action.index.IndexResponse
import org.opensearch.action.support.replication.ReplicationResponse
import org.opensearch.cluster.ClusterState
import org.opensearch.cluster.routing.RoutingTable
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.settings.Settings
import org.opensearch.common.util.concurrent.ThreadContext
import org.opensearch.core.action.ActionListener
import org.opensearch.core.common.bytes.BytesArray
import org.opensearch.core.index.shard.ShardId
import org.opensearch.index.engine.VersionConflictEngineException
import org.opensearch.index.get.GetResult
import org.opensearch.index.seqno.SequenceNumbers.UNASSIGNED_PRIMARY_TERM
import org.opensearch.index.seqno.SequenceNumbers.UNASSIGNED_SEQ_NO
import org.opensearch.threadpool.ThreadPool
import org.opensearch.transport.client.Client
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ownership rules of the config-creation mutex, driven against a fake lock index that reproduces
 * the two OpenSearch behaviours the service relies on: an [IndexRequest] with `create(true)` fails
 * while the document exists, and a [DeleteRequest] carrying `if_seq_no`/`if_primary_term` fails
 * unless those still match the stored revision.
 *
 * The interleavings that matter here -- a live holder whose lock is stolen from underneath it, a
 * stale lock that changes hands halfway through a steal -- depend on timing that cannot be produced
 * by hand against a running cluster, so they are staged with a fake store instead.
 */
internal class ConfigCreationLockServiceTests {
    private companion object {
        /** Document id and field name the service writes; part of the on-disk contract of the lock index. */
        const val LOCK_DOC_ID = "notification-config-creation"
        const val ACQUIRED_AT_FIELD = "acquired_at"
        const val PRIMARY_TERM = 1L

        /** Comfortably past the service's stale threshold, so staleness is never the variable under test. */
        const val CLEARLY_STALE_AGE_MS = 300_000L
    }

    /** The single lock document, with the revision bookkeeping the conditional deletes turn on. */
    private data class StoredLock(val acquiredAt: Long, val seqNo: Long, val primaryTerm: Long = PRIMARY_TERM)

    private val shardId = ShardId(ConfigCreationLockService.INDEX_NAME, "_na_", 0)

    private var stored: StoredLock? = null
    private var nextSeqNo = 0L

    /**
     * When set, the next get() reports the document as it is and then replaces it with a fresh
     * revision, standing in for a lock released and re-acquired between another caller's staleness
     * read and the delete that acts on it.
     */
    private var replaceLockAfterNextGet = false

    @BeforeEach
    fun setup() {
        stored = null
        nextSeqNo = 0L
        replaceLockAfterNextGet = false
        ConfigCreationLockService.initialize(fakeClient(), fakeClusterService())
    }

    @Test
    fun `test acquire returns a handle for the document it created and release deletes it`() {
        runBlocking {
            val handle = ConfigCreationLockService.acquire()

            assertEquals(stored?.seqNo, handle.seqNo, "the handle must identify the document acquire created")
            assertEquals(stored?.primaryTerm, handle.primaryTerm)

            ConfigCreationLockService.release(handle)
            assertNull(stored, "release must delete the lock it owns")
        }
    }

    @Test
    fun `test release leaves alone a lock another caller acquired after ours was stolen`() {
        runBlocking {
            val holder = ConfigCreationLockService.acquire()

            // The holder is alive, but its critical section has outlived the stale threshold, so the
            // next caller is entitled to assume it crashed and take the lock.
            stored = stored?.copy(acquiredAt = 0L)

            val stealer = ConfigCreationLockService.acquire()
            assertNotEquals(holder.seqNo, stealer.seqNo, "the stealer must hold a different lock document")
            assertEquals(stealer.seqNo, stored?.seqNo)

            // The original holder finishes and releases. Unconditionally, this deleted the stealer's
            // lock and let a third caller into the critical section alongside it.
            ConfigCreationLockService.release(holder)
            assertEquals(stealer.seqNo, stored?.seqNo, "the stale holder must not delete a lock it no longer owns")

            ConfigCreationLockService.release(stealer)
            assertNull(stored, "the current owner must still be able to release its own lock")
        }
    }

    @Test
    fun `test acquire steals a lock left behind past the stale threshold`() {
        runBlocking {
            val orphan = seedLockAged(CLEARLY_STALE_AGE_MS)

            val handle = ConfigCreationLockService.acquire()

            assertNotEquals(orphan.seqNo, handle.seqNo, "the orphaned lock must be replaced, not adopted")
            assertEquals(handle.seqNo, stored?.seqNo)
        }
    }

    @Test
    fun `test acquire gives up rather than steal a lock still within the stale threshold`() {
        runBlocking {
            val holder = seedLockAged(0L)

            assertThrows<IllegalStateException> { ConfigCreationLockService.acquire() }

            assertEquals(holder.seqNo, stored?.seqNo, "a lock in use must survive a failed acquire")
        }
    }

    @Test
    fun `test a stale lock that changes hands mid steal is not stolen from its new owner`() {
        runBlocking {
            seedLockAged(CLEARLY_STALE_AGE_MS)
            // The staleness read sees the orphan, but by the time the steal deletes, the lock has been
            // released and re-acquired by somebody else, so the conditional delete must miss.
            replaceLockAfterNextGet = true

            assertThrows<IllegalStateException> { ConfigCreationLockService.acquire() }

            val survivor = assertNotNull(stored, "the lock acquired mid-steal must not be deleted")
            assertTrue(
                Instant.now().toEpochMilli() - survivor.acquiredAt < CLEARLY_STALE_AGE_MS,
                "the surviving lock must be the fresh one rather than the orphan"
            )
        }
    }

    /** Puts a lock document in place as though it had been acquired [ageMs] ago. */
    private fun seedLockAged(ageMs: Long): StoredLock {
        val lock = StoredLock(Instant.now().toEpochMilli() - ageMs, nextSeqNo++)
        stored = lock
        return lock
    }

    private fun fakeClusterService(): ClusterService {
        val routingTable = mockk<RoutingTable>()
        every { routingTable.hasIndex(any<String>()) } returns true
        val clusterState = mockk<ClusterState>()
        every { clusterState.routingTable } returns routingTable
        val clusterService = mockk<ClusterService>()
        every { clusterService.state() } returns clusterState
        return clusterService
    }

    @Suppress("LongMethod")
    private fun fakeClient(): Client {
        val threadPool = mockk<ThreadPool>()
        every { threadPool.threadContext } returns ThreadContext(Settings.EMPTY)
        val client = mockk<Client>()
        every { client.threadPool() } returns threadPool

        // create(true) semantics: exactly one caller can put the document in place.
        every { client.index(any(), any<ActionListener<IndexResponse>>()) } answers {
            val listener = secondArg<ActionListener<IndexResponse>>()
            if (stored != null) {
                listener.onFailure(VersionConflictEngineException(shardId, LOCK_DOC_ID, "document already exists"))
            } else {
                val acquiredAt = (firstArg<IndexRequest>().sourceAsMap()[ACQUIRED_AT_FIELD] as Number).toLong()
                val lock = StoredLock(acquiredAt, nextSeqNo++)
                stored = lock
                listener.onResponse(
                    IndexResponse(shardId, LOCK_DOC_ID, lock.seqNo, lock.primaryTerm, 1L, true).withShardInfo()
                )
            }
        }

        every { client.get(any(), any<ActionListener<GetResponse>>()) } answers {
            val listener = secondArg<ActionListener<GetResponse>>()
            val read = stored
            if (replaceLockAfterNextGet) {
                replaceLockAfterNextGet = false
                stored = StoredLock(Instant.now().toEpochMilli(), nextSeqNo++)
            }
            listener.onResponse(getResponseOf(read))
        }

        // if_seq_no/if_primary_term semantics: the delete only lands on the revision it was aimed at.
        every { client.delete(any(), any<ActionListener<DeleteResponse>>()) } answers {
            val request = firstArg<DeleteRequest>()
            val listener = secondArg<ActionListener<DeleteResponse>>()
            val current = stored
            val aimedAtRevision = request.ifSeqNo() != UNASSIGNED_SEQ_NO
            val revisionMatches = current != null &&
                request.ifSeqNo() == current.seqNo &&
                request.ifPrimaryTerm() == current.primaryTerm
            if (aimedAtRevision && !revisionMatches) {
                listener.onFailure(VersionConflictEngineException(shardId, LOCK_DOC_ID, "revision no longer matches"))
            } else {
                stored = null
                listener.onResponse(
                    DeleteResponse(shardId, LOCK_DOC_ID, nextSeqNo++, PRIMARY_TERM, 2L, current != null).withShardInfo()
                )
            }
        }
        return client
    }

    /**
     * Fills in the replication shard info every real write response carries. Without it the
     * service's own debug logging of the response throws while rendering it.
     */
    private fun <T : DocWriteResponse> T.withShardInfo(): T = apply {
        shardInfo = ReplicationResponse.ShardInfo(1, 1)
    }

    private fun getResponseOf(lock: StoredLock?): GetResponse {
        val result = if (lock == null) {
            GetResult(
                ConfigCreationLockService.INDEX_NAME,
                LOCK_DOC_ID,
                UNASSIGNED_SEQ_NO,
                UNASSIGNED_PRIMARY_TERM,
                -1L,
                false,
                null,
                null,
                null
            )
        } else {
            GetResult(
                ConfigCreationLockService.INDEX_NAME,
                LOCK_DOC_ID,
                lock.seqNo,
                lock.primaryTerm,
                1L,
                true,
                BytesArray("""{"$ACQUIRED_AT_FIELD":${lock.acquiredAt}}"""),
                null,
                null
            )
        }
        return GetResponse(result)
    }
}
