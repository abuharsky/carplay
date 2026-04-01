package com.alexander.carplay.data.session.v2

import com.alexander.carplay.data.protocol.Cpc200Protocol
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConnectionReducerV2Test {
    @Test
    fun `late open acknowledgment does not rewind projection state`() {
        var snapshot = ConnectionSnapshotV2()
        snapshot = reduce(snapshot, ConnectionEventV2.TransportOpening(epoch = 1L, connectionLabel = "usb"))
        snapshot = reduce(
            snapshot,
            ConnectionEventV2.TransportOpened(
                epoch = 1L,
                connectionLabel = "usb",
                includeBrandingAssets = true,
            ),
        )
        snapshot = reduce(
            snapshot,
            ConnectionEventV2.PolicyUpdated(
                knownDeviceCount = 1,
                autoConnectEligible = true,
                manualSelectionRequired = false,
            ),
        )
        snapshot = reduce(snapshot, ConnectionEventV2.OpenAcknowledged(width = 1920, height = 688, fps = 60))
        snapshot = reduce(snapshot, ConnectionEventV2.BluetoothConnected(deviceId = "phone"))
        snapshot = reduce(
            snapshot,
            ConnectionEventV2.Plugged(
                phoneType = Cpc200Protocol.PhoneType.CARPLAY,
                wifiState = 1,
            ),
        )

        val projectionAfterPlugged = snapshot.projection

        snapshot = reduce(snapshot, ConnectionEventV2.OpenAcknowledged(width = 1920, height = 688, fps = 60))

        assertThat(snapshot.projection).isEqualTo(projectionAfterPlugged)
        assertThat(snapshot.projection.state).isEqualTo(ProjectionStateV2.PLUGGED)
    }

    @Test
    fun `late bluetooth connected does not rewind projection state`() {
        var snapshot = ConnectionSnapshotV2()
        snapshot = reduce(snapshot, ConnectionEventV2.TransportOpening(epoch = 1L, connectionLabel = "usb"))
        snapshot = reduce(
            snapshot,
            ConnectionEventV2.TransportOpened(
                epoch = 1L,
                connectionLabel = "usb",
                includeBrandingAssets = true,
            ),
        )
        snapshot = reduce(snapshot, ConnectionEventV2.OpenAcknowledged(width = 1920, height = 688, fps = 60))
        snapshot = reduce(
            snapshot,
            ConnectionEventV2.Plugged(
                phoneType = Cpc200Protocol.PhoneType.CARPLAY,
                wifiState = 1,
            ),
        )

        snapshot = reduce(snapshot, ConnectionEventV2.BluetoothConnected(deviceId = "phone"))

        assertThat(snapshot.projection.state).isEqualTo(ProjectionStateV2.PLUGGED)
        assertThat(snapshot.discovery.state).isEqualTo(DiscoveryStateV2.BT_CONNECTED)
    }

    @Test
    fun `phase 8 promotes streaming even without phase 7`() {
        var snapshot = ConnectionSnapshotV2()
        snapshot = reduce(snapshot, ConnectionEventV2.TransportOpening(epoch = 1L, connectionLabel = "usb"))
        snapshot = reduce(
            snapshot,
            ConnectionEventV2.TransportOpened(
                epoch = 1L,
                connectionLabel = "usb",
                includeBrandingAssets = true,
            ),
        )
        snapshot = reduce(snapshot, ConnectionEventV2.OpenAcknowledged(width = 1920, height = 688, fps = 60))
        snapshot = reduce(
            snapshot,
            ConnectionEventV2.Plugged(
                phoneType = Cpc200Protocol.PhoneType.CARPLAY,
                wifiState = 1,
            ),
        )

        snapshot = reduce(snapshot, ConnectionEventV2.RawPhaseReceived(value = 8))

        assertThat(snapshot.projection.state).isEqualTo(ProjectionStateV2.STREAMING)
        assertThat(snapshot.projection.rawPhase).isEqualTo(8)
    }

    @Test
    fun `stale transport epoch is ignored`() {
        var snapshot = ConnectionSnapshotV2()
        snapshot = reduce(snapshot, ConnectionEventV2.TransportOpening(epoch = 1L, connectionLabel = "usb-1"))
        snapshot = reduce(
            snapshot,
            ConnectionEventV2.TransportOpened(
                epoch = 1L,
                connectionLabel = "usb-1",
                includeBrandingAssets = true,
            ),
        )
        snapshot = reduce(snapshot, ConnectionEventV2.TransportOpening(epoch = 2L, connectionLabel = "usb-2"))

        val beforeStaleEvent = snapshot

        snapshot = reduce(
            snapshot,
            ConnectionEventV2.TransportOpened(
                epoch = 1L,
                connectionLabel = "stale",
                includeBrandingAssets = false,
            ),
        )

        assertThat(snapshot).isEqualTo(beforeStaleEvent)
        assertThat(snapshot.transport.epoch).isEqualTo(2L)
        assertThat(snapshot.transport.connectionLabel).isEqualTo("usb-2")
    }

    @Test
    fun `policy update after open acknowledgement arms auto connect`() {
        var snapshot = ConnectionSnapshotV2()
        snapshot = reduce(snapshot, ConnectionEventV2.TransportOpening(epoch = 1L, connectionLabel = "usb"))
        snapshot = reduce(
            snapshot,
            ConnectionEventV2.TransportOpened(
                epoch = 1L,
                connectionLabel = "usb",
                includeBrandingAssets = true,
            ),
        )
        snapshot = reduce(snapshot, ConnectionEventV2.OpenAcknowledged(width = 1920, height = 688, fps = 60))

        val result = ConnectionReducerV2.reduce(
            snapshot,
            ConnectionEventV2.PolicyUpdated(
                knownDeviceCount = 1,
                autoConnectEligible = true,
                manualSelectionRequired = false,
            ),
        )

        assertThat(result.snapshot.discovery.state).isEqualTo(DiscoveryStateV2.SCANNING)
        assertThat(result.effects).containsExactly(
            ConnectionEffectV2.StartBleAdvertising,
            ConnectionEffectV2.RequestAutoConnect,
        )
    }

    @Test
    fun `bluetooth disconnected before session triggers retry without rewinding transport`() {
        var snapshot = ConnectionSnapshotV2()
        snapshot = reduce(snapshot, ConnectionEventV2.TransportOpening(epoch = 1L, connectionLabel = "usb"))
        snapshot = reduce(
            snapshot,
            ConnectionEventV2.TransportOpened(
                epoch = 1L,
                connectionLabel = "usb",
                includeBrandingAssets = true,
            ),
        )
        snapshot = reduce(snapshot, ConnectionEventV2.OpenAcknowledged(width = 1920, height = 688, fps = 60))
        snapshot = reduce(
            snapshot,
            ConnectionEventV2.PolicyUpdated(
                knownDeviceCount = 1,
                autoConnectEligible = true,
                manualSelectionRequired = false,
            ),
        )

        val result = ConnectionReducerV2.reduce(snapshot, ConnectionEventV2.BluetoothDisconnected)

        assertThat(result.snapshot.transport.state).isEqualTo(TransportStateV2.OPEN)
        assertThat(result.snapshot.discovery.state).isEqualTo(DiscoveryStateV2.RETRYING)
        assertThat(result.effects).containsExactly(
            ConnectionEffectV2.StartBleAdvertising,
            ConnectionEffectV2.RequestAutoConnect,
        )
    }

    private fun reduce(
        snapshot: ConnectionSnapshotV2,
        event: ConnectionEventV2,
    ): ConnectionSnapshotV2 {
        return ConnectionReducerV2.reduce(snapshot, event).snapshot
    }
}
