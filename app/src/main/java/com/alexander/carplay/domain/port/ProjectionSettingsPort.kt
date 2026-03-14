package com.alexander.carplay.domain.port

import com.alexander.carplay.domain.model.ProjectionDeviceSettings

interface ProjectionSettingsPort {
    fun getSettings(deviceId: String?): ProjectionDeviceSettings

    fun saveSettings(settings: ProjectionDeviceSettings)

    fun getLastConnectedDeviceId(): String?

    fun setLastConnectedDeviceId(deviceId: String?)
}
