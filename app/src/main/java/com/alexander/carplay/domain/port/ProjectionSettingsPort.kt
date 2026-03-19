package com.alexander.carplay.domain.port

import com.alexander.carplay.domain.model.ProjectionDeviceSettings

interface ProjectionSettingsPort {
    fun getSettings(deviceId: String?): ProjectionDeviceSettings

    fun saveSettings(settings: ProjectionDeviceSettings)

    fun getAdapterName(): String

    fun saveAdapterName(name: String)

    fun isAutoConnectEnabled(): Boolean

    fun setAutoConnectEnabled(enabled: Boolean)

    fun getLastConnectedDeviceId(): String?

    fun setLastConnectedDeviceId(deviceId: String?)

    fun getCachedDeviceName(deviceId: String?): String?

    fun setCachedDeviceName(
        deviceId: String?,
        name: String?,
    )
}
