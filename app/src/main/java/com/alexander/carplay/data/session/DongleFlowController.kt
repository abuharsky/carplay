package com.alexander.carplay.data.session

import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.alexander.carplay.data.protocol.Cpc200Protocol
import com.alexander.carplay.data.protocol.ProjectionSessionConfig
import com.alexander.carplay.domain.model.ProjectionConnectionState
import com.alexander.carplay.domain.model.ProjectionProtocolPhase

class DongleFlowController(
    private val logStore: DiagnosticLogStore,
    private val delegate: Delegate,
) {
    companion object {
        private const val SOURCE = "Flow"
    }

    private var firstVideoPacketSeen = false
    private var phase = ProjectionProtocolPhase.NONE

    interface Delegate {
        fun queueMessage(message: ByteArray)

        fun startReadLoop()

        fun sendCommand(commandId: Int)

        fun startHeartbeat()

        fun stopHeartbeat()

        fun startFrameRequests()

        fun stopFrameRequests()

        fun startAutoConnectLoop(reason: String)

        fun stopAutoConnectLoop()

        fun startBleAdvertising(reason: String)

        fun stopBleAdvertising()

        fun hasKnownDevices(): Boolean

        fun prepareForDongleReinit(reason: String)

        fun requestReconnect(reason: String)

        fun requestHostUi()

        fun updateState(
            state: ProjectionConnectionState,
            protocolPhase: ProjectionProtocolPhase,
            message: String,
            phoneDescription: String? = null,
        )
    }

    private fun transitionTo(
        newPhase: ProjectionProtocolPhase,
        message: String,
    ) {
        if (phase == newPhase) return
        logStore.info(SOURCE, "Phase ${phase.name} -> ${newPhase.name}: $message")
        phase = newPhase
    }

    fun onSessionReady(config: ProjectionSessionConfig) {
        firstVideoPacketSeen = false
        phase = ProjectionProtocolPhase.NONE
        logStore.info(
            SOURCE,
            "Queueing init sequence: ${config.width}x${config.height}@${config.fps} dpi=${config.dpi}",
        )
        logStore.info(
            SOURCE,
            "Advanced adapter features require firmware flags: DashboardInfo=7, GNSSCapability=1/3, HudGPSSwitch=1, AdvancedFeatures=1",
        )
        delegate.updateState(
            state = ProjectionConnectionState.INIT,
            protocolPhase = ProjectionProtocolPhase.HOST_INIT,
            message = "Queueing adapter init sequence",
        )

        // Keep the inbound pipe draining before the first init packets are sent.
        // This adapter is sensitive to unread USB traffic during startup.
        delegate.startReadLoop()
        buildInitMessages(config).forEach(delegate::queueMessage)
        delegate.startHeartbeat()
        delegate.stopAutoConnectLoop()
        transitionTo(ProjectionProtocolPhase.HOST_INIT, "Host init sequence queued")

        delegate.updateState(
            state = ProjectionConnectionState.WAITING_PHONE,
            protocolPhase = ProjectionProtocolPhase.HOST_INIT,
            message = "Init queued. Read loop and heartbeat started",
        )
    }

    fun onSessionClosed(reason: String) {
        firstVideoPacketSeen = false
        delegate.stopFrameRequests()
        delegate.stopAutoConnectLoop()
        delegate.stopBleAdvertising()
        transitionTo(ProjectionProtocolPhase.NONE, "Session closed: $reason")
    }

    fun onSurfaceAttached() = Unit

    fun onSurfaceDetached() = Unit

    fun onDongleOpened() {
        transitionTo(ProjectionProtocolPhase.INIT_ECHO, "Adapter acknowledged Open")
        if (delegate.hasKnownDevices()) {
            delegate.startAutoConnectLoop("adapter opened with known devices")
            delegate.updateState(
                state = ProjectionConnectionState.CONNECTING,
                protocolPhase = ProjectionProtocolPhase.INIT_ECHO,
                message = "Adapter ready. Starting auto-connect scan",
            )
            logStore.info(SOURCE, "Opened -> startAutoConnect loop")
        } else {
            delegate.stopAutoConnectLoop()
            delegate.startBleAdvertising("no paired devices known after init echo")
            delegate.updateState(
                state = ProjectionConnectionState.WAITING_PHONE,
                protocolPhase = ProjectionProtocolPhase.INIT_ECHO,
                message = "Adapter ready. BLE pairing mode active",
            )
            logStore.info(SOURCE, "Opened -> startBleAdv")
        }
    }

    fun onPlugged(info: Cpc200Protocol.PluggedInfo) {
        delegate.stopAutoConnectLoop()
        delegate.stopBleAdvertising()
        delegate.sendCommand(Cpc200Protocol.Command.REQUEST_KEY_FRAME)
        delegate.startFrameRequests()
        transitionTo(ProjectionProtocolPhase.CARPLAY_SESSION_SETUP, "Plugged ${Cpc200Protocol.describePhoneType(info.phoneType)}")
        delegate.updateState(
            state = ProjectionConnectionState.CONNECTING,
            protocolPhase = ProjectionProtocolPhase.CARPLAY_SESSION_SETUP,
            message = "CarPlay linked. Finishing stream negotiation",
            phoneDescription = Cpc200Protocol.describePhoneType(info.phoneType),
        )
        logStore.info(SOURCE, "Plugged -> requestKeyFrame + frame timer")
    }

    fun onPhase(phase: Int) {
        when (phase) {
            7 -> {
                delegate.stopAutoConnectLoop()
                delegate.stopBleAdvertising()
                transitionTo(ProjectionProtocolPhase.AIRPLAY_NEGOTIATING, "Phase 7 negotiation started")
                delegate.updateState(
                    state = ProjectionConnectionState.CONNECTING,
                    protocolPhase = ProjectionProtocolPhase.AIRPLAY_NEGOTIATING,
                    message = "CarPlay negotiation in progress",
                )
            }

            8 -> {
                delegate.stopAutoConnectLoop()
                delegate.stopBleAdvertising()
                transitionTo(ProjectionProtocolPhase.STREAMING_ACTIVE, "Phase 8 streaming active")
                delegate.updateState(
                    state = ProjectionConnectionState.STREAMING,
                    protocolPhase = ProjectionProtocolPhase.STREAMING_ACTIVE,
                    message = "CarPlay streaming active",
                )
            }

            13 -> {
                firstVideoPacketSeen = false
                delegate.stopFrameRequests()
                beginDiscovery("phase 13 negotiation failed")
                transitionTo(ProjectionProtocolPhase.NEGOTIATION_FAILED, "Phase 13 negotiation failed")
                delegate.updateState(
                    state = ProjectionConnectionState.WAITING_PHONE,
                    protocolPhase = ProjectionProtocolPhase.NEGOTIATION_FAILED,
                    message = "Negotiation failed. ${discoveryStatusMessage()}",
                )
            }

            0 -> {
                firstVideoPacketSeen = false
                delegate.stopFrameRequests()
                beginDiscovery("phase 0 session ended")
                transitionTo(ProjectionProtocolPhase.SESSION_ENDED, "Phase 0 session ended")
                delegate.updateState(
                    state = ProjectionConnectionState.WAITING_PHONE,
                    protocolPhase = ProjectionProtocolPhase.SESSION_ENDED,
                    message = "Session ended. ${discoveryStatusMessage()}",
                )
            }
        }
    }

    fun onVideoFrameReceived(
        width: Int,
        height: Int,
    ) {
        if (firstVideoPacketSeen) return
        firstVideoPacketSeen = true
        delegate.stopAutoConnectLoop()
        delegate.stopBleAdvertising()
        transitionTo(ProjectionProtocolPhase.STREAMING_ACTIVE, "First video frame received")
        delegate.updateState(
            state = ProjectionConnectionState.STREAMING,
            protocolPhase = ProjectionProtocolPhase.STREAMING_ACTIVE,
            message = "First video packet received: ${width}x$height",
        )
        logStore.info(SOURCE, "First video packet -> STREAMING ${width}x$height")
    }

    fun onUnplugged() {
        firstVideoPacketSeen = false
        delegate.stopFrameRequests()
        beginDiscovery("unplugged")
        transitionTo(ProjectionProtocolPhase.WAITING_RETRY, "Phone unplugged")
        delegate.updateState(
            state = ProjectionConnectionState.WAITING_PHONE,
            protocolPhase = ProjectionProtocolPhase.WAITING_RETRY,
            message = "Phone disconnected. ${discoveryStatusMessage()}",
        )
    }

    fun onCommand(commandId: Int) {
        when (commandId) {
            Cpc200Protocol.Command.REQUEST_HOST_UI -> {
                logStore.info(SOURCE, "Adapter requested host UI")
                delegate.requestHostUi()
            }

            Cpc200Protocol.Command.HIDE -> {
                logStore.info(SOURCE, "Phone requested projection hide")
            }

            Cpc200Protocol.Command.SCANNING_DEVICE -> {
                transitionTo(ProjectionProtocolPhase.PHONE_SEARCH, "Adapter scanning for known devices")
                delegate.updateState(
                    state = ProjectionConnectionState.CONNECTING,
                    protocolPhase = ProjectionProtocolPhase.PHONE_SEARCH,
                    message = "Scanning for known iPhone",
                )
            }

            Cpc200Protocol.Command.DEVICE_FOUND -> {
                delegate.stopAutoConnectLoop()
                transitionTo(ProjectionProtocolPhase.PHONE_FOUND_BT_CONNECTED, "Known device found")
                delegate.updateState(
                    state = ProjectionConnectionState.CONNECTING,
                    protocolPhase = ProjectionProtocolPhase.PHONE_FOUND_BT_CONNECTED,
                    message = "Phone found. Establishing Bluetooth link",
                )
            }

            Cpc200Protocol.Command.CONNECT_DEVICE_FAILED -> {
                firstVideoPacketSeen = false
                delegate.stopFrameRequests()
                beginDiscovery("device connect failed")
                transitionTo(ProjectionProtocolPhase.WAITING_RETRY, "Bluetooth connect failed")
                delegate.updateState(
                    state = ProjectionConnectionState.WAITING_PHONE,
                    protocolPhase = ProjectionProtocolPhase.WAITING_RETRY,
                    message = "Connection failed. ${discoveryStatusMessage()}",
                )
            }

            Cpc200Protocol.Command.BT_CONNECTED -> {
                delegate.stopAutoConnectLoop()
                delegate.stopBleAdvertising()
                transitionTo(ProjectionProtocolPhase.PHONE_FOUND_BT_CONNECTED, "Bluetooth connected")
                delegate.updateState(
                    state = ProjectionConnectionState.CONNECTING,
                    protocolPhase = ProjectionProtocolPhase.PHONE_FOUND_BT_CONNECTED,
                    message = "Bluetooth connected. Waiting for CarPlay session",
                )
            }

            Cpc200Protocol.Command.BT_DISCONNECTED -> {
                val wifiSessionEstablished =
                    phase == ProjectionProtocolPhase.CARPLAY_SESSION_SETUP ||
                        phase == ProjectionProtocolPhase.AIRPLAY_NEGOTIATING ||
                        phase == ProjectionProtocolPhase.STREAMING_ACTIVE ||
                        firstVideoPacketSeen

                if (!wifiSessionEstablished) {
                    firstVideoPacketSeen = false
                    delegate.stopFrameRequests()
                    beginDiscovery("bluetooth disconnected")
                    transitionTo(ProjectionProtocolPhase.WAITING_RETRY, "Bluetooth disconnected before Wi-Fi session")
                    delegate.updateState(
                        state = ProjectionConnectionState.WAITING_PHONE,
                        protocolPhase = ProjectionProtocolPhase.WAITING_RETRY,
                        message = "Bluetooth disconnected. ${discoveryStatusMessage()}",
                    )
                }
            }

            Cpc200Protocol.Command.WIFI_CONNECTED -> {
                delegate.updateState(
                    state = ProjectionConnectionState.CONNECTING,
                    protocolPhase = ProjectionProtocolPhase.CARPLAY_SESSION_SETUP,
                    message = "Phone connected to adapter Wi-Fi",
                )
            }

            Cpc200Protocol.Command.BT_PAIR_START -> {
                delegate.updateState(
                    state = ProjectionConnectionState.CONNECTING,
                    protocolPhase = ProjectionProtocolPhase.PHONE_SEARCH,
                    message = "Bluetooth pairing in progress",
                )
            }

            Cpc200Protocol.Command.DEVICE_NOT_FOUND -> {
                firstVideoPacketSeen = false
                delegate.stopFrameRequests()
                beginDiscovery("device not found")
                transitionTo(ProjectionProtocolPhase.WAITING_RETRY, "No known device found")
                delegate.updateState(
                    state = ProjectionConnectionState.WAITING_PHONE,
                    protocolPhase = ProjectionProtocolPhase.WAITING_RETRY,
                    message = "No known device found. ${discoveryStatusMessage()}",
                )
            }
        }
    }

    private fun beginDiscovery(reason: String) {
        if (delegate.hasKnownDevices()) {
            delegate.startAutoConnectLoop(reason)
        } else {
            delegate.stopAutoConnectLoop()
            delegate.startBleAdvertising(reason)
        }
    }

    private fun discoveryStatusMessage(): String {
        return if (delegate.hasKnownDevices()) {
            "Rescanning for known iPhone"
        } else {
            "BLE pairing mode active"
        }
    }

    private fun buildInitMessages(config: ProjectionSessionConfig): List<ByteArray> = buildList {
        add(Cpc200Protocol.sendNumber("/tmp/screen_dpi", config.dpi))
        add(Cpc200Protocol.open(config))
        add(Cpc200Protocol.sendBoolean("/tmp/night_mode", config.nightMode))
        add(Cpc200Protocol.sendNumber("/tmp/hand_drive_mode", config.handDriveMode))
        add(Cpc200Protocol.sendBoolean("/tmp/charge_mode", true))
        add(Cpc200Protocol.sendString("/etc/box_name", config.boxName))
        Cpc200Protocol.oemIcon(config)?.let { add(it) }
        Cpc200Protocol.icon120(config)?.let { add(it) }
        Cpc200Protocol.icon180(config)?.let { add(it) }
        Cpc200Protocol.icon256(config)?.let { add(it) }
        logStore.info(SOURCE, "Queueing OEM branding: ${config.oemBranding.label}")
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
                    Cpc200Protocol.Command.USE_BOX_MIC
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
}
