package com.alexander.carplay.domain.model

enum class ProjectionConnectionState {
    IDLE,
    WAITING_VEHICLE,
    SEARCHING,
    WAITING_PERMISSION,
    CONNECTING,
    INIT,
    WAITING_PHONE,
    STREAMING,
    ERROR,
}
