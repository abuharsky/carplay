package com.alexander.carplay.data.session

import android.content.Context
import android.hardware.usb.UsbDevice
import android.view.MotionEvent
import android.view.Surface
import com.alexander.carplay.data.audio.AudioStreamManager
import com.alexander.carplay.data.executor.CarPlayExecutors
import com.alexander.carplay.data.input.TouchInputMapper
import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.alexander.carplay.data.protocol.Cpc200Protocol
import com.alexander.carplay.data.protocol.DongleBoxSettingsSnapshot
import com.alexander.carplay.data.protocol.DongleDeviceCatalogParser
import com.alexander.carplay.data.protocol.DongleKnownDevice
import com.alexander.carplay.data.protocol.ProjectionSessionConfig
import com.alexander.carplay.data.replay.CaptureReplaySession
import com.alexander.carplay.data.usb.AndroidUsbTransport
import com.alexander.carplay.data.usb.DongleConnectionSession
import com.alexander.carplay.data.usb.UsbTransport
import com.alexander.carplay.data.video.H264Renderer
import com.alexander.carplay.domain.model.ProjectionConnectionState
import com.alexander.carplay.domain.model.ProjectionSessionSnapshot
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DongleSessionManager(
    context: Context,
    private val logStore: DiagnosticLogStore,
) {
    private enum class SessionMode {
        USB,
        REPLAY,
    }

    private enum class SessionStage {
        IDLE,
        INIT_SENT,
        WAITING_PHONE,
        PHONE_CONNECTED,
        STREAMING,
    }

    companion object {
        private const val SOURCE = "Session"
        private const val READ_TIMEOUT_MS = 1_000
        private const val WRITE_TIMEOUT_MS = 1_000
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val USB_OPEN_STABILIZATION_DELAY_MS = 3_000L
        private const val HEARTBEAT_INTERVAL_SECONDS = 2L
        private const val FRAME_REQUEST_INTERVAL_MS = 5_000L
        private const val DEFAULT_REPLAY_FRAME_DELAY_MS = 16L
    }

    private val appContext = context.applicationContext
    private val executors = CarPlayExecutors()
    private val usbTransport: UsbTransport = AndroidUsbTransport(appContext, logStore)
    private val renderer = H264Renderer(executors, logStore)
    private val audioStreamManager = AudioStreamManager(logStore)
    private val touchInputMapper = TouchInputMapper()
    private val outboundQueue = LinkedBlockingQueue<ByteArray>()
    private val knownDevices = linkedMapOf<String, DongleKnownDevice>()
    private val _state = MutableStateFlow(ProjectionSessionSnapshot())
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

            override fun prepareForDongleReinit(reason: String) {
                resetProjectionRuntime(reason)
            }

            override fun requestReconnect(reason: String) {
                closeCurrentSession(reason, scheduleReconnect = true)
            }

            override fun updateState(
                state: ProjectionConnectionState,
                message: String,
                phoneDescription: String?,
            ) {
                this@DongleSessionManager.updateState(
                    state = state,
                    message = message,
                    phoneDescription = phoneDescription,
                    surfaceAttached = surfaceAttached,
                )
            }
        },
    )

    private var started = false
    private var shuttingDown = false
    private var sessionStage = SessionStage.IDLE
    private var sessionMode = SessionMode.USB
    private var surfaceAttached = false
    private var sessionConfig = ProjectionSessionConfig.fromContext(appContext)
    private var currentSession: DongleConnectionSession? = null
    private var currentDevice: UsbDevice? = null
    private var currentConnectionLabel: String? = null
    private var currentPhoneType: Int? = null
    private var currentSelectedDeviceId: String? = null
    private var currentActiveDeviceId: String? = null
    private var replayCapturePath: String? = null
    private var replayFrameDelayMs: Long = DEFAULT_REPLAY_FRAME_DELAY_MS
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var frameRequestFuture: ScheduledFuture<*>? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var writeLoopStarted = false

    val state: StateFlow<ProjectionSessionSnapshot> = _state.asStateFlow()

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
            outboundQueue.clear()
            audioStreamManager.release()
            renderer.release()
            updateState(ProjectionConnectionState.IDLE, "Foreground service stopped")
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
            closeCurrentSession(reason, scheduleReconnect = true)
        }
    }

    fun attachSurface(surface: Surface) {
        surfaceAttached = true
        renderer.attachSurface(surface)
        executors.session.execute {
            flowController.onSurfaceAttached()
            updateState(
                state = _state.value.state,
                message = _state.value.statusMessage,
                surfaceAttached = true,
            )
        }
    }

    fun detachSurface() {
        surfaceAttached = false
        renderer.detachSurface()
        executors.session.execute {
            flowController.onSurfaceDetached()
            updateState(
                state = if (currentSession == null) ProjectionConnectionState.SEARCHING else _state.value.state,
                message = if (currentSession == null) {
                    "Adapter not connected"
                } else {
                    "Video paused while activity is in background"
                },
                surfaceAttached = false,
            )
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

    private fun startUsbInternal(reason: String) {
        started = true
        sessionMode = SessionMode.USB
        sessionConfig = ProjectionSessionConfig.fromContext(appContext)
        updateState(
            state = ProjectionConnectionState.SEARCHING,
            message = "Scanning for USB adapter",
        )
        ensureWriteLoop()
        connectOrRequestPermission(reason)
    }

    private fun startReplayInternal(capturePath: String) {
        started = true
        sessionMode = SessionMode.REPLAY
        sessionConfig = ProjectionSessionConfig.fromContext(appContext)
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
        if (currentSession != null) return

        val device = usbTransport.findKnownDevice()
        if (device == null) {
            updateState(
                state = ProjectionConnectionState.SEARCHING,
                message = "Waiting for CarPlay adapter over USB",
                adapterDescription = null,
            )
            scheduleReconnect("device scan", RECONNECT_DELAY_MS)
            return
        }

        currentDevice = device
        val adapterLabel = "${device.deviceName} (0x${device.productId.toString(16)})"
        currentConnectionLabel = adapterLabel

        if (!usbTransport.hasPermission(device)) {
            updateState(
                state = ProjectionConnectionState.WAITING_PERMISSION,
                message = "Waiting for USB permission",
                adapterDescription = adapterLabel,
            )
            usbTransport.requestPermission(device)
            return
        }

        cancelReconnect()
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
            currentSession = session
            currentConnectionLabel = session.description
            sessionConfig = ProjectionSessionConfig.fromContext(appContext)
            resetProjectionRuntime("fresh usb session")

            ensureWriteLoop()
            flowController.onSessionReady(sessionConfig)
        } catch (t: Throwable) {
            logStore.error(SOURCE, "Unable to open session", t)
            closeCurrentSession("open session failed", scheduleReconnect = true)
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
            flowController.onSessionReady(sessionConfig)
        } catch (t: Throwable) {
            logStore.error(SOURCE, "Unable to open replay session", t)
            closeCurrentSession("open replay failed", scheduleReconnect = false)
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatFuture?.cancel(false)
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
            { queueOutbound(Cpc200Protocol.command(Cpc200Protocol.Command.FRAME_REQUEST)) },
            FRAME_REQUEST_INTERVAL_MS,
            FRAME_REQUEST_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
        logStore.info(SOURCE, "Frame request loop started (${FRAME_REQUEST_INTERVAL_MS}ms interval)")
    }

    private fun stopFrameRequestLoop() {
        frameRequestFuture?.cancel(false)
        frameRequestFuture = null
    }

    private fun resetProjectionRuntime(reason: String) {
        currentSelectedDeviceId = null
        currentActiveDeviceId = null
        knownDevices.clear()
        outboundQueue.clear()
        audioStreamManager.release()
        renderer.softReset()
        logStore.info(SOURCE, "Projection runtime reset: $reason")
    }

    private fun rememberKnownDevice(device: DongleKnownDevice) {
        val normalizedId = Cpc200Protocol.normalizeDeviceIdentifier(device.id)
        if (normalizedId.isBlank()) return
        val existing = knownDevices[normalizedId]
        knownDevices[normalizedId] = if (existing == null) {
            device.copy(id = normalizedId)
        } else {
            existing.copy(
                name = device.name ?: existing.name,
                type = device.type ?: existing.type,
                index = device.index ?: existing.index,
            )
        }
    }

    private fun rememberKnownDevices(devices: List<DongleKnownDevice>) {
        devices.forEach(::rememberKnownDevice)
    }

    private fun describeKnownDevice(deviceId: String?): String {
        val normalizedId = deviceId
            ?.let(Cpc200Protocol::normalizeDeviceIdentifier)
            ?.ifBlank { null }
            ?: return "-"
        val knownDevice = knownDevices[normalizedId] ?: return normalizedId
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

    private fun handleBoxSettingsMessage(payload: ByteArray) {
        val snapshot = DongleDeviceCatalogParser.parseBoxSettings(payload)
        if (snapshot == null) {
            logStore.info(SOURCE, "BoxSettings <- ${payload.toString(Charsets.UTF_8).take(180)}")
            return
        }

        rememberKnownDevices(snapshot.devices)
        logBoxSettingsSnapshot(snapshot)

        snapshot.activeDeviceId?.let { activeId ->
            currentActiveDeviceId = activeId
            updateState(
                state = _state.value.state,
                message = _state.value.statusMessage,
                streamDescription = _state.value.streamDescription,
                videoWidth = _state.value.videoWidth,
                videoHeight = _state.value.videoHeight,
            )
        }
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
                        handleIncomingMessage(header.type, null)
                        applyReplayPacing(header.type)
                        continue
                    }

                    if (header.type == Cpc200Protocol.MessageType.VIDEO) {
                        handleVideoMessage(session, header.length)
                    } else {
                        val payload = session.readPayload(header.length, READ_TIMEOUT_MS)
                        handleIncomingMessage(header.type, payload)
                    }
                    applyReplayPacing(header.type)
                }
            } catch (t: Throwable) {
                if (!shuttingDown) {
                    logStore.error(SOURCE, "Read loop failed", t)
                    executors.session.execute {
                        closeCurrentSession("read loop failed", scheduleReconnect = true)
                    }
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
                    try {
                        session.write(message, WRITE_TIMEOUT_MS)
                    } catch (t: Throwable) {
                        logStore.error(SOURCE, "Write failed", t)
                        outboundQueue.clear()
                        executors.session.execute {
                            closeCurrentSession("write failed", scheduleReconnect = true)
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
        var meta: Cpc200Protocol.VideoMeta? = null
        renderer.processDataDirect(
            payloadLength,
            Cpc200Protocol.VIDEO_SUB_HEADER_SIZE,
        ) { target, offset ->
            val actual = session.readPayloadInto(target, offset, payloadLength, READ_TIMEOUT_MS)
            require(actual == payloadLength) { "Video payload read mismatch: $actual/$payloadLength" }
            meta = Cpc200Protocol.parseVideoMeta(target, offset)
        }

        val safeMeta = meta ?: return
        renderer.updateVideoFormat(safeMeta.width, safeMeta.height)
        flowController.onVideoFrameReceived(safeMeta.width, safeMeta.height)

        updateState(
            state = if (surfaceAttached) {
                ProjectionConnectionState.STREAMING
            } else {
                ProjectionConnectionState.WAITING_PHONE
            },
            message = if (surfaceAttached) {
                "Streaming H.264 ${safeMeta.width}x${safeMeta.height}"
            } else {
                "Video frames received but surface is detached"
            },
            streamDescription = "${safeMeta.width}x${safeMeta.height}, flags=${safeMeta.flags}",
            videoWidth = safeMeta.width,
            videoHeight = safeMeta.height,
            surfaceAttached = surfaceAttached,
        )
    }

    private fun handleIncomingMessage(
        type: Int,
        payload: ByteArray?,
    ) {
        when (type) {
            Cpc200Protocol.MessageType.OPEN -> {
                if (payload != null) {
                    val openedInfo = Cpc200Protocol.parseOpen(payload)
                    logStore.info(
                        SOURCE,
                        "Open acknowledged by adapter: ${openedInfo.width}x${openedInfo.height}@${openedInfo.fps}",
                    )
                    renderer.updateVideoFormat(openedInfo.width, openedInfo.height)
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
                logStore.info(
                    SOURCE,
                    "Phone plugged: ${Cpc200Protocol.describePhoneType(pluggedInfo.phoneType)}, wifi=${pluggedInfo.wifiState}",
                )
                flowController.onPlugged(pluggedInfo)
            }

            Cpc200Protocol.MessageType.PHASE -> {
                val safePayload = payload ?: return
                val phase = Cpc200Protocol.parsePhase(safePayload)
                logStore.info(SOURCE, "Phase message: $phase")
                flowController.onPhase(phase)
            }

            Cpc200Protocol.MessageType.UNPLUGGED -> {
                logStore.info(SOURCE, "Phone unplugged")
                flowController.onUnplugged()
            }

            Cpc200Protocol.MessageType.COMMAND -> {
                val safePayload = payload ?: return
                val command = Cpc200Protocol.parseCommand(safePayload)
                logStore.info(SOURCE, "Adapter command: ${Cpc200Protocol.describeCommand(command)}")
                flowController.onCommand(command)
            }

            Cpc200Protocol.MessageType.BOX_SETTINGS -> {
                payload?.let(::handleBoxSettingsMessage)
            }

            Cpc200Protocol.MessageType.SELECTED_DEVICE -> {
                val safePayload = payload ?: return
                currentSelectedDeviceId = Cpc200Protocol.parseDeviceIdentifier(safePayload)
                logStore.info(SOURCE, "Selected device: ${describeKnownDevice(currentSelectedDeviceId)}")
            }

            Cpc200Protocol.MessageType.ACTIVE_DEVICE -> {
                val safePayload = payload ?: return
                currentActiveDeviceId = Cpc200Protocol.parseDeviceIdentifier(safePayload)
                logStore.info(SOURCE, "Active device: ${describeKnownDevice(currentActiveDeviceId)}")
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
            Cpc200Protocol.MessageType.MEDIA_DATA,
            -> {
                logProtocolTextMessage(type, payload)
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
            }

            Cpc200Protocol.MessageType.UNKNOWN_37,
            Cpc200Protocol.MessageType.UNKNOWN_38,
            -> {
                logProtocolTextMessage(type, payload)
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

    private fun queueOutbound(message: ByteArray) {
        if (!started || shuttingDown) return
        logOutboundMessage(message)
        outboundQueue.offer(message)
    }

    private fun logOutboundMessage(message: ByteArray) {
        try {
            if (message.size < Cpc200Protocol.HEADER_SIZE) return
            val header = Cpc200Protocol.parseHeader(message.copyOfRange(0, Cpc200Protocol.HEADER_SIZE))
            if (header.type == Cpc200Protocol.MessageType.MULTI_TOUCH) return
            val payload = message.copyOfRange(Cpc200Protocol.HEADER_SIZE, message.size)
            logStore.info(SOURCE, "SEND ${describeOutboundMessage(header.type, payload)}")
        } catch (_: Throwable) {
        }
    }

    private fun describeOutboundMessage(
        type: Int,
        payload: ByteArray,
    ): String = when (type) {
        Cpc200Protocol.MessageType.HEARTBEAT -> Cpc200Protocol.describeMessageType(type)
        Cpc200Protocol.MessageType.OPEN -> {
            val openedInfo = Cpc200Protocol.parseOpen(payload)
            "Open ${openedInfo.width}x${openedInfo.height}@${openedInfo.fps}"
        }

        Cpc200Protocol.MessageType.COMMAND -> {
            val command = Cpc200Protocol.parseCommand(payload)
            "Command ${Cpc200Protocol.describeCommand(command)}"
        }

        Cpc200Protocol.MessageType.SELECT_BT_DEVICE -> {
            "SelectBtDevice ${Cpc200Protocol.parseDeviceIdentifier(payload)}"
        }

        Cpc200Protocol.MessageType.SEND_FILE -> {
            "SendFile ${parseSendFilePath(payload)}"
        }

        else -> Cpc200Protocol.describeMessageType(type)
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

    private fun updateState(
        state: ProjectionConnectionState,
        message: String,
        adapterDescription: String? = _state.value.adapterDescription,
        phoneDescription: String? = _state.value.phoneDescription,
        streamDescription: String? = _state.value.streamDescription,
        videoWidth: Int? = _state.value.videoWidth,
        videoHeight: Int? = _state.value.videoHeight,
        surfaceAttached: Boolean = this.surfaceAttached,
        lastError: String? = _state.value.lastError,
    ) {
        _state.value = ProjectionSessionSnapshot(
            state = state,
            statusMessage = message,
            adapterDescription = adapterDescription,
            phoneDescription = phoneDescription,
            streamDescription = streamDescription,
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
