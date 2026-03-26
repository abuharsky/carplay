package com.alexander.carplay.app.di

import android.app.Application
import com.alexander.carplay.data.automotive.climate.ClimateController
import com.alexander.carplay.data.automotive.climate.SeatAutoComfortController
import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.alexander.carplay.data.settings.SharedPreferencesProjectionSettingsStore
import com.alexander.carplay.data.session.DongleServiceConnector
import com.alexander.carplay.data.session.DongleServiceSessionPort
import com.alexander.carplay.domain.port.ProjectionSessionPort
import com.alexander.carplay.domain.port.ProjectionSettingsPort
import com.alexander.carplay.domain.usecase.AttachProjectionSurfaceUseCase
import com.alexander.carplay.domain.usecase.BindProjectionUiUseCase
import com.alexander.carplay.domain.usecase.CancelProjectionDeviceConnectionUseCase
import com.alexander.carplay.domain.usecase.DetachProjectionSurfaceUseCase
import com.alexander.carplay.domain.usecase.LoadProjectionAdapterNameUseCase
import com.alexander.carplay.domain.usecase.LoadProjectionAutoConnectUseCase
import com.alexander.carplay.domain.usecase.LoadProjectionClimatePanelEnabledUseCase
import com.alexander.carplay.domain.usecase.ObserveDiagnosticLogsUseCase
import com.alexander.carplay.domain.usecase.ObserveProjectionUiEventsUseCase
import com.alexander.carplay.domain.usecase.ObserveProjectionStateUseCase
import com.alexander.carplay.domain.usecase.RefreshProjectionRuntimeSettingsUseCase
import com.alexander.carplay.domain.usecase.LoadProjectionDeviceSettingsUseCase
import com.alexander.carplay.domain.usecase.PreviewProjectionRuntimeSettingsUseCase
import com.alexander.carplay.domain.usecase.RequestProjectionReconnectUseCase
import com.alexander.carplay.domain.usecase.SaveProjectionAdapterNameUseCase
import com.alexander.carplay.domain.usecase.SaveProjectionAutoConnectUseCase
import com.alexander.carplay.domain.usecase.SaveProjectionClimatePanelEnabledUseCase
import com.alexander.carplay.domain.usecase.LoadProjectionCarPlaySafeAreaBottomUseCase
import com.alexander.carplay.domain.usecase.SaveProjectionCarPlaySafeAreaBottomUseCase
import com.alexander.carplay.domain.usecase.SaveProjectionDeviceSettingsUseCase
import com.alexander.carplay.domain.usecase.SendProjectionMotionUseCase
import com.alexander.carplay.domain.usecase.SelectProjectionDeviceUseCase
import com.alexander.carplay.domain.usecase.SetProjectionVideoStreamEnabledUseCase
import com.alexander.carplay.domain.usecase.StartReplaySessionUseCase
import com.alexander.carplay.domain.usecase.StartProjectionServiceUseCase
import com.alexander.carplay.domain.usecase.StartUsbSessionUseCase
import com.alexander.carplay.domain.usecase.UnbindProjectionUiUseCase

class AppContainer(application: Application) {
    val logStore = DiagnosticLogStore(application)
    val settingsPort: ProjectionSettingsPort = SharedPreferencesProjectionSettingsStore(application)
    val hasAndroidCarRuntime: Boolean = detectAndroidCarRuntime()
    val climateController: ClimateController? = if (hasAndroidCarRuntime) {
        ClimateController(application, logStore)
    } else {
        null
    }
    val seatAutoComfortController: SeatAutoComfortController? = climateController?.let { climateController ->
        SeatAutoComfortController(
            climateController = climateController,
            settingsPort = settingsPort,
            logStore = logStore,
        )
    }

    private val serviceConnector = DongleServiceConnector(application, logStore)

    private val sessionPort: ProjectionSessionPort = DongleServiceSessionPort(serviceConnector)

    val observeProjectionStateUseCase = ObserveProjectionStateUseCase(sessionPort)
    val observeDiagnosticLogsUseCase = ObserveDiagnosticLogsUseCase(sessionPort)
    val observeProjectionUiEventsUseCase = ObserveProjectionUiEventsUseCase(sessionPort)
    val loadProjectionDeviceSettingsUseCase = LoadProjectionDeviceSettingsUseCase(settingsPort)
    val saveProjectionDeviceSettingsUseCase = SaveProjectionDeviceSettingsUseCase(settingsPort)
    val loadProjectionAdapterNameUseCase = LoadProjectionAdapterNameUseCase(settingsPort)
    val saveProjectionAdapterNameUseCase = SaveProjectionAdapterNameUseCase(settingsPort)
    val loadProjectionAutoConnectUseCase = LoadProjectionAutoConnectUseCase(settingsPort)
    val saveProjectionAutoConnectUseCase = SaveProjectionAutoConnectUseCase(settingsPort)
    val loadProjectionClimatePanelEnabledUseCase = LoadProjectionClimatePanelEnabledUseCase(settingsPort)
    val saveProjectionClimatePanelEnabledUseCase = SaveProjectionClimatePanelEnabledUseCase(settingsPort)
    val loadProjectionCarPlaySafeAreaBottomUseCase = LoadProjectionCarPlaySafeAreaBottomUseCase(settingsPort)
    val saveProjectionCarPlaySafeAreaBottomUseCase = SaveProjectionCarPlaySafeAreaBottomUseCase(settingsPort)
    val startProjectionServiceUseCase = StartProjectionServiceUseCase(sessionPort)
    val startUsbSessionUseCase = StartUsbSessionUseCase(sessionPort)
    val startReplaySessionUseCase = StartReplaySessionUseCase(sessionPort)
    val bindProjectionUiUseCase = BindProjectionUiUseCase(sessionPort)
    val unbindProjectionUiUseCase = UnbindProjectionUiUseCase(sessionPort)
    val requestProjectionReconnectUseCase = RequestProjectionReconnectUseCase(sessionPort)
    val refreshProjectionRuntimeSettingsUseCase = RefreshProjectionRuntimeSettingsUseCase(sessionPort)
    val previewProjectionRuntimeSettingsUseCase = PreviewProjectionRuntimeSettingsUseCase(sessionPort)
    val setProjectionVideoStreamEnabledUseCase = SetProjectionVideoStreamEnabledUseCase(sessionPort)
    val selectProjectionDeviceUseCase = SelectProjectionDeviceUseCase(sessionPort)
    val cancelProjectionDeviceConnectionUseCase = CancelProjectionDeviceConnectionUseCase(sessionPort)
    val attachProjectionSurfaceUseCase = AttachProjectionSurfaceUseCase(sessionPort)
    val detachProjectionSurfaceUseCase = DetachProjectionSurfaceUseCase(sessionPort)
    val sendProjectionMotionUseCase = SendProjectionMotionUseCase(sessionPort)

    private fun detectAndroidCarRuntime(): Boolean {
        val available = runCatching {
            Class.forName("android.car.Car")
            Class.forName("android.car.hardware.hvac.CarHvacManager\$CarHvacEventCallback")
            Class.forName("android.car.hardware.cabin.CarCabinManager\$CarCabinEventCallback")
            true
        }.getOrElse { false }

        if (available) {
            logStore.info("Climate", "android.car runtime available")
        } else {
            logStore.info("Climate", "android.car runtime missing; climate panel will use placeholders")
        }
        return available
    }
}
