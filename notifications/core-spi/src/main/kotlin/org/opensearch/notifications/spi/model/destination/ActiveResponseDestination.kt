/*
 * Copyright Wazuh Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.notifications.spi.model.destination

/**
 * This class holds the contents of generic active response destination
 */
class ActiveResponseDestination(
    // val name: String,
    val type: String,
    val stateful_timeout: Int? = null,
    val executable: String,
    extra_args: String? = null,
    val location: String,
    val agent_id: String? = null
) : BaseDestination(DestinationType.ACTIVE_RESPONSE) {

    val extra_args: String? = extra_args?.ifBlank { null }

    init {

        // require(name.isNotBlank()) { "name is null or empty" }
        require(type.isNotBlank()) { "type is null or empty" }
        require(type in listOf("stateful", "stateless")) { "type must be either 'stateful' or 'stateless'" }
        require(executable.isNotBlank()) { "executable is null or empty" }
        require(location.isNotBlank()) { "location is null or empty" }
        require(location in listOf("local", "defined-agent", "all")) { "location must be 'local', 'defined-agent', or 'all'" }
        if (location == "defined-agent") {
            require(!agent_id.isNullOrEmpty()) { "agent_id is required when location is defined-agent" }
            require(agent_id!!.matches(Regex("^\\d+$"))) { "agent_id must contain only numeric characters" }
        }
        if (type == "stateful") {
            require(stateful_timeout != null) { "stateful_timeout is required for stateful type" }
            require(stateful_timeout > 0) { "stateful_timeout must be greater than 0 for stateful type" }
        }
    }
}
