package com.alexander.carplay.app.di

import android.app.Application
import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.alexander.carplay.data.session.DongleServiceConnector
import com.alexander.carplay.data.session.DongleServiceSessionPort
import com.alexander.carplay.domain.port.ProjectionSessionPort
import com.alexander.carplay.domain.usecase.AttachProjectionSurfaceUseCase
import com.alexander.carplay.domain.usecase.BindProjectionUiUseCase
import com.alexander.carplay.domain.usecase.DetachProjectionSurfaceUseCase
import com.alexander.carplay.domain.usecase.ObserveDiagnosticLogsUseCase
import com.alexander.carplay.domain.usecase.ObserveProjectionStateUseCase
import com.alexander.carplay.domain.usecase.RequestProjectionReconnectUseCase
import com.alexander.carplay.domain.usecase.SendProjectionMotionUseCase
import com.alexander.carplay.domain.usecase.StartReplaySessionUseCase
import com.alexander.carplay.domain.usecase.StartProjectionServiceUseCase
import com.alexander.carplay.domain.usecase.StartUsbSessionUseCase
import com.alexander.carplay.domain.usecase.UnbindProjectionUiUseCase

class AppContainer(application: Application) {
    val logStore = DiagnosticLogStore()

    private val serviceConnector = DongleServiceConnector(application, logStore)

    private val sessionPort: ProjectionSessionPort = DongleServiceSessionPort(serviceConnector)

    val observeProjectionStateUseCase = ObserveProjectionStateUseCase(sessionPort)
    val observeDiagnosticLogsUseCase = ObserveDiagnosticLogsUseCase(sessionPort)
    val startProjectionServiceUseCase = StartProjectionServiceUseCase(sessionPort)
    val startUsbSessionUseCase = StartUsbSessionUseCase(sessionPort)
    val startReplaySessionUseCase = StartReplaySessionUseCase(sessionPort)
    val bindProjectionUiUseCase = BindProjectionUiUseCase(sessionPort)
    val unbindProjectionUiUseCase = UnbindProjectionUiUseCase(sessionPort)
    val requestProjectionReconnectUseCase = RequestProjectionReconnectUseCase(sessionPort)
    val attachProjectionSurfaceUseCase = AttachProjectionSurfaceUseCase(sessionPort)
    val detachProjectionSurfaceUseCase = DetachProjectionSurfaceUseCase(sessionPort)
    val sendProjectionMotionUseCase = SendProjectionMotionUseCase(sessionPort)
}
