package com.alexander.carplay.domain.usecase

import com.alexander.carplay.domain.model.ProjectionDeviceSettings
import com.alexander.carplay.domain.port.ProjectionSettingsPort

class LoadProjectionDeviceSettingsUseCase(
    private val settingsPort: ProjectionSettingsPort,
) {
    operator fun invoke(deviceId: String?): ProjectionDeviceSettings = settingsPort.getSettings(deviceId)
}

class SaveProjectionDeviceSettingsUseCase(
    private val settingsPort: ProjectionSettingsPort,
) {
    operator fun invoke(settings: ProjectionDeviceSettings) = settingsPort.saveSettings(settings)
}
