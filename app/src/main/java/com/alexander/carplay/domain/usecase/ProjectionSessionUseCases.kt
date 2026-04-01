package com.alexander.carplay.domain.usecase

import android.view.MotionEvent
import android.view.Surface
import com.alexander.carplay.domain.model.DiagnosticLogEntry
import com.alexander.carplay.domain.model.ProjectionDeviceSettings
import com.alexander.carplay.domain.model.ProjectionSessionSnapshot
import com.alexander.carplay.domain.model.ProjectionUiEvent
import com.alexander.carplay.domain.port.ProjectionSessionPort
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class ObserveProjectionStateUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke(): StateFlow<ProjectionSessionSnapshot> = sessionPort.state
}

class ObserveDiagnosticLogsUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke(): StateFlow<List<DiagnosticLogEntry>> = sessionPort.logs
}

class ObserveProjectionUiEventsUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke(): SharedFlow<ProjectionUiEvent> = sessionPort.events
}

class StartProjectionServiceUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke() = sessionPort.ensureServiceStarted()
}

class StartUsbSessionUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke() = sessionPort.startUsb()
}

class StartReplaySessionUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke(capturePath: String) = sessionPort.startReplay(capturePath)
}

class BindProjectionUiUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke() = sessionPort.bind()
}

class UnbindProjectionUiUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke() = sessionPort.unbind()
}

class RequestProjectionReconnectUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke() = sessionPort.requestReconnect()
}

class DebugSimulateVehicleSleepCycleUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke() = sessionPort.debugSimulateVehicleSleepCycle()
}

class RefreshProjectionRuntimeSettingsUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke() = sessionPort.refreshRuntimeSettings()
}

class PreviewProjectionRuntimeSettingsUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke(settings: ProjectionDeviceSettings) = sessionPort.previewRuntimeSettings(settings)
}

class SetProjectionVideoStreamEnabledUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke(enabled: Boolean) = sessionPort.setVideoStreamEnabled(enabled)
}

class SetProjectionActivityVisibleUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke(visible: Boolean) = sessionPort.setActivityVisible(visible)
}

class SelectProjectionDeviceUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke(deviceId: String) = sessionPort.selectDevice(deviceId)
}

class CancelProjectionDeviceConnectionUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke() = sessionPort.cancelDeviceConnection()
}

class AttachProjectionSurfaceUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke(surface: Surface) = sessionPort.attachSurface(surface)
}

class DetachProjectionSurfaceUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke() = sessionPort.detachSurface()
}

class SendProjectionMotionUseCase(
    private val sessionPort: ProjectionSessionPort,
) {
    operator fun invoke(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ) = sessionPort.sendMotionEvent(event, surfaceWidth, surfaceHeight)
}
