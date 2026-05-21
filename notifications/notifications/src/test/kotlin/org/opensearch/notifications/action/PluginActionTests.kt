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
            "agent" to mapOf<String, Any?>(
                "build" to emptyMap<String, Any?>(),
                "host" to mapOf<String, Any?>(
                    "boot" to emptyMap<String, Any?>(),
                    "cpu" to emptyMap<String, Any?>(),
                    "disk" to mapOf<String, Any?>(
                        "read" to emptyMap<String, Any?>(),
                        "write" to emptyMap<String, Any?>()
                    ),
                    "geo" to emptyMap<String, Any?>(),
                    "memory" to mapOf<String, Any?>("used" to emptyMap<String, Any?>()),
                    "network" to mapOf<String, Any?>(
                        "egress" to emptyMap<String, Any?>(),
                        "ingress" to emptyMap<String, Any?>()
                    ),
                    "os" to emptyMap<String, Any?>(),
                    "risk" to emptyMap<String, Any?>()
                )
            ),
            "cluster" to emptyMap<String, Any?>(),
            "event" to emptyMap<String, Any?>(),
            "integration" to emptyMap<String, Any?>(),
            "protocol" to emptyMap<String, Any?>(),
            "schema" to emptyMap<String, Any?>(),
            "space" to emptyMap<String, Any?>(),
            "threat" to mapOf<String, Any?>(
                "enrichments" to mapOf<String, Any?>(
                    "indicator" to mapOf<String, Any?>(
                        "as" to mapOf<String, Any?>(
                            "organization" to emptyMap<String, Any?>()
                        ),
                        "email" to emptyMap<String, Any?>(),
                        "feed" to emptyMap<String, Any?>(),
                        "file" to mapOf<String, Any?>(
                            "code_signature" to emptyMap<String, Any?>(),
                            "elf" to mapOf<String, Any?>(
                                "header" to emptyMap<String, Any?>(),
                                "sections" to emptyMap<String, Any?>(),
                                "segments" to emptyMap<String, Any?>()
                            ),
                            "hash" to emptyMap<String, Any?>(),
                            "pe" to mapOf<String, Any?>(
                                "sections" to emptyMap<String, Any?>()
                            ),
                            "x509" to mapOf<String, Any?>(
                                "issuer" to emptyMap<String, Any?>(),
                                "subject" to emptyMap<String, Any?>()
                            )
                        ),
                        "geo" to emptyMap<String, Any?>(),
                        "marking" to emptyMap<String, Any?>(),
                        "registry" to mapOf<String, Any?>(
                            "data" to emptyMap<String, Any?>()
                        ),
                        "software" to emptyMap<String, Any?>(),
                        "x509" to mapOf<String, Any?>(
                            "issuer" to emptyMap<String, Any?>(),
                            "subject" to emptyMap<String, Any?>()
                        )
                    ),
                    "matched" to emptyMap<String, Any?>()
                ),
                "feed" to emptyMap<String, Any?>(),
                "group" to emptyMap<String, Any?>(),
                "indicator" to mapOf<String, Any?>(
                    "as" to mapOf<String, Any?>(
                        "organization" to emptyMap<String, Any?>()
                    ),
                    "email" to emptyMap<String, Any?>(),
                    "file" to mapOf<String, Any?>(
                        "code_signature" to emptyMap<String, Any?>(),
                        "elf" to mapOf<String, Any?>(
                            "header" to emptyMap<String, Any?>(),
                            "sections" to emptyMap<String, Any?>(),
                            "segments" to emptyMap<String, Any?>()
                        ),
                        "hash" to emptyMap<String, Any?>(),
                        "pe" to mapOf<String, Any?>(
                            "sections" to emptyMap<String, Any?>()
                        ),
                        "x509" to mapOf<String, Any?>(
                            "issuer" to emptyMap<String, Any?>(),
                            "subject" to emptyMap<String, Any?>()
                        )
                    ),
                    "geo" to emptyMap<String, Any?>(),
                    "marking" to emptyMap<String, Any?>(),
                    "registry" to mapOf<String, Any?>(
                        "data" to emptyMap<String, Any?>()
                    ),
                    "x509" to mapOf<String, Any?>(
                        "issuer" to emptyMap<String, Any?>(),
                        "subject" to emptyMap<String, Any?>()
                    )
                ),
                "software" to emptyMap<String, Any?>(),
                "tactic" to emptyMap<String, Any?>(),
                "technique" to mapOf<String, Any?>("subtechnique" to emptyMap<String, Any?>())
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
        assertTrue(indexedSource.contains("\"file\""), "expected wazuh.file to be preserved")
        assertTrue(indexedSource.contains("\"agent\""), "expected wazuh.agent to be preserved")
        assertTrue(indexedSource.contains("\"threat\""), "expected wazuh.threat to be preserved")
        assertTrue(indexedSource.contains("\"technique\""), "expected wazuh.technique to be preserved")
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
