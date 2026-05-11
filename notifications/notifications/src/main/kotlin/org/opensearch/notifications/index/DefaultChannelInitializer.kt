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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.opensearch.action.admin.indices.exists.indices.IndicesExistsRequest
import org.opensearch.action.get.GetRequest
import org.opensearch.action.index.IndexRequest
import org.opensearch.common.xcontent.XContentFactory
import org.opensearch.commons.notifications.model.ConfigType
import org.opensearch.commons.notifications.model.HttpMethodType
import org.opensearch.commons.notifications.model.NotificationConfig
import org.opensearch.commons.notifications.model.Slack
import org.opensearch.commons.notifications.model.Webhook
import org.opensearch.commons.utils.logger
import org.opensearch.core.xcontent.ToXContent
import org.opensearch.index.engine.VersionConflictEngineException
import org.opensearch.notifications.NotificationPlugin.Companion.LOG_PREFIX
import org.opensearch.notifications.model.DocMetadata
import org.opensearch.notifications.model.NotificationConfigDoc
import org.opensearch.transport.client.Client
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Creates default notification channels on startup if they don't already exist.
 *
 * These channels are created disabled with placeholder URLs so that users can
 * configure them with their own credentials before enabling.
 */
object DefaultChannelInitializer {
    private val log by logger(DefaultChannelInitializer::class.java)
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    private const val TIMEOUT_SECONDS = 30L
    private lateinit var client: Client

    /**
     * Sets the OpenSearch client used for existence checks.
     */
    fun setClient(client: Client) {
        this.client = client
    }

    /** Default channel definitions matching the ones previously created by the Wazuh Dashboard. */
    internal data class ChannelDefinition(
        val id: String,
        val config: NotificationConfig
    )

    internal val DEFAULT_CHANNELS: List<ChannelDefinition> = listOf(
        ChannelDefinition(
            id = "default_slack_channel",
            config = NotificationConfig(
                name = "Slack Channel",
                description = "Default Slack notification channel. A sample monitor is created automatically. " +
                    "Go to Alerting > Monitors to review it and configure this channel before enabling alerts.",
                configType = ConfigType.SLACK,
                isEnabled = false,
                configData = Slack(
                    url = "https://hooks.slack.com/services/YOUR_WORKSPACE_ID/YOUR_CHANNEL_ID/YOUR_WEBHOOK_TOKEN"
                )
            )
        ),
        ChannelDefinition(
            id = "default_jira_channel",
            config = NotificationConfig(
                name = "Jira Channel",
                description = "Default Jira notification channel.\n\n" +
                    "Configure your Jira domain and authentication (use a Base64-encoded 'email:api_token' " +
                    "in the Authorization header). A sample monitor is created automatically — go to " +
                    "Alerting > Monitors to review it and enable the action once this channel is configured.",
                configType = ConfigType.WEBHOOK,
                isEnabled = false,
                configData = Webhook(
                    url = "https://your-domain.atlassian.net/rest/api/3/issue",
                    headerParams = mapOf(
                        "Content-Type" to "application/json",
                        "Authorization" to "Basic base64(email:api_token)"
                    ),
                    method = HttpMethodType.POST
                )
            )
        ),
        ChannelDefinition(
            id = "default_pagerduty_channel",
            config = NotificationConfig(
                name = "PagerDuty Channel",
                description = "Default PagerDuty notification channel.\n\n" +
                    "Configure a PagerDuty integration and set its Integration Key in the 'X-Routing-Key' " +
                    "header below. A sample monitor is created automatically — go to Alerting > Monitors " +
                    "to review it and enable the action once this channel is configured.",
                configType = ConfigType.WEBHOOK,
                isEnabled = false,
                configData = Webhook(
                    url = "https://events.pagerduty.com/v2/enqueue",
                    headerParams = mapOf(
                        "Content-Type" to "application/json",
                        "X-Routing-Key" to "YOUR_PAGERDUTY_API_KEY"
                    ),
                    method = HttpMethodType.POST
                )
            )
        ),
        ChannelDefinition(
            id = "default_shuffle_channel",
            config = NotificationConfig(
                name = "Shuffle Channel",
                description = "Default Shuffle notification channel. A sample monitor is created automatically. " +
                    "Go to Alerting > Monitors to review it and configure this channel before enabling workflows.",
                configType = ConfigType.WEBHOOK,
                isEnabled = false,
                configData = Webhook(
                    url = "https://shuffler.io/api/v1/hooks/WEBHOOK_ID",
                    headerParams = mapOf(
                        "Content-Type" to "application/json"
                    ),
                    method = HttpMethodType.POST
                )
            )
        )
    )

    /**
     * Initializes default notification channels asynchronously.
     * Checks which channels already exist and creates only the missing ones.
     * This method is idempotent and safe to call on every startup.
     */
    fun initialize() {
        scope.launch {
            try {
                initializeDefaultChannels()
            } catch (e: Exception) {
                log.error("$LOG_PREFIX:Failed to initialize default notification channels", e)
            }
        }
    }

    /**
     * Core initialization logic.
     *
     * If the index does not exist yet (first startup), channels are created through
     * [NotificationConfigIndex] which handles index creation with proper mappings.
     * If the index already exists (subsequent restarts), channels are managed directly
     * through the OpenSearch client for a lightweight, silent operation.
     */
    internal suspend fun initializeDefaultChannels() {
        log.info("$LOG_PREFIX:Starting initialization of default notification channels")

        val indexExists = indexExists()

        for (channel in DEFAULT_CHANNELS) {
            if (indexExists && channelExists(channel.id)) {
                log.info("$LOG_PREFIX:Default notification channel [${channel.id}]: already exists, ignored")
                continue
            }
            try {
                if (indexExists) {
                    insertChannel(channel)
                } else {
                    createChannelWithIndex(channel)
                }
                log.info("$LOG_PREFIX:Default notification channel [${channel.id}]: created")
            } catch (e: Exception) {
                if (isDocumentAlreadyExists(e)) {
                    log.info("$LOG_PREFIX:Default notification channel [${channel.id}]: already exists, ignored")
                } else {
                    log.error(
                        "$LOG_PREFIX:Failed to create default notification channel: ${channel.config.name} (${channel.id})",
                        e
                    )
                }
            }
        }

        log.info("$LOG_PREFIX:Default notification channels initialization complete")
    }

    /**
     * Checks whether the exception indicates the document already exists.
     * This can happen when the index is still recovering and the existence check
     * returns a false negative, but the subsequent insert finds the document.
     */
    private fun isDocumentAlreadyExists(e: Exception): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is VersionConflictEngineException) return true
            current = current.cause
        }
        return false
    }

    /**
     * Checks whether the notifications config index exists.
     */
    private fun indexExists(): Boolean {
        return try {
            client.admin().indices()
                .exists(IndicesExistsRequest(NotificationConfigIndex.INDEX_NAME))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isExists
        } catch (e: Exception) {
            log.debug("$LOG_PREFIX:Could not check if index exists: ${e.message}")
            false
        }
    }

    /**
     * Checks whether a channel document already exists in the index.
     */
    private fun channelExists(id: String): Boolean {
        return try {
            client.get(
                GetRequest(NotificationConfigIndex.INDEX_NAME, id)
            ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isExists
        } catch (e: Exception) {
            log.debug("$LOG_PREFIX:Could not check if channel [$id] exists: ${e.message}")
            false
        }
    }

    /**
     * Creates a channel through [NotificationConfigIndex], which ensures the index
     * is created with the correct mappings. Used on first startup when the index
     * does not exist yet.
     */
    private suspend fun createChannelWithIndex(channel: ChannelDefinition) {
        val configDoc = buildConfigDoc(channel)
        NotificationConfigIndex.createNotificationConfig(configDoc, channel.id)
    }

    /**
     * Inserts a channel document directly into the existing index.
     * Uses OpType.CREATE to fail gracefully if the document already exists.
     */
    private fun insertChannel(channel: ChannelDefinition) {
        val configDoc = buildConfigDoc(channel)
        val builder = XContentFactory.jsonBuilder()
        configDoc.toXContent(builder, ToXContent.EMPTY_PARAMS)

        val indexRequest = IndexRequest(NotificationConfigIndex.INDEX_NAME)
            .id(channel.id)
            .source(builder)
            .create(true)

        client.index(indexRequest).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * Builds a NotificationConfigDoc for the given channel definition.
     */
    private fun buildConfigDoc(channel: ChannelDefinition): NotificationConfigDoc {
        val now = Instant.now()
        val metadata = DocMetadata(
            lastUpdateTime = now,
            createdTime = now,
            access = listOf()
        )
        return NotificationConfigDoc(metadata, channel.config)
    }
}
