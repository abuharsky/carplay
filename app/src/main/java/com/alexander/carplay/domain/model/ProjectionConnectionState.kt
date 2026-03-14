package com.alexander.carplay.domain.model

enum class ProjectionConnectionState {
    IDLE,
    SEARCHING,
    WAITING_PERMISSION,
    CONNECTING,
    INIT,
    WAITING_PHONE,
    STREAMING,
    ERROR,
}

