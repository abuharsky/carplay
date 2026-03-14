package com.alexander.carplay.domain.model

data class ProjectionSessionSnapshot(
    val state: ProjectionConnectionState = ProjectionConnectionState.IDLE,
    val statusMessage: String = "Service is idle",
    val adapterDescription: String? = null,
    val phoneDescription: String? = null,
    val currentDeviceId: String? = null,
    val currentDeviceName: String? = null,
    val streamDescription: String? = null,
    val devices: List<ProjectionDeviceSnapshot> = emptyList(),
    val appliedAudioRoute: ProjectionAudioRoute = ProjectionAudioRoute.ADAPTER,
    val appliedMicRoute: ProjectionMicRoute = ProjectionMicRoute.ADAPTER,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val surfaceAttached: Boolean = false,
    val lastError: String? = null,
)
