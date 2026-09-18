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
package org.opensearch.notifications.send

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.opensearch.OpenSearchStatusException
import org.opensearch.action.get.GetRequest
import org.opensearch.action.get.GetResponse
import org.opensearch.action.index.IndexRequest
import org.opensearch.common.action.ActionFuture
import org.opensearch.common.settings.Settings
import org.opensearch.common.util.concurrent.ThreadContext
import org.opensearch.commons.notifications.action.SendNotificationRequest
import org.opensearch.commons.notifications.action.SendNotificationResponse
import org.opensearch.commons.notifications.model.ActiveResponse
import org.opensearch.commons.notifications.model.ChannelMessage
import org.opensearch.commons.notifications.model.ConfigType
import org.opensearch.commons.notifications.model.EventSource
import org.opensearch.commons.notifications.model.NotificationConfig
import org.opensearch.commons.notifications.model.SeverityType
import org.opensearch.core.rest.RestStatus
import org.opensearch.notifications.index.ConfigOperations
import org.opensearch.notifications.model.DocInfo
import org.opensearch.notifications.model.DocMetadata
import org.opensearch.notifications.model.NotificationConfigDoc
import org.opensearch.notifications.model.NotificationConfigDocInfo
import org.opensearch.notifications.security.UserAccess
import org.opensearch.threadpool.ThreadPool
import org.opensearch.transport.client.Client
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An active response channel with `location = "local"` takes its target agent from the
 * `wazuh.agent.id` field of the alert it is applied to, not from the channel's own `agent_id`.
 * These tests pin that the producer refuses the pair when the alert cannot satisfy it, instead of
 * indexing a document that can never be dispatched and reporting it as delivered.
 */
internal class SendActiveResponseMessageTests {

    private val channelId = "active-response-channel"
    private val docId = "doc-1"
    private val indexName = "my-custom-index"

    private lateinit var configOperations: ConfigOperations
    private lateinit var userAccess: UserAccess
    private lateinit var bulkIndexer: ActiveResponseBulkIndexer
    private lateinit var client: Client

    @BeforeEach
    fun setUp() {
        configOperations = mockk()
        userAccess = mockk()
        bulkIndexer = mockk(relaxed = true)
        client = mockk()

        val threadPool = mockk<ThreadPool>()
        every { threadPool.threadContext } returns ThreadContext(Settings.EMPTY)
        every { client.threadPool() } returns threadPool
        every { userAccess.validateUser(any()) } returns Unit
        every { userAccess.doesUserHaveAccess(any(), any()) } returns true

        SendMessageActionHelper.initialize(configOperations, userAccess, client, bulkIndexer)
    }

    @Test
    fun `local location with an agent id is queued and keeps the id`() {
        val response = send(location = "local", alertSource = alertWithAgent("001"))

        assertEquals(
            RestStatus.OK.status.toString(),
            response.notificationEvent.statusList.single().deliveryStatus?.statusCode
        )
        val wazuh = queuedWazuhObject()
        assertEquals("001", (wazuh["agent"] as Map<*, *>)["id"])
        assertEquals("local", (wazuh["active_response"] as Map<*, *>)["location"])
    }

    @Test
    fun `local location without a wazuh object is refused and nothing is queued`() {
        val exception = assertFailsWith<OpenSearchStatusException> {
            send(location = "local", alertSource = alertWithoutWazuh())
        }

        assertEquals(RestStatus.BAD_REQUEST, exception.status())
        assertTrue(
            exception.message!!.contains("wazuh.agent.id"),
            "delivery status should name the missing field, was: ${exception.message}"
        )
        verify(exactly = 0) { bulkIndexer.add(any()) }
    }

    @Test
    fun `local location with a wazuh agent but no id is refused and nothing is queued`() {
        val alert = mapOf("wazuh" to mapOf("agent" to mapOf("name" to "agent-1")))

        val exception = assertFailsWith<OpenSearchStatusException> {
            send(location = "local", alertSource = alert)
        }

        assertEquals(RestStatus.BAD_REQUEST, exception.status())
        assertTrue(exception.message!!.contains("wazuh.agent.id"))
        verify(exactly = 0) { bulkIndexer.add(any()) }
    }

    @Test
    fun `defined-agent location without a wazuh object is still queued`() {
        val response = send(location = "defined-agent", alertSource = alertWithoutWazuh(), agentId = "007")

        assertEquals(
            RestStatus.OK.status.toString(),
            response.notificationEvent.statusList.single().deliveryStatus?.statusCode
        )
        val activeResponse = queuedWazuhObject()["active_response"] as Map<*, *>
        assertEquals("defined-agent", activeResponse["location"])
        assertEquals("007", activeResponse["agent_id"])
    }

    @Test
    fun `all location without a wazuh object is still queued`() {
        val response = send(location = "all", alertSource = alertWithoutWazuh())

        assertEquals(
            RestStatus.OK.status.toString(),
            response.notificationEvent.statusList.single().deliveryStatus?.statusCode
        )
        assertEquals("all", (queuedWazuhObject()["active_response"] as Map<*, *>)["location"])
    }

    /**
     * The refusal has to reach the caller of the send API the same way the malformed-payload refusal
     * does — as the channel's delivery status — so Alerting reports the action as failed instead of
     * delivered.
     */
    @Test
    fun `refusal travels through the send API as a delivery status`() {
        val exception = assertFailsWith<OpenSearchStatusException> {
            send(location = "local", alertSource = alertWithoutWazuh())
        }

        val message = exception.message!!
        assertTrue(message.contains("event_status_list"), "expected the per-channel status list, was: $message")
        assertTrue(message.contains("\"config_id\":\"$channelId\""), "expected the channel id, was: $message")
        assertTrue(message.contains("\"status_code\":\"400\""), "expected a failing status code, was: $message")
        assertTrue(message.contains("Active response not queued"), "expected the refusal reason, was: $message")
    }

    @Test
    fun `target check only constrains the local location`() {
        val withoutAgent = emptyMap<String, Any?>()

        assertEquals(
            "wazuh.agent.id",
            SendMessageActionHelper.missingActiveResponseTarget("local", withoutAgent)
        )
        assertNull(SendMessageActionHelper.missingActiveResponseTarget("defined-agent", withoutAgent))
        assertNull(SendMessageActionHelper.missingActiveResponseTarget("all", withoutAgent))
    }

    @Test
    fun `target check rejects blank and malformed agent ids`() {
        assertEquals(
            "wazuh.agent.id",
            SendMessageActionHelper.missingActiveResponseTarget("local", mapOf("agent" to mapOf("id" to "   ")))
        )
        assertEquals(
            "wazuh.agent.id",
            SendMessageActionHelper.missingActiveResponseTarget("local", mapOf("agent" to mapOf("id" to null)))
        )
        // An `agent` that is not an object cannot yield an id either.
        assertEquals(
            "wazuh.agent.id",
            SendMessageActionHelper.missingActiveResponseTarget("local", mapOf("agent" to "001"))
        )
        // A non-string id still carries a target, so this check — which only asks whether the field
        // is there — must not reject it. Whether the manager can match a non-string id against its
        // agent list is a separate concern, and a separate bug if it cannot.
        assertNull(SendMessageActionHelper.missingActiveResponseTarget("local", mapOf("agent" to mapOf("id" to 1))))
    }

    private fun alertWithAgent(agentId: String): Map<String, Any?> = mapOf(
        "@timestamp" to "2026-09-08T10:00:00Z",
        "wazuh" to mapOf("agent" to mapOf("id" to agentId, "name" to "agent-$agentId"))
    )

    private fun alertWithoutWazuh(): Map<String, Any?> = mapOf(
        "@timestamp" to "2026-09-08T10:00:00Z",
        "message" to "trigger me",
        "severity" to "high"
    )

    /** The `wazuh` object of the single document handed to the bulk indexer. */
    private fun queuedWazuhObject(): Map<*, *> {
        val queued = mutableListOf<IndexRequest>()
        verify(exactly = 1) { bulkIndexer.add(capture(queued)) }
        assertEquals("wazuh-active-responses", queued.single().index())
        return queued.single().sourceAsMap()["wazuh"] as Map<*, *>
    }

    private fun send(
        location: String,
        alertSource: Map<String, Any?>,
        agentId: String? = null
    ): SendNotificationResponse {
        coEvery { configOperations.getNotificationConfig(channelId) } returns channelConfig(location, agentId)
        stubAlert(alertSource)
        return runBlocking {
            SendMessageActionHelper.executeRequest(
                SendNotificationRequest(
                    EventSource("active response", "reference-id", SeverityType.INFO),
                    // The format the helper parses out of {{ctx.alerts.0.related_doc_ids}}.
                    ChannelMessage("$docId|$indexName", null, null),
                    listOf(channelId),
                    null
                )
            )
        }
    }

    private fun channelConfig(location: String, agentId: String?): NotificationConfigDocInfo {
        val activeResponse = ActiveResponse(
            type = "stateless",
            executable = "block-ip",
            location = location,
            agentId = agentId
        )
        val config = NotificationConfig(
            "active response channel",
            "channel under test",
            ConfigType.ACTIVE_RESPONSE,
            activeResponse
        )
        val metadata = DocMetadata(Instant.now(), Instant.now(), listOf("br1"))
        return NotificationConfigDocInfo(DocInfo(id = channelId), NotificationConfigDoc(metadata, config))
    }

    private fun stubAlert(source: Map<String, Any?>) {
        val getResponse = mockk<GetResponse>()
        every { getResponse.isExists } returns true
        every { getResponse.sourceAsMap } returns source
        val future = mockk<ActionFuture<GetResponse>>()
        every { future.actionGet() } returns getResponse
        every { client.get(any<GetRequest>()) } returns future
    }
}
