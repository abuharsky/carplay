package com.alexander.carplay.data.session.v2

enum class TransportStateV2 {
    DETACHED,
    OPENING,
    OPEN,
    CLOSED,
    FAILED,
}

enum class DiscoveryStateV2 {
    IDLE,
    MANUAL_SELECTION,
    SCANNING,
    DEVICE_FOUND,
    BT_CONNECTING,
    BT_CONNECTED,
    RETRYING,
}

enum class ProjectionStateV2 {
    NONE,
    PLUGGED,
    NEGOTIATING,
    STREAMING,
    DISCONNECTED,
    ENDED,
    FAILED,
}

enum class SelectionModeV2 {
    NONE,
    AUTO,
    MANUAL,
}

data class TransportLaneV2(
    val state: TransportStateV2 = TransportStateV2.DETACHED,
    val epoch: Long = 0L,
    val connectionLabel: String? = null,
    val openAcknowledged: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val lastFailure: String? = null,
)

data class DiscoveryLaneV2(
    val state: DiscoveryStateV2 = DiscoveryStateV2.IDLE,
    val activeDeviceId: String? = null,
    val lastReason: String? = null,
)

data class ProjectionLaneV2(
    val state: ProjectionStateV2 = ProjectionStateV2.NONE,
    val rawPhase: Int? = null,
    val phoneType: Int? = null,
    val wifiState: Int? = null,
    val firstVideoSeen: Boolean = false,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
)

data class PolicyLaneV2(
    val vehicleReady: Boolean = true,
    val knownDeviceCount: Int = 0,
    val autoConnectEligible: Boolean = false,
    val selectionMode: SelectionModeV2 = SelectionModeV2.NONE,
)

data class ConnectionSnapshotV2(
    val transport: TransportLaneV2 = TransportLaneV2(),
    val discovery: DiscoveryLaneV2 = DiscoveryLaneV2(),
    val projection: ProjectionLaneV2 = ProjectionLaneV2(),
    val policy: PolicyLaneV2 = PolicyLaneV2(),
) {
    fun isSessionEstablished(): Boolean {
        return projection.state == ProjectionStateV2.PLUGGED ||
            projection.state == ProjectionStateV2.NEGOTIATING ||
            projection.state == ProjectionStateV2.STREAMING
    }

    fun isStreamingActive(): Boolean {
        return projection.state == ProjectionStateV2.STREAMING || projection.firstVideoSeen
    }
}
