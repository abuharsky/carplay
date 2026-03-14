package com.alexander.carplay.domain.port

import android.view.MotionEvent
import android.view.Surface
import com.alexander.carplay.domain.model.DiagnosticLogEntry
import com.alexander.carplay.domain.model.ProjectionSessionSnapshot
import kotlinx.coroutines.flow.StateFlow

interface ProjectionSessionPort {
    val state: StateFlow<ProjectionSessionSnapshot>
    val logs: StateFlow<List<DiagnosticLogEntry>>

    fun ensureServiceStarted()

    fun startUsb()

    fun startReplay(capturePath: String)

    fun bind()

    fun unbind()

    fun requestReconnect()

    fun attachSurface(surface: Surface)

    fun detachSurface()

    fun sendMotionEvent(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int,
    )
}
