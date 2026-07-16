/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.notifications

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.whenever
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.opensearch.cluster.ClusterState
import org.opensearch.cluster.LocalNodeClusterManagerListener
import org.opensearch.cluster.node.DiscoveryNode
import org.opensearch.cluster.node.DiscoveryNodes
import org.opensearch.cluster.service.ClusterService

internal class NotificationPluginTests {
    private lateinit var plugin: NotificationPlugin
    private lateinit var clusterService: ClusterService
    private lateinit var clusterState: ClusterState
    private lateinit var discoveryNodes: DiscoveryNodes
    private lateinit var localNode: DiscoveryNode

    @BeforeEach
    fun setup() {
        plugin = NotificationPlugin()
        clusterService = mock(ClusterService::class.java)
        clusterState = mock(ClusterState::class.java)
        discoveryNodes = mock(DiscoveryNodes::class.java)
        localNode = mock(DiscoveryNode::class.java)

        plugin.clusterService = clusterService
        whenever(clusterService.state()).thenReturn(clusterState)
        whenever(clusterState.nodes()).thenReturn(discoveryNodes)
    }

    @Test
    fun `test onNodeStarted registers a local node cluster manager listener`() {
        // Registering a LocalNodeClusterManagerListener, instead of relying on the role check
        // that used to run at onNodeStarted time, is what lets initialization wait for (and react
        // to) this specific node actually winning the election, instead of firing on every
        // cluster-manager-eligible node during a full cluster restart.
        whenever(discoveryNodes.isLocalNodeElectedClusterManager).thenReturn(false)

        plugin.onNodeStarted(localNode)

        verify(clusterService).addLocalNodeClusterManagerListener(any())
    }

    @Test
    fun `test onNodeStarted triggers the listener immediately if already elected`() {
        whenever(discoveryNodes.isLocalNodeElectedClusterManager).thenReturn(true)

        assertDoesNotThrow { plugin.onNodeStarted(localNode) }

        verify(clusterService).addLocalNodeClusterManagerListener(any())
    }

    @Test
    fun `test the registered listener is safe to invoke repeatedly and does not throw`() {
        whenever(discoveryNodes.isLocalNodeElectedClusterManager).thenReturn(false)

        val captor = argumentCaptor<LocalNodeClusterManagerListener>()
        plugin.onNodeStarted(localNode)
        verify(clusterService).addLocalNodeClusterManagerListener(captor.capture())

        val listener = captor.firstValue
        assertDoesNotThrow {
            listener.onClusterManager()
            listener.onClusterManager()
            listener.offClusterManager()
        }
    }
}
