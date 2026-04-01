package com.alexander.carplay.domain.port

import android.view.MotionEvent
import android.view.Surface
import com.alexander.carplay.domain.model.DiagnosticLogEntry
import com.alexander.carplay.domain.model.ProjectionDeviceSettings
import com.alexander.carplay.domain.model.ProjectionSessionSnapshot
import com.alexander.carplay.domain.model.ProjectionUiEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface ProjectionSessionPort {
    val state: StateFlow<ProjectionSessionSnapshot>
    val logs: StateFlow<List<DiagnosticLogEntry>>
    val events: SharedFlow<ProjectionUiEvent>

    fun ensureServiceStarted()

    fun startUsb()

    fun startReplay(capturePath: String)

    fun bind()

    fun unbind()

    fun requestReconnect()

    fun debugSimulateVehicleSleepCycle()

    fun refreshRuntimeSettings()

    fun previewRuntimeSettings(settings: ProjectionDeviceSettings)

    fun setVideoStreamEnabled(enabled: Boolean)

    fun setActivityVisible(visible: Boolean)

    fun selectDevice(deviceId: String)

    fun cancelDeviceConnection()

    fun attachSurface(surface: Surface)

    fun detachSurface()

    fun sendMotionEvent(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int,
    )
}
