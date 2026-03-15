package com.alexander.carplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.alexander.carplay.app.di.AppContainer

class CarPlayViewModelFactory(
    private val appContainer: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CarPlayViewModel::class.java))
        return CarPlayViewModel(
            observeProjectionStateUseCase = appContainer.observeProjectionStateUseCase,
            observeDiagnosticLogsUseCase = appContainer.observeDiagnosticLogsUseCase,
            observeProjectionUiEventsUseCase = appContainer.observeProjectionUiEventsUseCase,
            startProjectionServiceUseCase = appContainer.startProjectionServiceUseCase,
            startUsbSessionUseCase = appContainer.startUsbSessionUseCase,
            startReplaySessionUseCase = appContainer.startReplaySessionUseCase,
            bindProjectionUiUseCase = appContainer.bindProjectionUiUseCase,
            unbindProjectionUiUseCase = appContainer.unbindProjectionUiUseCase,
            requestProjectionReconnectUseCase = appContainer.requestProjectionReconnectUseCase,
            refreshProjectionRuntimeSettingsUseCase = appContainer.refreshProjectionRuntimeSettingsUseCase,
            setProjectionVideoStreamEnabledUseCase = appContainer.setProjectionVideoStreamEnabledUseCase,
            loadProjectionDeviceSettingsUseCase = appContainer.loadProjectionDeviceSettingsUseCase,
            saveProjectionDeviceSettingsUseCase = appContainer.saveProjectionDeviceSettingsUseCase,
            loadProjectionAdapterNameUseCase = appContainer.loadProjectionAdapterNameUseCase,
            saveProjectionAdapterNameUseCase = appContainer.saveProjectionAdapterNameUseCase,
            loadProjectionAutoConnectUseCase = appContainer.loadProjectionAutoConnectUseCase,
            saveProjectionAutoConnectUseCase = appContainer.saveProjectionAutoConnectUseCase,
            selectProjectionDeviceUseCase = appContainer.selectProjectionDeviceUseCase,
            cancelProjectionDeviceConnectionUseCase = appContainer.cancelProjectionDeviceConnectionUseCase,
            attachProjectionSurfaceUseCase = appContainer.attachProjectionSurfaceUseCase,
            detachProjectionSurfaceUseCase = appContainer.detachProjectionSurfaceUseCase,
            sendProjectionMotionUseCase = appContainer.sendProjectionMotionUseCase,
        ) as T
    }
}
