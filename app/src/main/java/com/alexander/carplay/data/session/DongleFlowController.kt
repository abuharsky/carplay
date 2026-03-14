package com.alexander.carplay.data.session

import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.alexander.carplay.data.protocol.Cpc200Protocol
import com.alexander.carplay.data.protocol.ProjectionSessionConfig
import com.alexander.carplay.domain.model.ProjectionConnectionState

class DongleFlowController(
    private val logStore: DiagnosticLogStore,
    private val delegate: Delegate,
) {
    companion object {
        private const val SOURCE = "Flow"
    }

    private var firstVideoPacketSeen = false

    interface Delegate {
        fun queueMessage(message: ByteArray)

        fun startReadLoop()

        fun sendCommand(commandId: Int)

        fun startHeartbeat()

        fun stopHeartbeat()

        fun startFrameRequests()

        fun stopFrameRequests()

        fun prepareForDongleReinit(reason: String)

        fun requestReconnect(reason: String)

        fun updateState(
            state: ProjectionConnectionState,
            message: String,
            phoneDescription: String? = null,
        )
    }

    fun onSessionReady(config: ProjectionSessionConfig) {
        firstVideoPacketSeen = false
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
            message = "Queueing adapter init sequence",
        )

        buildInitMessages(config).forEach(delegate::queueMessage)
        delegate.startReadLoop()
        delegate.startHeartbeat()

        delegate.updateState(
            state = ProjectionConnectionState.WAITING_PHONE,
            message = "Init queued. Read loop and heartbeat started",
        )
    }

    fun onSessionClosed(@Suppress("UNUSED_PARAMETER") reason: String) {
        firstVideoPacketSeen = false
    }

    fun onSurfaceAttached() = Unit

    fun onSurfaceDetached() = Unit

    fun onDongleOpened() {
        delegate.sendCommand(Cpc200Protocol.Command.WIFI_CONNECT)
        delegate.updateState(
            state = ProjectionConnectionState.WAITING_PHONE,
            message = "Opened received. wifiConnect sent",
        )
        logStore.info(SOURCE, "Opened -> wifiConnect")
    }

    fun onPlugged(info: Cpc200Protocol.PluggedInfo) {
        delegate.sendCommand(Cpc200Protocol.Command.FRAME_REQUEST)
        delegate.startFrameRequests()
        delegate.updateState(
            state = ProjectionConnectionState.WAITING_PHONE,
            message = "Plugged received: ${Cpc200Protocol.describePhoneType(info.phoneType)}",
            phoneDescription = Cpc200Protocol.describePhoneType(info.phoneType),
        )
        logStore.info(SOURCE, "Plugged -> frameRequest + frame timer")
    }

    fun onPhase(@Suppress("UNUSED_PARAMETER") phase: Int) = Unit

    fun onVideoFrameReceived(
        width: Int,
        height: Int,
    ) {
        if (firstVideoPacketSeen) return
        firstVideoPacketSeen = true
        delegate.updateState(
            state = ProjectionConnectionState.STREAMING,
            message = "First video packet received: ${width}x$height",
        )
        logStore.info(SOURCE, "First video packet -> STREAMING ${width}x$height")
    }

    fun onUnplugged() = Unit

    fun onCommand(@Suppress("UNUSED_PARAMETER") commandId: Int) = Unit

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
        add(Cpc200Protocol.command(Cpc200Protocol.Command.WIFI_ENABLE))
        add(
            Cpc200Protocol.command(
                if (config.wifi5g) {
                    Cpc200Protocol.Command.WIFI_5
                } else {
                    Cpc200Protocol.Command.WIFI_24
                },
            ),
        )
        add(
            Cpc200Protocol.command(
                if (config.useBoxMic) {
                    Cpc200Protocol.Command.BOX_MIC
                } else {
                    Cpc200Protocol.Command.MIC
                },
            ),
        )
        add(
            Cpc200Protocol.command(
                if (config.audioTransferOn) {
                    Cpc200Protocol.Command.AUDIO_TRANSFER_ON
                } else {
                    Cpc200Protocol.Command.AUDIO_TRANSFER_OFF
                },
            ),
        )
        if (config.androidWorkMode) {
            add(Cpc200Protocol.sendBoolean("/etc/android_work_mode", true))
        }
    }
}
