package com.alexander.carplay.data.session

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.MotionEvent
import android.view.Surface
import androidx.core.content.ContextCompat
import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.alexander.carplay.domain.model.ProjectionConnectionState
import com.alexander.carplay.domain.model.ProjectionSessionSnapshot
import com.alexander.carplay.domain.model.ProjectionUiEvent
import com.alexander.carplay.platform.service.DongleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class DongleServiceConnector(
    application: Application,
    private val logStore: DiagnosticLogStore,
) {
    companion object {
        const val DEFAULT_REPLAY_CAPTURE_PATH =
            "/data/local/tmp/test-capture.bin"
        private const val SOURCE = "UiSession"
    }

    private val appContext = application.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(
        ProjectionSessionSnapshot(
            state = ProjectionConnectionState.IDLE,
            statusMessage = "Service is not bound yet",
        ),
    )
    private val _events = MutableSharedFlow<ProjectionUiEvent>(extraBufferCapacity = 8)

    private var binder: DongleService.ServiceBinder? = null
    private var stateJob: Job? = null
    private var eventsJob: Job? = null
    private var bound = false
    private var pendingSurface: Surface? = null
    private var pendingVideoStreamEnabled: Boolean? = null
    private var lastLoggedSnapshotSignature: String? = null

    val state: StateFlow<ProjectionSessionSnapshot> = _state.asStateFlow()
    val logs = logStore.logs
    val events: SharedFlow<ProjectionUiEvent> = _events.asSharedFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            name: ComponentName?,
            service: IBinder?,
        ) {
            logStore.info(
                SOURCE,
                "Service connected: component=${name?.className ?: "-"} pendingSurface=${pendingSurface != null} pendingVideo=${pendingVideoStreamEnabled ?: "null"}",
            )
            binder = service as? DongleService.ServiceBinder
            binder?.ensureStarted()
            stateJob?.cancel()
            eventsJob?.cancel()
            stateJob = scope.launch {
                binder?.state?.collect { snapshot ->
                    _state.value = snapshot
                    logSnapshot(snapshot)
                }
            }
            eventsJob = scope.launch {
                binder?.events?.collect { event ->
                    logStore.info(SOURCE, "UI event from service: $event")
                    _events.emit(event)
                }
            }
            pendingSurface?.let { safeSurface ->
                logStore.info(SOURCE, "Flushing pending surface to service after bind")
                binder?.attachSurface(safeSurface)
                pendingSurface = null
            }
            pendingVideoStreamEnabled?.let { enabled ->
                logStore.info(SOURCE, "Flushing pending video target=$enabled after bind")
                binder?.setVideoStreamEnabled(enabled)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            logStore.info(SOURCE, "Service disconnected: component=${name?.className ?: "-"}")
            binder = null
            stateJob?.cancel()
            eventsJob?.cancel()
            _state.value = _state.value.copy(
                state = ProjectionConnectionState.ERROR,
                statusMessage = "Service disconnected",
            )
        }
    }

    fun ensureServiceStarted() {
        logStore.info(SOURCE, "ensureServiceStarted")
        val intent = Intent(appContext, DongleService::class.java)
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun startUsb() {
        binder?.let { serviceBinder ->
            serviceBinder.startUsb()
            return
        }

        val intent = Intent(appContext, DongleService::class.java).apply {
            action = DongleService.ACTION_START_USB
        }
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun startReplay(capturePath: String = DEFAULT_REPLAY_CAPTURE_PATH) {
        binder?.let { serviceBinder ->
            serviceBinder.startReplay(capturePath)
            return
        }

        val intent = Intent(appContext, DongleService::class.java).apply {
            action = DongleService.ACTION_START_REPLAY
            putExtra(DongleService.EXTRA_CAPTURE_PATH, capturePath)
        }
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun bind() {
        if (bound) return
        logStore.info(SOURCE, "bind requested")
        ensureServiceStarted()
        val intent = Intent(appContext, DongleService::class.java)
        bound = appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        logStore.info(SOURCE, "bind result=$bound")
    }

    fun unbind() {
        if (!bound) return
        logStore.info(SOURCE, "unbind requested")
        runCatching { appContext.unbindService(serviceConnection) }
        bound = false
        binder = null
        stateJob?.cancel()
        eventsJob?.cancel()
    }

    fun requestReconnect() {
        ensureServiceStarted()
        binder?.requestReconnect()
    }

    fun refreshRuntimeSettings() {
        ensureServiceStarted()
        binder?.refreshRuntimeSettings()
    }

    fun setVideoStreamEnabled(enabled: Boolean) {
        ensureServiceStarted()
        pendingVideoStreamEnabled = enabled
        logStore.info(SOURCE, "setVideoStreamEnabled enabled=$enabled binderReady=${binder != null}")
        binder?.setVideoStreamEnabled(enabled)
    }

    fun selectDevice(deviceId: String) {
        ensureServiceStarted()
        binder?.selectDevice(deviceId)
    }

    fun cancelDeviceConnection() {
        ensureServiceStarted()
        binder?.cancelDeviceConnection()
    }

    fun attachSurface(surface: Surface) {
        ensureServiceStarted()
        pendingSurface = surface
        logStore.info(SOURCE, "attachSurface binderReady=${binder != null}")
        binder?.attachSurface(surface)
    }

    fun detachSurface() {
        logStore.info(SOURCE, "detachSurface binderReady=${binder != null}")
        binder?.detachSurface()
        pendingSurface = null
    }

    fun sendMotionEvent(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ) {
        val copiedEvent = MotionEvent.obtain(event)
        binder?.sendMotionEvent(copiedEvent, surfaceWidth, surfaceHeight) ?: copiedEvent.recycle()
    }

    private fun logSnapshot(snapshot: ProjectionSessionSnapshot) {
        val signature = buildString {
            append("state=").append(snapshot.state)
            append(" phase=").append(snapshot.protocolPhase)
            append(" surface=").append(snapshot.surfaceAttached)
            append(" device=").append(snapshot.currentDeviceName ?: snapshot.currentDeviceId ?: "-")
            append(" video=")
            append(snapshot.videoWidth ?: 0)
            append("x")
            append(snapshot.videoHeight ?: 0)
            append(" msg=").append(snapshot.statusMessage)
        }
        if (signature == lastLoggedSnapshotSignature) return
        lastLoggedSnapshotSignature = signature
        logStore.info(SOURCE, "Snapshot $signature")
    }
}
