package com.alexander.carplay.data.session.v2

sealed interface ConnectionEventV2 {
    object TransportDetached : ConnectionEventV2

    data class TransportOpening(
        val epoch: Long,
        val connectionLabel: String?,
    ) : ConnectionEventV2

    data class TransportOpened(
        val epoch: Long,
        val connectionLabel: String?,
        val includeBrandingAssets: Boolean,
    ) : ConnectionEventV2

    data class TransportClosed(
        val reason: String,
    ) : ConnectionEventV2

    data class TransportFailed(
        val reason: String,
    ) : ConnectionEventV2

    data class VehicleAvailabilityChanged(
        val ready: Boolean,
    ) : ConnectionEventV2

    data class PolicyUpdated(
        val knownDeviceCount: Int,
        val autoConnectEligible: Boolean,
        val manualSelectionRequired: Boolean,
    ) : ConnectionEventV2

    data class ManualSelectionRequested(
        val deviceId: String?,
    ) : ConnectionEventV2

    data class OpenAcknowledged(
        val width: Int?,
        val height: Int?,
        val fps: Int?,
    ) : ConnectionEventV2

    data class DiscoveryScanning(
        val reason: String,
    ) : ConnectionEventV2

    data class DeviceFound(
        val deviceId: String?,
    ) : ConnectionEventV2

    data class BluetoothConnectStarting(
        val deviceId: String?,
    ) : ConnectionEventV2

    object BluetoothPairingStarted : ConnectionEventV2

    data class BluetoothConnected(
        val deviceId: String?,
    ) : ConnectionEventV2

    object BluetoothDisconnected : ConnectionEventV2

    object ConnectDeviceFailed : ConnectionEventV2

    object DeviceNotFound : ConnectionEventV2

    data class Plugged(
        val phoneType: Int,
        val wifiState: Int?,
    ) : ConnectionEventV2

    data class RawPhaseReceived(
        val value: Int,
    ) : ConnectionEventV2

    data class FirstVideoFrame(
        val width: Int,
        val height: Int,
    ) : ConnectionEventV2

    object Unplugged : ConnectionEventV2
}
