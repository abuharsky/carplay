package com.alexander.carplay.platform.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.alexander.carplay.data.protocol.Cpc200Protocol

class ServiceStartupReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> if (hasKnownAdapter(context)) {
                val serviceIntent = Intent(context, DongleService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }

    private fun hasKnownAdapter(context: Context): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return usbManager.deviceList.values.any { device ->
            device.vendorId == Cpc200Protocol.Vendor.ID &&
                (device.productId == Cpc200Protocol.Vendor.PID_PRIMARY ||
                    device.productId == Cpc200Protocol.Vendor.PID_SECONDARY)
        }
    }
}
