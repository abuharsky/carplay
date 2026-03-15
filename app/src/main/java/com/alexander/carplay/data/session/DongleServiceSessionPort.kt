package com.alexander.carplay.data.session

import android.view.MotionEvent
import android.view.Surface
import com.alexander.carplay.domain.model.DiagnosticLogEntry
import com.alexander.carplay.domain.model.ProjectionSessionSnapshot
import com.alexander.carplay.domain.model.ProjectionUiEvent
import com.alexander.carplay.domain.port.ProjectionSessionPort
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class DongleServiceSessionPort(
    private val connector: DongleServiceConnector,
) : ProjectionSessionPort {
    override val state: StateFlow<ProjectionSessionSnapshot> = connector.state
    override val logs: StateFlow<List<DiagnosticLogEntry>> = connector.logs
    override val events: SharedFlow<ProjectionUiEvent> = connector.events

    override fun ensureServiceStarted() = connector.ensureServiceStarted()

    override fun startUsb() = connector.startUsb()

    override fun startReplay(capturePath: String) = connector.startReplay(capturePath)

    override fun bind() = connector.bind()

    override fun unbind() = connector.unbind()

    override fun requestReconnect() = connector.requestReconnect()

    override fun refreshRuntimeSettings() = connector.refreshRuntimeSettings()

    override fun setVideoStreamEnabled(enabled: Boolean) = connector.setVideoStreamEnabled(enabled)

    override fun selectDevice(deviceId: String) = connector.selectDevice(deviceId)

    override fun cancelDeviceConnection() = connector.cancelDeviceConnection()

    override fun attachSurface(surface: Surface) = connector.attachSurface(surface)

    override fun detachSurface() = connector.detachSurface()

    override fun sendMotionEvent(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ) = connector.sendMotionEvent(event, surfaceWidth, surfaceHeight)
}
