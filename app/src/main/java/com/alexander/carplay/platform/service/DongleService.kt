package com.alexander.carplay.platform.service

import android.app.Service
import android.content.ComponentCallbacks2
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.view.MotionEvent
import android.view.Surface
import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.alexander.carplay.data.logging.ProcessDiagnostics
import com.alexander.carplay.data.session.DongleSessionManager
import com.alexander.carplay.data.usb.AndroidUsbTransport
import com.alexander.carplay.domain.model.DiagnosticLogEntry
import com.alexander.carplay.domain.model.ProjectionConnectionState
import com.alexander.carplay.domain.model.ProjectionDeviceSettings
import com.alexander.carplay.domain.model.ProjectionSessionSnapshot
import com.alexander.carplay.domain.model.ProjectionUiEvent
import com.alexander.carplay.domain.port.ProjectionSettingsPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DongleService : Service() {
    companion object {
        const val ACTION_START_USB = "com.alexander.carplay.action.START_USB"
        const val ACTION_START_REPLAY = "com.alexander.carplay.action.START_REPLAY"
        const val EXTRA_CAPTURE_PATH = "capture_path"
        private const val SOURCE = "Service"
    }

    private lateinit var logStore: DiagnosticLogStore
    private lateinit var sessionManager: DongleSessionManager
    private lateinit var wakeLockController: ServiceWakeLockController
    private lateinit var settingsPort: ProjectionSettingsPort
    private lateinit var carPlayMediaSession: CarPlayMediaSession
    private lateinit var audioFocusManager: CarPlayAudioFocusManager
    private lateinit var a2dpDisconnectManager: CarPlayA2dpDisconnectManager
    private lateinit var audioPlaybackObserver: CarPlayAudioPlaybackObserver
    private val binder = ServiceBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastAudioFixSummary: String? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent,
        ) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    logStore.info(SOURCE, "USB broadcast: attached device=${device?.deviceName ?: "-"}")
                    sessionManager.onUsbAttached(device)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    logStore.info(SOURCE, "USB broadcast: detached device=${device?.deviceName ?: "-"}")
                    sessionManager.onUsbDetached(device)
                }
                AndroidUsbTransport.ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    logStore.info(
                        SOURCE,
                        "USB permission result: granted=$granted device=${device?.deviceName ?: "-"}",
                    )
                    sessionManager.onUsbPermissionResult(device, granted)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val appContainer = (application as com.alexander.carplay.CarPlayApp).appContainer
        logStore = appContainer.logStore
        logStore.info(SOURCE, "onCreate | ${ProcessDiagnostics.describeCurrentProcess()}")
        settingsPort = appContainer.settingsPort
        sessionManager = DongleSessionManager(this, logStore, settingsPort)
        wakeLockController = ServiceWakeLockController(this, logStore)
        carPlayMediaSession = CarPlayMediaSession(this, logStore)
        audioFocusManager = CarPlayAudioFocusManager(
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager,
            logStore = logStore,
        )
        a2dpDisconnectManager = CarPlayA2dpDisconnectManager(this, logStore)
        audioPlaybackObserver = CarPlayAudioPlaybackObserver(
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager,
            logStore = logStore,
        )
        audioPlaybackObserver.start()

        ServiceNotificationFactory.ensureChannel(this)
        val notification = ServiceNotificationFactory.create(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Bootstrapping as media playback avoids Android 14 connected-device
            // validation before the app obtains the USB device permission token.
            startForeground(
                ServiceNotificationFactory.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(
                ServiceNotificationFactory.NOTIFICATION_ID,
                notification,
            )
        }
        registerUsbReceiver()
        serviceScope.launch {
            sessionManager.state.collect { snapshot ->
                wakeLockController.setActive(
                    active = snapshot.shouldHoldServiceWakeLock(),
                    reason = snapshot.state.name,
                )
                updateAudioFixState(snapshot.state)
            }
        }
        serviceScope.launch {
            sessionManager.nowPlaying.collect { nowPlaying ->
                if (nowPlaying != null &&
                    carPlayMediaSession.isActive() &&
                    settingsPort.isMediaSessionMetadataEnabled()
                ) {
                    carPlayMediaSession.updateMetadata(nowPlaying)
                }
            }
        }
    }

    private fun updateAudioFixState(state: ProjectionConnectionState) {
        val isStreaming = state == ProjectionConnectionState.STREAMING
        val mediaSessionFixEnabled = settingsPort.isMediaSessionFixEnabled()
        val audioFocusFixEnabled = settingsPort.isAudioFocusFixEnabled()
        if (mediaSessionFixEnabled) {
            carPlayMediaSession.activate()
            if (isStreaming && settingsPort.isMediaSessionMetadataEnabled()) {
                sessionManager.nowPlaying.value?.let { carPlayMediaSession.updateMetadata(it) }
            } else {
                carPlayMediaSession.clearMetadata()
            }
        } else {
            carPlayMediaSession.deactivate()
        }
        if (isStreaming && audioFocusFixEnabled) {
            audioFocusManager.requestFocus()
        } else {
            audioFocusManager.abandonFocus()
        }
        val a2dpDisconnectFixEnabled = settingsPort.isA2dpDisconnectFixEnabled()
        if (isStreaming && a2dpDisconnectFixEnabled) {
            a2dpDisconnectManager.onStreamingStarted()
        } else {
            a2dpDisconnectManager.onStreamingStopped()
        }
        val summary = buildString {
            append("Audio fix state: projection=").append(state.name)
            append(" streaming=").append(isStreaming)
            append(" mediaSessionEnabled=").append(mediaSessionFixEnabled)
            append(" mediaSessionActive=").append(carPlayMediaSession.isActive())
            append(" audioFocusEnabled=").append(audioFocusFixEnabled)
            append(" audioFocusHeld=").append(audioFocusManager.hasFocus())
            append(" a2dpDisconnectEnabled=").append(a2dpDisconnectFixEnabled)
        }
        if (summary != lastAudioFixSummary) {
            lastAudioFixSummary = summary
            logStore.info(SOURCE, summary)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        logStore.info(
            SOURCE,
            buildString {
                append("onStartCommand action=").append(intent?.action ?: "-")
                append(" intentNull=").append(intent == null)
                append(" startId=").append(startId)
                append(" flags=").append(flags)
                append(" sticky=").append(true)
                append(" pid=").append(Process.myPid())
                intent?.extras?.keySet()?.sorted()?.takeIf { it.isNotEmpty() }?.let { keys ->
                    append(" extras=").append(keys)
                }
            },
        )
        when (intent?.action) {
            ACTION_START_REPLAY -> {
                val capturePath = intent.getStringExtra(EXTRA_CAPTURE_PATH)
                if (!capturePath.isNullOrBlank()) {
                    sessionManager.startReplay(capturePath)
                } else {
                    sessionManager.start()
                }
            }

            ACTION_START_USB -> sessionManager.startUsb()
            else -> sessionManager.ensureStarted()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder
        .also {
            logStore.info(
                SOURCE,
                "onBind action=${intent?.action ?: "-"} intentNull=${intent == null} | ${ProcessDiagnostics.describeCurrentProcess()}",
            )
        }

    override fun onUnbind(intent: Intent?): Boolean {
        logStore.info(
            SOURCE,
            "onUnbind action=${intent?.action ?: "-"} intentNull=${intent == null} | ${ProcessDiagnostics.describeCurrentProcess()}",
        )
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        logStore.info(
            SOURCE,
            "onRebind action=${intent?.action ?: "-"} intentNull=${intent == null} | ${ProcessDiagnostics.describeCurrentProcess()}",
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        logStore.info(
            SOURCE,
            "onTaskRemoved rootIntentAction=${rootIntent?.action ?: "-"} | ${ProcessDiagnostics.describeCurrentProcess()}",
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ProcessDiagnostics.logTrimMemory(logStore, SOURCE, level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            sessionManager.onCriticalMemoryPressure()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ProcessDiagnostics.logLowMemory(logStore, SOURCE)
    }

    override fun onDestroy() {
        super.onDestroy()
        logStore.info(SOURCE, "onDestroy | ${ProcessDiagnostics.describeCurrentProcess()}")
        unregisterReceiver(usbReceiver)
        wakeLockController.release("service destroyed")
        serviceScope.cancel()
        sessionManager.shutdown()
        audioFocusManager.abandonFocus()
        a2dpDisconnectManager.onStreamingStopped()
        audioPlaybackObserver.stop()
        carPlayMediaSession.release()
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(AndroidUsbTransport.ACTION_USB_PERMISSION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
    }

    inner class ServiceBinder : Binder() {
        val state: StateFlow<ProjectionSessionSnapshot>
            get() = sessionManager.state

        val logs: StateFlow<List<DiagnosticLogEntry>>
            get() = logStore.logs

        val events: SharedFlow<ProjectionUiEvent>
            get() = sessionManager.events

        fun ensureStarted() {
            sessionManager.ensureStarted()
        }

        fun startUsb() {
            sessionManager.startUsb()
        }

        fun startReplay(capturePath: String) {
            sessionManager.startReplay(capturePath)
        }

        fun requestReconnect() {
            sessionManager.requestReconnect()
        }

        fun debugSimulateVehicleSleepCycle() {
            sessionManager.debugSimulateVehicleSleepCycle()
        }

        fun refreshRuntimeSettings() {
            sessionManager.refreshRuntimeSettings()
            updateAudioFixState(sessionManager.state.value.state)
        }

        fun previewRuntimeSettings(settings: ProjectionDeviceSettings) {
            sessionManager.previewRuntimeSettings(settings)
        }

        fun setVideoStreamEnabled(enabled: Boolean) {
            sessionManager.setVideoStreamEnabled(enabled)
        }

        fun setActivityVisible(visible: Boolean) {
            sessionManager.setActivityVisible(visible)
        }

        fun selectDevice(deviceId: String) {
            sessionManager.selectDevice(deviceId)
        }

        fun cancelDeviceConnection() {
            sessionManager.cancelDeviceConnection()
        }

        fun attachSurface(surface: Surface) {
            sessionManager.attachSurface(surface)
        }

        fun detachSurface() {
            sessionManager.detachSurface()
        }

        fun sendMotionEvent(
            event: MotionEvent,
            surfaceWidth: Int,
            surfaceHeight: Int,
        ) {
            sessionManager.sendMotionEvent(event, surfaceWidth, surfaceHeight)
        }
    }

    private fun ProjectionSessionSnapshot.shouldHoldServiceWakeLock(): Boolean =
        when (state) {
            ProjectionConnectionState.WAITING_PERMISSION,
            ProjectionConnectionState.CONNECTING,
            ProjectionConnectionState.INIT,
            ProjectionConnectionState.WAITING_PHONE,
            ProjectionConnectionState.STREAMING,
            -> true

            ProjectionConnectionState.IDLE,
            ProjectionConnectionState.WAITING_VEHICLE,
            ProjectionConnectionState.SEARCHING,
            ProjectionConnectionState.ERROR,
            -> false
        }
}
