/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.notifications.settings

import org.opensearch.bootstrap.BootstrapInfo
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.settings.Setting
import org.opensearch.common.settings.Setting.Property.Deprecated
import org.opensearch.common.settings.Setting.Property.Dynamic
import org.opensearch.common.settings.Setting.Property.NodeScope
import org.opensearch.common.settings.Settings
import org.opensearch.commons.utils.OpenForTesting
import org.opensearch.commons.utils.logger
import org.opensearch.notifications.NotificationPlugin.Companion.LOG_PREFIX
import org.opensearch.notifications.NotificationPlugin.Companion.PLUGIN_NAME
import org.opensearch.remote.metadata.common.CommonValue.REMOTE_METADATA_ENDPOINT_KEY
import org.opensearch.remote.metadata.common.CommonValue.REMOTE_METADATA_REGION_KEY
import org.opensearch.remote.metadata.common.CommonValue.REMOTE_METADATA_SERVICE_NAME_KEY
import org.opensearch.remote.metadata.common.CommonValue.REMOTE_METADATA_TYPE_KEY
import java.io.IOException
import java.nio.file.Path

/**
 * settings specific to Notifications Plugin.
 */
internal object PluginSettings {

    private lateinit var clusterService: ClusterService

    /**
     * Settings Key-prefix for this plugin.
     */
    private const val KEY_PREFIX = "opensearch.notifications"

    /**
     * General settings Key prefix.
     */
    private const val GENERAL_KEY_PREFIX = "$KEY_PREFIX.general"

    /**
     * Active response settings Key prefix.
     */
    private const val ACTIVE_RESPONSE_KEY_PREFIX = "$KEY_PREFIX.active_response"

    /**
     * Bulk flush interval for active response indexing (ms).
     */
    private const val ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS_KEY = "$ACTIVE_RESPONSE_KEY_PREFIX.bulk_flush_interval_ms"

    /**
     * Maximum number of bulk actions before a forced flush.
     */
    private const val ACTIVE_RESPONSE_BULK_MAX_ACTIONS_KEY = "$ACTIVE_RESPONSE_KEY_PREFIX.bulk_max_actions"

    /**
     * Operation timeout for network operations.
     */
    private const val OPERATION_TIMEOUT_MS_KEY = "$GENERAL_KEY_PREFIX.operation_timeout_ms"

    /**
     * Setting to choose default number of items to query.
     */
    private const val DEFAULT_ITEMS_QUERY_COUNT_KEY = "$GENERAL_KEY_PREFIX.default_items_query_count"

    /**
     * Maximum number of notification channel configurations allowed.
     */
    private const val MAX_NOTIFICATION_CONFIGS_KEY = "$GENERAL_KEY_PREFIX.max_notification_configs"

    /**
     * Maximum number of notification groups (email groups) allowed.
     */
    private const val MAX_NOTIFICATION_GROUPS_KEY = "plugins.notifications.general.max_notification_groups"

    /**
     * Maximum number of notification senders (SMTP/SES accounts) allowed.
     */
    private const val MAX_NOTIFICATION_SENDERS_KEY = "plugins.notifications.general.max_notification_senders"

    /**
     * Maximum number of active response configurations allowed.
     */
    private const val MAX_ACTIVE_RESPONSES_KEY = "plugins.notifications.max_active_responses"

    /**
     * Legacy alerting plugin filter_by_backend_roles setting.
     */
    private const val LEGACY_ALERTING_FILTER_BY_BACKEND_ROLES_KEY = "opendistro.alerting.filter_by_backend_roles"

    /**
     * Alerting plugin filter_by_backend_roles setting.
     */
    private const val ALERTING_FILTER_BY_BACKEND_ROLES_KEY = "plugins.alerting.filter_by_backend_roles"

    /**
     * Setting to enable filtering by backend roles.
     */
    private const val FILTER_BY_BACKEND_ROLES_KEY = "$GENERAL_KEY_PREFIX.filter_by_backend_roles"

    /**
     * Default operation timeout for network operations.
     */
    private const val DEFAULT_OPERATION_TIMEOUT_MS = 60000L

    /**
     * Minimum operation timeout for network operations.
     */
    private const val MINIMUM_OPERATION_TIMEOUT_MS = 100L

    /**
     * Default number of items to query.
     */
    private const val DEFAULT_ITEMS_QUERY_COUNT_VALUE = 100

    /**
     * Minimum number of items to query.
     */
    private const val MINIMUM_ITEMS_QUERY_COUNT = 10

    /**
     * Default maximum number of notification channel configurations.
     */
    private const val DEFAULT_MAX_NOTIFICATION_CONFIGS_VALUE = 10

    /**
     * Minimum allowed value for the max notification configs setting.
     */
    private const val MINIMUM_MAX_NOTIFICATION_CONFIGS = 0

    /**
     * Default maximum number of notification groups.
     */
    private const val DEFAULT_MAX_NOTIFICATION_GROUPS_VALUE = 10

    /**
     * Minimum allowed value for the max notification groups setting.
     */
    private const val MINIMUM_MAX_NOTIFICATION_GROUPS = 0

    /**
     * Default maximum number of notification senders.
     */
    private const val DEFAULT_MAX_NOTIFICATION_SENDERS_VALUE = 5

    /**
     * Minimum allowed value for the max notification senders setting.
     */
    private const val MINIMUM_MAX_NOTIFICATION_SENDERS = 0

    /**
     * Default maximum number of active response configurations.
     */
    private const val DEFAULT_MAX_ACTIVE_RESPONSES_VALUE = 10

    /**
     * Minimum allowed value for the max active responses setting.
     */
    private const val MINIMUM_MAX_ACTIVE_RESPONSES = 0

    /**
     * Default bulk flush interval for active response (ms).
     */
    private const val DEFAULT_ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS = 500L

    /**
     * Minimum bulk flush interval for active response (ms).
     */
    private const val MINIMUM_ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS = 100L

    /**
     * Default maximum bulk actions before a forced flush.
     */
    private const val DEFAULT_ACTIVE_RESPONSE_BULK_MAX_ACTIONS = 1000

    /**
     * Minimum maximum bulk actions value.
     */
    private const val MINIMUM_ACTIVE_RESPONSE_BULK_MAX_ACTIONS = 1

    /**
     * Operation timeout setting in ms for I/O operations
     */
    @Volatile
    var operationTimeoutMs: Long

    /**
     * Default number of items to query.
     */
    @Volatile
    var defaultItemsQueryCount: Int

    /**
     * Maximum number of notification channel configurations allowed.
     */
    @Volatile
    var maxNotificationConfigs: Int

    /**
     * Maximum number of notification groups allowed.
     */
    @Volatile
    var maxNotificationGroups: Int

    /**
     * Maximum number of notification senders allowed.
     */
    @Volatile
    var maxNotificationSenders: Int

    /**
     * Maximum number of active response configurations allowed.
     */
    @Volatile
    var maxActiveResponses: Int

    /**
     * Bulk flush interval for active response indexing (ms).
     * Read once at plugin startup. The value is fixed for the lifetime of the node —
     * BulkProcessor does not support live reconfiguration, so this setting is NodeScope-only.
     */
    var activeResponseBulkFlushIntervalMs: Long = DEFAULT_ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS
        private set

    /**
     * Maximum number of bulk actions before a forced flush for active response.
     * Read once at plugin startup; see [activeResponseBulkFlushIntervalMs] for rationale.
     */
    var activeResponseBulkMaxActions: Int = DEFAULT_ACTIVE_RESPONSE_BULK_MAX_ACTIONS
        private set

    private const val DECIMAL_RADIX: Int = 10

    private val log by logger(javaClass)
    private val defaultSettings: Map<String, String>

    init {
        var settings: Settings? = null
        val configDirName = BootstrapInfo.getSystemProperties()?.get("opensearch.path.conf")?.toString()
        if (configDirName != null) {
            val defaultSettingYmlFile = Path.of(configDirName, PLUGIN_NAME, "notifications.yml")
            try {
                settings = Settings.builder().loadFromPath(defaultSettingYmlFile).build()
            } catch (e: IOException) {
                log.warn("$LOG_PREFIX:Failed to load ${defaultSettingYmlFile.toAbsolutePath()}:${e.message}")
            }
        }
        // Initialize the settings values to default values
        operationTimeoutMs = (settings?.get(OPERATION_TIMEOUT_MS_KEY)?.toLong()) ?: DEFAULT_OPERATION_TIMEOUT_MS
        defaultItemsQueryCount = (settings?.get(DEFAULT_ITEMS_QUERY_COUNT_KEY)?.toInt())
            ?: DEFAULT_ITEMS_QUERY_COUNT_VALUE
        maxNotificationConfigs = (settings?.get(MAX_NOTIFICATION_CONFIGS_KEY)?.toInt())
            ?: DEFAULT_MAX_NOTIFICATION_CONFIGS_VALUE
        maxNotificationGroups = (settings?.get(MAX_NOTIFICATION_GROUPS_KEY)?.toInt())
            ?: DEFAULT_MAX_NOTIFICATION_GROUPS_VALUE
        maxNotificationSenders = (settings?.get(MAX_NOTIFICATION_SENDERS_KEY)?.toInt())
            ?: DEFAULT_MAX_NOTIFICATION_SENDERS_VALUE
        maxActiveResponses = (settings?.get(MAX_ACTIVE_RESPONSES_KEY)?.toInt())
            ?: DEFAULT_MAX_ACTIVE_RESPONSES_VALUE
        activeResponseBulkFlushIntervalMs = (settings?.get(ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS_KEY)?.toLong())
            ?: DEFAULT_ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS
        activeResponseBulkMaxActions = (settings?.get(ACTIVE_RESPONSE_BULK_MAX_ACTIONS_KEY)?.toInt())
            ?: DEFAULT_ACTIVE_RESPONSE_BULK_MAX_ACTIONS
        defaultSettings = mapOf(
            OPERATION_TIMEOUT_MS_KEY to operationTimeoutMs.toString(DECIMAL_RADIX),
            DEFAULT_ITEMS_QUERY_COUNT_KEY to defaultItemsQueryCount.toString(DECIMAL_RADIX),
            MAX_NOTIFICATION_CONFIGS_KEY to maxNotificationConfigs.toString(DECIMAL_RADIX),
            MAX_NOTIFICATION_GROUPS_KEY to maxNotificationGroups.toString(DECIMAL_RADIX),
            MAX_NOTIFICATION_SENDERS_KEY to maxNotificationSenders.toString(DECIMAL_RADIX),
            MAX_ACTIVE_RESPONSES_KEY to maxActiveResponses.toString(DECIMAL_RADIX)
        )
    }

    val OPERATION_TIMEOUT_MS: Setting<Long> = Setting.longSetting(
        OPERATION_TIMEOUT_MS_KEY,
        defaultSettings[OPERATION_TIMEOUT_MS_KEY]!!.toLong(),
        MINIMUM_OPERATION_TIMEOUT_MS,
        NodeScope,
        Dynamic
    )

    val DEFAULT_ITEMS_QUERY_COUNT: Setting<Int> = Setting.intSetting(
        DEFAULT_ITEMS_QUERY_COUNT_KEY,
        defaultSettings[DEFAULT_ITEMS_QUERY_COUNT_KEY]!!.toInt(),
        MINIMUM_ITEMS_QUERY_COUNT,
        NodeScope,
        Dynamic
    )

    val MAX_NOTIFICATION_CONFIGS: Setting<Int> = Setting.intSetting(
        MAX_NOTIFICATION_CONFIGS_KEY,
        defaultSettings[MAX_NOTIFICATION_CONFIGS_KEY]!!.toInt(),
        MINIMUM_MAX_NOTIFICATION_CONFIGS,
        NodeScope,
        Dynamic
    )

    val MAX_NOTIFICATION_GROUPS: Setting<Int> = Setting.intSetting(
        MAX_NOTIFICATION_GROUPS_KEY,
        defaultSettings[MAX_NOTIFICATION_GROUPS_KEY]!!.toInt(),
        MINIMUM_MAX_NOTIFICATION_GROUPS,
        NodeScope,
        Dynamic
    )

    val MAX_NOTIFICATION_SENDERS: Setting<Int> = Setting.intSetting(
        MAX_NOTIFICATION_SENDERS_KEY,
        defaultSettings[MAX_NOTIFICATION_SENDERS_KEY]!!.toInt(),
        MINIMUM_MAX_NOTIFICATION_SENDERS,
        NodeScope,
        Dynamic
    )

    val MAX_ACTIVE_RESPONSES: Setting<Int> = Setting.intSetting(
        MAX_ACTIVE_RESPONSES_KEY,
        defaultSettings[MAX_ACTIVE_RESPONSES_KEY]!!.toInt(),
        MINIMUM_MAX_ACTIVE_RESPONSES,
        NodeScope,
        Dynamic
    )

    // BulkProcessor does not support live reconfiguration of flushInterval / bulkActions,
    // so these settings are NodeScope-only — they take effect on plugin/node startup only.
    val ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS: Setting<Long> = Setting.longSetting(
        ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS_KEY,
        DEFAULT_ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS,
        MINIMUM_ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS,
        NodeScope
    )

    val ACTIVE_RESPONSE_BULK_MAX_ACTIONS: Setting<Int> = Setting.intSetting(
        ACTIVE_RESPONSE_BULK_MAX_ACTIONS_KEY,
        DEFAULT_ACTIVE_RESPONSE_BULK_MAX_ACTIONS,
        MINIMUM_ACTIVE_RESPONSE_BULK_MAX_ACTIONS,
        NodeScope
    )

    val LEGACY_ALERTING_FILTER_BY_BACKEND_ROLES: Setting<Boolean> = Setting.boolSetting(
        LEGACY_ALERTING_FILTER_BY_BACKEND_ROLES_KEY,
        false,
        NodeScope,
        Dynamic,
        Deprecated
    )

    val ALERTING_FILTER_BY_BACKEND_ROLES: Setting<Boolean> = Setting.boolSetting(
        ALERTING_FILTER_BY_BACKEND_ROLES_KEY,
        LEGACY_ALERTING_FILTER_BY_BACKEND_ROLES,
        NodeScope,
        Dynamic
    )

    val FILTER_BY_BACKEND_ROLES: Setting<Boolean> = Setting.boolSetting(
        FILTER_BY_BACKEND_ROLES_KEY,
        ALERTING_FILTER_BY_BACKEND_ROLES,
        NodeScope,
        Dynamic
    )

    /**
     * Settings Key-prefix for remote metadata configuration.
     */
    private const val REMOTE_METADATA_KEY_PREFIX = "plugins.notifications"

    /** This setting enables multi-tenancy for the remote metadata SDK client */
    val MULTI_TENANCY_ENABLED: Setting<Boolean> = Setting.boolSetting(
        "$REMOTE_METADATA_KEY_PREFIX.multi_tenancy_enabled",
        false,
        NodeScope,
        Setting.Property.Final
    )

    /** This setting sets the remote metadata store type  */
    val REMOTE_METADATA_STORE_TYPE: Setting<String?> = Setting
        .simpleString(
            "$REMOTE_METADATA_KEY_PREFIX.$REMOTE_METADATA_TYPE_KEY",
            NodeScope,
            Setting.Property.Final
        )

    /** This setting sets the remote metadata endpoint  */
    val REMOTE_METADATA_ENDPOINT: Setting<String?> = Setting
        .simpleString(
            "$REMOTE_METADATA_KEY_PREFIX.$REMOTE_METADATA_ENDPOINT_KEY",
            NodeScope,
            Setting.Property.Final
        )

    /** This setting sets the remote metadata region  */
    val REMOTE_METADATA_REGION: Setting<String?> = Setting
        .simpleString(
            "$REMOTE_METADATA_KEY_PREFIX.$REMOTE_METADATA_REGION_KEY",
            NodeScope,
            Setting.Property.Final
        )

    /** This setting sets the remote metadata service name  */
    val REMOTE_METADATA_SERVICE_NAME: Setting<String?> = Setting
        .simpleString(
            "$REMOTE_METADATA_KEY_PREFIX.$REMOTE_METADATA_SERVICE_NAME_KEY",
            NodeScope,
            Setting.Property.Final
        )

    fun isRbacEnabled(): Boolean {
        return if (clusterService.clusterSettings.get(FILTER_BY_BACKEND_ROLES_KEY) != null) {
            return clusterService.clusterSettings.get(FILTER_BY_BACKEND_ROLES) ?: false
        } else {
            false
        }
    }

    /**
     * Returns list of additional settings available specific to this plugin.
     *
     * @return list of settings defined in this plugin
     */
    fun getAllSettings(): List<Setting<*>> {
        return listOf(
            OPERATION_TIMEOUT_MS,
            DEFAULT_ITEMS_QUERY_COUNT,
            MAX_NOTIFICATION_CONFIGS,
            MAX_NOTIFICATION_GROUPS,
            MAX_NOTIFICATION_SENDERS,
            MAX_ACTIVE_RESPONSES,
            ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS,
            ACTIVE_RESPONSE_BULK_MAX_ACTIONS,
            FILTER_BY_BACKEND_ROLES,
            MULTI_TENANCY_ENABLED,
            REMOTE_METADATA_REGION,
            REMOTE_METADATA_ENDPOINT,
            REMOTE_METADATA_STORE_TYPE,
            REMOTE_METADATA_SERVICE_NAME
        )
    }

    /**
     * Update the setting variables to setting values from local settings
     * @param clusterService cluster service instance
     */
    private fun updateSettingValuesFromLocal(clusterService: ClusterService) {
        operationTimeoutMs = OPERATION_TIMEOUT_MS.get(clusterService.settings)
        defaultItemsQueryCount = DEFAULT_ITEMS_QUERY_COUNT.get(clusterService.settings)
        maxNotificationConfigs = MAX_NOTIFICATION_CONFIGS.get(clusterService.settings)
        maxNotificationGroups = MAX_NOTIFICATION_GROUPS.get(clusterService.settings)
        maxNotificationSenders = MAX_NOTIFICATION_SENDERS.get(clusterService.settings)
        maxActiveResponses = MAX_ACTIVE_RESPONSES.get(clusterService.settings)
        activeResponseBulkFlushIntervalMs = ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS.get(clusterService.settings)
        activeResponseBulkMaxActions = ACTIVE_RESPONSE_BULK_MAX_ACTIONS.get(clusterService.settings)
    }

    /**
     * Update the setting variables to setting values from cluster settings
     * @param clusterService cluster service instance
     */
    @Suppress("LongMethod")
    private fun updateSettingValuesFromCluster(clusterService: ClusterService) {
        val clusterOperationTimeoutMs = clusterService.clusterSettings.get(OPERATION_TIMEOUT_MS)
        if (clusterOperationTimeoutMs != null) {
            log.debug("$LOG_PREFIX:$OPERATION_TIMEOUT_MS_KEY -autoUpdatedTo-> $clusterOperationTimeoutMs")
            operationTimeoutMs = clusterOperationTimeoutMs
        }
        val clusterDefaultItemsQueryCount = clusterService.clusterSettings.get(DEFAULT_ITEMS_QUERY_COUNT)
        if (clusterDefaultItemsQueryCount != null) {
            log.debug("$LOG_PREFIX:$DEFAULT_ITEMS_QUERY_COUNT_KEY -autoUpdatedTo-> $clusterDefaultItemsQueryCount")
            defaultItemsQueryCount = clusterDefaultItemsQueryCount
        }
        val clusterMaxNotificationConfigs = clusterService.clusterSettings.get(MAX_NOTIFICATION_CONFIGS)
        if (clusterMaxNotificationConfigs != null) {
            log.debug("$LOG_PREFIX:$MAX_NOTIFICATION_CONFIGS_KEY -autoUpdatedTo-> $clusterMaxNotificationConfigs")
            maxNotificationConfigs = clusterMaxNotificationConfigs
        }
        val clusterMaxNotificationGroups = clusterService.clusterSettings.get(MAX_NOTIFICATION_GROUPS)
        if (clusterMaxNotificationGroups != null) {
            log.debug("$LOG_PREFIX:$MAX_NOTIFICATION_GROUPS_KEY -autoUpdatedTo-> $clusterMaxNotificationGroups")
            maxNotificationGroups = clusterMaxNotificationGroups
        }
        val clusterMaxNotificationSenders = clusterService.clusterSettings.get(MAX_NOTIFICATION_SENDERS)
        if (clusterMaxNotificationSenders != null) {
            log.debug("$LOG_PREFIX:$MAX_NOTIFICATION_SENDERS_KEY -autoUpdatedTo-> $clusterMaxNotificationSenders")
            maxNotificationSenders = clusterMaxNotificationSenders
        }
        val clusterMaxActiveResponses = clusterService.clusterSettings.get(MAX_ACTIVE_RESPONSES)
        if (clusterMaxActiveResponses != null) {
            log.debug("$LOG_PREFIX:$MAX_ACTIVE_RESPONSES_KEY -autoUpdatedTo-> $clusterMaxActiveResponses")
            maxActiveResponses = clusterMaxActiveResponses
        }
        // ACTIVE_RESPONSE_BULK_* settings are NodeScope-only; they are read once in
        // updateSettingValuesFromLocal at startup and cannot change at runtime, so
        // there is no cluster-state listener to register here.
    }

    /**
     * adds Settings update listeners to all settings.
     * @param clusterService cluster service instance
     */
    fun addSettingsUpdateConsumer(clusterService: ClusterService) {
        this.clusterService = clusterService
        updateSettingValuesFromLocal(clusterService)
        // Update the variables to cluster setting values
        // If the cluster is not yet started then we get default values again
        updateSettingValuesFromCluster(clusterService)

        clusterService.clusterSettings.addSettingsUpdateConsumer(OPERATION_TIMEOUT_MS) {
            operationTimeoutMs = it
            log.info("$LOG_PREFIX:$OPERATION_TIMEOUT_MS_KEY -updatedTo-> $it")
        }
        clusterService.clusterSettings.addSettingsUpdateConsumer(DEFAULT_ITEMS_QUERY_COUNT) {
            defaultItemsQueryCount = it
            log.info("$LOG_PREFIX:$DEFAULT_ITEMS_QUERY_COUNT_KEY -updatedTo-> $it")
        }
        clusterService.clusterSettings.addSettingsUpdateConsumer(MAX_NOTIFICATION_CONFIGS) {
            maxNotificationConfigs = it
            log.info("$LOG_PREFIX:$MAX_NOTIFICATION_CONFIGS_KEY -updatedTo-> $it")
        }
        clusterService.clusterSettings.addSettingsUpdateConsumer(MAX_NOTIFICATION_GROUPS) {
            maxNotificationGroups = it
            log.info("$LOG_PREFIX:$MAX_NOTIFICATION_GROUPS_KEY -updatedTo-> $it")
        }
        clusterService.clusterSettings.addSettingsUpdateConsumer(MAX_NOTIFICATION_SENDERS) {
            maxNotificationSenders = it
            log.info("$LOG_PREFIX:$MAX_NOTIFICATION_SENDERS_KEY -updatedTo-> $it")
        }
        clusterService.clusterSettings.addSettingsUpdateConsumer(MAX_ACTIVE_RESPONSES) {
            maxActiveResponses = it
            log.info("$LOG_PREFIX:$MAX_ACTIVE_RESPONSES_KEY -updatedTo-> $it")
        }
        // No update consumers for ACTIVE_RESPONSE_BULK_* — those settings are NodeScope-only
        // and cannot change at runtime (BulkProcessor has no live-reconfig API).
    }

    // reset the settings values to default values for testing purpose
    @OpenForTesting
    fun reset() {
        operationTimeoutMs = DEFAULT_OPERATION_TIMEOUT_MS
        defaultItemsQueryCount = DEFAULT_ITEMS_QUERY_COUNT_VALUE
        maxNotificationConfigs = DEFAULT_MAX_NOTIFICATION_CONFIGS_VALUE
        maxNotificationGroups = DEFAULT_MAX_NOTIFICATION_GROUPS_VALUE
        maxNotificationSenders = DEFAULT_MAX_NOTIFICATION_SENDERS_VALUE
        maxActiveResponses = DEFAULT_MAX_ACTIVE_RESPONSES_VALUE
        activeResponseBulkFlushIntervalMs = DEFAULT_ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS
        activeResponseBulkMaxActions = DEFAULT_ACTIVE_RESPONSE_BULK_MAX_ACTIONS
    }
}
