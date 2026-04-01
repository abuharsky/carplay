package com.alexander.carplay.data.session

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.content.ComponentCallbacks2
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import com.alexander.carplay.BuildConfig
import com.alexander.carplay.data.automotive.AutomotivePowerMonitor
import com.alexander.carplay.data.automotive.AutomotivePowerPolicy
import com.alexander.carplay.data.automotive.AutomotivePowerSnapshot
import com.alexander.carplay.data.automotive.DoubleMediaServerPublisher
import com.alexander.carplay.data.audio.AudioStreamManager
import com.alexander.carplay.data.audio.MicrophoneInputManager
import com.alexander.carplay.data.executor.CarPlayExecutors
import com.alexander.carplay.data.input.TouchInputMapper
import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.alexander.carplay.data.protocol.Cpc200Protocol
import com.alexander.carplay.data.protocol.DongleBoxSettingsSnapshot
import com.alexander.carplay.data.protocol.DongleDeviceCatalogParser
import com.alexander.carplay.data.protocol.DongleKnownDevice
import com.alexander.carplay.data.protocol.ProjectionSessionConfig
import com.alexander.carplay.data.replay.CaptureReplaySession
import com.alexander.carplay.data.session.v2.ConnectionEngineV2
import com.alexander.carplay.data.session.v2.ConnectionEffectV2
import com.alexander.carplay.data.session.v2.ConnectionEventV2
import com.alexander.carplay.data.session.v2.ConnectionSnapshotV2
import com.alexander.carplay.data.session.v2.DiscoveryStateV2
import com.alexander.carplay.data.session.v2.ProjectionStateV2
import com.alexander.carplay.data.session.v2.SelectionModeV2
import com.alexander.carplay.data.session.v2.TransportStateV2
import com.alexander.carplay.domain.model.ProjectionAudioRoute
import com.alexander.carplay.data.usb.AndroidUsbTransport
import com.alexander.carplay.data.usb.DongleConnectionSession
import com.alexander.carplay.data.usb.UsbTransport
import com.alexander.carplay.data.video.H264Renderer
import com.alexander.carplay.domain.model.ProjectionConnectionState
import com.alexander.carplay.domain.model.ProjectionDeviceSettings
import com.alexander.carplay.domain.model.ProjectionDeviceSnapshot
import com.alexander.carplay.domain.model.ProjectionMicRoute
import com.alexander.carplay.domain.model.ProjectionProtocolPhase
import com.alexander.carplay.domain.model.ProjectionSessionSnapshot
import com.alexander.carplay.domain.model.ProjectionUiEvent
import com.alexander.carplay.domain.port.ProjectionSettingsPort
import com.alexander.carplay.presentation.ui.CarPlayActivity
import com.alexander.carplay.presentation.ui.ProjectionFrameSnapshotStore
import com.incall.serversdk.power.PowerConstant
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.random.Random

class DongleSessionManager(
    context: Context,
    private val logStore: DiagnosticLogStore,
    private val settingsStore: ProjectionSettingsPort,
) {
    private enum class SessionMode {
        USB,
        REPLAY,
    }

    private data class ConnectAttemptTrace(
        val id: Int,
        val reason: String,
        val startedAtMs: Long,
        val targetDeviceId: String?,
        val sessionDescription: String?,
        val stateAtStart: ProjectionConnectionState,
        val phaseAtStart: ProjectionProtocolPhase,
        var explicitTargetPreparedAtMs: Long? = null,
        var autoConnectClearedAtMs: Long? = null,
        var deviceFoundAtMs: Long? = null,
        var bluetoothConnectStartAtMs: Long? = null,
        var bluetoothConnectedAtMs: Long? = null,
        var pluggedAtMs: Long? = null,
        var wifiConnectedAtMs: Long? = null,
        var phase7AtMs: Long? = null,
        var phase8AtMs: Long? = null,
        var firstVideoAtMs: Long? = null,
        var phase13AtMs: Long? = null,
        var phase0AtMs: Long? = null,
        var unpluggedAtMs: Long? = null,
    )

    companion object {
        private const val SOURCE = "Session"
        private const val READ_TIMEOUT_MS = 1_000
        private const val WRITE_TIMEOUT_MS = 1_000
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val SLEEP_DISCONNECT_GRACE_MS = 1_500L
        private const val MANUAL_VEHICLE_GATE_BYPASS_WINDOW_MS = 120_000L
        private const val USB_OPEN_STABILIZATION_DELAY_MS = 3_000L
        private const val USB_POST_OPEN_STABILIZATION_DELAY_MS = 250L
        private const val OPEN_ACK_TIMEOUT_MS = 6_000L
        private const val OPEN_ACK_RECOVERY_DELAY_INITIAL_MS = 1_500L
        private const val OPEN_ACK_RECOVERY_DELAY_REPEATED_MS = 3_000L
        private const val OPEN_ACK_MAX_CONSECUTIVE_TIMEOUTS = 8
        private const val OPEN_ACK_TIMEOUT_REASON = "open ack timeout"
        private const val HEARTBEAT_INTERVAL_SECONDS = 2L
        private const val FRAME_REQUEST_INTERVAL_MS = 5_000L
        private const val DEFAULT_REPLAY_FRAME_DELAY_MS = 16L
        private const val INBOUND_ACTIVITY_LOG_GAP_MS = 10_000L
        private const val POWER_REASON_SLEEPING = "sleeping"
        private const val CONNECT_ATTEMPT_DUPLICATE_WINDOW_MS = 1_500L
        private const val CONNECT_ATTEMPT_PRE_PLUGGED_STALL_LOG_MS = 8_000L
        private const val CONNECT_ATTEMPT_POST_PLUGGED_STALL_LOG_MS = 8_000L
        private const val CONNECT_ATTEMPT_POST_PLUGGED_STALL_RECONNECT_MS = 20_000L
        private const val CONNECT_ATTEMPT_POST_PHASE7_STALL_LOG_MS = 8_000L
        private const val DEBUG_SLEEP_RESTORE_MIN_MS = 4_000L
        private const val DEBUG_SLEEP_RESTORE_MAX_MS = 10_000L
    }

    private val appContext = context.applicationContext
    private val executors = CarPlayExecutors()
    private val usbTransport: UsbTransport = AndroidUsbTransport(appContext, logStore)
    private val renderer = H264Renderer(executors, logStore)
    private val audioStreamManager = AudioStreamManager(logStore)
    private val microphoneInputManager = MicrophoneInputManager(logStore) { pcm ->
        queueOutbound(Cpc200Protocol.audioInput(pcm))
    }
    private val doubleMediaPublisher = DoubleMediaServerPublisher(appContext, logStore)
    private val automotivePowerMonitor = AutomotivePowerMonitor(logStore) { snapshot, reason ->
        executors.session.execute {
            handleAutomotivePowerChanged(snapshot, reason)
        }
    }
    private val touchInputMapper = TouchInputMapper()
    private val outboundQueue = LinkedBlockingQueue<ByteArray>()
    private val knownDevices = linkedMapOf<String, DongleKnownDevice>()
    private val availableDeviceIds = linkedSetOf<String>()
    private val _state = MutableStateFlow(ProjectionSessionSnapshot())
    private val _events = MutableSharedFlow<ProjectionUiEvent>(extraBufferCapacity = 8)
    private val connectionEngineV2 = ConnectionEngineV2(logStore::info)

    private var started = false
    private var shuttingDown = false
    private var sessionMode = SessionMode.USB
    private var surfaceAttached = false
    private var sessionConfig = ProjectionSessionConfig.fromContext(
        context = appContext,
        adapterName = settingsStore.getAdapterName(),
        climatePanelEnabled = settingsStore.isClimatePanelEnabled(),
        carplaySafeAreaBottomDp = 0,
    )
    private var currentSession: DongleConnectionSession? = null
    private var currentDevice: UsbDevice? = null
    private var currentConnectionLabel: String? = null
    private var currentPhoneType: Int? = null
    private var currentSelectedDeviceId: String? = null
    private var currentSessionDeviceId: String? = null
    private var catalogActiveDeviceId: String? = null
    private var currentPeerBluetoothDeviceId: String? = null
    private var pendingUsbPermissionDeviceId: Int? = null
    private var openingUsbDeviceId: Int? = null
    private var brandingInitializedDeviceId: Int? = null
    private var pendingConnectionDeviceId: String? = null
    private var pendingDeviceSelectionSent = false
    private var replayCapturePath: String? = null
    private var replayFrameDelayMs: Long = DEFAULT_REPLAY_FRAME_DELAY_MS
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var frameRequestFuture: ScheduledFuture<*>? = null
    private var bleAdvertisingActive = false
    private var awaitingAutoConnectResult = false
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var sleepDisconnectFuture: ScheduledFuture<*>? = null
    private var debugVehicleWakeFuture: ScheduledFuture<*>? = null
    private var openAckTimeoutFuture: ScheduledFuture<*>? = null
    private var openAckRecoveryFuture: ScheduledFuture<*>? = null
    private var connectAttemptWatchdogFuture: ScheduledFuture<*>? = null
    private var postPluggedReconnectFuture: ScheduledFuture<*>? = null
    private var lastInboundActivityAtMs = 0L
    private var lastHeartbeatEchoAtMs = 0L
    private var lastUsbConnectRequestedAtMs = 0L
    private var lastUsbSessionOpenedAtMs = 0L
    private var lastOpenAckAtMs = 0L
    private var writeLoopStarted = false
    private var videoStreamEnabled = false
    private var backgroundVideoDropLogged = false
    private var automotivePowerSnapshot = AutomotivePowerSnapshot()
    private var vehicleActive = true
    private var transportEpochV2 = 0L
    private var manualVehicleGateBypassUntilMs = 0L
    private var consecutiveOpenAckTimeouts = 0
    private var connectAttemptSeq = 0
    private var activeConnectAttempt: ConnectAttemptTrace? = null
    private var restoreActivityAfterSleep = false
    private var activityVisible = false

    val state: StateFlow<ProjectionSessionSnapshot> = _state.asStateFlow()
    val events: SharedFlow<ProjectionUiEvent> = _events.asSharedFlow()

    init {
        automotivePowerMonitor.start()
        automotivePowerSnapshot = automotivePowerMonitor.currentSnapshot()
        vehicleActive = AutomotivePowerPolicy.isVehicleActive(automotivePowerSnapshot)
        applyV2(ConnectionEventV2.VehicleAvailabilityChanged(isVehicleGateOpen()), render = false)
    }

    fun start() {
        startUsb()
    }

    fun ensureStarted() {
        executors.session.execute {
            if (shuttingDown || started) return@execute
            when (sessionMode) {
                SessionMode.USB -> startUsbInternal("ensureStarted")
                SessionMode.REPLAY -> {
                    val capturePath = replayCapturePath
                    if (capturePath.isNullOrBlank()) {
                        startUsbInternal("ensureStarted")
                    } else {
                        startReplayInternal(capturePath)
                    }
                }
            }
        }
    }

    fun startUsb() {
        executors.session.execute {
            if (shuttingDown) return@execute
            armManualVehicleGateBypass("manual start")
            sessionMode = SessionMode.USB
            replayCapturePath = null
            if (currentSession != null && currentSession?.isReplay == true) {
                closeCurrentSession("switch to usb", scheduleReconnect = false)
            }
            if (started && currentSession != null && sessionMode == SessionMode.USB) return@execute
            startUsbInternal("manual start")
        }
    }

    fun startReplay(capturePath: String) {
        executors.session.execute {
            if (shuttingDown) return@execute
            sessionMode = SessionMode.REPLAY
            replayCapturePath = capturePath
            if (currentSession != null) {
                closeCurrentSession("switch to replay", scheduleReconnect = false)
            }
            startReplayInternal(capturePath)
        }
    }

    fun shutdown() {
        executors.session.execute {
            shuttingDown = true
            started = false
            cancelDebugVehicleWake()
            closeCurrentSession("service destroyed", scheduleReconnect = false)
            automotivePowerMonitor.stop()
            outboundQueue.clear()
            microphoneInputManager.stop("service destroyed")
            audioStreamManager.release()
            renderer.release()
            updateState(
                state = ProjectionConnectionState.IDLE,
                message = "Foreground service stopped",
            )
            executors.shutdown()
        }
    }

    fun onUsbAttached(device: UsbDevice?) {
        executors.session.execute {
            if (sessionMode != SessionMode.USB) return@execute
            if (!started || !usbTransport.isKnownDevice(device)) return@execute
            logStore.info(SOURCE, "USB attached: ${device?.deviceName}")
            connectOrRequestPermission("usb attached")
        }
    }

    fun onUsbDetached(device: UsbDevice?) {
        executors.session.execute {
            if (sessionMode != SessionMode.USB) return@execute
            if (!usbTransport.isKnownDevice(device)) return@execute
            val matchesCurrent = device == null || device.deviceId == currentDevice?.deviceId
            if (!matchesCurrent) return@execute

            logStore.info(SOURCE, "USB detached: ${device?.deviceName ?: "unknown"}")
            brandingInitializedDeviceId = null
            closeCurrentSession("usb detached", scheduleReconnect = true)
        }
    }

    fun onUsbPermissionResult(
        device: UsbDevice?,
        granted: Boolean,
    ) {
        executors.session.execute {
            if (sessionMode != SessionMode.USB) return@execute
            if (!started || !usbTransport.isKnownDevice(device) || device == null) return@execute
            if (pendingUsbPermissionDeviceId == device.deviceId) {
                pendingUsbPermissionDeviceId = null
            }
            if (granted) {
                logStore.info(SOURCE, "USB permission granted for ${device.deviceName}")
                connectOrRequestPermission("permission granted")
            } else {
                logStore.error(SOURCE, "USB permission denied for ${device.deviceName}")
                updateState(
                    state = ProjectionConnectionState.ERROR,
                    message = "USB permission denied",
                    lastError = "Permission denied for ${device.deviceName}",
                )
            }
        }
    }

    fun requestReconnect(reason: String = "manual") {
        logStore.info(SOURCE, "requestReconnect($reason) started=$started shuttingDown=$shuttingDown")
        executors.session.execute {
            if (!started) {
                logStore.info(SOURCE, "requestReconnect($reason) skipped: not started")
                return@execute
            }
            armManualVehicleGateBypass(reason)
            restartSessionNow(reason)
        }
    }

    fun debugSimulateVehicleSleepCycle() {
        if (!BuildConfig.DEBUG) {
            logStore.info(SOURCE, "debugSimulateVehicleSleepCycle ignored: release build")
            return
        }
        executors.session.execute {
            if (debugVehicleWakeFuture != null) {
                logStore.info(SOURCE, "Debug sleep cycle already running; ignoring duplicate request")
                return@execute
            }

            val restoreDelayMs = Random.nextLong(
                from = DEBUG_SLEEP_RESTORE_MIN_MS,
                until = DEBUG_SLEEP_RESTORE_MAX_MS + 1L,
            )
            val sleepSnapshot = buildDebugVehicleSleepSnapshot()
            val wakeSnapshot = buildDebugVehicleWakeSnapshot()

            logStore.info(
                SOURCE,
                "Debug sleep cycle started: power off now, wake in ${restoreDelayMs}ms | ${AutomotivePowerPolicy.describeSnapshot(sleepSnapshot)}",
            )

            handleAutomotivePowerChanged(sleepSnapshot, POWER_REASON_SLEEPING)

            debugVehicleWakeFuture = executors.scheduler.schedule(
                {
                    executors.session.execute {
                        debugVehicleWakeFuture = null
                        logStore.info(
                            SOURCE,
                            "Debug sleep cycle restoring power after ${restoreDelayMs}ms | ${AutomotivePowerPolicy.describeSnapshot(wakeSnapshot)}",
                        )
                        handleAutomotivePowerChanged(wakeSnapshot, "debug wake restore")
                    }
                },
                restoreDelayMs,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    fun refreshRuntimeSettings() {
        executors.session.execute {
            applyRuntimeDeviceSettings(loadRuntimeDeviceSettings())
            refreshState()
        }
    }

    fun previewRuntimeSettings(settings: ProjectionDeviceSettings) {
        executors.session.execute {
            applyRuntimeDeviceSettings(settings)
        }
    }

    fun setVideoStreamEnabled(enabled: Boolean) {
        executors.session.execute {
            if (videoStreamEnabled == enabled) {
                if (canControlVideoFocus()) {
                    logStore.info(
                        SOURCE,
                        "Video stream target unchanged; re-syncing focus enabled=$enabled",
                    )
                    syncVideoFocus("ui visibility re-sync")
                }
                return@execute
            }
            videoStreamEnabled = enabled
            logStore.info(
                SOURCE,
                "Video stream target changed: enabled=$enabled",
            )
            if (!enabled && connectionEngineV2.snapshot().isStreamingActive()) {
                stopFrameRequestLoop()
            } else if (enabled && connectionEngineV2.snapshot().isStreamingActive()) {
                startFrameRequestLoop()
            }
            syncVideoFocus("ui visibility changed")
        }
    }

    fun selectDevice(deviceId: String) {
        executors.session.execute {
            armManualVehicleGateBypass("manual device selection")
            val normalizedId = Cpc200Protocol.normalizeDeviceIdentifier(deviceId).ifBlank { return@execute }
            currentSelectedDeviceId = normalizedId
            pendingConnectionDeviceId = normalizedId
            pendingDeviceSelectionSent = false
            applyV2(ConnectionEventV2.ManualSelectionRequested(normalizedId))
            logStore.info(SOURCE, "Manual device selection: ${describeKnownDevice(normalizedId)}")

            if (!started) {
                startUsbInternal("manual device selection")
            }

            if (!isVehicleGateOpen()) {
                updateWaitingForVehicleState("manual device selection")
                return@execute
            }

            sendExplicitDeviceSelectionIfPossible()
            refreshState(
                message = "Connecting to ${describeKnownDevice(normalizedId)}",
                phoneDescription = describeKnownDevice(normalizedId),
            )
        }
    }

    fun cancelDeviceConnection() {
        executors.session.execute {
            val pendingId = pendingConnectionDeviceId
            pendingConnectionDeviceId = null
            pendingDeviceSelectionSent = false
            currentPeerBluetoothDeviceId = null
            currentSessionDeviceId = null
            currentPhoneType = null
            if (currentSession != null) {
                queueOutbound(Cpc200Protocol.disconnectPhone())
            }
            refreshState(
                state = ProjectionConnectionState.WAITING_PHONE,
                message = if (pendingId != null) {
                    "Отключение: ${describeKnownDevice(pendingId)}"
                } else {
                    "Отключено"
                },
                phoneDescription = null,
                streamDescription = null,
            )
            if (!isVehicleGateOpen()) {
                updateWaitingForVehicleState("cancel device connection")
            }
        }
    }

    fun attachSurface(surface: Surface) {
        surfaceAttached = true
        backgroundVideoDropLogged = false
        logStore.info(SOURCE, "Projection surface attached")
        renderer.attachSurface(surface)
        if (currentSession != null && (_state.value.videoWidth != null || currentPhoneType != null)) {
            queueOutbound(Cpc200Protocol.command(Cpc200Protocol.Command.REQUEST_KEY_FRAME))
            logStore.info(SOURCE, "Requested key frame after surface attach")
        }
        executors.session.execute {
            updateState(
                state = if (_state.value.protocolPhase == ProjectionProtocolPhase.STREAMING_ACTIVE) {
                    ProjectionConnectionState.STREAMING
                } else {
                    _state.value.state
                },
                message = _state.value.statusMessage,
                surfaceAttached = true,
            )
        }
    }

    fun detachSurface() {
        surfaceAttached = false
        logStore.info(SOURCE, "Projection surface detached")
        renderer.detachSurface()
        executors.session.execute {
            updateState(
                state = if (currentSession == null) {
                    if (isVehicleGateOpen()) {
                        ProjectionConnectionState.SEARCHING
                    } else {
                        ProjectionConnectionState.WAITING_VEHICLE
                    }
                } else {
                    _state.value.state
                },
                message = if (currentSession == null) {
                    if (isVehicleGateOpen()) {
                        "Adapter not connected"
                    } else {
                        buildVehicleWaitMessage()
                    }
                } else {
                    "Video paused while activity is in background"
                },
                surfaceAttached = false,
            )
        }
    }

    fun onCriticalMemoryPressure() {
        executors.session.execute {
            if (surfaceAttached) {
                logStore.info(SOURCE, "Critical memory pressure observed, but surface is attached; keeping active video path")
                return@execute
            }

            logStore.info(SOURCE, "Critical memory pressure without UI surface; trimming video decoder and buffers")
            renderer.trimMemory("critical memory pressure without surface")
        }
    }

    fun sendMotionEvent(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ) {
        executors.session.execute {
            try {
                val contacts = touchInputMapper.map(event, surfaceWidth, surfaceHeight) ?: return@execute
                if (currentSession == null) return@execute
                queueOutbound(Cpc200Protocol.multiTouch(contacts))
            } finally {
                event.recycle()
            }
        }
    }

    private fun handleAutomotivePowerChanged(
        snapshot: AutomotivePowerSnapshot,
        reason: String,
    ) {
        val previousSnapshot = automotivePowerSnapshot
        val gateWasOpen = isVehicleGateOpen()
        val wasVehicleActive = vehicleActive
        automotivePowerSnapshot = snapshot
        vehicleActive = AutomotivePowerPolicy.isVehicleActive(snapshot)
        if (wasVehicleActive && !vehicleActive) {
            clearManualVehicleGateBypass("vehicle became inactive")
        }
        val gateIsOpen = isVehicleGateOpen()
        applyV2(ConnectionEventV2.VehicleAvailabilityChanged(gateIsOpen), render = false)

        if (sessionMode != SessionMode.USB || shuttingDown) {
            return
        }

        if (reason == POWER_REASON_SLEEPING) {
            restoreActivityAfterSleep = shouldRestoreActivityAfterSleep()
            logStore.info(
                SOURCE,
                "Sleep restore snapshot captured: restoreActivity=$restoreActivityAfterSleep " +
                    "surface=$surfaceAttached videoTarget=$videoStreamEnabled state=${_state.value.state.name}",
            )
        }

        if (
            restoreActivityAfterSleep &&
            previousSnapshot.powerState != PowerConstant.POWER_WORKING &&
            snapshot.powerState == PowerConstant.POWER_WORKING
        ) {
            launchCarPlayActivityForWakeRestore()
            restoreActivityAfterSleep = false
        }

        if (reason == POWER_REASON_SLEEPING && started && currentSession != null) {
            disconnectProjectionForVehicleSleeping(snapshot)
        }

        if (gateIsOpen == gateWasOpen) {
            if (!gateIsOpen && started && currentSession == null) {
                updateWaitingForVehicleState(reason)
            }
            return
        }

        logStore.info(
            SOURCE,
            "Vehicle gate ${if (gateIsOpen) "opened" else "closed"}: $reason | ${AutomotivePowerPolicy.describeSnapshot(snapshot)}",
        )

        if (!started) return

        if (!gateIsOpen) {
            cancelReconnect()
            clearAutoConnectPending()
            stopBleAdvertising()
            if (currentSession != null) {
                logStore.info(
                    SOURCE,
                    "Vehicle became inactive while projection session is still alive; keeping current session until it ends naturally | ${AutomotivePowerPolicy.describeSnapshot(snapshot)}",
                )
                return
            }
            updateWaitingForVehicleState(reason)
            return
        }

        if (currentSession == null) {
            connectOrRequestPermission("vehicle active: $reason")
        }
    }

    private fun shouldRestoreActivityAfterSleep(): Boolean {
        return activityVisible
    }

    private fun launchCarPlayActivityForWakeRestore() {
        val intent = Intent(appContext, CarPlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        logStore.info(
            SOURCE,
            "Restoring CarPlay activity on POWER_WORKING (surface=$surfaceAttached videoTarget=$videoStreamEnabled state=${_state.value.state.name})",
        )
        appContext.startActivity(intent)
    }

    fun setActivityVisible(visible: Boolean) {
        executors.session.execute {
            if (activityVisible == visible) return@execute
            activityVisible = visible
            logStore.info(
                SOURCE,
                "Activity visibility changed: visible=$visible surface=$surfaceAttached videoTarget=$videoStreamEnabled state=${_state.value.state.name}",
            )
        }
    }

    private fun disconnectProjectionForVehicleSleeping(snapshot: AutomotivePowerSnapshot) {
        if (sleepDisconnectFuture != null) return

        cancelReconnect()
        clearAutoConnectPending()
        stopBleAdvertising()

        logStore.info(
            SOURCE,
            "Vehicle sleeping detected; requesting CarPlay disconnect | ${AutomotivePowerPolicy.describeSnapshot(snapshot)}",
        )
        queueOutbound(Cpc200Protocol.disconnectPhone())

        sleepDisconnectFuture = executors.scheduler.schedule(
            {
                executors.session.execute {
                    sleepDisconnectFuture = null
                    if (currentSession != null) {
                        logStore.info(SOURCE, "Force closing CarPlay session after sleeping disconnect grace")
                        closeCurrentSession("vehicle sleeping", scheduleReconnect = false)
                    }
                    if (!isVehicleGateOpen() && started) {
                        updateWaitingForVehicleState("vehicle sleeping")
                    }
                }
            },
            SLEEP_DISCONNECT_GRACE_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun buildDebugVehicleSleepSnapshot(): AutomotivePowerSnapshot {
        return automotivePowerSnapshot.copy(
            powerServiceConnected = true,
            accState = PowerConstant.ACC_OFF,
            powerState = PowerConstant.POWER_UNWOKGING,
            currentScreenSignal = PowerConstant.SCREEN_HIDE,
            welcomeScreenState = PowerConstant.SCREEN_HIDE,
            screenStates = automotivePowerSnapshot.screenStates + mapOf(
                PowerConstant.SCREEN_TYPE_WORK to PowerConstant.SCREEN_HIDE,
            ),
            longPressPower = false,
        )
    }

    private fun buildDebugVehicleWakeSnapshot(): AutomotivePowerSnapshot {
        return automotivePowerSnapshot.copy(
            powerServiceConnected = true,
            accState = PowerConstant.ACC_ON,
            powerState = PowerConstant.POWER_WORKING,
            currentScreenSignal = PowerConstant.SCREEN_SHOW,
            welcomeScreenState = PowerConstant.SCREEN_SHOW,
            screenStates = automotivePowerSnapshot.screenStates + mapOf(
                PowerConstant.SCREEN_TYPE_WORK to PowerConstant.SCREEN_SHOW,
            ),
            longPressPower = false,
        )
    }

    private fun updateWaitingForVehicleState(reason: String) {
        cancelReconnect()
        cancelOpenAckRecovery()
        consecutiveOpenAckTimeouts = 0
        updateState(
            state = ProjectionConnectionState.WAITING_VEHICLE,
            message = buildVehicleWaitMessage(),
            adapterDescription = null,
            phoneDescription = null,
            streamDescription = null,
            videoWidth = null,
            videoHeight = null,
            lastError = null,
        )
        logStore.info(
            SOURCE,
            "Waiting for vehicle power before USB session: $reason | ${AutomotivePowerPolicy.describeSnapshot(automotivePowerSnapshot)}",
        )
    }

    private fun buildVehicleWaitMessage(): String {
        val accState = AutomotivePowerPolicy.describeAccState(automotivePowerSnapshot.accState)
        return if (automotivePowerSnapshot.accState != null) {
            "Waiting for vehicle ignition ($accState)"
        } else {
            "Waiting for vehicle power"
        }
    }

    private fun startUsbInternal(reason: String) {
        started = true
        sessionMode = SessionMode.USB
        sessionConfig = buildSessionConfig()
        ensureWriteLoop()
        if (!isVehicleGateOpen()) {
            updateWaitingForVehicleState(reason)
            return
        }
        updateState(
            state = ProjectionConnectionState.SEARCHING,
            message = "Scanning for USB adapter",
        )
        connectOrRequestPermission(reason)
    }

    private fun startReplayInternal(capturePath: String) {
        started = true
        sessionMode = SessionMode.REPLAY
        sessionConfig = buildSessionConfig()
        updateState(
            state = ProjectionConnectionState.CONNECTING,
            message = "Opening replay capture ${File(capturePath).name}",
            adapterDescription = "Replay ${File(capturePath).name}",
        )
        ensureWriteLoop()
        openReplaySession(capturePath)
    }

    private fun connectOrRequestPermission(reason: String) {
        cancelOpenAckRecovery()
        if (sessionMode != SessionMode.USB || !started || shuttingDown) return
        if (!isVehicleGateOpen()) {
            updateWaitingForVehicleState(reason)
            return
        }
        if (currentSession != null) return

        val device = usbTransport.findKnownDevice()
        if (device == null) {
            applyV2(ConnectionEventV2.TransportDetached, render = false)
            cancelReconnect()
            currentDevice = null
            currentConnectionLabel = null
            pendingUsbPermissionDeviceId = null
            openingUsbDeviceId = null
            updateState(
                state = ProjectionConnectionState.SEARCHING,
                message = "Waiting for CarPlay adapter over USB",
                adapterDescription = null,
            )
            return
        }

        cancelReconnect()
        currentDevice = device
        val adapterLabel = "${device.deviceName} (0x${device.productId.toString(16)})"
        currentConnectionLabel = adapterLabel

        if (!usbTransport.hasPermission(device)) {
            if (pendingUsbPermissionDeviceId == device.deviceId) {
                updateState(
                    state = ProjectionConnectionState.WAITING_PERMISSION,
                    message = "Waiting for USB permission",
                    adapterDescription = adapterLabel,
                )
                return
            }

            pendingUsbPermissionDeviceId = device.deviceId
            updateState(
                state = ProjectionConnectionState.WAITING_PERMISSION,
                message = "Waiting for USB permission",
                adapterDescription = adapterLabel,
            )
            usbTransport.requestPermission(device)
            return
        }

        pendingUsbPermissionDeviceId = null
        if (openingUsbDeviceId == device.deviceId) {
            updateState(
                state = ProjectionConnectionState.CONNECTING,
                message = "Opening USB connection ($reason)",
                adapterDescription = adapterLabel,
            )
            return
        }

        openingUsbDeviceId = device.deviceId
        lastUsbConnectRequestedAtMs = SystemClock.elapsedRealtime()
        applyV2TransportOpening(adapterLabel)
        updateState(
            state = ProjectionConnectionState.CONNECTING,
            message = "Opening USB connection ($reason)",
            adapterDescription = adapterLabel,
        )
        openSession(device)
    }

    private fun openSession(device: UsbDevice) {
        try {
            logStore.info(
                SOURCE,
                "Applying USB open stabilization delay (${USB_OPEN_STABILIZATION_DELAY_MS}ms)",
            )
            Thread.sleep(USB_OPEN_STABILIZATION_DELAY_MS)

            val session = usbTransport.open(device)
            lastUsbSessionOpenedAtMs = SystemClock.elapsedRealtime()
            val connectRequestLatencyMs = if (lastUsbConnectRequestedAtMs > 0L) {
                lastUsbSessionOpenedAtMs - lastUsbConnectRequestedAtMs
            } else {
                -1L
            }
            logStore.info(
                SOURCE,
                "USB session opened: ${session.description} " +
                    "(since connect request=${formatElapsedMs(connectRequestLatencyMs)})",
            )
            if (USB_POST_OPEN_STABILIZATION_DELAY_MS > 0L) {
                logStore.info(
                    SOURCE,
                    "Applying USB post-open stabilization delay (${USB_POST_OPEN_STABILIZATION_DELAY_MS}ms)",
                )
                Thread.sleep(USB_POST_OPEN_STABILIZATION_DELAY_MS)
            }
            currentSession = session
            currentConnectionLabel = session.description
            sessionConfig = buildSessionConfig()
            val includeBrandingAssets = brandingInitializedDeviceId != device.deviceId
            lastOpenAckAtMs = 0L
            resetProjectionRuntime("fresh usb session")

            ensureWriteLoop()
            startReadLoop(session)
            applyV2TransportOpened(
                connectionLabel = session.description,
                includeBrandingAssets = includeBrandingAssets,
            )
            armOpenAckTimeout(session)
            if (includeBrandingAssets) {
                brandingInitializedDeviceId = device.deviceId
            }
        } catch (t: Throwable) {
            logStore.error(SOURCE, "Unable to open session", t)
            closeCurrentSession("open session failed", scheduleReconnect = true)
        } finally {
            if (openingUsbDeviceId == device.deviceId) {
                openingUsbDeviceId = null
            }
        }
    }

    private fun openReplaySession(capturePath: String) {
        try {
            applyV2TransportOpening("Replay ${File(capturePath).name}")
            val session = CaptureReplaySession(capturePath, logStore)
            currentSession = session
            currentDevice = null
            currentConnectionLabel = session.description
            replayFrameDelayMs = DEFAULT_REPLAY_FRAME_DELAY_MS
            resetProjectionRuntime("fresh replay session")

            ensureWriteLoop()
            startReadLoop(session)
            applyV2TransportOpened(
                connectionLabel = session.description,
                includeBrandingAssets = true,
            )
        } catch (t: Throwable) {
            logStore.error(SOURCE, "Unable to open replay session", t)
            closeCurrentSession("open replay failed", scheduleReconnect = false)
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatFuture?.cancel(false)
        val now = SystemClock.elapsedRealtime()
        lastInboundActivityAtMs = now
        lastHeartbeatEchoAtMs = now
        heartbeatFuture = executors.scheduler.scheduleAtFixedRate(
            { queueOutbound(Cpc200Protocol.heartbeat()) },
            HEARTBEAT_INTERVAL_SECONDS,
            HEARTBEAT_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
        logStore.info(SOURCE, "Heartbeat started at 2s interval")
    }

    private fun stopHeartbeatLoop() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
    }

    private fun startFrameRequestLoop() {
        frameRequestFuture?.cancel(false)
        frameRequestFuture = executors.scheduler.scheduleAtFixedRate(
            { queueOutbound(Cpc200Protocol.command(Cpc200Protocol.Command.REQUEST_KEY_FRAME)) },
            FRAME_REQUEST_INTERVAL_MS,
            FRAME_REQUEST_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
        logStore.info(SOURCE, "Key frame loop started (${FRAME_REQUEST_INTERVAL_MS}ms interval)")
    }

    private fun stopFrameRequestLoop() {
        frameRequestFuture?.cancel(false)
        frameRequestFuture = null
    }

    private fun requestAutoConnect(reason: String) {
        if (currentSession == null || !started || shuttingDown) return
        if (!isVehicleGateOpen()) {
            logStore.info(SOURCE, "Auto-connect suppressed while vehicle gate is closed: $reason")
            return
        }
        beginConnectAttempt(reason)
        emitAutoConnectRequest()
        awaitingAutoConnectResult = true
        logStore.info(
            SOURCE,
            "Auto-connect requested: $reason",
        )
    }

    private fun clearAutoConnectPending() {
        if (!awaitingAutoConnectResult) return
        awaitingAutoConnectResult = false
        noteConnectAttemptAutoConnectCleared()
        logStore.info(SOURCE, "Auto-connect pending cleared")
    }

    private fun startBleAdvertising(reason: String) {
        if (currentSession == null || !started || shuttingDown) return
        if (!isVehicleGateOpen()) {
            logStore.info(SOURCE, "BLE advertising suppressed while vehicle gate is closed: $reason")
            return
        }
        if (bleAdvertisingActive) return
        bleAdvertisingActive = true
        queueOutbound(Cpc200Protocol.command(Cpc200Protocol.Command.START_BLE_ADV))
        logStore.info(SOURCE, "BLE advertising enabled: $reason")
    }

    private fun stopBleAdvertising() {
        if (!bleAdvertisingActive) return
        bleAdvertisingActive = false
        if (currentSession != null && started && !shuttingDown) {
            queueOutbound(Cpc200Protocol.command(Cpc200Protocol.Command.STOP_BLE_ADV))
        }
        logStore.info(SOURCE, "BLE advertising disabled")
    }

    private fun emitAutoConnectRequest() {
        val selectedId = resolveSessionDeviceCandidateId()
            ?.let(Cpc200Protocol::normalizeDeviceIdentifier)
            ?.ifBlank { null }
        if (selectedId != null) {
            queueOutbound(Cpc200Protocol.selectDevice(selectedId))
            noteConnectAttemptExplicitTargetPrepared(selectedId)
            logStore.info(SOURCE, "Auto-connect target prepared: ${describeKnownDevice(selectedId)}")
        }
        queueOutbound(Cpc200Protocol.command(Cpc200Protocol.Command.START_AUTO_CONNECT))
    }

    private fun beginConnectAttempt(reason: String) {
        val now = SystemClock.elapsedRealtime()
        val existingAttempt = activeConnectAttempt
        if (existingAttempt != null) {
            val stillEarlyDuplicate = now - existingAttempt.startedAtMs <= CONNECT_ATTEMPT_DUPLICATE_WINDOW_MS &&
                existingAttempt.pluggedAtMs == null &&
                existingAttempt.phase8AtMs == null &&
                existingAttempt.phase13AtMs == null &&
                existingAttempt.phase0AtMs == null &&
                existingAttempt.unpluggedAtMs == null
            if (stillEarlyDuplicate) {
                logStore.info(
                    SOURCE,
                    "Connect attempt #${existingAttempt.id} retriggered before Plugged: $reason",
                )
                return
            }
            completeConnectAttempt("superseded before streaming", extra = "nextReason=$reason")
        }

        val attempt = ConnectAttemptTrace(
            id = ++connectAttemptSeq,
            reason = reason,
            startedAtMs = now,
            targetDeviceId = resolveSessionDeviceCandidateId(),
            sessionDescription = currentSession?.description ?: currentConnectionLabel,
            stateAtStart = _state.value.state,
            phaseAtStart = _state.value.protocolPhase,
        )
        activeConnectAttempt = attempt
        val sinceOpenAckMs = if (lastOpenAckAtMs > 0L) now - lastOpenAckAtMs else -1L
        logStore.info(
            SOURCE,
            "Connect attempt #${attempt.id} started: reason=$reason " +
                "target=${describeKnownDevice(attempt.targetDeviceId)} " +
                "phase=${attempt.phaseAtStart.name} state=${attempt.stateAtStart.name} " +
                "sinceOpenAck=${formatElapsedMs(sinceOpenAckMs)} session=${attempt.sessionDescription ?: "-"}",
        )
        scheduleConnectAttemptWatchdog(
            attemptId = attempt.id,
            delayMs = CONNECT_ATTEMPT_PRE_PLUGGED_STALL_LOG_MS,
            stage = "waiting for Plugged",
        )
    }

    private fun noteConnectAttemptExplicitTargetPrepared(deviceId: String) {
        val attempt = activeConnectAttempt ?: return
        if (attempt.explicitTargetPreparedAtMs != null) return
        attempt.explicitTargetPreparedAtMs = SystemClock.elapsedRealtime()
        logStore.info(
            SOURCE,
            "Connect attempt #${attempt.id} explicit target prepared at ${formatAttemptDelta(attempt, attempt.explicitTargetPreparedAtMs)}: " +
                describeKnownDevice(deviceId),
        )
    }

    private fun noteConnectAttemptAutoConnectCleared() {
        val attempt = activeConnectAttempt ?: return
        if (attempt.autoConnectClearedAtMs != null) return
        attempt.autoConnectClearedAtMs = SystemClock.elapsedRealtime()
        logStore.info(
            SOURCE,
            "Connect attempt #${attempt.id} auto-connect cleared at ${formatAttemptDelta(attempt, attempt.autoConnectClearedAtMs)}",
        )
    }

    private fun noteConnectAttemptDeviceFound() {
        val attempt = activeConnectAttempt ?: return
        if (attempt.deviceFoundAtMs != null) return
        attempt.deviceFoundAtMs = SystemClock.elapsedRealtime()
        logStore.info(
            SOURCE,
            "Connect attempt #${attempt.id} deviceFound at ${formatAttemptDelta(attempt, attempt.deviceFoundAtMs)}",
        )
    }

    private fun noteConnectAttemptBluetoothConnectStart(deviceId: String?) {
        val attempt = activeConnectAttempt ?: return
        if (attempt.bluetoothConnectStartAtMs != null) return
        attempt.bluetoothConnectStartAtMs = SystemClock.elapsedRealtime()
        logStore.info(
            SOURCE,
            "Connect attempt #${attempt.id} bluetoothConnectStart at ${formatAttemptDelta(attempt, attempt.bluetoothConnectStartAtMs)} " +
                "device=${describeKnownDevice(deviceId ?: attempt.targetDeviceId)}",
        )
    }

    private fun noteConnectAttemptBluetoothConnected(deviceId: String?) {
        val attempt = activeConnectAttempt ?: return
        if (attempt.bluetoothConnectedAtMs != null) return
        attempt.bluetoothConnectedAtMs = SystemClock.elapsedRealtime()
        logStore.info(
            SOURCE,
            "Connect attempt #${attempt.id} bluetoothConnected at ${formatAttemptDelta(attempt, attempt.bluetoothConnectedAtMs)} " +
                "device=${describeKnownDevice(deviceId ?: attempt.targetDeviceId)}",
        )
    }

    private fun noteConnectAttemptPlugged(pluggedInfo: Cpc200Protocol.PluggedInfo) {
        val attempt = activeConnectAttempt ?: return
        if (attempt.pluggedAtMs != null) return
        attempt.pluggedAtMs = SystemClock.elapsedRealtime()
        logStore.info(
            SOURCE,
            "Connect attempt #${attempt.id} plugged at ${formatAttemptDelta(attempt, attempt.pluggedAtMs)} " +
                "phoneType=${Cpc200Protocol.describePhoneType(pluggedInfo.phoneType)} wifi=${pluggedInfo.wifiState}",
        )
        scheduleConnectAttemptWatchdog(
            attemptId = attempt.id,
            delayMs = CONNECT_ATTEMPT_POST_PLUGGED_STALL_LOG_MS,
            stage = "waiting for Phase 7/8 after Plugged",
        )
        schedulePostPluggedReconnect(attempt.id)
    }

    private fun noteConnectAttemptWifiConnected() {
        val attempt = activeConnectAttempt ?: return
        if (attempt.wifiConnectedAtMs == null) {
            attempt.wifiConnectedAtMs = SystemClock.elapsedRealtime()
        }
        logStore.info(
            SOURCE,
            "Connect attempt #${attempt.id} wifiConnected at ${formatAttemptDelta(attempt, attempt.wifiConnectedAtMs)} " +
                "phase=${_state.value.protocolPhase.name} state=${_state.value.state.name}",
        )
    }

    private fun noteConnectAttemptPhase(phase: Int) {
        val attempt = activeConnectAttempt ?: return
        val now = SystemClock.elapsedRealtime()
        when (phase) {
            7 -> {
                if (attempt.phase7AtMs == null) {
                    attempt.phase7AtMs = now
                    logStore.info(
                        SOURCE,
                        "Connect attempt #${attempt.id} phase7 at ${formatAttemptDelta(attempt, attempt.phase7AtMs)}",
                    )
                }
                scheduleConnectAttemptWatchdog(
                    attemptId = attempt.id,
                    delayMs = CONNECT_ATTEMPT_POST_PHASE7_STALL_LOG_MS,
                    stage = "waiting for Phase 8 after Phase 7",
                )
            }

            8 -> {
                if (attempt.phase8AtMs == null) {
                    attempt.phase8AtMs = now
                }
                cancelPostPluggedReconnect()
                completeConnectAttempt("streaming active", terminalAtMs = now)
            }

            13 -> {
                if (attempt.phase13AtMs == null) {
                    attempt.phase13AtMs = now
                }
                completeConnectAttempt("negotiation failed (phase 13)", terminalAtMs = now)
            }

            0 -> {
                if (attempt.phase0AtMs == null) {
                    attempt.phase0AtMs = now
                }
                completeConnectAttempt("session ended (phase 0)", terminalAtMs = now)
            }
        }
    }

    private fun noteConnectAttemptFirstVideo(width: Int, height: Int) {
        val attempt = activeConnectAttempt ?: return
        if (attempt.firstVideoAtMs != null) return
        val now = SystemClock.elapsedRealtime()
        attempt.firstVideoAtMs = now
        if (attempt.phase8AtMs == null) {
            completeConnectAttempt(
                "first video before phase 8",
                terminalAtMs = now,
                extra = "video=${width}x${height}",
            )
        }
    }

    private fun noteConnectAttemptUnplugged() {
        val attempt = activeConnectAttempt ?: return
        if (attempt.unpluggedAtMs == null) {
            attempt.unpluggedAtMs = SystemClock.elapsedRealtime()
        }
        val unpluggedAtMs = attempt.unpluggedAtMs ?: SystemClock.elapsedRealtime()
        completeConnectAttempt("unplugged before streaming", terminalAtMs = unpluggedAtMs)
    }

    private fun scheduleConnectAttemptWatchdog(
        attemptId: Int,
        delayMs: Long,
        stage: String,
    ) {
        connectAttemptWatchdogFuture?.cancel(false)
        connectAttemptWatchdogFuture = executors.scheduler.schedule(
            {
                executors.session.execute {
                    val attempt = activeConnectAttempt ?: return@execute
                    if (attempt.id != attemptId) return@execute
                    val stalled = when (stage) {
                        "waiting for Plugged" -> {
                            attempt.pluggedAtMs == null &&
                                attempt.phase8AtMs == null &&
                                attempt.phase13AtMs == null &&
                                attempt.phase0AtMs == null &&
                                attempt.unpluggedAtMs == null
                        }

                        "waiting for Phase 7/8 after Plugged" -> {
                            attempt.pluggedAtMs != null &&
                                attempt.phase7AtMs == null &&
                                attempt.phase8AtMs == null &&
                                attempt.phase13AtMs == null &&
                                attempt.phase0AtMs == null &&
                                attempt.unpluggedAtMs == null
                        }

                        "waiting for Phase 8 after Phase 7" -> {
                            attempt.phase7AtMs != null &&
                                attempt.phase8AtMs == null &&
                                attempt.phase13AtMs == null &&
                                attempt.phase0AtMs == null &&
                                attempt.unpluggedAtMs == null
                        }

                        else -> false
                    }
                    if (!stalled) return@execute
                    logStore.info(
                        SOURCE,
                        "Connect attempt #${attempt.id} stall detected: $stage | " +
                            "${buildConnectAttemptSummary(attempt, terminalAtMs = SystemClock.elapsedRealtime())} " +
                            "phase=${_state.value.protocolPhase.name} state=${_state.value.state.name}",
                    )
                }
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun completeConnectAttempt(
        outcome: String,
        terminalAtMs: Long = SystemClock.elapsedRealtime(),
        extra: String? = null,
    ) {
        val attempt = activeConnectAttempt ?: return
        connectAttemptWatchdogFuture?.cancel(false)
        connectAttemptWatchdogFuture = null
        val suffix = extra?.let { " $it" } ?: ""
        logStore.info(
            SOURCE,
            "Connect attempt #${attempt.id} $outcome | ${buildConnectAttemptSummary(attempt, terminalAtMs)}$suffix",
        )
        activeConnectAttempt = null
    }

    private fun buildConnectAttemptSummary(
        attempt: ConnectAttemptTrace,
        terminalAtMs: Long,
    ): String {
        return buildString {
            append("reason=").append(attempt.reason)
            append(" target=").append(describeKnownDevice(attempt.targetDeviceId))
            append(" explicitTarget=").append(attempt.explicitTargetPreparedAtMs != null)
            append(" session=").append(attempt.sessionDescription ?: "-")
            append(" total=").append(formatElapsedMs(terminalAtMs - attempt.startedAtMs))
            append(" cleared=").append(formatAttemptDelta(attempt, attempt.autoConnectClearedAtMs))
            append(" deviceFound=").append(formatAttemptDelta(attempt, attempt.deviceFoundAtMs))
            append(" btStart=").append(formatAttemptDelta(attempt, attempt.bluetoothConnectStartAtMs))
            append(" btConnected=").append(formatAttemptDelta(attempt, attempt.bluetoothConnectedAtMs))
            append(" plugged=").append(formatAttemptDelta(attempt, attempt.pluggedAtMs))
            append(" wifiConnected=").append(formatAttemptDelta(attempt, attempt.wifiConnectedAtMs))
            append(" phase7=").append(formatAttemptDelta(attempt, attempt.phase7AtMs))
            append(" phase8=").append(formatAttemptDelta(attempt, attempt.phase8AtMs))
            append(" firstVideo=").append(formatAttemptDelta(attempt, attempt.firstVideoAtMs))
            append(" phase13=").append(formatAttemptDelta(attempt, attempt.phase13AtMs))
            append(" phase0=").append(formatAttemptDelta(attempt, attempt.phase0AtMs))
            append(" unplugged=").append(formatAttemptDelta(attempt, attempt.unpluggedAtMs))
        }
    }

    private fun formatAttemptDelta(
        attempt: ConnectAttemptTrace,
        timestampMs: Long?,
    ): String {
        return timestampMs?.let { formatElapsedMs(it - attempt.startedAtMs) } ?: "-"
    }

    private fun formatElapsedMs(durationMs: Long): String {
        return if (durationMs >= 0L) "${durationMs}ms" else "-"
    }

    private fun applyV2(
        event: ConnectionEventV2,
        render: Boolean = shouldRenderV2State(),
        streamDescription: String? = _state.value.streamDescription,
    ): ConnectionSnapshotV2 {
        val result = connectionEngineV2.onEvent(event)
        result.effects.forEach(::executeV2Effect)
        if (render && shouldRenderV2State(result.snapshot)) {
            renderConnectionStateFromV2(
                snapshot = result.snapshot,
                streamDescription = streamDescription,
            )
        }
        return result.snapshot
    }

    private fun applyV2TransportOpening(connectionLabel: String?) {
        transportEpochV2 += 1L
        applyV2(
            ConnectionEventV2.TransportOpening(
                epoch = transportEpochV2,
                connectionLabel = connectionLabel,
            ),
            render = true,
        )
    }

    private fun applyV2TransportOpened(
        connectionLabel: String?,
        includeBrandingAssets: Boolean,
    ) {
        applyV2(
            ConnectionEventV2.TransportOpened(
                epoch = transportEpochV2,
                connectionLabel = connectionLabel,
                includeBrandingAssets = includeBrandingAssets,
            ),
            render = true,
        )
    }

    private fun applyV2TransportClosed(reason: String) {
        applyV2(
            if (isTransportFailureReason(reason)) {
                ConnectionEventV2.TransportFailed(reason)
            } else {
                ConnectionEventV2.TransportClosed(reason)
            },
            render = false,
        )
    }

    private fun applyV2PolicyUpdated(render: Boolean = shouldRenderV2State()) {
        applyV2(
            ConnectionEventV2.PolicyUpdated(
                knownDeviceCount = effectiveKnownDeviceCount(),
                autoConnectEligible = shouldAutoConnectKnownDevices(),
                manualSelectionRequired = requiresManualDeviceSelection(),
            ),
            render = render,
        )
    }

    private fun isTransportFailureReason(reason: String): Boolean {
        return reason.contains("failed", ignoreCase = true) ||
            reason.contains("timeout", ignoreCase = true) ||
            reason.contains("error", ignoreCase = true)
    }

    private fun shouldRenderV2State(snapshot: ConnectionSnapshotV2 = connectionEngineV2.snapshot()): Boolean {
        return currentSession != null ||
            snapshot.transport.state == TransportStateV2.OPENING ||
            snapshot.transport.state == TransportStateV2.OPEN
    }

    private fun executeV2Effect(effect: ConnectionEffectV2) {
        when (effect) {
            ConnectionEffectV2.StartHeartbeat -> startHeartbeatLoop()
            ConnectionEffectV2.StopFrameRequests -> stopFrameRequestLoop()
            ConnectionEffectV2.StartBleAdvertising -> startBleAdvertising("protocol v2")
            ConnectionEffectV2.StopBleAdvertising -> stopBleAdvertising()
            ConnectionEffectV2.RequestAutoConnect -> requestAutoConnect("protocol v2")
            ConnectionEffectV2.ClearPendingAutoConnect -> clearAutoConnectPending()
            ConnectionEffectV2.RequestKeyFrame -> {
                queueOutbound(Cpc200Protocol.command(Cpc200Protocol.Command.REQUEST_KEY_FRAME))
                logStore.info(SOURCE, "Protocol v2 requested immediate key frame")
            }

            ConnectionEffectV2.StartFrameRequests -> startFrameRequestLoop()

            is ConnectionEffectV2.QueueInitSequence -> {
                queueInitSequenceV2(includeBrandingAssets = effect.includeBrandingAssets)
            }
        }
    }

    private fun queueInitSequenceV2(includeBrandingAssets: Boolean) {
        logStore.info(
            SOURCE,
            "Queueing V2 init sequence: ${sessionConfig.width}x${sessionConfig.height}@${sessionConfig.fps} " +
                "safeArea=${sessionConfig.carplaySafeAreaBottomDp}dp dpi=${sessionConfig.dpi} " +
                "name=${sessionConfig.boxName} mic=${if (sessionConfig.useAdapterMic) "adapter" else "phone"} " +
                "audio=${if (sessionConfig.useBluetoothAudio) "car_bt" else "adapter_usb"}",
        )
        logStore.info(
            SOURCE,
            "Advanced adapter features require firmware flags: DashboardInfo=7, GNSSCapability=1/3, HudGPSSwitch=1, AdvancedFeatures=1",
        )
        buildInitMessagesV2(
            config = sessionConfig,
            includeBrandingAssets = includeBrandingAssets,
        ).forEach(::queueOutbound)
    }

    private fun buildInitMessagesV2(
        config: ProjectionSessionConfig,
        includeBrandingAssets: Boolean,
    ): List<ByteArray> = buildList {
        // SendFile messages must precede Open per host_app_guide.md §2 init sequence
        add(Cpc200Protocol.sendNumber("/tmp/screen_dpi", config.dpi))
        add(Cpc200Protocol.open(config))
        add(Cpc200Protocol.sendBoolean("/tmp/night_mode", config.nightMode))
        add(Cpc200Protocol.sendNumber("/tmp/hand_drive_mode", config.handDriveMode))
        add(Cpc200Protocol.sendBoolean("/tmp/charge_mode", true))
        add(Cpc200Protocol.sendString("/etc/box_name", config.boxName))
        if (includeBrandingAssets) {
            Cpc200Protocol.oemIcon(config)?.let { add(it) }
            Cpc200Protocol.icon120(config)?.let { add(it) }
            Cpc200Protocol.icon180(config)?.let { add(it) }
            Cpc200Protocol.icon256(config)?.let { add(it) }
            logStore.info(
                SOURCE,
                "Queueing OEM branding: ${config.oemBranding.label} name=${config.oemBranding.name}",
            )
        } else {
            logStore.info(SOURCE, "Skipping OEM branding assets for reconnect init")
        }
        add(Cpc200Protocol.boxSettings(config))
        add(Cpc200Protocol.airplayConfig(config))
        add(Cpc200Protocol.command(Cpc200Protocol.Command.SUPPORT_WIFI))
        add(Cpc200Protocol.command(Cpc200Protocol.Command.SUPPORT_AUTO_CONNECT))
        add(
            Cpc200Protocol.command(
                if (config.wifi5g) {
                    Cpc200Protocol.Command.USE_5G_WIFI
                } else {
                    Cpc200Protocol.Command.USE_24G_WIFI
                },
            ),
        )
        add(
            Cpc200Protocol.command(
                if (config.useAdapterMic) {
                    Cpc200Protocol.Command.MIC
                } else {
                    Cpc200Protocol.Command.USE_PHONE_MIC
                },
            ),
        )
        add(
            Cpc200Protocol.command(
                if (config.useBluetoothAudio) {
                    Cpc200Protocol.Command.USE_BLUETOOTH_AUDIO
                } else {
                    Cpc200Protocol.Command.USE_BOX_TRANS_AUDIO
                },
            ),
        )
        if (config.androidWorkMode) {
            add(Cpc200Protocol.sendBoolean("/etc/android_work_mode", true))
        }
    }

    private fun renderConnectionStateFromV2(
        snapshot: ConnectionSnapshotV2 = connectionEngineV2.snapshot(),
        streamDescription: String? = _state.value.streamDescription,
    ) {
        val protocolPhase = when {
            snapshot.projection.state == ProjectionStateV2.FAILED -> ProjectionProtocolPhase.NEGOTIATION_FAILED
            snapshot.projection.state == ProjectionStateV2.ENDED -> ProjectionProtocolPhase.SESSION_ENDED
            snapshot.isStreamingActive() -> ProjectionProtocolPhase.STREAMING_ACTIVE
            snapshot.projection.state == ProjectionStateV2.NEGOTIATING -> ProjectionProtocolPhase.AIRPLAY_NEGOTIATING
            snapshot.projection.state == ProjectionStateV2.PLUGGED -> ProjectionProtocolPhase.CARPLAY_SESSION_SETUP
            snapshot.discovery.state == DiscoveryStateV2.RETRYING ||
                snapshot.projection.state == ProjectionStateV2.DISCONNECTED -> ProjectionProtocolPhase.WAITING_RETRY
            snapshot.discovery.state == DiscoveryStateV2.SCANNING -> ProjectionProtocolPhase.PHONE_SEARCH
            snapshot.discovery.state == DiscoveryStateV2.DEVICE_FOUND ||
                snapshot.discovery.state == DiscoveryStateV2.BT_CONNECTING ||
                snapshot.discovery.state == DiscoveryStateV2.BT_CONNECTED -> ProjectionProtocolPhase.PHONE_FOUND_BT_CONNECTED
            snapshot.transport.openAcknowledged -> ProjectionProtocolPhase.INIT_ECHO
            snapshot.transport.state == TransportStateV2.OPEN ||
                snapshot.transport.state == TransportStateV2.OPENING -> ProjectionProtocolPhase.HOST_INIT
            else -> ProjectionProtocolPhase.NONE
        }

        val state = when {
            snapshot.transport.state == TransportStateV2.FAILED -> ProjectionConnectionState.ERROR
            snapshot.isStreamingActive() -> ProjectionConnectionState.STREAMING
            snapshot.projection.state == ProjectionStateV2.PLUGGED ||
                snapshot.projection.state == ProjectionStateV2.NEGOTIATING -> ProjectionConnectionState.CONNECTING
            snapshot.projection.state == ProjectionStateV2.DISCONNECTED ||
                snapshot.projection.state == ProjectionStateV2.ENDED ||
                snapshot.projection.state == ProjectionStateV2.FAILED ||
                snapshot.discovery.state == DiscoveryStateV2.RETRYING ||
                snapshot.discovery.state == DiscoveryStateV2.MANUAL_SELECTION -> ProjectionConnectionState.WAITING_PHONE
            snapshot.discovery.state == DiscoveryStateV2.SCANNING ||
                snapshot.discovery.state == DiscoveryStateV2.DEVICE_FOUND ||
                snapshot.discovery.state == DiscoveryStateV2.BT_CONNECTING ||
                snapshot.discovery.state == DiscoveryStateV2.BT_CONNECTED -> ProjectionConnectionState.CONNECTING
            snapshot.transport.state == TransportStateV2.OPENING -> ProjectionConnectionState.CONNECTING
            snapshot.transport.state == TransportStateV2.OPEN && !snapshot.transport.openAcknowledged -> ProjectionConnectionState.INIT
            snapshot.transport.state == TransportStateV2.OPEN -> ProjectionConnectionState.WAITING_PHONE
            !snapshot.policy.vehicleReady && currentSession == null -> ProjectionConnectionState.WAITING_VEHICLE
            else -> _state.value.state
        }

        val message = when {
            snapshot.transport.state == TransportStateV2.FAILED -> {
                "Connection lost: ${snapshot.transport.lastFailure ?: "transport failed"}"
            }

            snapshot.isStreamingActive() -> {
                if (surfaceAttached) {
                    "CarPlay streaming active"
                } else {
                    "CarPlay streaming active while activity is in background"
                }
            }

            snapshot.projection.state == ProjectionStateV2.NEGOTIATING -> {
                "CarPlay negotiation in progress"
            }

            snapshot.projection.state == ProjectionStateV2.PLUGGED -> {
                "CarPlay linked. Finishing stream negotiation"
            }

            snapshot.projection.state == ProjectionStateV2.FAILED -> {
                "Negotiation failed. ${v2RetryHint(snapshot)}"
            }

            snapshot.projection.state == ProjectionStateV2.ENDED -> {
                "Session ended. ${v2RetryHint(snapshot)}"
            }

            snapshot.projection.state == ProjectionStateV2.DISCONNECTED ||
                snapshot.discovery.state == DiscoveryStateV2.RETRYING -> {
                when {
                    snapshot.discovery.lastReason == "bluetooth disconnected" -> {
                        "Bluetooth disconnected. ${v2RetryHint(snapshot)}"
                    }

                    snapshot.discovery.lastReason == "device connect failed" -> {
                        "Connection failed. ${v2RetryHint(snapshot)}"
                    }

                    snapshot.discovery.lastReason == "device not found" -> {
                        "No known device found. ${v2RetryHint(snapshot)}"
                    }

                    else -> {
                        "Phone disconnected. ${v2RetryHint(snapshot)}"
                    }
                }
            }

            snapshot.discovery.state == DiscoveryStateV2.MANUAL_SELECTION -> {
                pendingConnectionDeviceId?.let { pendingId ->
                    "Connecting to ${describeKnownDevice(pendingId)}"
                } ?: "Adapter ready. Select iPhone to connect"
            }

            snapshot.discovery.state == DiscoveryStateV2.SCANNING -> {
                "Scanning for known iPhone"
            }

            snapshot.discovery.state == DiscoveryStateV2.DEVICE_FOUND -> {
                "Phone found. Establishing Bluetooth link"
            }

            snapshot.discovery.state == DiscoveryStateV2.BT_CONNECTING -> {
                if (snapshot.discovery.lastReason == "bluetooth pairing in progress") {
                    "Bluetooth pairing in progress"
                } else {
                    "Phone found. Establishing Bluetooth link"
                }
            }

            snapshot.discovery.state == DiscoveryStateV2.BT_CONNECTED -> {
                "Bluetooth connected. Waiting for CarPlay session"
            }

            snapshot.transport.state == TransportStateV2.OPEN && !snapshot.transport.openAcknowledged -> {
                "Init queued. Waiting for adapter Open acknowledgment"
            }

            snapshot.transport.state == TransportStateV2.OPEN && snapshot.transport.openAcknowledged -> {
                when (snapshot.policy.selectionMode) {
                    SelectionModeV2.MANUAL -> "Adapter ready. Select iPhone to connect"
                    SelectionModeV2.AUTO -> "Adapter ready. Scanning and waiting for iPhone"
                    SelectionModeV2.NONE -> {
                        if (snapshot.policy.knownDeviceCount > 0) {
                            "Adapter ready. Waiting for iPhone"
                        } else {
                            "Adapter ready. BLE pairing mode active"
                        }
                    }
                }
            }

            snapshot.transport.state == TransportStateV2.OPENING -> {
                "Opening USB connection"
            }

            else -> _state.value.statusMessage
        }

        val videoWidth = snapshot.projection.videoWidth
            ?: if (snapshot.isSessionEstablished()) snapshot.transport.width else null
        val videoHeight = snapshot.projection.videoHeight
            ?: if (snapshot.isSessionEstablished()) snapshot.transport.height else null
        val resolvedPhoneDescription = when {
            snapshot.projection.phoneType != null -> Cpc200Protocol.describePhoneType(snapshot.projection.phoneType)
            snapshot.discovery.activeDeviceId != null -> describeKnownDevice(snapshot.discovery.activeDeviceId)
            currentPeerBluetoothDeviceId != null -> describeKnownDevice(currentPeerBluetoothDeviceId)
            currentSessionDeviceId != null -> describeKnownDevice(currentSessionDeviceId)
            else -> null
        }
        val resolvedStreamDescription = if (snapshot.isStreamingActive()) {
            streamDescription ?: _state.value.streamDescription
        } else {
            null
        }

        updateState(
            state = state,
            protocolPhase = protocolPhase,
            message = message,
            adapterDescription = snapshot.transport.connectionLabel ?: currentConnectionLabel,
            phoneDescription = resolvedPhoneDescription,
            streamDescription = resolvedStreamDescription,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            surfaceAttached = surfaceAttached,
            lastError = if (state == ProjectionConnectionState.ERROR) {
                snapshot.transport.lastFailure ?: _state.value.lastError
            } else {
                null
            },
        )
    }

    private fun v2RetryHint(snapshot: ConnectionSnapshotV2): String {
        return when {
            snapshot.policy.selectionMode == SelectionModeV2.MANUAL -> "Select iPhone to connect"
            snapshot.policy.knownDeviceCount > 0 -> "Rescanning and waiting for iPhone"
            else -> "BLE pairing mode active"
        }
    }

    private fun resetProjectionRuntime(reason: String) {
        ProjectionFrameSnapshotStore.clear()
        currentSessionDeviceId = null
        catalogActiveDeviceId = null
        currentPeerBluetoothDeviceId = null
        pendingUsbPermissionDeviceId = null
        openingUsbDeviceId = null
        pendingConnectionDeviceId = null
        pendingDeviceSelectionSent = false
        knownDevices.clear()
        availableDeviceIds.clear()
        outboundQueue.clear()
        clearAutoConnectPending()
        bleAdvertisingActive = false
        awaitingAutoConnectResult = false
        lastInboundActivityAtMs = 0L
        lastHeartbeatEchoAtMs = 0L
        backgroundVideoDropLogged = false
        microphoneInputManager.stop("projection runtime reset: $reason")
        audioStreamManager.release()
        audioStreamManager.updateDeviceSettings(null)
        renderer.hardReset("projection runtime reset: $reason")
        logStore.info(SOURCE, "Projection runtime reset: $reason")
    }

    private fun markInboundActivity(label: String) {
        val now = SystemClock.elapsedRealtime()
        val previousInboundAtMs = lastInboundActivityAtMs
        lastInboundActivityAtMs = now
        if (previousInboundAtMs <= 0L) return
        val gapMs = now - previousInboundAtMs
        if (gapMs >= INBOUND_ACTIVITY_LOG_GAP_MS) {
            logStore.info(SOURCE, "Inbound activity after ${gapMs}ms: $label")
        }
    }

    private fun rememberKnownDevice(device: DongleKnownDevice) {
        val normalizedId = Cpc200Protocol.normalizeDeviceIdentifier(device.id)
        if (normalizedId.isBlank()) return
        val existing = knownDevices[normalizedId]
        val mergedDevice = if (existing == null) {
            device.copy(id = normalizedId)
        } else {
            existing.copy(
                name = device.name ?: existing.name,
                type = device.type ?: existing.type,
                index = device.index ?: existing.index,
            )
        }
        knownDevices[normalizedId] = mergedDevice
        mergedDevice.name?.let { settingsStore.setCachedDeviceName(normalizedId, it) }
    }

    private fun rememberKnownDevices(devices: List<DongleKnownDevice>) {
        devices.forEach(::rememberKnownDevice)
    }

    private fun refreshAvailableDevices(devices: List<DongleKnownDevice>) {
        availableDeviceIds.clear()
        devices.forEach { device ->
            val normalizedId = Cpc200Protocol.normalizeDeviceIdentifier(device.id)
            if (normalizedId.isNotBlank()) {
                availableDeviceIds += normalizedId
            }
        }
    }

    private fun effectiveKnownDeviceCount(): Int {
        if (availableDeviceIds.isNotEmpty()) return availableDeviceIds.size
        if (knownDevices.isNotEmpty()) return knownDevices.size
        return if (settingsStore.getLastConnectedDeviceId() != null) 1 else 0
    }

    private fun requiresManualDeviceSelection(): Boolean {
        if (!settingsStore.isAutoConnectEnabled()) return knownDevices.isNotEmpty()
        if (pendingConnectionDeviceId != null) return false
        return effectiveKnownDeviceCount() > 1
    }

    private fun shouldAutoConnectKnownDevices(): Boolean {
        if (!settingsStore.isAutoConnectEnabled()) return false
        val hasKnown = knownDevices.isNotEmpty() || settingsStore.getLastConnectedDeviceId() != null
        if (!hasKnown) return false
        if (!AutomotivePowerPolicy.isReadyForAutoConnect(automotivePowerSnapshot)) return false
        if (pendingConnectionDeviceId != null) return true
        return !requiresManualDeviceSelection()
    }

    private fun buildSessionConfig(): ProjectionSessionConfig {
        val baseConfig = ProjectionSessionConfig.fromContext(
            context = appContext,
            adapterName = settingsStore.getAdapterName(),
            climatePanelEnabled = settingsStore.isClimatePanelEnabled(),
            carplaySafeAreaBottomDp = 0,
        )
        val preferredDeviceId = pendingConnectionDeviceId
            ?: currentSessionDeviceId
            ?: currentPeerBluetoothDeviceId
            ?: currentSelectedDeviceId
            ?: catalogActiveDeviceId
            ?: settingsStore.getLastConnectedDeviceId()
        val deviceSettings = settingsStore.getSettings(preferredDeviceId)
        val resolvedConfig = baseConfig.copy(
            useBluetoothAudio = deviceSettings.audioRoute == ProjectionAudioRoute.CAR_BLUETOOTH,
            useAdapterMic = deviceSettings.micRoute == ProjectionMicRoute.ADAPTER,
        )
        logStore.info(
            SOURCE,
            "Session config resolved: device=${describeKnownDevice(preferredDeviceId)} " +
                "audio=${deviceSettings.audioRoute.name} mic=${deviceSettings.micRoute.name} " +
                "screen=${resolvedConfig.width}x${resolvedConfig.height} safeArea=${resolvedConfig.carplaySafeAreaBottomDp}dp dpi=${resolvedConfig.dpi}",
        )
        return resolvedConfig
    }

    private fun currentMicRouteCommand(): Int {
        return if (sessionConfig.useAdapterMic) {
            Cpc200Protocol.Command.MIC
        } else {
            Cpc200Protocol.Command.USE_PHONE_MIC
        }
    }

    private fun currentAudioRouteCommand(): Int {
        return if (sessionConfig.useBluetoothAudio) {
            Cpc200Protocol.Command.USE_BLUETOOTH_AUDIO
        } else {
            Cpc200Protocol.Command.USE_BOX_TRANS_AUDIO
        }
    }

    private fun reapplySessionRouting(reason: String) {
        val micCommand = currentMicRouteCommand()
        val audioCommand = currentAudioRouteCommand()
        queueOutbound(Cpc200Protocol.command(micCommand))
        queueOutbound(Cpc200Protocol.command(audioCommand))
        logStore.info(
            SOURCE,
            "Re-applied session routing ($reason): mic=${Cpc200Protocol.describeCommand(micCommand)} " +
                "audio=${Cpc200Protocol.describeCommand(audioCommand)}",
        )
    }

    private fun loadRuntimeDeviceSettings(): ProjectionDeviceSettings {
        return settingsStore.getSettings(resolveCurrentDeviceId())
    }

    private fun applyRuntimeDeviceSettings(settings: ProjectionDeviceSettings) {
        microphoneInputManager.updateGain(settings.micSettings.gainMultiplier)
        audioStreamManager.updateDeviceSettings(settings)
    }

    private fun resolveCurrentDeviceId(): String? {
        return currentSessionDeviceId ?: currentSelectedDeviceId ?: pendingConnectionDeviceId
    }

    private fun resolveCurrentDeviceName(deviceId: String?): String? {
        val normalizedId = deviceId
            ?.let(Cpc200Protocol::normalizeDeviceIdentifier)
            ?.ifBlank { null }
            ?: return null
        return knownDevices[normalizedId]?.name ?: settingsStore.getCachedDeviceName(normalizedId)
    }

    private fun buildDeviceSnapshots(): List<ProjectionDeviceSnapshot> {
        val ordered = linkedMapOf<String, DongleKnownDevice>()
        knownDevices.forEach { (id, device) ->
            ordered[id] = device.copy(id = id)
        }

        listOf(pendingConnectionDeviceId, currentSelectedDeviceId, currentSessionDeviceId, catalogActiveDeviceId)
            .mapNotNull { id ->
                id?.let(Cpc200Protocol::normalizeDeviceIdentifier)?.ifBlank { null }
            }
            .forEach { id ->
                ordered.putIfAbsent(id, DongleKnownDevice(id = id))
            }

        currentPeerBluetoothDeviceId
            ?.let(Cpc200Protocol::normalizeDeviceIdentifier)
            ?.ifBlank { null }
            ?.let { id ->
                ordered.putIfAbsent(id, DongleKnownDevice(id = id))
            }

        return ordered.values
            .map { device ->
                val normalizedId = Cpc200Protocol.normalizeDeviceIdentifier(device.id)
                ProjectionDeviceSnapshot(
                    id = normalizedId,
                    name = device.name ?: settingsStore.getCachedDeviceName(normalizedId) ?: normalizedId,
                    type = device.type,
                    isAvailable = normalizedId in availableDeviceIds,
                    isActive = normalizedId == currentSessionDeviceId,
                    isSelected = normalizedId == currentSelectedDeviceId,
                    isConnecting = normalizedId == pendingConnectionDeviceId || normalizedId == currentPeerBluetoothDeviceId,
                )
            }
            .sortedWith(
                compareByDescending<ProjectionDeviceSnapshot> { it.isConnecting }
                    .thenByDescending { it.isAvailable }
                    .thenByDescending { it.isActive }
                    .thenByDescending { it.isSelected }
                    .thenBy { it.name.lowercase() },
            )
    }

    private fun describeKnownDevice(deviceId: String?): String {
        val normalizedId = deviceId
            ?.let(Cpc200Protocol::normalizeDeviceIdentifier)
            ?.ifBlank { null }
            ?: return "-"
        val knownDevice = knownDevices[normalizedId]
        if (knownDevice == null) {
            val cachedName = settingsStore.getCachedDeviceName(normalizedId) ?: return normalizedId
            return "$normalizedId name=$cachedName"
        }
        return buildString {
            append(normalizedId)
            knownDevice.name?.let { append(" name=").append(it) }
            knownDevice.type?.let { append(" type=").append(it) }
            knownDevice.index?.let { append(" index=").append(it) }
        }
    }

    private fun logPairedDevices(devices: List<DongleKnownDevice>) {
        if (devices.isEmpty()) {
            logStore.info(SOURCE, "BluetoothPairedList is empty")
            return
        }
        val entries = devices.joinToString(" | ") { describeKnownDevice(it.id) }
        logStore.info(SOURCE, "BluetoothPairedList(${devices.size}): $entries")
    }

    private fun refreshState(
        state: ProjectionConnectionState = _state.value.state,
        message: String = _state.value.statusMessage,
        phoneDescription: String? = _state.value.phoneDescription,
        adapterDescription: String? = _state.value.adapterDescription,
        streamDescription: String? = _state.value.streamDescription,
        videoWidth: Int? = _state.value.videoWidth,
        videoHeight: Int? = _state.value.videoHeight,
        lastError: String? = _state.value.lastError,
    ) {
        updateState(
            state = state,
            message = message,
            adapterDescription = adapterDescription,
            phoneDescription = phoneDescription,
            streamDescription = streamDescription,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            lastError = lastError,
        )
    }

    private fun sendExplicitDeviceSelectionIfPossible() {
        val pendingId = pendingConnectionDeviceId ?: return
        if (pendingDeviceSelectionSent) return
        if (currentSession == null) return

        queueOutbound(Cpc200Protocol.selectDevice(pendingId))
        queueOutbound(Cpc200Protocol.command(Cpc200Protocol.Command.START_AUTO_CONNECT))
        pendingDeviceSelectionSent = true
        logStore.info(SOURCE, "AutoConnect_By_BluetoothAddress sent: ${describeKnownDevice(pendingId)}")
    }

    private fun rememberSessionDevice(deviceId: String?, reason: String) {
        val normalizedId = deviceId
            ?.let(Cpc200Protocol::normalizeDeviceIdentifier)
            ?.ifBlank { null }
            ?: return
        if (currentSessionDeviceId == normalizedId) return
        currentSessionDeviceId = normalizedId
        settingsStore.setLastConnectedDeviceId(normalizedId)
        logStore.info(SOURCE, "Session device -> ${describeKnownDevice(normalizedId)} ($reason)")
        applyRuntimeDeviceSettings(loadRuntimeDeviceSettings())
    }

    private fun resolveSessionDeviceCandidateId(): String? {
        return pendingConnectionDeviceId
            ?: currentPeerBluetoothDeviceId
            ?: currentSelectedDeviceId
            ?: catalogActiveDeviceId
            ?: settingsStore.getLastConnectedDeviceId()
    }

    private fun handleBoxSettingsMessage(payload: ByteArray) {
        val snapshot = DongleDeviceCatalogParser.parseBoxSettings(payload)
        if (snapshot == null) {
            logStore.info(SOURCE, "BoxSettings <- ${payload.toString(Charsets.UTF_8).take(180)}")
            return
        }

        rememberKnownDevices(snapshot.devices)
        if (snapshot.devices.isNotEmpty()) {
            refreshAvailableDevices(snapshot.devices)
        }
        logBoxSettingsSnapshot(snapshot)

        if (snapshot.activeDeviceId != null) {
            val activeId = Cpc200Protocol.normalizeDeviceIdentifier(snapshot.activeDeviceId)
            catalogActiveDeviceId = activeId
            settingsStore.setLastConnectedDeviceId(activeId)
            snapshot.activeDeviceName?.let { settingsStore.setCachedDeviceName(activeId, it) }
            if (!isProjectionSessionEstablished() && currentSelectedDeviceId == null && pendingConnectionDeviceId == null) {
                applyRuntimeDeviceSettings(loadRuntimeDeviceSettings())
            }
        } else {
            catalogActiveDeviceId = null
            if (isProjectionSessionEstablished()) {
                logStore.info(
                    SOURCE,
                    "Ignoring empty activeDeviceId in BoxSettings while projection session is established",
                )
            } else {
                currentPhoneType = null
            }
        }
        refreshState()
    }

    private fun logBoxSettingsSnapshot(snapshot: DongleBoxSettingsSnapshot) {
        if (snapshot.devices.isNotEmpty()) {
            val entries = snapshot.devices.joinToString(" | ") { describeKnownDevice(it.id) }
            logStore.info(SOURCE, "BoxSettings.DevList(${snapshot.devices.size}): $entries")
        }

        val activeId = snapshot.activeDeviceId
        val activeName = snapshot.activeDeviceName ?: "-"
        val linkType = snapshot.linkType ?: "-"
        if (activeId != null || activeName != "-" || linkType != "-") {
            logStore.info(
                SOURCE,
                "BoxSettings active link: linkType=$linkType btName=$activeName ${describeKnownDevice(activeId)}",
            )
        }
    }

    private fun logProtocolTextMessage(
        type: Int,
        payload: ByteArray?,
    ) {
        if (payload == null || payload.isEmpty()) {
            logStore.info(SOURCE, "Message 0x${type.toString(16)} marker")
            return
        }
        logStore.info(SOURCE, "Message 0x${type.toString(16)} <- ${payload.toString(Charsets.UTF_8).take(160)}")
    }

    private fun handleMediaDataMessage(payload: ByteArray?) {
        if (payload == null || payload.size < 4) {
            logStore.info(SOURCE, "MediaData payload is empty")
            return
        }

        val mediaData = runCatching {
            Cpc200Protocol.parseMediaData(payload)
        }.getOrElse { error ->
            logStore.error(SOURCE, "Failed to parse MediaData", error)
            return
        }

        when (mediaData) {
            is Cpc200Protocol.MediaDataPayload.Metadata -> {
                val compactPayload = mediaData.rawJson
                    .replace(Regex("\\s+"), " ")
                    .take(500)
                markInboundActivity("MediaData metadata")
                logStore.info(SOURCE, "MediaData metadata <- $compactPayload")
                doubleMediaPublisher.onMediaMetadata(mediaData.json)
            }

            is Cpc200Protocol.MediaDataPayload.AlbumCover -> {
                markInboundActivity("MediaData cover")
                logStore.info(SOURCE, "MediaData album cover <- ${mediaData.bytes.size} bytes")
                doubleMediaPublisher.onAlbumCover(mediaData.bytes)
            }

            is Cpc200Protocol.MediaDataPayload.TextSubtype -> {
                val subtypeLabel = when (mediaData.subtype) {
                    200 -> "NaviJSON"
                    else -> "subtype=${mediaData.subtype}"
                }
                val compactPayload = mediaData.payloadText
                    .replace(Regex("\\s+"), " ")
                    .take(500)
                if (compactPayload.isBlank()) {
                    logStore.info(SOURCE, "DashBoard_DATA $subtypeLabel")
                } else {
                    logStore.info(SOURCE, "DashBoard_DATA $subtypeLabel <- $compactPayload")
                }
            }

            is Cpc200Protocol.MediaDataPayload.Unknown -> {
                logStore.info(
                    SOURCE,
                    "MediaData subtype=${mediaData.subtype} raw=${mediaData.rawBytes.size} bytes",
                )
            }
        }
    }

    private fun handleNaviVideoMessage(
        session: DongleConnectionSession,
        payloadLength: Int,
    ) {
        val payload = session.readPayload(payloadLength, READ_TIMEOUT_MS)
        if (currentSession !== session) return
        if (payload.size < Cpc200Protocol.VIDEO_SUB_HEADER_SIZE) {
            logStore.info(SOURCE, "NaviVideo packet too short: ${payload.size} bytes")
            return
        }

        val meta = Cpc200Protocol.parseVideoMeta(payload, 0)
        markInboundActivity("NaviVideo ${meta.width}x${meta.height}")
        logStore.info(
            SOURCE,
            "NaviVideo packet ${meta.width}x${meta.height}, flags=${meta.flags}, data=${meta.dataLength}",
        )
    }

    private fun startReadLoop(session: DongleConnectionSession) {
        executors.usbRead.execute {
            try {
                while (!shuttingDown && currentSession === session) {
                    val headerBytes = session.readHeader(READ_TIMEOUT_MS)
                    if (headerBytes == null) {
                        if (session.isReplay) {
                            logStore.info(SOURCE, "Replay capture reached EOF")
                            executors.session.execute {
                                closeCurrentSession("replay finished", scheduleReconnect = false)
                            }
                            break
                        }
                        continue
                    }

                    val header = Cpc200Protocol.parseHeader(headerBytes)
                    if (header.length == 0) {
                        if (currentSession !== session) break
                        handleIncomingMessage(session, header.type, null)
                        applyReplayPacing(header.type)
                        continue
                    }

                    when (header.type) {
                        Cpc200Protocol.MessageType.VIDEO -> handleVideoMessage(session, header.length)
                        Cpc200Protocol.MessageType.NAVI_VIDEO -> handleNaviVideoMessage(session, header.length)
                        else -> {
                            val payload = session.readPayload(header.length, READ_TIMEOUT_MS)
                            if (currentSession !== session) break
                            handleIncomingMessage(session, header.type, payload)
                        }
                    }
                    applyReplayPacing(header.type)
                }
            } catch (t: Throwable) {
                if (!shuttingDown && currentSession === session) {
                    logStore.error(SOURCE, "Read loop failed", t)
                    executors.session.execute {
                        if (currentSession === session) {
                            if (sessionMode == SessionMode.USB && started && !shuttingDown) {
                                logStore.info(
                                    SOURCE,
                                    "Restarting USB session immediately after read loop failure",
                                )
                                restartSessionNow("read loop failed")
                            } else {
                                closeCurrentSession("read loop failed", scheduleReconnect = true)
                            }
                        }
                    }
                } else if (!shuttingDown) {
                    logStore.info(SOURCE, "Ignoring stale read loop failure from closed session")
                }
            }
        }
    }

    private fun ensureWriteLoop() {
        if (writeLoopStarted) return
        writeLoopStarted = true
        executors.usbWrite.execute {
            try {
                while (!shuttingDown) {
                    val message = outboundQueue.poll(1, TimeUnit.SECONDS) ?: continue
                    val session = currentSession ?: continue
                    val outboundDescription = describeOutboundMessage(message)
                    try {
                        session.write(message, WRITE_TIMEOUT_MS)
                    } catch (t: Throwable) {
                        if (currentSession === session) {
                            logStore.error(SOURCE, "Write failed while sending: $outboundDescription", t)
                            outboundQueue.clear()
                            executors.session.execute {
                                if (currentSession === session) {
                                    closeCurrentSession("write failed", scheduleReconnect = true)
                                }
                            }
                        } else {
                            logStore.info(SOURCE, "Ignoring stale write failure from closed session")
                        }
                    }
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                writeLoopStarted = false
            }
        }
    }

    private fun handleVideoMessage(
        session: DongleConnectionSession,
        payloadLength: Int,
    ) {
        val packet = ByteArray(payloadLength)
        val actual = session.readPayloadInto(packet, 0, payloadLength, READ_TIMEOUT_MS)
        require(actual == payloadLength) { "Video payload read mismatch: $actual/$payloadLength" }
        if (currentSession !== session) return

        val safeMeta = Cpc200Protocol.parseVideoMeta(packet, 0)
        if (surfaceAttached) {
            renderer.processDataDirect(
                payloadLength,
                Cpc200Protocol.VIDEO_SUB_HEADER_SIZE,
            ) { target, offset ->
                System.arraycopy(packet, 0, target, offset, payloadLength)
            }
        } else {
            if (!backgroundVideoDropLogged) {
                backgroundVideoDropLogged = true
                logStore.info(
                    SOURCE,
                    "Dropping video packets while projection surface is detached (${safeMeta.width}x${safeMeta.height})",
                )
            }
        }
        markInboundActivity("Video ${safeMeta.width}x${safeMeta.height}")
        renderer.updateVideoFormat(safeMeta.width, safeMeta.height)
        if (pendingConnectionDeviceId != null) {
            pendingConnectionDeviceId = null
            pendingDeviceSelectionSent = false
        }
        noteConnectAttemptFirstVideo(safeMeta.width, safeMeta.height)
        if (openAckTimeoutFuture != null) {
            cancelOpenAckTimeout()
            consecutiveOpenAckTimeouts = 0
        }
        applyV2(
            ConnectionEventV2.FirstVideoFrame(safeMeta.width, safeMeta.height),
            streamDescription = "${safeMeta.width}x${safeMeta.height}, flags=${safeMeta.flags}",
        )
    }

    private fun handleIncomingMessage(
        session: DongleConnectionSession,
        type: Int,
        payload: ByteArray?,
    ) {
        if (currentSession !== session) return
        markInboundActivity(Cpc200Protocol.describeMessageType(type))
        when (type) {
            Cpc200Protocol.MessageType.HEARTBEAT -> {
                lastHeartbeatEchoAtMs = lastInboundActivityAtMs
            }

            Cpc200Protocol.MessageType.OPEN -> {
                cancelOpenAckTimeout()
                consecutiveOpenAckTimeouts = 0
                val openAckAtMs = SystemClock.elapsedRealtime()
                lastOpenAckAtMs = openAckAtMs
                if (payload != null) {
                    val openedInfo = Cpc200Protocol.parseOpen(payload)
                    val sinceUsbRequestMs = if (lastUsbConnectRequestedAtMs > 0L) {
                        openAckAtMs - lastUsbConnectRequestedAtMs
                    } else {
                        -1L
                    }
                    val sinceUsbOpenMs = if (lastUsbSessionOpenedAtMs > 0L) {
                        openAckAtMs - lastUsbSessionOpenedAtMs
                    } else {
                        -1L
                    }
                    logStore.info(
                        SOURCE,
                        "Open acknowledged by adapter: ${openedInfo.width}x${openedInfo.height}@${openedInfo.fps} " +
                            "(since connect request=${formatElapsedMs(sinceUsbRequestMs)}, since usb open=${formatElapsedMs(sinceUsbOpenMs)})",
                    )
                    renderer.updateVideoFormat(openedInfo.width, openedInfo.height, openedInfo.fps)
                    if (sessionMode == SessionMode.REPLAY && openedInfo.fps > 0) {
                        replayFrameDelayMs = (1000L / openedInfo.fps).coerceAtLeast(1L)
                    }
                    updateState(
                        state = _state.value.state,
                        message = _state.value.statusMessage,
                        videoWidth = openedInfo.width,
                        videoHeight = openedInfo.height,
                    )
                    applyV2(
                        ConnectionEventV2.OpenAcknowledged(
                            width = openedInfo.width,
                            height = openedInfo.height,
                            fps = openedInfo.fps,
                        ),
                    )
                } else {
                    logStore.info(SOURCE, "Open acknowledged by adapter")
                    applyV2(
                        ConnectionEventV2.OpenAcknowledged(
                            width = null,
                            height = null,
                            fps = null,
                        ),
                    )
                }
            }

            Cpc200Protocol.MessageType.PLUGGED -> {
                val safePayload = payload ?: return
                cancelOpenAckTimeout()
                consecutiveOpenAckTimeouts = 0
                val pluggedInfo = Cpc200Protocol.parsePlugged(safePayload)
                currentPhoneType = pluggedInfo.phoneType
                rememberSessionDevice(resolveSessionDeviceCandidateId(), "plugged")
                sessionConfig = buildSessionConfig()
                noteConnectAttemptPlugged(pluggedInfo)
                logStore.info(
                    SOURCE,
                    "Phone plugged: ${Cpc200Protocol.describePhoneType(pluggedInfo.phoneType)}, wifi=${pluggedInfo.wifiState}",
                )
                applyV2(
                    ConnectionEventV2.Plugged(
                        phoneType = pluggedInfo.phoneType,
                        wifiState = pluggedInfo.wifiState,
                    ),
                )
                reapplySessionRouting("plugged")
                syncVideoFocus("plugged")
            }

            Cpc200Protocol.MessageType.PHASE -> {
                val safePayload = payload ?: return
                val phase = Cpc200Protocol.parsePhase(safePayload)
                if (phase == 8) {
                    cancelOpenAckTimeout()
                    consecutiveOpenAckTimeouts = 0
                }
                if (phase == 0 || phase == 13) {
                    currentPhoneType = null
                    currentSessionDeviceId = null
                    currentPeerBluetoothDeviceId = null
                }
                noteConnectAttemptPhase(phase)
                logStore.info(SOURCE, "Phase message: $phase")
                applyV2(ConnectionEventV2.RawPhaseReceived(phase))
                if (phase == 8) {
                    syncVideoFocus("phase 8")
                }
            }

            Cpc200Protocol.MessageType.UNPLUGGED -> {
                currentPhoneType = null
                currentSessionDeviceId = null
                currentPeerBluetoothDeviceId = null
                noteConnectAttemptUnplugged()
                logStore.info(SOURCE, "Phone unplugged")
                applyV2(ConnectionEventV2.Unplugged)
            }

            Cpc200Protocol.MessageType.COMMAND -> {
                val safePayload = payload ?: return
                val envelope = Cpc200Protocol.parseCommandEnvelope(safePayload)
                val command = envelope.commandId
                if (command == Cpc200Protocol.Command.BT_DISCONNECTED) {
                    currentPeerBluetoothDeviceId = null
                }
                logStore.info(SOURCE, "Adapter command: ${Cpc200Protocol.describeCommand(command)}")
                when (command) {
                    Cpc200Protocol.Command.START_RECORD_AUDIO -> {
                        applyRuntimeDeviceSettings(loadRuntimeDeviceSettings())
                        microphoneInputManager.start()
                    }

                    Cpc200Protocol.Command.STOP_RECORD_AUDIO -> {
                        microphoneInputManager.stop("adapter stopRecordAudio")
                    }
                }
                if (command == Cpc200Protocol.Command.DISABLE_BLUETOOTH && envelope.extraPayload.isNotEmpty()) {
                    Cpc200Protocol.parsePhoneBluetoothMac(safePayload)?.let { phoneBtMac ->
                        logStore.info(SOURCE, "Phone Bluetooth MAC: $phoneBtMac")
                    }
                }
                if (command == Cpc200Protocol.Command.DEVICE_FOUND) {
                    noteConnectAttemptDeviceFound()
                }
                if (command == Cpc200Protocol.Command.WIFI_CONNECTED) {
                    noteConnectAttemptWifiConnected()
                }
                when (command) {
                    Cpc200Protocol.Command.SCANNING_DEVICE -> {
                        applyV2(ConnectionEventV2.DiscoveryScanning("adapter command"))
                    }

                    Cpc200Protocol.Command.DEVICE_FOUND -> {
                        applyV2(ConnectionEventV2.DeviceFound(resolveSessionDeviceCandidateId()))
                    }

                    Cpc200Protocol.Command.BT_CONNECTED -> {
                        applyV2(ConnectionEventV2.BluetoothConnected(currentPeerBluetoothDeviceId))
                    }

                    Cpc200Protocol.Command.BT_DISCONNECTED -> {
                        applyV2(ConnectionEventV2.BluetoothDisconnected)
                    }

                    Cpc200Protocol.Command.BT_PAIR_START -> {
                        applyV2(ConnectionEventV2.BluetoothPairingStarted)
                    }

                    Cpc200Protocol.Command.CONNECT_DEVICE_FAILED -> {
                        applyV2(ConnectionEventV2.ConnectDeviceFailed)
                    }

                    Cpc200Protocol.Command.DEVICE_NOT_FOUND -> {
                        applyV2(ConnectionEventV2.DeviceNotFound)
                    }

                    Cpc200Protocol.Command.REQUEST_HOST_UI -> {
                        _events.tryEmit(ProjectionUiEvent.OpenSettings)
                        logStore.info(SOURCE, "Adapter requested host UI")
                    }

                    Cpc200Protocol.Command.HIDE -> {
                        logStore.info(SOURCE, "Phone requested projection hide")
                    }
                }
            }

            Cpc200Protocol.MessageType.BOX_SETTINGS -> {
                payload?.let(::handleBoxSettingsMessage)
            }

            Cpc200Protocol.MessageType.AUTO_CONNECT_BY_BLUETOOTH_ADDRESS -> {
                val safePayload = payload ?: return
                val deviceId = Cpc200Protocol.parseDeviceIdentifier(safePayload)
                currentPeerBluetoothDeviceId = deviceId
                logStore.info(SOURCE, "AutoConnect_By_BluetoothAddress <- ${describeKnownDevice(deviceId)}")
                refreshState(phoneDescription = describeKnownDevice(deviceId))
            }

            Cpc200Protocol.MessageType.BLUETOOTH_CONNECT_START -> {
                val safePayload = payload ?: return
                currentPeerBluetoothDeviceId = Cpc200Protocol.parseDeviceIdentifier(safePayload)
                noteConnectAttemptBluetoothConnectStart(currentPeerBluetoothDeviceId)
                logStore.info(SOURCE, "BluetoothConnectStart: ${describeKnownDevice(currentPeerBluetoothDeviceId)}")
                applyV2(ConnectionEventV2.BluetoothConnectStarting(currentPeerBluetoothDeviceId))
                refreshState(phoneDescription = describeKnownDevice(currentPeerBluetoothDeviceId))
            }

            Cpc200Protocol.MessageType.BLUETOOTH_CONNECTED -> {
                val safePayload = payload ?: return
                currentPeerBluetoothDeviceId = Cpc200Protocol.parseDeviceIdentifier(safePayload)
                noteConnectAttemptBluetoothConnected(currentPeerBluetoothDeviceId)
                logStore.info(SOURCE, "BluetoothConnected: ${describeKnownDevice(currentPeerBluetoothDeviceId)}")
                applyV2(ConnectionEventV2.BluetoothConnected(currentPeerBluetoothDeviceId))
                refreshState(phoneDescription = describeKnownDevice(currentPeerBluetoothDeviceId))
            }

            Cpc200Protocol.MessageType.BLUETOOTH_DISCONNECT -> {
                logStore.info(SOURCE, "BluetoothDisconnect")
                currentPeerBluetoothDeviceId = null
                applyV2(ConnectionEventV2.BluetoothDisconnected)
                refreshState()
            }

            Cpc200Protocol.MessageType.BLUETOOTH_LISTEN -> {
                payload
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(Cpc200Protocol::parseDeviceIdentifier)
                    ?.let { deviceId ->
                        currentPeerBluetoothDeviceId = deviceId
                        logStore.info(SOURCE, "BluetoothListen: ${describeKnownDevice(deviceId)}")
                        refreshState(phoneDescription = describeKnownDevice(deviceId))
                    }
                    ?: logStore.info(SOURCE, "BluetoothListen")
            }

            Cpc200Protocol.MessageType.SOFTWARE_VERSION -> {
                payload?.toString(Charsets.US_ASCII)?.let { version ->
                    logStore.info(SOURCE, "Dongle software version: $version")
                }
            }

            Cpc200Protocol.MessageType.BT_ADDRESS,
            Cpc200Protocol.MessageType.BT_PIN,
            Cpc200Protocol.MessageType.HICAR,
            Cpc200Protocol.MessageType.MANUFACTURER_INFO,
            -> {
                logProtocolTextMessage(type, payload)
            }

            Cpc200Protocol.MessageType.MEDIA_DATA -> {
                handleMediaDataMessage(payload)
            }

            Cpc200Protocol.MessageType.BT_DEVICE_NAME -> {
                payload?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotBlank() }?.let { value ->
                    logStore.info(SOURCE, "Adapter Bluetooth name: $value")
                }
            }

            Cpc200Protocol.MessageType.WIFI_DEVICE_NAME -> {
                payload?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotBlank() }?.let { value ->
                    logStore.info(SOURCE, "Adapter Wi-Fi name: $value")
                }
            }

            Cpc200Protocol.MessageType.BT_PAIRED_LIST -> {
                val safePayload = payload ?: return
                val devices = DongleDeviceCatalogParser.parsePairedList(safePayload)
                rememberKnownDevices(devices)
                logPairedDevices(devices)
                applyRuntimeDeviceSettings(loadRuntimeDeviceSettings())
                applyV2PolicyUpdated()
                refreshState()
            }

            Cpc200Protocol.MessageType.AUDIO -> {
                val safePayload = payload ?: return
                val audioPacket = Cpc200Protocol.parseAudioPacket(safePayload)
                audioStreamManager.handleAudioPacket(audioPacket)
            }
        }
    }

    private fun closeCurrentSession(
        reason: String,
        scheduleReconnect: Boolean,
    ) {
        cancelReconnect()
        cancelSleepDisconnect()
        cancelOpenAckTimeout()
        cancelOpenAckRecovery()
        cancelPostPluggedReconnect()
        if (reason != OPEN_ACK_TIMEOUT_REASON) {
            consecutiveOpenAckTimeouts = 0
        }
        if (activeConnectAttempt != null) {
            completeConnectAttempt("aborted: session closed ($reason)")
        }
        applyV2TransportClosed(reason)
        stopHeartbeatLoop()
        stopFrameRequestLoop()

        val session = currentSession
        currentSession = null
        currentDevice = null
        currentConnectionLabel = null

        resetProjectionRuntime("session closed: $reason")

        try {
            session?.close()
        } catch (_: Throwable) {
        }

        updateState(
            state = if (scheduleReconnect) ProjectionConnectionState.ERROR else ProjectionConnectionState.IDLE,
            protocolPhase = ProjectionProtocolPhase.NONE,
            message = if (scheduleReconnect) {
                "Connection lost: $reason. Reconnect scheduled."
            } else {
                "Session closed: $reason"
            },
            adapterDescription = null,
            phoneDescription = null,
            streamDescription = null,
            videoWidth = null,
            videoHeight = null,
            surfaceAttached = surfaceAttached,
            lastError = if (scheduleReconnect) reason else null,
        )

        if (scheduleReconnect && started && !shuttingDown) {
            scheduleReconnect(reason, RECONNECT_DELAY_MS)
        }
    }

    private fun syncVideoFocus(reason: String) {
        val session = currentSession ?: return
        if (!canControlVideoFocus()) return

        val commandId = if (videoStreamEnabled) {
            Cpc200Protocol.Command.RELEASE_VIDEO_FOCUS
        } else {
            Cpc200Protocol.Command.REQUEST_VIDEO_FOCUS
        }

        queueOutbound(Cpc200Protocol.command(commandId))
        logStore.info(
            SOURCE,
            "Video focus ${if (videoStreamEnabled) "requested" else "released"}: $reason (${session.description})",
        )
    }

    private fun canControlVideoFocus(): Boolean {
        val snapshot = connectionEngineV2.snapshot()
        return currentPhoneType != null || snapshot.isSessionEstablished()
    }

    private fun isProjectionSessionEstablished(): Boolean {
        return currentSession != null && (
            currentPhoneType != null ||
                connectionEngineV2.snapshot().isSessionEstablished()
            )
    }

    private fun restartSessionNow(reason: String) {
        val restartMode = sessionMode
        val replayPath = replayCapturePath
        closeCurrentSession(reason, scheduleReconnect = false)
        if (!started || shuttingDown) return
        sessionConfig = buildSessionConfig()
        logStore.info(
            SOURCE,
            "Reconnect session config rebuilt: screen=${sessionConfig.width}x${sessionConfig.height} safeArea=${sessionConfig.carplaySafeAreaBottomDp}dp dpi=${sessionConfig.dpi} climatePanel=${sessionConfig.climatePanelEnabled}",
        )
        when (restartMode) {
            SessionMode.USB -> connectOrRequestPermission(reason)
            SessionMode.REPLAY -> replayPath?.let(::openReplaySession)
        }
    }

    private fun queueOutbound(message: ByteArray) {
        if (!started || shuttingDown) return
        logOutboundMessage(message)
        outboundQueue.offer(message)
    }

    private fun logOutboundMessage(message: ByteArray) {
        try {
            val description = describeOutboundMessage(message) ?: return
            logStore.info(SOURCE, "SEND $description")
        } catch (_: Throwable) {
        }
    }

    private fun describeOutboundMessage(message: ByteArray): String? {
        if (message.size < Cpc200Protocol.HEADER_SIZE) return null
        val header = Cpc200Protocol.parseHeader(message.copyOfRange(0, Cpc200Protocol.HEADER_SIZE))
        if (header.type == Cpc200Protocol.MessageType.MULTI_TOUCH || header.type == Cpc200Protocol.MessageType.AUDIO) {
            return null
        }
        val payload = message.copyOfRange(Cpc200Protocol.HEADER_SIZE, message.size)
        return when (header.type) {
            Cpc200Protocol.MessageType.HEARTBEAT -> Cpc200Protocol.describeMessageType(header.type)
            Cpc200Protocol.MessageType.OPEN -> {
            val openedInfo = Cpc200Protocol.parseOpen(payload)
            "Open ${openedInfo.width}x${openedInfo.height}@${openedInfo.fps}"
        }

            Cpc200Protocol.MessageType.COMMAND -> {
            val command = Cpc200Protocol.parseCommand(payload)
            "Command ${Cpc200Protocol.describeCommand(command)}"
        }

            Cpc200Protocol.MessageType.AUTO_CONNECT_BY_BLUETOOTH_ADDRESS -> {
            "AutoConnectByBluetoothAddress ${Cpc200Protocol.parseDeviceIdentifier(payload)}"
        }

            Cpc200Protocol.MessageType.SEND_FILE -> {
            "SendFile ${parseSendFilePath(payload)}"
        }

            Cpc200Protocol.MessageType.BOX_SETTINGS -> {
            "BoxSettings ${payload.toString(Charsets.UTF_8).take(220)}"
        }

            else -> Cpc200Protocol.describeMessageType(header.type)
        }
    }

    private fun parseSendFilePath(payload: ByteArray): String {
        if (payload.size < 8) return "?"
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val nameLength = buffer.int
        if (nameLength <= 0 || nameLength > payload.size - 4) return "?"
        val nameBytes = ByteArray(nameLength)
        buffer.get(nameBytes)
        return nameBytes.toString(Charsets.US_ASCII).trimEnd('\u0000')
    }

    private fun scheduleReconnect(
        reason: String,
        delayMs: Long,
    ) {
        if (sessionMode == SessionMode.USB && !isVehicleGateOpen()) {
            updateWaitingForVehicleState("reconnect blocked: $reason")
            return
        }
        cancelReconnect()
        reconnectFuture = executors.scheduler.schedule(
            {
                logStore.info(SOURCE, "Reconnect triggered after $delayMs ms: $reason")
                executors.session.execute {
                    when (sessionMode) {
                        SessionMode.USB -> connectOrRequestPermission(reason)
                        SessionMode.REPLAY -> replayCapturePath?.let(::openReplaySession)
                    }
                }
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelReconnect() {
        reconnectFuture?.cancel(false)
        reconnectFuture = null
    }

    private fun cancelSleepDisconnect() {
        sleepDisconnectFuture?.cancel(false)
        sleepDisconnectFuture = null
    }

    private fun cancelDebugVehicleWake() {
        debugVehicleWakeFuture?.cancel(false)
        debugVehicleWakeFuture = null
    }

    private fun armOpenAckTimeout(session: DongleConnectionSession) {
        cancelOpenAckTimeout()
        openAckTimeoutFuture = executors.scheduler.schedule(
            {
                executors.session.execute {
                    if (
                        shuttingDown ||
                        sessionMode != SessionMode.USB ||
                        currentSession !== session
                    ) {
                        return@execute
                    }
                    val timeoutCount = consecutiveOpenAckTimeouts + 1
                    consecutiveOpenAckTimeouts = timeoutCount
                    if (timeoutCount > OPEN_ACK_MAX_CONSECUTIVE_TIMEOUTS) {
                        logStore.info(
                            SOURCE,
                            "Open ACK timeout after ${OPEN_ACK_TIMEOUT_MS} ms; " +
                                "max retries reached ($timeoutCount/${OPEN_ACK_MAX_CONSECUTIVE_TIMEOUTS}), " +
                                "adapter unresponsive — stopping auto-retry (${session.description})",
                        )
                        consecutiveOpenAckTimeouts = 0
                        closeCurrentSession(OPEN_ACK_TIMEOUT_REASON, scheduleReconnect = false)
                        applyV2(ConnectionEventV2.TransportFailed("adapter unresponsive after $timeoutCount timeouts"))
                        updateState(
                            state = ProjectionConnectionState.ERROR,
                            message = "Adapter not responding. Reconnect USB cable or restart.",
                            lastError = "Open ACK timeout after $timeoutCount attempts",
                        )
                        return@execute
                    }
                    val recoveryDelayMs = openAckRecoveryDelayMs(timeoutCount)
                    logStore.info(
                        SOURCE,
                        "Open ACK timeout after ${OPEN_ACK_TIMEOUT_MS} ms; scheduling USB reopen in $recoveryDelayMs ms " +
                            "(timeout #$timeoutCount, ${session.description})",
                    )
                    closeCurrentSession(OPEN_ACK_TIMEOUT_REASON, scheduleReconnect = false)
                    scheduleOpenAckRecovery(recoveryDelayMs)
                }
            },
            OPEN_ACK_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelOpenAckTimeout() {
        openAckTimeoutFuture?.cancel(false)
        openAckTimeoutFuture = null
    }

    private fun scheduleOpenAckRecovery(delayMs: Long) {
        cancelOpenAckRecovery()
        openAckRecoveryFuture = executors.scheduler.schedule(
            {
                executors.session.execute {
                    openAckRecoveryFuture = null
                    if (
                        !started ||
                        shuttingDown ||
                        sessionMode != SessionMode.USB ||
                        currentSession != null
                    ) {
                        return@execute
                    }
                    if (!isVehicleGateOpen()) {
                        updateWaitingForVehicleState(OPEN_ACK_TIMEOUT_REASON)
                        return@execute
                    }
                    sessionConfig = buildSessionConfig()
                    logStore.info(
                        SOURCE,
                        "Reopening USB after Open ACK timeout delay (${delayMs} ms, timeout streak=$consecutiveOpenAckTimeouts)",
                    )
                    connectOrRequestPermission(OPEN_ACK_TIMEOUT_REASON)
                }
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelOpenAckRecovery() {
        openAckRecoveryFuture?.cancel(false)
        openAckRecoveryFuture = null
    }

    private fun schedulePostPluggedReconnect(attemptId: Int) {
        postPluggedReconnectFuture?.cancel(false)
        postPluggedReconnectFuture = executors.scheduler.schedule(
            {
                executors.session.execute {
                    postPluggedReconnectFuture = null
                    val attempt = activeConnectAttempt ?: return@execute
                    if (attempt.id != attemptId) return@execute
                    if (attempt.phase8AtMs != null || attempt.firstVideoAtMs != null) return@execute
                    logStore.info(
                        SOURCE,
                        "Post-plugged video stall: no Phase 8 in ${CONNECT_ATTEMPT_POST_PLUGGED_STALL_RECONNECT_MS}ms; force reconnect",
                    )
                    restartSessionNow("post-plugged video stall")
                }
            },
            CONNECT_ATTEMPT_POST_PLUGGED_STALL_RECONNECT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelPostPluggedReconnect() {
        postPluggedReconnectFuture?.cancel(false)
        postPluggedReconnectFuture = null
    }

    private fun openAckRecoveryDelayMs(timeoutCount: Int): Long {
        return if (timeoutCount <= 1) {
            OPEN_ACK_RECOVERY_DELAY_INITIAL_MS
        } else {
            OPEN_ACK_RECOVERY_DELAY_REPEATED_MS
        }
    }

    private fun armManualVehicleGateBypass(reason: String) {
        manualVehicleGateBypassUntilMs = SystemClock.elapsedRealtime() + MANUAL_VEHICLE_GATE_BYPASS_WINDOW_MS
        logStore.info(
            SOURCE,
            "Manual vehicle-gate bypass armed for ${MANUAL_VEHICLE_GATE_BYPASS_WINDOW_MS}ms: $reason",
        )
    }

    private fun clearManualVehicleGateBypass(reason: String) {
        if (manualVehicleGateBypassUntilMs <= 0L) return
        manualVehicleGateBypassUntilMs = 0L
        logStore.info(SOURCE, "Manual vehicle-gate bypass cleared: $reason")
    }

    private fun isVehicleGateOpen(): Boolean {
        return vehicleActive || isManualVehicleGateBypassActive()
    }

    private fun isManualVehicleGateBypassActive(): Boolean {
        val untilMs = manualVehicleGateBypassUntilMs
        if (untilMs <= 0L) return false

        val now = SystemClock.elapsedRealtime()
        if (now <= untilMs) {
            return true
        }

        manualVehicleGateBypassUntilMs = 0L
        logStore.info(SOURCE, "Manual vehicle-gate bypass expired")
        return false
    }

    private fun updateState(
        state: ProjectionConnectionState,
        protocolPhase: ProjectionProtocolPhase = _state.value.protocolPhase,
        message: String,
        adapterDescription: String? = _state.value.adapterDescription,
        phoneDescription: String? = _state.value.phoneDescription,
        streamDescription: String? = _state.value.streamDescription,
        devices: List<ProjectionDeviceSnapshot> = buildDeviceSnapshots(),
        videoWidth: Int? = _state.value.videoWidth,
        videoHeight: Int? = _state.value.videoHeight,
        surfaceAttached: Boolean = this.surfaceAttached,
        lastError: String? = _state.value.lastError,
    ) {
        _state.value = ProjectionSessionSnapshot(
            state = state,
            protocolPhase = protocolPhase,
            statusMessage = message,
            adapterDescription = adapterDescription,
            phoneDescription = phoneDescription,
            currentDeviceId = resolveCurrentDeviceId(),
            currentDeviceName = resolveCurrentDeviceName(resolveCurrentDeviceId()),
            streamDescription = streamDescription,
            devices = devices,
            appliedAudioRoute = if (sessionConfig.useBluetoothAudio) {
                ProjectionAudioRoute.CAR_BLUETOOTH
            } else {
                ProjectionAudioRoute.ADAPTER
            },
            appliedMicRoute = if (sessionConfig.useAdapterMic) {
                ProjectionMicRoute.ADAPTER
            } else {
                ProjectionMicRoute.PHONE
            },
            appliedClimatePanelEnabled = sessionConfig.climatePanelEnabled,
            appliedClimateBarHeightDp = sessionConfig.climateBarHeightDp,
            appliedVideoClipTopPx = sessionConfig.videoClipTopPx,
            appliedVideoClipBottomPx = sessionConfig.videoClipBottomPx,
            appliedCarPlaySafeAreaBottomDp = sessionConfig.carplaySafeAreaBottomDp,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            surfaceAttached = surfaceAttached,
            lastError = lastError,
        )
    }

    private fun applyReplayPacing(type: Int) {
        if (sessionMode == SessionMode.REPLAY && type == Cpc200Protocol.MessageType.VIDEO) {
            Thread.sleep(replayFrameDelayMs)
        }
    }
}
