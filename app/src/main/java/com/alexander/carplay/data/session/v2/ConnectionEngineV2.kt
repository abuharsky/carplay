package com.alexander.carplay.data.session.v2

class ConnectionEngineV2(
    private val logInfo: (source: String, message: String) -> Unit,
) {
    companion object {
        private const val SOURCE = "FlowV2"
    }

    private var snapshot = ConnectionSnapshotV2()

    fun snapshot(): ConnectionSnapshotV2 = snapshot

    fun onEvent(event: ConnectionEventV2): ReductionResultV2 {
        val previous = snapshot
        val result = ConnectionReducerV2.reduce(previous, event)
        snapshot = result.snapshot
        if (result.snapshot != previous || result.effects.isNotEmpty()) {
            logInfo(SOURCE, describeTransition(previous, result.snapshot, event, result.effects))
        }
        return result
    }

    private fun describeTransition(
        previous: ConnectionSnapshotV2,
        current: ConnectionSnapshotV2,
        event: ConnectionEventV2,
        effects: List<ConnectionEffectV2>,
    ): String {
        val changes = mutableListOf<String>()
        if (previous.transport != current.transport) {
            changes += "transport ${describeTransport(previous.transport)} -> ${describeTransport(current.transport)}"
        }
        if (previous.discovery != current.discovery) {
            changes += "discovery ${describeDiscovery(previous.discovery)} -> ${describeDiscovery(current.discovery)}"
        }
        if (previous.projection != current.projection) {
            changes += "projection ${describeProjection(previous.projection)} -> ${describeProjection(current.projection)}"
        }
        if (previous.policy != current.policy) {
            changes += "policy ${describePolicy(previous.policy)} -> ${describePolicy(current.policy)}"
        }
        if (changes.isEmpty()) {
            changes += "state unchanged"
        }
        val effectsLabel = if (effects.isEmpty()) {
            ""
        } else {
            " | effects=${effects.joinToString { it.javaClass.simpleName.ifBlank { it.toString() } }}"
        }
        return "Event ${event.javaClass.simpleName}: ${changes.joinToString("; ")}$effectsLabel"
    }

    private fun describeTransport(lane: TransportLaneV2): String {
        return "${lane.state.name}(epoch=${lane.epoch}, ack=${lane.openAcknowledged}, label=${lane.connectionLabel ?: "-"}, failure=${lane.lastFailure ?: "-"})"
    }

    private fun describeDiscovery(lane: DiscoveryLaneV2): String {
        return "${lane.state.name}(device=${lane.activeDeviceId ?: "-"}, reason=${lane.lastReason ?: "-"})"
    }

    private fun describeProjection(lane: ProjectionLaneV2): String {
        return "${lane.state.name}(raw=${lane.rawPhase ?: "-"}, phone=${lane.phoneType ?: "-"}, wifi=${lane.wifiState ?: "-"}, video=${lane.videoWidth ?: 0}x${lane.videoHeight ?: 0}, firstVideo=${lane.firstVideoSeen})"
    }

    private fun describePolicy(lane: PolicyLaneV2): String {
        return "vehicleReady=${lane.vehicleReady}, known=${lane.knownDeviceCount}, auto=${lane.autoConnectEligible}, selection=${lane.selectionMode.name}"
    }
}
