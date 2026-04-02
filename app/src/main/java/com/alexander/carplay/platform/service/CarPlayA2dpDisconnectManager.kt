package com.alexander.carplay.platform.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.alexander.carplay.data.logging.DiagnosticLogStore

/**
 * Disconnects the Bluetooth A2DP profile from all connected devices when CarPlay enters
 * STREAMING state.
 *
 * When Android is connected to iPhone via Bluetooth and CarPlay starts streaming audio,
 * the Android BT stack may attempt to use A2DP to pull audio from iPhone. Disconnecting
 * A2DP at streaming start prevents this interference. Android reconnects A2DP automatically
 * when the session ends.
 *
 * Works on Android 9/10 (API 28-29) via reflection on the hidden BluetoothA2dp.disconnect()
 * method. On Android 12+ this would require BLUETOOTH_PRIVILEGED — not applicable here.
 */
class CarPlayA2dpDisconnectManager(
    context: Context,
    private val logStore: DiagnosticLogStore,
) {
    companion object {
        private const val SOURCE = "A2dpDisconnectFix"
    }

    private val appContext = context.applicationContext

    @Suppress("DEPRECATION")
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private var disconnectSent = false

    fun onStreamingStarted() {
        if (disconnectSent) return
        disconnectSent = true
        val adapter = bluetoothAdapter ?: run {
            logStore.info(SOURCE, "BluetoothAdapter unavailable — skipping A2DP disconnect")
            return
        }
        if (!adapter.isEnabled) {
            logStore.info(SOURCE, "Bluetooth disabled — skipping A2DP disconnect")
            return
        }
        adapter.getProfileProxy(
            appContext,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(
                    profile: Int,
                    proxy: BluetoothProfile,
                ) {
                    try {
                        val devices = proxy.connectedDevices
                        logStore.info(SOURCE, "A2DP proxy connected, devices=${devices.size}")
                        if (devices.isEmpty()) {
                            logStore.info(SOURCE, "No A2DP devices connected — nothing to disconnect")
                            return
                        }
                        val disconnectMethod = runCatching {
                            proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        }.getOrNull()
                        if (disconnectMethod == null) {
                            logStore.info(SOURCE, "BluetoothA2dp.disconnect() method not found via reflection")
                            return
                        }
                        devices.forEach { device ->
                            runCatching {
                                disconnectMethod.invoke(proxy, device)
                                logStore.info(SOURCE, "A2DP disconnect invoked for ${device.address}")
                            }.onFailure { e ->
                                logStore.info(SOURCE, "A2DP disconnect failed for ${device.address}: ${e.message}")
                            }
                        }
                    } finally {
                        adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    logStore.info(SOURCE, "A2DP proxy service disconnected")
                }
            },
            BluetoothProfile.A2DP,
        )
    }

    fun onStreamingStopped() {
        if (disconnectSent) {
            logStore.info(SOURCE, "Streaming stopped — A2DP disconnect guard reset")
        }
        disconnectSent = false
    }
}
