package com.alexander.carplay.presentation.viewmodel

import android.view.MotionEvent
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexander.carplay.R
import com.alexander.carplay.domain.model.DiagnosticLogEntry
import com.alexander.carplay.domain.model.ProjectionConnectionState
import com.alexander.carplay.domain.model.ProjectionSessionSnapshot
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CarPlayUiState(
    val stateLabel: String,
    val statusMessage: String,
    val overlayColorRes: Int,
    val showConnectButton: Boolean,
    val diagnosticsText: String,
    val videoWidth: Int?,
    val videoHeight: Int?,
)

class CarPlayViewModel(
    observeProjectionStateUseCase: ObserveProjectionStateUseCase,
    observeDiagnosticLogsUseCase: ObserveDiagnosticLogsUseCase,
    private val startProjectionServiceUseCase: StartProjectionServiceUseCase,
    private val startUsbSessionUseCase: StartUsbSessionUseCase,
    private val startReplaySessionUseCase: StartReplaySessionUseCase,
    private val bindProjectionUiUseCase: BindProjectionUiUseCase,
    private val unbindProjectionUiUseCase: UnbindProjectionUiUseCase,
    private val requestProjectionReconnectUseCase: RequestProjectionReconnectUseCase,
    private val attachProjectionSurfaceUseCase: AttachProjectionSurfaceUseCase,
    private val detachProjectionSurfaceUseCase: DetachProjectionSurfaceUseCase,
    private val sendProjectionMotionUseCase: SendProjectionMotionUseCase,
) : ViewModel() {
    companion object {
        const val DEFAULT_REPLAY_CAPTURE_PATH =
            "/data/local/tmp/test-capture.bin"
    }

    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val uiState: StateFlow<CarPlayUiState> = combine(
        observeProjectionStateUseCase(),
        observeDiagnosticLogsUseCase(),
    ) { snapshot, logs ->
        snapshot.toUiState(logs)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProjectionSessionSnapshot().toUiState(emptyList()),
    )

    fun onStart() {
        startProjectionServiceUseCase()
    }

    fun onBindUi() {
        startProjectionServiceUseCase()
        bindProjectionUiUseCase()
    }

    fun onUnbindUi() {
        detachProjectionSurfaceUseCase()
        unbindProjectionUiUseCase()
    }

    fun onConnectClicked() {
        startUsbSessionUseCase()
    }

    fun onReplayClicked(
        capturePath: String = DEFAULT_REPLAY_CAPTURE_PATH,
    ) {
        startReplaySessionUseCase(capturePath)
    }

    fun onSurfaceAvailable(surface: Surface) {
        attachProjectionSurfaceUseCase(surface)
    }

    fun onSurfaceDestroyed() {
        detachProjectionSurfaceUseCase()
    }

    fun onTouchEvent(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ) {
        sendProjectionMotionUseCase(event, surfaceWidth, surfaceHeight)
    }

    private fun ProjectionSessionSnapshot.toUiState(logs: List<DiagnosticLogEntry>): CarPlayUiState {
        val stateLabel = when (state) {
            ProjectionConnectionState.IDLE -> "IDLE"
            ProjectionConnectionState.SEARCHING -> "SEARCHING"
            ProjectionConnectionState.WAITING_PERMISSION -> "WAITING_PERMISSION"
            ProjectionConnectionState.CONNECTING -> "CONNECTING"
            ProjectionConnectionState.INIT -> "INIT"
            ProjectionConnectionState.WAITING_PHONE -> "WAITING_PHONE"
            ProjectionConnectionState.STREAMING -> "STREAMING"
            ProjectionConnectionState.ERROR -> "ERROR"
        }

        val metadata = buildList {
            adapterDescription?.let { add("adapter=$it") }
            phoneDescription?.let { add("phone=$it") }
            streamDescription?.let { add("stream=$it") }
            lastError?.let { add("error=$it") }
        }.joinToString(" | ")

        val diagnosticsText = buildString {
            if (metadata.isNotBlank()) {
                append(metadata)
                append('\n')
                append('\n')
            }
            if (logs.isEmpty()) {
                append("Логи пока пусты")
            } else {
                logs.forEach { entry ->
                    append(timeFormatter.format(Date(entry.timestampMillis)))
                    append("  ")
                    append(entry.source)
                    append("  ")
                    append(entry.message)
                    append('\n')
                }
            }
        }.trimEnd()

        return CarPlayUiState(
            stateLabel = stateLabel,
            statusMessage = statusMessage,
            overlayColorRes = when (state) {
                ProjectionConnectionState.IDLE -> R.color.status_idle
                ProjectionConnectionState.SEARCHING -> R.color.status_searching
                ProjectionConnectionState.WAITING_PERMISSION -> R.color.status_searching
                ProjectionConnectionState.CONNECTING -> R.color.status_connecting
                ProjectionConnectionState.INIT -> R.color.status_init
                ProjectionConnectionState.WAITING_PHONE -> R.color.status_waiting_phone
                ProjectionConnectionState.STREAMING -> R.color.status_streaming
                ProjectionConnectionState.ERROR -> R.color.status_error
            },
            showConnectButton = state != ProjectionConnectionState.STREAMING,
            diagnosticsText = diagnosticsText,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
        )
    }
}
