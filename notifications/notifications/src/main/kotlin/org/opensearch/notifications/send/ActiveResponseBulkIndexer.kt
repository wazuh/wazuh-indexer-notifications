/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.notifications.send

import org.opensearch.action.bulk.BulkProcessor
import org.opensearch.action.bulk.BulkRequest
import org.opensearch.action.bulk.BulkResponse
import org.opensearch.action.index.IndexRequest
import org.opensearch.common.unit.TimeValue
import org.opensearch.commons.utils.logger
import org.opensearch.notifications.NotificationPlugin.Companion.LOG_PREFIX
import org.opensearch.notifications.metrics.Metrics
import org.opensearch.notifications.util.SecureIndexClient
import org.opensearch.transport.client.Client
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * Accumulates Active Response [IndexRequest]s and flushes them as a single [BulkRequest]
 * either when [flushIntervalMs] elapses (timeout-based) or [maxActions] is reached.
 *
 * Uses the explicit-scheduler [BulkProcessor.builder] overload, passing the plugin's
 * own [org.opensearch.threadpool.ThreadPool] as the flush/retry scheduler. The simpler
 * `builder(Client, Listener)` overload spawns an unmanaged scheduler thread that is
 * blocked by the OpenSearch SecurityManager, causing the flush timer to silently
 * never fire. The [SecureIndexClient] wrapper ensures each bulk request runs without
 * an inherited security context, consistent with all other direct-index operations
 * in this plugin.
 */
internal class ActiveResponseBulkIndexer(
    client: Client,
    flushIntervalMs: Long,
    maxActions: Int
) : Closeable {

    private val log by logger(ActiveResponseBulkIndexer::class.java)

    private val bulkProcessor: BulkProcessor = BulkProcessor.builder(
        SecureIndexClient(client),
        BulkListener(),
        client.threadPool(),
        client.threadPool(),
        Runnable { /* no-op: BulkProcessor close hook */ }
    )
        .setFlushInterval(TimeValue.timeValueMillis(flushIntervalMs))
        .setBulkActions(maxActions)
        .setConcurrentRequests(1)
        .build()

    fun add(request: IndexRequest) = bulkProcessor.add(request)

    override fun close() {
        bulkProcessor.awaitClose(10, TimeUnit.SECONDS)
    }

    private inner class BulkListener : BulkProcessor.Listener {
        override fun beforeBulk(executionId: Long, request: BulkRequest) {
            log.debug("$LOG_PREFIX:ActiveResponseBulkIndexer flushing ${request.numberOfActions()} action(s) (executionId=$executionId)")
        }

        override fun afterBulk(executionId: Long, request: BulkRequest, response: BulkResponse) {
            if (response.hasFailures()) {
                val failedItems = response.items.filter { it.isFailed }
                log.error("$LOG_PREFIX:ActiveResponseBulkIndexer ${failedItems.size}/${request.numberOfActions()} item(s) failed (executionId=$executionId): ${response.buildFailureMessage()}")
                repeat(failedItems.size) {
                    Metrics.NOTIFICATIONS_MESSAGE_DESTINATION_ACTIVE_RESPONSE_BULK_FAILED.counter.increment()
                }
            } else {
                log.debug("$LOG_PREFIX:ActiveResponseBulkIndexer all ${request.numberOfActions()} item(s) succeeded (executionId=$executionId)")
            }
        }

        override fun afterBulk(executionId: Long, request: BulkRequest, failure: Throwable) {
            val count = request.numberOfActions()
            log.error("$LOG_PREFIX:ActiveResponseBulkIndexer bulk dispatch failed for $count item(s) (executionId=$executionId)", failure)
            repeat(count) {
                Metrics.NOTIFICATIONS_MESSAGE_DESTINATION_ACTIVE_RESPONSE_BULK_FAILED.counter.increment()
            }
        }
    }
}
