package com.alexander.carplay.data.session

import android.content.Context
import android.hardware.usb.UsbDevice
import android.content.ComponentCallbacks2
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
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
import com.alexander.carplay.presentation.ui.ProjectionFrameSnapshotStore
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

class DongleSessionManager(
    context: Context,
    private val logStore: DiagnosticLogStore,
    private val settingsStore: ProjectionSettingsPort,
) {
    private enum class SessionMode {
        USB,
        REPLAY,
    }

    companion object {
        private const val SOURCE = "Session"
        private const val READ_TIMEOUT_MS = 1_000
        private const val WRITE_TIMEOUT_MS = 1_000
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val MANUAL_VEHICLE_GATE_BYPASS_WINDOW_MS = 120_000L
        private const val USB_OPEN_STABILIZATION_DELAY_MS = 3_000L
        private const val USB_POST_OPEN_STABILIZATION_DELAY_MS = 250L
        private const val HEARTBEAT_INTERVAL_SECONDS = 2L
        private const val FRAME_REQUEST_INTERVAL_MS = 5_000L
        private const val DEFAULT_REPLAY_FRAME_DELAY_MS = 16L
        private const val INBOUND_ACTIVITY_LOG_GAP_MS = 10_000L
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
    private val flowController = DongleFlowController(
        logStore = logStore,
        delegate = object : DongleFlowController.Delegate {
            override fun queueMessage(message: ByteArray) {
                queueOutbound(message)
            }

            override fun startReadLoop() {
                currentSession?.let(::startReadLoop)
            }

            override fun sendCommand(commandId: Int) {
                queueOutbound(Cpc200Protocol.command(commandId))
            }

            override fun startHeartbeat() {
                startHeartbeatLoop()
            }

            override fun stopHeartbeat() {
                stopHeartbeatLoop()
            }

            override fun startFrameRequests() {
                startFrameRequestLoop()
            }

            override fun stopFrameRequests() {
                stopFrameRequestLoop()
            }

            override fun requestAutoConnect(reason: String) {
                this@DongleSessionManager.requestAutoConnect(reason)
            }

            override fun clearAutoConnectPending() {
                this@DongleSessionManager.clearAutoConnectPending()
            }

            override fun startBleAdvertising(reason: String) {
                this@DongleSessionManager.startBleAdvertising(reason)
            }

            override fun stopBleAdvertising() {
                this@DongleSessionManager.stopBleAdvertising()
            }

            override fun hasKnownDevices(): Boolean = knownDevices.isNotEmpty()

            override fun shouldAutoConnectKnownDevices(): Boolean =
                this@DongleSessionManager.shouldAutoConnectKnownDevices()

            override fun requiresManualDeviceSelection(): Boolean =
                this@DongleSessionManager.requiresManualDeviceSelection()

            override fun prepareForDongleReinit(reason: String) {
                resetProjectionRuntime(reason)
            }

            override fun requestReconnect(reason: String) {
                restartSessionNow(reason)
            }

            override fun requestHostUi() {
                _events.tryEmit(ProjectionUiEvent.OpenSettings)
            }

            override fun updateState(
                state: ProjectionConnectionState,
                protocolPhase: ProjectionProtocolPhase,
                message: String,
                phoneDescription: String?,
            ) {
                this@DongleSessionManager.updateState(
                    state = state,
                    protocolPhase = protocolPhase,
                    message = message,
                    phoneDescription = phoneDescription,
                    surfaceAttached = surfaceAttached,
                )
            }
        },
    )

    private var started = false
    private var shuttingDown = false
    private var sessionMode = SessionMode.USB
    private var surfaceAttached = false
    private var sessionConfig = ProjectionSessionConfig.fromContext(
        context = appContext,
        adapterName = settingsStore.getAdapterName(),
        climatePanelEnabled = settingsStore.isClimatePanelEnabled(),
        dpi = settingsStore.getAdapterDpi(),
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
    private var lastInboundActivityAtMs = 0L
    private var lastHeartbeatEchoAtMs = 0L
    private var writeLoopStarted = false
    private var videoStreamEnabled = false
    private var backgroundVideoDropLogged = false
    private var automotivePowerSnapshot = AutomotivePowerSnapshot()
    private var vehicleActive = true
    private var manualVehicleGateBypassUntilMs = 0L

    val state: StateFlow<ProjectionSessionSnapshot> = _state.asStateFlow()
    val events: SharedFlow<ProjectionUiEvent> = _events.asSharedFlow()

    init {
        automotivePowerMonitor.start()
        automotivePowerSnapshot = automotivePowerMonitor.currentSnapshot()
        vehicleActive = AutomotivePowerPolicy.isVehicleActive(automotivePowerSnapshot)
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
        executors.session.execute {
            if (!started) return@execute
            armManualVehicleGateBypass(reason)
            restartSessionNow(reason)
        }
    }

    fun refreshRuntimeSettings() {
        executors.session.execute {
            applyRuntimeDeviceSettings()
            refreshState()
        }
    }

    fun previewRuntimeSettings(settings: ProjectionDeviceSettings) {
        executors.session.execute {
            microphoneInputManager.updateGain(settings.micSettings.gainMultiplier)
            audioStreamManager.updateDeviceSettings(settings)
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
            flowController.onSurfaceAttached()
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
            flowController.onSurfaceDetached()
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
        val gateWasOpen = isVehicleGateOpen()
        val wasVehicleActive = vehicleActive
        automotivePowerSnapshot = snapshot
        vehicleActive = AutomotivePowerPolicy.isVehicleActive(snapshot)
        if (wasVehicleActive && !vehicleActive) {
            clearManualVehicleGateBypass("vehicle became inactive")
        }
        val gateIsOpen = isVehicleGateOpen()

        if (sessionMode != SessionMode.USB || shuttingDown) {
            return
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

    private fun updateWaitingForVehicleState(reason: String) {
        cancelReconnect()
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
        if (sessionMode != SessionMode.USB || !started || shuttingDown) return
        if (!isVehicleGateOpen()) {
            updateWaitingForVehicleState(reason)
            return
        }
        if (currentSession != null) return

        val device = usbTransport.findKnownDevice()
        if (device == null) {
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
            resetProjectionRuntime("fresh usb session")

            ensureWriteLoop()
            flowController.onSessionReady(sessionConfig, includeBrandingAssets)
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
            val session = CaptureReplaySession(capturePath, logStore)
            currentSession = session
            currentDevice = null
            currentConnectionLabel = session.description
            replayFrameDelayMs = DEFAULT_REPLAY_FRAME_DELAY_MS
            resetProjectionRuntime("fresh replay session")

            ensureWriteLoop()
            flowController.onSessionReady(sessionConfig, includeBrandingAssets = true)
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
        val selectedId = currentSelectedDeviceId
            ?.let(Cpc200Protocol::normalizeDeviceIdentifier)
            ?.ifBlank { null }
        if (selectedId != null) {
            queueOutbound(Cpc200Protocol.selectDevice(selectedId))
        }
        queueOutbound(Cpc200Protocol.command(Cpc200Protocol.Command.START_AUTO_CONNECT))
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
        return if (availableDeviceIds.isNotEmpty()) {
            availableDeviceIds.size
        } else {
            knownDevices.size
        }
    }

    private fun requiresManualDeviceSelection(): Boolean {
        if (!settingsStore.isAutoConnectEnabled()) return knownDevices.isNotEmpty()
        if (pendingConnectionDeviceId != null) return false
        return effectiveKnownDeviceCount() > 1
    }

    private fun shouldAutoConnectKnownDevices(): Boolean {
        if (!settingsStore.isAutoConnectEnabled()) return false
        if (knownDevices.isEmpty()) return false
        if (!AutomotivePowerPolicy.isReadyForAutoConnect(automotivePowerSnapshot)) return false
        if (pendingConnectionDeviceId != null) return true
        return !requiresManualDeviceSelection()
    }

    private fun buildSessionConfig(): ProjectionSessionConfig {
        val baseConfig = ProjectionSessionConfig.fromContext(
            context = appContext,
            adapterName = settingsStore.getAdapterName(),
            climatePanelEnabled = settingsStore.isClimatePanelEnabled(),
            dpi = settingsStore.getAdapterDpi(),
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
                "screen=${resolvedConfig.width}x${resolvedConfig.height} dpi=${resolvedConfig.dpi}",
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

    private fun applyRuntimeDeviceSettings() {
        val settings = settingsStore.getSettings(resolveCurrentDeviceId())
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
        applyRuntimeDeviceSettings()
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
                applyRuntimeDeviceSettings()
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
                            closeCurrentSession("read loop failed", scheduleReconnect = true)
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
        flowController.onVideoFrameReceived(safeMeta.width, safeMeta.height)

        updateState(
            state = ProjectionConnectionState.STREAMING,
            message = if (surfaceAttached) {
                "Streaming H.264 ${safeMeta.width}x${safeMeta.height}"
            } else {
                "CarPlay streaming active while activity is in background"
            },
            streamDescription = "${safeMeta.width}x${safeMeta.height}, flags=${safeMeta.flags}",
            videoWidth = safeMeta.width,
            videoHeight = safeMeta.height,
            surfaceAttached = surfaceAttached,
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
                if (payload != null) {
                    val openedInfo = Cpc200Protocol.parseOpen(payload)
                    logStore.info(
                        SOURCE,
                        "Open acknowledged by adapter: ${openedInfo.width}x${openedInfo.height}@${openedInfo.fps}",
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
                } else {
                    logStore.info(SOURCE, "Open acknowledged by adapter")
                }
                flowController.onDongleOpened()
            }

            Cpc200Protocol.MessageType.PLUGGED -> {
                val safePayload = payload ?: return
                val pluggedInfo = Cpc200Protocol.parsePlugged(safePayload)
                currentPhoneType = pluggedInfo.phoneType
                rememberSessionDevice(resolveSessionDeviceCandidateId(), "plugged")
                sessionConfig = buildSessionConfig()
                logStore.info(
                    SOURCE,
                    "Phone plugged: ${Cpc200Protocol.describePhoneType(pluggedInfo.phoneType)}, wifi=${pluggedInfo.wifiState}",
                )
                flowController.onPlugged(pluggedInfo)
                reapplySessionRouting("plugged")
                syncVideoFocus("plugged")
            }

            Cpc200Protocol.MessageType.PHASE -> {
                val safePayload = payload ?: return
                val phase = Cpc200Protocol.parsePhase(safePayload)
                if (phase == 0 || phase == 13) {
                    currentPhoneType = null
                    currentSessionDeviceId = null
                    currentPeerBluetoothDeviceId = null
                }
                logStore.info(SOURCE, "Phase message: $phase")
                flowController.onPhase(phase)
            }

            Cpc200Protocol.MessageType.UNPLUGGED -> {
                currentPhoneType = null
                currentSessionDeviceId = null
                currentPeerBluetoothDeviceId = null
                logStore.info(SOURCE, "Phone unplugged")
                flowController.onUnplugged()
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
                        applyRuntimeDeviceSettings()
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
                flowController.onCommand(command)
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
                logStore.info(SOURCE, "BluetoothConnectStart: ${describeKnownDevice(currentPeerBluetoothDeviceId)}")
                refreshState(phoneDescription = describeKnownDevice(currentPeerBluetoothDeviceId))
            }

            Cpc200Protocol.MessageType.BLUETOOTH_CONNECTED -> {
                val safePayload = payload ?: return
                currentPeerBluetoothDeviceId = Cpc200Protocol.parseDeviceIdentifier(safePayload)
                logStore.info(SOURCE, "BluetoothConnected: ${describeKnownDevice(currentPeerBluetoothDeviceId)}")
                refreshState(phoneDescription = describeKnownDevice(currentPeerBluetoothDeviceId))
            }

            Cpc200Protocol.MessageType.BLUETOOTH_DISCONNECT -> {
                logStore.info(SOURCE, "BluetoothDisconnect")
                currentPeerBluetoothDeviceId = null
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
                applyRuntimeDeviceSettings()
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
        flowController.onSessionClosed(reason)
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
        val phase = _state.value.protocolPhase
        return currentPhoneType != null ||
            phase == ProjectionProtocolPhase.CARPLAY_SESSION_SETUP ||
            phase == ProjectionProtocolPhase.AIRPLAY_NEGOTIATING ||
            phase == ProjectionProtocolPhase.STREAMING_ACTIVE
    }

    private fun isProjectionSessionEstablished(): Boolean {
        val phase = _state.value.protocolPhase
        return currentSession != null && (
            currentPhoneType != null ||
                _state.value.state == ProjectionConnectionState.STREAMING ||
                phase == ProjectionProtocolPhase.CARPLAY_SESSION_SETUP ||
                phase == ProjectionProtocolPhase.AIRPLAY_NEGOTIATING ||
                phase == ProjectionProtocolPhase.STREAMING_ACTIVE
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
            "Reconnect session config rebuilt: screen=${sessionConfig.width}x${sessionConfig.height} dpi=${sessionConfig.dpi} climatePanel=${sessionConfig.climatePanelEnabled}",
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
