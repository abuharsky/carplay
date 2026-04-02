package com.alexander.carplay.domain.port

import com.alexander.carplay.domain.model.ProjectionDeviceSettings

interface ProjectionSettingsPort {
    fun getSettings(deviceId: String?): ProjectionDeviceSettings

    fun saveSettings(settings: ProjectionDeviceSettings)

    fun getAdapterName(): String

    fun saveAdapterName(name: String)

    fun isAutoConnectEnabled(): Boolean

    fun setAutoConnectEnabled(enabled: Boolean)

    fun isClimatePanelEnabled(): Boolean

    fun setClimatePanelEnabled(enabled: Boolean)

    fun getCarPlaySafeAreaBottomDp(): Int

    fun setCarPlaySafeAreaBottomDp(bottomDp: Int)

    fun getLastConnectedDeviceId(): String?

    fun setLastConnectedDeviceId(deviceId: String?)

    fun getCachedDeviceName(deviceId: String?): String?

    fun setCachedDeviceName(
        deviceId: String?,
        name: String?,
    )

    fun isMediaSessionFixEnabled(): Boolean

    fun setMediaSessionFixEnabled(enabled: Boolean)

    fun isAudioFocusFixEnabled(): Boolean

    fun setAudioFocusFixEnabled(enabled: Boolean)

    fun isA2dpDisconnectFixEnabled(): Boolean

    fun setA2dpDisconnectFixEnabled(enabled: Boolean)

    fun isMediaSessionMetadataEnabled(): Boolean

    fun setMediaSessionMetadataEnabled(enabled: Boolean)
}
