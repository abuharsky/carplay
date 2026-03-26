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

class LoadProjectionAdapterNameUseCase(
    private val settingsPort: ProjectionSettingsPort,
) {
    operator fun invoke(): String = settingsPort.getAdapterName()
}

class SaveProjectionAdapterNameUseCase(
    private val settingsPort: ProjectionSettingsPort,
) {
    operator fun invoke(name: String) = settingsPort.saveAdapterName(name)
}

class LoadProjectionAutoConnectUseCase(
    private val settingsPort: ProjectionSettingsPort,
) {
    operator fun invoke(): Boolean = settingsPort.isAutoConnectEnabled()
}

class SaveProjectionAutoConnectUseCase(
    private val settingsPort: ProjectionSettingsPort,
) {
    operator fun invoke(enabled: Boolean) = settingsPort.setAutoConnectEnabled(enabled)
}

class LoadProjectionClimatePanelEnabledUseCase(
    private val settingsPort: ProjectionSettingsPort,
) {
    operator fun invoke(): Boolean = settingsPort.isClimatePanelEnabled()
}

class SaveProjectionClimatePanelEnabledUseCase(
    private val settingsPort: ProjectionSettingsPort,
) {
    operator fun invoke(enabled: Boolean) = settingsPort.setClimatePanelEnabled(enabled)
}

class LoadProjectionCarPlaySafeAreaBottomUseCase(
    private val settingsPort: ProjectionSettingsPort,
) {
    operator fun invoke(): Int = settingsPort.getCarPlaySafeAreaBottomDp()
}

class SaveProjectionCarPlaySafeAreaBottomUseCase(
    private val settingsPort: ProjectionSettingsPort,
) {
    operator fun invoke(bottomDp: Int) = settingsPort.setCarPlaySafeAreaBottomDp(bottomDp)
}
