package com.alexander.carplay.data.session

import com.alexander.carplay.data.protocol.Cpc200Protocol
import com.alexander.carplay.data.protocol.ProjectionSessionConfig
import com.alexander.carplay.domain.model.ProjectionConnectionState
import com.alexander.carplay.domain.model.ProjectionProtocolPhase
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DongleFlowControllerTest {
    @Test
    fun `late discovery commands do not rewind phase after plugged`() {
        val delegate = FakeDelegate()
        val controller = DongleFlowController({ _, _ -> }, delegate)

        controller.onSessionReady(
            config = ProjectionSessionConfig(
                width = 1920,
                height = 688,
            ),
            includeBrandingAssets = false,
        )
        controller.onDongleOpened()
        controller.onPlugged(
            Cpc200Protocol.PluggedInfo(
                phoneType = Cpc200Protocol.PhoneType.CARPLAY,
                wifiState = 1,
            ),
        )

        val snapshotAfterPlugged = delegate.snapshot()

        listOf(
            Cpc200Protocol.Command.SCANNING_DEVICE,
            Cpc200Protocol.Command.DEVICE_FOUND,
            Cpc200Protocol.Command.BT_PAIR_START,
            Cpc200Protocol.Command.BT_CONNECTED,
            Cpc200Protocol.Command.BT_DISCONNECTED,
            Cpc200Protocol.Command.CONNECT_DEVICE_FAILED,
            Cpc200Protocol.Command.DEVICE_NOT_FOUND,
        ).forEach(controller::onCommand)

        assertThat(delegate.snapshot()).isEqualTo(snapshotAfterPlugged)
        assertThat(delegate.stateUpdates.last().protocolPhase)
            .isEqualTo(ProjectionProtocolPhase.CARPLAY_SESSION_SETUP)
        assertThat(delegate.stateUpdates.last().state)
            .isEqualTo(ProjectionConnectionState.CONNECTING)
    }

    private data class StateUpdate(
        val state: ProjectionConnectionState,
        val protocolPhase: ProjectionProtocolPhase,
        val message: String,
        val phoneDescription: String?,
    )

    private data class DelegateSnapshot(
        val stateUpdateCount: Int,
        val lastProtocolPhase: ProjectionProtocolPhase?,
        val lastConnectionState: ProjectionConnectionState?,
        val startBleAdvertisingCount: Int,
        val stopBleAdvertisingCount: Int,
        val autoConnectRequestCount: Int,
        val clearAutoConnectPendingCount: Int,
        val sentCommandCount: Int,
        val startFrameRequestsCount: Int,
        val stopFrameRequestsCount: Int,
        val requestHostUiCount: Int,
    )

    private class FakeDelegate : DongleFlowController.Delegate {
        val stateUpdates = mutableListOf<StateUpdate>()
        private val startBleAdvertisingReasons = mutableListOf<String>()
        private val autoConnectReasons = mutableListOf<String>()
        private val sentCommands = mutableListOf<Int>()

        private var stopBleAdvertisingCount = 0
        private var clearAutoConnectPendingCount = 0
        private var startFrameRequestsCount = 0
        private var stopFrameRequestsCount = 0
        private var requestHostUiCount = 0

        override fun queueMessage(message: ByteArray) = Unit

        override fun startReadLoop() = Unit

        override fun sendCommand(commandId: Int) {
            sentCommands += commandId
        }

        override fun startHeartbeat() = Unit

        override fun stopHeartbeat() = Unit

        override fun startFrameRequests() {
            startFrameRequestsCount += 1
        }

        override fun stopFrameRequests() {
            stopFrameRequestsCount += 1
        }

        override fun requestAutoConnect(reason: String) {
            autoConnectReasons += reason
        }

        override fun clearAutoConnectPending() {
            clearAutoConnectPendingCount += 1
        }

        override fun startBleAdvertising(reason: String) {
            startBleAdvertisingReasons += reason
        }

        override fun stopBleAdvertising() {
            stopBleAdvertisingCount += 1
        }

        override fun hasKnownDevices(): Boolean = true

        override fun shouldAutoConnectKnownDevices(): Boolean = true

        override fun requiresManualDeviceSelection(): Boolean = false

        override fun prepareForDongleReinit(reason: String) = Unit

        override fun requestReconnect(reason: String) = Unit

        override fun requestHostUi() {
            requestHostUiCount += 1
        }

        override fun updateState(
            state: ProjectionConnectionState,
            protocolPhase: ProjectionProtocolPhase,
            message: String,
            phoneDescription: String?,
        ) {
            stateUpdates += StateUpdate(
                state = state,
                protocolPhase = protocolPhase,
                message = message,
                phoneDescription = phoneDescription,
            )
        }

        fun snapshot(): DelegateSnapshot = DelegateSnapshot(
            stateUpdateCount = stateUpdates.size,
            lastProtocolPhase = stateUpdates.lastOrNull()?.protocolPhase,
            lastConnectionState = stateUpdates.lastOrNull()?.state,
            startBleAdvertisingCount = startBleAdvertisingReasons.size,
            stopBleAdvertisingCount = stopBleAdvertisingCount,
            autoConnectRequestCount = autoConnectReasons.size,
            clearAutoConnectPendingCount = clearAutoConnectPendingCount,
            sentCommandCount = sentCommands.size,
            startFrameRequestsCount = startFrameRequestsCount,
            stopFrameRequestsCount = stopFrameRequestsCount,
            requestHostUiCount = requestHostUiCount,
        )
    }
}
