package com.alexander.carplay.domain.model

data class ProjectionDeviceSnapshot(
    val id: String,
    val name: String,
    val type: String? = null,
    val isActive: Boolean = false,
    val isSelected: Boolean = false,
    val isConnecting: Boolean = false,
)
