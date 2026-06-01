/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.notifications.action

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.fail
import org.mockito.Answers
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.junit.jupiter.MockitoExtension
import org.opensearch.action.bulk.BulkProcessor
import org.opensearch.action.get.GetResponse
import org.opensearch.action.index.IndexRequest
import org.opensearch.action.support.ActionFilters
import org.opensearch.common.action.ActionFuture
import org.opensearch.commons.destination.response.LegacyDestinationResponse
import org.opensearch.commons.notifications.action.BaseResponse
import org.opensearch.commons.notifications.action.CreateNotificationConfigRequest
import org.opensearch.commons.notifications.action.CreateNotificationConfigResponse
import org.opensearch.commons.notifications.action.DeleteNotificationConfigRequest
import org.opensearch.commons.notifications.action.DeleteNotificationConfigResponse
import org.opensearch.commons.notifications.action.GetChannelListRequest
import org.opensearch.commons.notifications.action.GetChannelListResponse
import org.opensearch.commons.notifications.action.GetNotificationConfigRequest
import org.opensearch.commons.notifications.action.GetNotificationConfigResponse
import org.opensearch.commons.notifications.action.GetPluginFeaturesRequest
import org.opensearch.commons.notifications.action.GetPluginFeaturesResponse
import org.opensearch.commons.notifications.action.LegacyPublishNotificationRequest
import org.opensearch.commons.notifications.action.LegacyPublishNotificationResponse
import org.opensearch.commons.notifications.action.SendNotificationRequest
import org.opensearch.commons.notifications.action.SendNotificationResponse
import org.opensearch.commons.notifications.action.UpdateNotificationConfigRequest
import org.opensearch.commons.notifications.action.UpdateNotificationConfigResponse
import org.opensearch.commons.notifications.model.ActiveResponse
import org.opensearch.commons.notifications.model.ChannelList
import org.opensearch.commons.notifications.model.ConfigType
import org.opensearch.commons.notifications.model.DeliveryStatus
import org.opensearch.commons.notifications.model.EventSource
import org.opensearch.commons.notifications.model.EventStatus
import org.opensearch.commons.notifications.model.NotificationConfigSearchResult
import org.opensearch.commons.notifications.model.NotificationEvent
import org.opensearch.commons.notifications.model.SeverityType
import org.opensearch.core.action.ActionListener
import org.opensearch.core.rest.RestStatus
import org.opensearch.core.xcontent.NamedXContentRegistry
import org.opensearch.notifications.index.ConfigIndexingActions
import org.opensearch.notifications.send.ActiveResponseBulkIndexer
import org.opensearch.notifications.send.SendMessageActionHelper
import org.opensearch.notifications.spi.model.MessageContent
import org.opensearch.notifications.util.SecureIndexClient
import org.opensearch.tasks.Task
import org.opensearch.transport.TransportService
import org.opensearch.transport.client.Client
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
internal class PluginActionTests {

    @Mock
    private lateinit var transportService: TransportService

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private lateinit var client: Client

    @Mock
    private lateinit var xContentRegistry: NamedXContentRegistry

    @Mock
    private lateinit var task: Task

    private val actionFilters = ActionFilters(setOf())

    @Test
    fun `Create notification config action should call back action listener`() {
        val notificationId = "notification-1"
        val request = mock(CreateNotificationConfigRequest::class.java)
        val response = CreateNotificationConfigResponse(notificationId)

        // Mock singleton's method by mockk framework
        mockkObject(ConfigIndexingActions)
        every {
            runBlocking {
                ConfigIndexingActions.create(request, any())
            }
        } returns response

        val createNotificationConfigAction = CreateNotificationConfigAction(
            transportService,
            client,
            actionFilters,
            xContentRegistry
        )
        createNotificationConfigAction.execute(task, request, AssertionListener(response))
    }

    @Test
    fun `Update notification config action should call back action listener`() {
        val notificationId = "notification-1"
        val request = mock(UpdateNotificationConfigRequest::class.java)
        val response = UpdateNotificationConfigResponse(notificationId)

        // Mock singleton's method by mockk framework
        mockkObject(ConfigIndexingActions)
        every {
            runBlocking {
                ConfigIndexingActions.update(request, any())
            }
        } returns response

        val updateNotificationConfigAction = UpdateNotificationConfigAction(
            transportService,
            client,
            actionFilters,
            xContentRegistry
        )
        updateNotificationConfigAction.execute(task, request, AssertionListener(response))
    }

    @Test
    fun `Delete notification config action should call back action listener`() {
        val request = mock(DeleteNotificationConfigRequest::class.java)
        val response = DeleteNotificationConfigResponse(
            mapOf(Pair("sample_config_id", RestStatus.OK))
        )

        // Mock singleton's method by mockk framework
        mockkObject(ConfigIndexingActions)
        every {
            runBlocking {
                ConfigIndexingActions.delete(request, any())
            }
        } returns response

        val deleteNotificationConfigAction = DeleteNotificationConfigAction(
            transportService,
            client,
            actionFilters,
            xContentRegistry
        )
        deleteNotificationConfigAction.execute(task, request, AssertionListener(response))
    }

    @Test
    fun `Get notification config action should call back action listener`() {
        val request = mock(GetNotificationConfigRequest::class.java)
        val response = GetNotificationConfigResponse(
            mock(NotificationConfigSearchResult::class.java)
        )

        // Mock singleton's method by mockk framework
        mockkObject(ConfigIndexingActions)
        every {
            runBlocking {
                ConfigIndexingActions.get(request, any())
            }
        } returns response

        val getNotificationConfigAction = GetNotificationConfigAction(
            transportService,
            client,
            actionFilters,
            xContentRegistry
        )
        getNotificationConfigAction.execute(task, request, AssertionListener(response))
    }

    @Test
    fun `Get plugin features action should call back action listener`() {
        val allowedConfigTypes = listOf("type1")
        val pluginFeatures = mapOf(Pair("FeatureKey1", "Feature1"))
        val request = mock(GetPluginFeaturesRequest::class.java)
        val response = GetPluginFeaturesResponse(allowedConfigTypes, pluginFeatures)

        val getPluginFeaturesAction = GetPluginFeaturesAction(
            transportService,
            client,
            actionFilters,
            xContentRegistry
        )
        getPluginFeaturesAction.execute(task, request, AssertionListener(response))
    }

    @Test
    fun `Get channel list action should call back action listener`() {
        val request = mock(GetChannelListRequest::class.java)
        val response = GetChannelListResponse(mock(ChannelList::class.java))

        // Mock singleton's method by mockk framework
        mockkObject(ConfigIndexingActions)
        every {
            runBlocking {
                ConfigIndexingActions.getChannelList(request, any())
            }
        } returns response

        val getChannelListAction = GetChannelListAction(
            transportService,
            client,
            actionFilters,
            xContentRegistry
        )
        getChannelListAction.execute(task, request, AssertionListener(response))
    }

    @Test
    fun `Send notification action should call back action listener`() {
        val request = mock(SendNotificationRequest::class.java)

        val sampleEventSource = EventSource(
            "title",
            "reference_id",
            severity = SeverityType.INFO
        )
        val sampleStatus = EventStatus(
            "config_id",
            "name",
            ConfigType.SLACK,
            deliveryStatus = DeliveryStatus("404", "invalid recipient")
        )

        val sampleEvent = NotificationEvent(sampleEventSource, listOf(sampleStatus))

        val response = SendNotificationResponse(sampleEvent)

        // Mock singleton's method by mockk framework
        mockkObject(SendMessageActionHelper)
        every {
            runBlocking {
                SendMessageActionHelper.executeRequest(request)
            }
        } returns response

        val sendNotificationAction = SendNotificationAction(
            transportService,
            client,
            actionFilters,
            xContentRegistry
        )
        sendNotificationAction.execute(task, request, AssertionListener(response))
    }

    @Test
    fun `Send active response message should preserve full wazuh object`() {
        val wazuhObject = mapOf<String, Any?>(
            "cluster" to mapOf<String, Any?>(
                "node" to "node01",
                "name" to "wazuh"
            ),
            "protocol" to mapOf<String, Any?>(
                "location" to "syscheck",
                "queue" to 56
            ),
            "agent" to mapOf<String, Any?>(
                "host" to mapOf<String, Any?>(
                    "hostname" to "primary",
                    "os" to mapOf<String, Any?>(
                        "name" to "Ubuntu",
                        "type" to "linux",
                        "version" to "22.04.3 LTS (Jammy Jellyfish)",
                        "platform" to "ubuntu"
                    ),
                    "architecture" to "x86_64"
                ),
                "name" to "primary",
                "groups" to listOf("default"),
                "id" to "001",
                "version" to "v5.0.0"
            ),
            "integration" to mapOf<String, Any?>(
                "name" to "wazuh-fim",
                "decoders" to listOf(
                    "decoder/core-wazuh-message/0",
                    "decoder/wazuh-fim/0"
                ),
                "category" to "system-activity"
            ),
            "rule" to mapOf<String, Any?>(
                "sigma_id" to "95cbac32-7a12-4b63-b85d-0a4d598b74e9",
                "level" to "low",
                "compliance" to mapOf<String, Any?>(
                    "iso_27001" to listOf("A.12.4.1", "A.12.6.1", "A.16.1.2"),
                    "hipaa" to listOf("164.308.a.1.ii.D", "164.308.a.6", "164.312.b"),
                    "pci_dss" to listOf("6.2", "10.5", "11.4"),
                    "tsc" to listOf("A1.2", "CC7.2", "CC7.3"),
                    "nis2" to listOf("21.2.a", "21.2.e", "23"),
                    "nist_800_171" to listOf("3.3.1", "3.3.2", "3.4.1", "3.14.6", "3.14.7"),
                    "fedramp" to listOf("AU-6", "CM-6", "SI-4"),
                    "nist_800_53" to listOf("AU-6", "CM-6", "SI-4"),
                    "cmmc" to listOf("AU.L2-3.3.1", "CM.L2-3.4.1", "SI.L2-3.14.1"),
                    "gdpr" to listOf("IV_32.1.a", "IV_33.1")
                ),
                "mitre" to mapOf<String, Any?>(
                    "technique" to listOf("T1105", "T1036"),
                    "tactic" to listOf("TA0003", "TA0005")
                ),
                "id" to "95cbac32-7a12-4b63-b85d-0a4d598b74e9",
                "title" to "Wazuh FIM - File created",
                "tags" to listOf(
                    "low",
                    "wazuh-fim",
                    "attack.persistence",
                    "attack.defense-evasion",
                    "attack.t1105",
                    "attack.t1036"
                ),
                "status" to "stable"
            ),
            "threat" to mapOf<String, Any?>(
                "enrichments" to listOf(
                    mapOf<String, Any?>(
                        "indicator" to mapOf<String, Any?>(
                            "feed" to mapOf<String, Any?>("name" to "wazuh-custom"),
                            "first_seen" to "2026-03-31T00:00:00.000Z",
                            "last_seen" to "2026-03-31T00:00:00.000Z",
                            "software" to mapOf<String, Any?>(
                                "name" to "eicar_test_file",
                                "alias" to listOf("EICAR Test File", "AntiVirus Test File"),
                                "type" to "test"
                            ),
                            "provider" to "wazuh",
                            "confidence" to 100,
                            "name" to "44d88612fea8a8f36de82e1278abb02f",
                            "id" to "9999991",
                            "type" to "hash_md5",
                            "tags" to listOf("eicar", "test", "antivirus", "safe-test")
                        ),
                        "matched" to mapOf<String, Any?>("field" to "file.hash.md5")
                    ),
                    mapOf<String, Any?>(
                        "indicator" to mapOf<String, Any?>(
                            "feed" to mapOf<String, Any?>("name" to "wazuh-custom"),
                            "first_seen" to "2026-03-31T00:00:00.000Z",
                            "last_seen" to "2026-03-31T00:00:00.000Z",
                            "software" to mapOf<String, Any?>(
                                "name" to "eicar_test_file",
                                "alias" to listOf("EICAR Test File", "AntiVirus Test File"),
                                "type" to "test"
                            ),
                            "provider" to "wazuh",
                            "confidence" to 100,
                            "name" to "3395856ce81f2b7382dee72602f798b642f14140",
                            "id" to "9999992",
                            "type" to "hash_sha1",
                            "tags" to listOf("eicar", "test", "antivirus", "safe-test")
                        ),
                        "matched" to mapOf<String, Any?>("field" to "file.hash.sha1")
                    ),
                    mapOf<String, Any?>(
                        "indicator" to mapOf<String, Any?>(
                            "feed" to mapOf<String, Any?>("name" to "wazuh-custom"),
                            "first_seen" to "2026-03-31T00:00:00.000Z",
                            "last_seen" to "2026-03-31T00:00:00.000Z",
                            "software" to mapOf<String, Any?>(
                                "name" to "eicar_test_file",
                                "alias" to listOf("EICAR Test File", "AntiVirus Test File"),
                                "type" to "test"
                            ),
                            "provider" to "wazuh",
                            "confidence" to 100,
                            "name" to "275a021bbfb6489e54d471899f7db9d1663fc695ec2fe2a2c4538aabf651fd0f",
                            "id" to "9999990",
                            "type" to "hash_sha256",
                            "tags" to listOf("eicar", "test", "antivirus", "safe-test")
                        ),
                        "matched" to mapOf<String, Any?>("field" to "file.hash.sha256")
                    )
                )
            ),
            "event" to mapOf<String, Any?>(
                "id" to "285c2315-f92d-4ba7-ab87-fbe3a31ee378"
            ),
            "space" to mapOf<String, Any?>(
                "name" to "standard"
            )
        )

        val sourceDocument = mapOf(
            "wazuh" to wazuhObject
        )

        val getResponse = mockk<GetResponse>()
        every { getResponse.isExists } returns true
        every { getResponse.sourceAsMap } returns sourceDocument

        val actionFuture = mockk<ActionFuture<GetResponse>>()
        every { actionFuture.actionGet() } returns getResponse

        val mockedClient = mockk<SecureIndexClient>()
        every { mockedClient.get(any()) } returns actionFuture

        val indexRequestSlot = slot<IndexRequest>()
        val mockedBulkProcessor = mockk<BulkProcessor>()
        val mockedBulkIndexer = mockk<ActiveResponseBulkIndexer>()
        every { mockedBulkIndexer.add(capture(indexRequestSlot)) } returns mockedBulkProcessor

        setPrivateField("client", mockedClient)
        setPrivateField("activeResponseBulkIndexer", mockedBulkIndexer)

        val activeResponse = mockk<ActiveResponse>(relaxed = true)
        val eventStatus = EventStatus(
            "config-id",
            "config-name",
            ConfigType.ACTIVE_RESPONSE,
            listOf(),
            DeliveryStatus("Scheduled", "Pending execution")
        )

        val response = sendActiveResponseMessage.invoke(
            SendMessageActionHelper,
            activeResponse,
            "active-response-channel",
            MessageContent("title", "doc-1|wazuh-alerts-1"),
            eventStatus,
            "reference-1"
        ) as EventStatus

        assertEquals(RestStatus.OK.status.toString(), response.deliveryStatus?.statusCode)
        assertTrue(indexRequestSlot.isCaptured)

        val indexedSource = indexRequestSlot.captured.source().utf8ToString()
        assertTrue(indexedSource.contains("\"agent\""), "expected wazuh.agent to be preserved")
        assertTrue(indexedSource.contains("\"syscheck\""), "expected wazuh.protocol.location to be preserved")
        assertTrue(indexedSource.contains("\"rule\""), "expected wazuh.rule to be preserved")
        assertTrue(indexedSource.contains("95cbac32-7a12-4b63-b85d-0a4d598b74e9"), "expected wazuh.rule.sigma_id to be preserved")
        assertTrue(indexedSource.contains("\"threat\""), "expected wazuh.threat to be preserved")
        assertTrue(indexedSource.contains("\"hash_sha256\""), "expected wazuh.threat.enrichments indicator data to be preserved")
        assertTrue(indexedSource.contains("\"file.hash.sha256\""), "expected wazuh.threat.enrichments.matched.field to be preserved")
        assertTrue(indexedSource.contains("\"active_response\""), "expected active_response block to be present")
        assertTrue(indexedSource.contains("\"doc_id\":\"doc-1\""))
    }

    @Test
    fun `Publish notification action should call back action listener`() {
        val request = mock(LegacyPublishNotificationRequest::class.java)
        val response = LegacyPublishNotificationResponse(
            LegacyDestinationResponse.Builder().withStatusCode(200).withResponseContent("Hello world").build()
        )

        // Mock singleton's method by mockk framework
        mockkObject(SendMessageActionHelper)
        every { SendMessageActionHelper.executeLegacyRequest(request) } returns response

        val publishNotificationAction = PublishNotificationAction(
            transportService,
            client,
            actionFilters,
            xContentRegistry
        )
        publishNotificationAction.execute(task, request, AssertionListener(response))
    }

    /**
     * This listener class is to assert on response rather than verify it called.
     * The reason why this is required is because it is harder to do the latter
     * (verify listener being called once) due to CoroutineScope used in execute()
     */
    private class AssertionListener<Response : BaseResponse>(
        val expected: Response
    ) : ActionListener<Response> {

        override fun onResponse(actual: Response?) {
            assertEquals(expected, actual)
        }

        override fun onFailure(error: Exception?) {
            fail("Unexpected error happened", error)
        }
    }

    companion object {
        private lateinit var sendActiveResponseMessage: Method

        @JvmStatic
        @org.junit.jupiter.api.BeforeAll
        fun initializeReflection() {
            sendActiveResponseMessage = SendMessageActionHelper::class.java.getDeclaredMethod(
                "sendActiveResponseMessage",
                ActiveResponse::class.java,
                String::class.java,
                MessageContent::class.java,
                EventStatus::class.java,
                String::class.java
            )
            sendActiveResponseMessage.isAccessible = true
        }

        private fun setPrivateField(fieldName: String, value: Any) {
            val field: Field = SendMessageActionHelper::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(SendMessageActionHelper, value)
        }
    }
}
