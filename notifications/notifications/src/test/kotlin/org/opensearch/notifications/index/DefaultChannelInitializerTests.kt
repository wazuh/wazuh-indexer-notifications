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

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.opensearch.OpenSearchStatusException
import org.opensearch.common.settings.Settings
import org.opensearch.common.util.concurrent.ThreadContext
import org.opensearch.commons.ConfigConstants.OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT
import org.opensearch.commons.notifications.model.ConfigType
import org.opensearch.core.index.shard.ShardId
import org.opensearch.core.rest.RestStatus
import org.opensearch.index.engine.VersionConflictEngineException
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultChannelInitializerTests {

    @Test
    fun `test default channels are defined with correct IDs`() {
        val ids = DefaultChannelInitializer.DEFAULT_CHANNELS.map { it.id }
        assertTrue(ids.contains("default_slack_channel"), "Missing default_slack_channel")
        assertTrue(ids.contains("default_jira_channel"), "Missing default_jira_channel")
        assertTrue(ids.contains("default_pagerduty_channel"), "Missing default_pagerduty_channel")
        assertTrue(ids.contains("default_shuffle_channel"), "Missing default_shuffle_channel")
        assertEquals(4, ids.size, "Expected exactly 4 default channels")
    }

    @Test
    fun `test default channels have unique IDs`() {
        val ids = DefaultChannelInitializer.DEFAULT_CHANNELS.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "Default channel IDs must be unique")
    }

    @Test
    fun `test all default channels are disabled`() {
        DefaultChannelInitializer.DEFAULT_CHANNELS.forEach { channel ->
            assertFalse(channel.config.isEnabled, "Channel ${channel.id} should be disabled by default")
        }
    }

    @Test
    fun `test slack channel has correct config type`() {
        val slack = DefaultChannelInitializer.DEFAULT_CHANNELS.first { it.id == "default_slack_channel" }
        assertEquals(ConfigType.SLACK, slack.config.configType, "Slack channel should have SLACK config type")
    }

    @Test
    fun `test webhook channels have correct config type`() {
        val webhookIds = listOf("default_jira_channel", "default_pagerduty_channel", "default_shuffle_channel")
        webhookIds.forEach { id ->
            val channel = DefaultChannelInitializer.DEFAULT_CHANNELS.first { it.id == id }
            assertEquals(ConfigType.WEBHOOK, channel.config.configType, "$id should have WEBHOOK config type")
        }
    }

    @Test
    fun `test all default channels have non-empty names`() {
        DefaultChannelInitializer.DEFAULT_CHANNELS.forEach { channel ->
            assertTrue(channel.config.name.isNotEmpty(), "Channel ${channel.id} should have a non-empty name")
        }
    }

    @Test
    fun `test all default channels have non-empty descriptions`() {
        DefaultChannelInitializer.DEFAULT_CHANNELS.forEach { channel ->
            assertTrue(
                channel.config.description.isNotEmpty(),
                "Channel ${channel.id} should have a non-empty description"
            )
        }
    }

    @Test
    fun `test all default channels have config data`() {
        DefaultChannelInitializer.DEFAULT_CHANNELS.forEach { channel ->
            assertTrue(
                channel.config.configData != null,
                "Channel ${channel.id} should have config data"
            )
        }
    }

    @Test
    fun `test initialization does not run as the user left on the thread`() {
        val threadContext = ThreadContext(Settings.EMPTY)
        threadContext.putTransient(OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT, STALE_USER)

        runBlocking(DefaultChannelInitializer.StashedThreadContext(threadContext)) {
            assertNull(threadContext.getTransient<String>(OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT))
        }
        assertEquals(STALE_USER, threadContext.getTransient(OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT))
    }

    @Test
    fun `test initialization context does not leak when the coroutine changes threads`() {
        val threadContext = ThreadContext(Settings.EMPTY)
        val first = Executors.newSingleThreadExecutor()
        val second = Executors.newSingleThreadExecutor()
        try {
            first.submit { threadContext.putTransient(OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT, STALE_USER) }.get()
            val seen = mutableListOf<String?>()
            runBlocking {
                withContext(first.asCoroutineDispatcher() + DefaultChannelInitializer.StashedThreadContext(threadContext)) {
                    seen += threadContext.getTransient<String>(OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT)
                    withContext(second.asCoroutineDispatcher()) {
                        seen += threadContext.getTransient<String>(OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT)
                    }
                    seen += threadContext.getTransient<String>(OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT)
                }
            }
            assertEquals(listOf<String?>(null, null, null), seen)
            assertEquals(
                STALE_USER,
                first.submit<String?> { threadContext.getTransient(OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT) }.get()
            )
            assertNull(second.submit<String?> { threadContext.getTransient(OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT) }.get())
        } finally {
            first.shutdownNow()
            second.shutdownNow()
        }
    }

    @Test
    fun `test conflict reported by the metadata SDK counts as already existing`() {
        val sdkConflict = OpenSearchStatusException(
            "Document version conflict putting default_slack_channel in index .opensearch-notifications-config",
            RestStatus.CONFLICT
        )
        assertTrue(DefaultChannelInitializer.isDocumentAlreadyExists(sdkConflict))
    }

    @Test
    fun `test engine version conflict counts as already existing`() {
        val engineConflict = VersionConflictEngineException(
            ShardId(".opensearch-notifications-config", "_na_", 0),
            "default_slack_channel",
            "document already exists (current version [1])"
        )
        assertTrue(DefaultChannelInitializer.isDocumentAlreadyExists(RuntimeException(engineConflict)))
    }

    @Test
    fun `test other failures do not count as already existing`() {
        assertFalse(
            DefaultChannelInitializer.isDocumentAlreadyExists(
                OpenSearchStatusException("no permissions", RestStatus.FORBIDDEN)
            )
        )
        assertFalse(DefaultChannelInitializer.isDocumentAlreadyExists(IllegalStateException("boom")))
    }

    private companion object {
        const val STALE_USER = "kibanaserver||"
    }
}
