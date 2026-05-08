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
import org.opensearch.action.get.GetRequest
import org.opensearch.action.get.GetResponse
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
     * Sets the OpenSearch client used for direct index operations.
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
     * Core initialization logic. Checks for existing channels and creates missing ones.
     */
    internal fun initializeDefaultChannels() {
        log.info("$LOG_PREFIX:Starting initialization of default notification channels")

        val existingIds = getExistingDefaultChannelIds()
        val missingChannels = DEFAULT_CHANNELS.filter { it.id !in existingIds }

        if (missingChannels.isEmpty()) {
            log.info("$LOG_PREFIX:All default notification channels already exist")
            return
        }

        log.info("$LOG_PREFIX:Creating ${missingChannels.size} missing default notification channels")

        var created = 0
        var alreadyExisted = 0
        for (channel in missingChannels) {
            try {
                createChannel(channel)
                created++
                log.info("$LOG_PREFIX:Created default notification channel: ${channel.config.name} (${channel.id})")
            } catch (e: Exception) {
                if (isVersionConflict(e)) {
                    alreadyExisted++
                    log.debug(
                        "$LOG_PREFIX:Default notification channel already exists: " +
                            "${channel.config.name} (${channel.id})"
                    )
                } else {
                    log.error(
                        "$LOG_PREFIX:Failed to create default notification channel: ${channel.config.name} (${channel.id})",
                        e
                    )
                }
            }
        }

        log.info(
            "$LOG_PREFIX:Default notification channels initialization complete. " +
                "Created: $created, already existed: $alreadyExisted, failed: ${missingChannels.size - created - alreadyExisted}"
        )
    }

    /**
     * Returns the set of default channel IDs that already exist in the index.
     */
    private fun getExistingDefaultChannelIds(): Set<String> {
        val existingIds = mutableSetOf<String>()
        for (channel in DEFAULT_CHANNELS) {
            try {
                val response: GetResponse = client.get(
                    GetRequest(NotificationConfigIndex.INDEX_NAME, channel.id)
                ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (response.isExists) {
                    existingIds.add(channel.id)
                }
            } catch (e: Exception) {
                log.debug("$LOG_PREFIX:Could not check if channel [${channel.id}] exists: ${e.message}")
            }
        }
        return existingIds
    }

    /**
     * Checks whether the exception (or any cause in its chain) is a VersionConflictEngineException.
     */
    private fun isVersionConflict(e: Exception): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is VersionConflictEngineException) return true
            current = current.cause
        }
        return false
    }

    /**
     * Creates a single default notification channel.
     */
    private fun createChannel(channel: ChannelDefinition) {
        val now = Instant.now()
        val metadata = DocMetadata(
            lastUpdateTime = now,
            createdTime = now,
            access = listOf()
        )
        val configDoc = NotificationConfigDoc(metadata, channel.config)
        val builder = XContentFactory.jsonBuilder()
        configDoc.toXContent(builder, ToXContent.EMPTY_PARAMS)

        val indexRequest = IndexRequest(NotificationConfigIndex.INDEX_NAME)
            .id(channel.id)
            .source(builder)
            .create(true)

        client.index(indexRequest).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }
}
