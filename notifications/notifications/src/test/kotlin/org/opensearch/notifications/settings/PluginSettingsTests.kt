/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.notifications.settings

import com.nhaarman.mockitokotlin2.whenever
import org.junit.Assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.settings.ClusterSettings
import org.opensearch.common.settings.Settings
import org.opensearch.notifications.NotificationPlugin

internal class PluginSettingsTests {
    private lateinit var plugin: NotificationPlugin
    private lateinit var clusterService: ClusterService

    private val keyPrefix = "opensearch.notifications"
    private val generalKeyPrefix = "$keyPrefix.general"
    private val operationTimeoutKey = "$generalKeyPrefix.operation_timeout_ms"
    private val defaultItemQueryCountKey = "$generalKeyPrefix.default_items_query_count"
    private val legacyAlertingFilterByBackendRolesKey = "opendistro.alerting.filter_by_backend_roles"
    private val alertingFilterByBackendRolesKey = "plugins.alerting.filter_by_backend_roles"
    private val filterByBackendRolesKey = "$generalKeyPrefix.filter_by_backend_roles"
    private val multiTenancyEnabledKey = "plugins.notifications.multi_tenancy_enabled"
    private val maxNotificationConfigsKey = "plugins.notifications.max_notification_configs"
    private val maxNotificationGroupsKey = "plugins.notifications.max_notification_groups"
    private val maxNotificationSendersKey = "plugins.notifications.max_notification_senders"
    private val maxActiveResponsesKey = "plugins.notifications.max_active_responses"

    private val defaultSettings = Settings.builder()
        .put(operationTimeoutKey, 60000L)
        .put(defaultItemQueryCountKey, 100L)
        .put(filterByBackendRolesKey, false)
        .build()

    @BeforeEach
    fun setup() {
        plugin = NotificationPlugin()
        clusterService = mock(ClusterService::class.java, "clusterService")
    }

    @AfterEach
    fun reset() {
        PluginSettings.reset()
    }

    @Test
    fun `test get all settings as defaults`() {
        val settings = plugin.settings

        Assert.assertTrue(
            settings.containsAll(
                listOf<Any>(
                    PluginSettings.OPERATION_TIMEOUT_MS,
                    PluginSettings.DEFAULT_ITEMS_QUERY_COUNT,
                    PluginSettings.FILTER_BY_BACKEND_ROLES
                )
            )
        )
        Assertions.assertEquals(defaultSettings[operationTimeoutKey], PluginSettings.operationTimeoutMs.toString())
        Assertions.assertEquals(
            defaultSettings[defaultItemQueryCountKey],
            PluginSettings.defaultItemsQueryCount.toString()
        )
    }

    @Test
    fun `test update settings should take cluster settings if available`() {
        val clusterSettings = Settings.builder()
            .put(operationTimeoutKey, 50000L)
            .put(defaultItemQueryCountKey, 200)
            .build()

        whenever(clusterService.settings).thenReturn(defaultSettings)
        whenever(clusterService.clusterSettings).thenReturn(
            ClusterSettings(
                clusterSettings,
                setOf(
                    PluginSettings.OPERATION_TIMEOUT_MS,
                    PluginSettings.DEFAULT_ITEMS_QUERY_COUNT,
                    PluginSettings.MAX_NOTIFICATION_CONFIGS,
                    PluginSettings.MAX_NOTIFICATION_GROUPS,
                    PluginSettings.MAX_NOTIFICATION_SENDERS,
                    PluginSettings.MAX_ACTIVE_RESPONSES,
                    PluginSettings.ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS,
                    PluginSettings.ACTIVE_RESPONSE_BULK_MAX_ACTIONS
                )
            )
        )
        PluginSettings.addSettingsUpdateConsumer(clusterService)
        Assertions.assertEquals(
            50000L,
            clusterService.clusterSettings.get(PluginSettings.OPERATION_TIMEOUT_MS)
        )
        Assertions.assertEquals(
            200,
            clusterService.clusterSettings.get(PluginSettings.DEFAULT_ITEMS_QUERY_COUNT)
        )
    }

    @Test
    fun `test update settings should fall back to node settings if cluster settings is not available`() {
        val clusterSettings = Settings.builder().build()
        whenever(clusterService.settings).thenReturn(defaultSettings)
        whenever(clusterService.clusterSettings).thenReturn(
            ClusterSettings(
                clusterSettings,
                setOf(
                    PluginSettings.OPERATION_TIMEOUT_MS,
                    PluginSettings.DEFAULT_ITEMS_QUERY_COUNT,
                    PluginSettings.MAX_NOTIFICATION_CONFIGS,
                    PluginSettings.MAX_NOTIFICATION_GROUPS,
                    PluginSettings.MAX_NOTIFICATION_SENDERS,
                    PluginSettings.MAX_ACTIVE_RESPONSES,
                    PluginSettings.ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS,
                    PluginSettings.ACTIVE_RESPONSE_BULK_MAX_ACTIONS
                )
            )
        )
        PluginSettings.addSettingsUpdateConsumer(clusterService)
        Assertions.assertEquals(
            defaultSettings[operationTimeoutKey],
            clusterService.clusterSettings.get(PluginSettings.OPERATION_TIMEOUT_MS).toString()
        )
        Assertions.assertEquals(
            defaultSettings[defaultItemQueryCountKey],
            clusterService.clusterSettings.get(PluginSettings.DEFAULT_ITEMS_QUERY_COUNT).toString()
        )
    }

    @Test
    fun `test filter by backend roles setting uses notifications setting`() {
        val clusterSettings = Settings.builder()
            .put(filterByBackendRolesKey, true)
            .build()

        whenever(clusterService.settings).thenReturn(defaultSettings)
        whenever(clusterService.clusterSettings).thenReturn(
            ClusterSettings(
                clusterSettings,
                setOf(
                    PluginSettings.OPERATION_TIMEOUT_MS,
                    PluginSettings.DEFAULT_ITEMS_QUERY_COUNT,
                    PluginSettings.MAX_NOTIFICATION_CONFIGS,
                    PluginSettings.MAX_NOTIFICATION_GROUPS,
                    PluginSettings.MAX_NOTIFICATION_SENDERS,
                    PluginSettings.MAX_ACTIVE_RESPONSES,
                    PluginSettings.ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS,
                    PluginSettings.ACTIVE_RESPONSE_BULK_MAX_ACTIONS,
                    PluginSettings.FILTER_BY_BACKEND_ROLES
                )
            )
        )
        PluginSettings.addSettingsUpdateConsumer(clusterService)
        Assertions.assertEquals(true, PluginSettings.isRbacEnabled())
    }

    @Test
    fun `test filter by backend roles setting prioritizes notifications setting`() {
        val clusterSettings = Settings.builder()
            .put(legacyAlertingFilterByBackendRolesKey, false)
            .put(alertingFilterByBackendRolesKey, false)
            .put(filterByBackendRolesKey, true)
            .build()

        whenever(clusterService.settings).thenReturn(defaultSettings)
        whenever(clusterService.clusterSettings).thenReturn(
            ClusterSettings(
                clusterSettings,
                setOf(
                    PluginSettings.OPERATION_TIMEOUT_MS,
                    PluginSettings.DEFAULT_ITEMS_QUERY_COUNT,
                    PluginSettings.MAX_NOTIFICATION_CONFIGS,
                    PluginSettings.MAX_NOTIFICATION_GROUPS,
                    PluginSettings.MAX_NOTIFICATION_SENDERS,
                    PluginSettings.MAX_ACTIVE_RESPONSES,
                    PluginSettings.ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS,
                    PluginSettings.ACTIVE_RESPONSE_BULK_MAX_ACTIONS,
                    PluginSettings.LEGACY_ALERTING_FILTER_BY_BACKEND_ROLES,
                    PluginSettings.ALERTING_FILTER_BY_BACKEND_ROLES,
                    PluginSettings.FILTER_BY_BACKEND_ROLES
                )
            )
        )
        PluginSettings.addSettingsUpdateConsumer(clusterService)
        Assertions.assertEquals(true, PluginSettings.isRbacEnabled())
    }

    @Test
    fun `test filter by backend roles setting falls back to alerting setting`() {
        val clusterSettings = Settings.builder()
            .put(legacyAlertingFilterByBackendRolesKey, false)
            .put(alertingFilterByBackendRolesKey, true)
            .build()

        whenever(clusterService.settings).thenReturn(defaultSettings)
        whenever(clusterService.clusterSettings).thenReturn(
            ClusterSettings(
                clusterSettings,
                setOf(
                    PluginSettings.OPERATION_TIMEOUT_MS,
                    PluginSettings.DEFAULT_ITEMS_QUERY_COUNT,
                    PluginSettings.MAX_NOTIFICATION_CONFIGS,
                    PluginSettings.MAX_NOTIFICATION_GROUPS,
                    PluginSettings.MAX_NOTIFICATION_SENDERS,
                    PluginSettings.MAX_ACTIVE_RESPONSES,
                    PluginSettings.ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS,
                    PluginSettings.ACTIVE_RESPONSE_BULK_MAX_ACTIONS,
                    PluginSettings.LEGACY_ALERTING_FILTER_BY_BACKEND_ROLES,
                    PluginSettings.ALERTING_FILTER_BY_BACKEND_ROLES,
                    PluginSettings.FILTER_BY_BACKEND_ROLES
                )
            )
        )
        PluginSettings.addSettingsUpdateConsumer(clusterService)
        Assertions.assertEquals(true, PluginSettings.isRbacEnabled())
    }

    @Test
    fun `test filter by backend roles setting falls back to alerting legacy setting when newer settings are not set`() {
        val clusterSettings = Settings.builder()
            .put(legacyAlertingFilterByBackendRolesKey, true)
            .build()

        whenever(clusterService.settings).thenReturn(defaultSettings)
        whenever(clusterService.clusterSettings).thenReturn(
            ClusterSettings(
                clusterSettings,
                setOf(
                    PluginSettings.OPERATION_TIMEOUT_MS,
                    PluginSettings.DEFAULT_ITEMS_QUERY_COUNT,
                    PluginSettings.MAX_NOTIFICATION_CONFIGS,
                    PluginSettings.MAX_NOTIFICATION_GROUPS,
                    PluginSettings.MAX_NOTIFICATION_SENDERS,
                    PluginSettings.MAX_ACTIVE_RESPONSES,
                    PluginSettings.ACTIVE_RESPONSE_BULK_FLUSH_INTERVAL_MS,
                    PluginSettings.ACTIVE_RESPONSE_BULK_MAX_ACTIONS,
                    PluginSettings.LEGACY_ALERTING_FILTER_BY_BACKEND_ROLES,
                    PluginSettings.ALERTING_FILTER_BY_BACKEND_ROLES,
                    PluginSettings.FILTER_BY_BACKEND_ROLES
                )
            )
        )
        PluginSettings.addSettingsUpdateConsumer(clusterService)
        Assertions.assertEquals(true, PluginSettings.isRbacEnabled())
    }

    @Test
    fun `test multi_tenancy_enabled setting defaults to false`() {
        Assertions.assertEquals(false, PluginSettings.MULTI_TENANCY_ENABLED.getDefault(Settings.EMPTY))
    }

    @Test
    fun `test multi_tenancy_enabled setting is registered`() {
        val settings = plugin.settings
        Assert.assertTrue(settings.contains(PluginSettings.MULTI_TENANCY_ENABLED))
    }

    @Test
    fun `test multi_tenancy_enabled setting reads from config`() {
        val settings = Settings.builder()
            .put(multiTenancyEnabledKey, true)
            .build()
        Assertions.assertEquals(true, PluginSettings.MULTI_TENANCY_ENABLED.get(settings))
    }

    @Test
    fun `test resource limit settings fall back to their defaults`() {
        Assertions.assertEquals(40, PluginSettings.MAX_NOTIFICATION_CONFIGS.get(Settings.EMPTY))
        Assertions.assertEquals(10, PluginSettings.MAX_NOTIFICATION_GROUPS.get(Settings.EMPTY))
        Assertions.assertEquals(5, PluginSettings.MAX_NOTIFICATION_SENDERS.get(Settings.EMPTY))
        Assertions.assertEquals(10, PluginSettings.MAX_ACTIVE_RESPONSES.get(Settings.EMPTY))
    }

    @Test
    fun `test resource limit settings have no upper bound`() {
        val settings = Settings.builder()
            .put(maxNotificationConfigsKey, 100000)
            .put(maxNotificationGroupsKey, 100000)
            .put(maxNotificationSendersKey, 100000)
            .put(maxActiveResponsesKey, 100000)
            .build()

        Assertions.assertEquals(100000, PluginSettings.MAX_NOTIFICATION_CONFIGS.get(settings))
        Assertions.assertEquals(100000, PluginSettings.MAX_NOTIFICATION_GROUPS.get(settings))
        Assertions.assertEquals(100000, PluginSettings.MAX_NOTIFICATION_SENDERS.get(settings))
        Assertions.assertEquals(100000, PluginSettings.MAX_ACTIVE_RESPONSES.get(settings))
    }

    @Test
    fun `test resource limit settings accept Integer MAX_VALUE`() {
        val settings = Settings.builder()
            .put(maxNotificationConfigsKey, Int.MAX_VALUE)
            .put(maxNotificationGroupsKey, Int.MAX_VALUE)
            .put(maxNotificationSendersKey, Int.MAX_VALUE)
            .put(maxActiveResponsesKey, Int.MAX_VALUE)
            .build()

        Assertions.assertEquals(Int.MAX_VALUE, PluginSettings.MAX_NOTIFICATION_CONFIGS.get(settings))
        Assertions.assertEquals(Int.MAX_VALUE, PluginSettings.MAX_NOTIFICATION_GROUPS.get(settings))
        Assertions.assertEquals(Int.MAX_VALUE, PluginSettings.MAX_NOTIFICATION_SENDERS.get(settings))
        Assertions.assertEquals(Int.MAX_VALUE, PluginSettings.MAX_ACTIVE_RESPONSES.get(settings))
    }

    @Test
    fun `test resource limit settings accept zero`() {
        val settings = Settings.builder()
            .put(maxNotificationConfigsKey, 0)
            .put(maxNotificationGroupsKey, 0)
            .put(maxNotificationSendersKey, 0)
            .put(maxActiveResponsesKey, 0)
            .build()

        Assertions.assertEquals(0, PluginSettings.MAX_NOTIFICATION_CONFIGS.get(settings))
        Assertions.assertEquals(0, PluginSettings.MAX_NOTIFICATION_GROUPS.get(settings))
        Assertions.assertEquals(0, PluginSettings.MAX_NOTIFICATION_SENDERS.get(settings))
        Assertions.assertEquals(0, PluginSettings.MAX_ACTIVE_RESPONSES.get(settings))
    }

    @Test
    fun `test resource limit settings reject negative values`() {
        assertThrows<IllegalArgumentException> {
            PluginSettings.MAX_NOTIFICATION_CONFIGS.get(
                Settings.builder().put(maxNotificationConfigsKey, -1).build()
            )
        }
        assertThrows<IllegalArgumentException> {
            PluginSettings.MAX_NOTIFICATION_GROUPS.get(
                Settings.builder().put(maxNotificationGroupsKey, -1).build()
            )
        }
        assertThrows<IllegalArgumentException> {
            PluginSettings.MAX_NOTIFICATION_SENDERS.get(
                Settings.builder().put(maxNotificationSendersKey, -1).build()
            )
        }
        assertThrows<IllegalArgumentException> {
            PluginSettings.MAX_ACTIVE_RESPONSES.get(
                Settings.builder().put(maxActiveResponsesKey, -1).build()
            )
        }
    }

    @Test
    fun `test remote metadata settings use plugins notifications prefix`() {
        Assertions.assertTrue(PluginSettings.REMOTE_METADATA_STORE_TYPE.key.startsWith("plugins.notifications."))
        Assertions.assertTrue(PluginSettings.REMOTE_METADATA_ENDPOINT.key.startsWith("plugins.notifications."))
        Assertions.assertTrue(PluginSettings.REMOTE_METADATA_REGION.key.startsWith("plugins.notifications."))
        Assertions.assertTrue(PluginSettings.REMOTE_METADATA_SERVICE_NAME.key.startsWith("plugins.notifications."))
    }
}
