package com.alexander.carplay.platform.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.MotionEvent
import android.view.Surface
import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.alexander.carplay.data.session.DongleSessionManager
import com.alexander.carplay.data.usb.AndroidUsbTransport
import com.alexander.carplay.domain.model.DiagnosticLogEntry
import com.alexander.carplay.domain.model.ProjectionSessionSnapshot
import kotlinx.coroutines.flow.StateFlow

class DongleService : Service() {
    companion object {
        const val ACTION_START_USB = "com.alexander.carplay.action.START_USB"
        const val ACTION_START_REPLAY = "com.alexander.carplay.action.START_REPLAY"
        const val EXTRA_CAPTURE_PATH = "capture_path"
    }

    private lateinit var logStore: DiagnosticLogStore
    private lateinit var sessionManager: DongleSessionManager
    private val binder = ServiceBinder()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent,
        ) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> sessionManager.onUsbAttached(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> sessionManager.onUsbDetached(device)
                AndroidUsbTransport.ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    sessionManager.onUsbPermissionResult(device, granted)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        logStore = (application as com.alexander.carplay.CarPlayApp).appContainer.logStore
        sessionManager = DongleSessionManager(this, logStore)

        ServiceNotificationFactory.ensureChannel(this)
        val notification = ServiceNotificationFactory.create(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Bootstrapping as media playback avoids Android 14 connected-device
            // validation before the app obtains the USB device permission token.
            startForeground(
                ServiceNotificationFactory.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(
                ServiceNotificationFactory.NOTIFICATION_ID,
                notification,
            )
        }
        registerUsbReceiver()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START_REPLAY -> {
                val capturePath = intent.getStringExtra(EXTRA_CAPTURE_PATH)
                if (!capturePath.isNullOrBlank()) {
                    sessionManager.startReplay(capturePath)
                } else {
                    sessionManager.start()
                }
            }

            ACTION_START_USB -> sessionManager.startUsb()
            else -> sessionManager.ensureStarted()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        sessionManager.shutdown()
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(AndroidUsbTransport.ACTION_USB_PERMISSION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
    }

    inner class ServiceBinder : Binder() {
        val state: StateFlow<ProjectionSessionSnapshot>
            get() = sessionManager.state

        val logs: StateFlow<List<DiagnosticLogEntry>>
            get() = logStore.logs

        fun ensureStarted() {
            sessionManager.ensureStarted()
        }

        fun startUsb() {
            sessionManager.startUsb()
        }

        fun startReplay(capturePath: String) {
            sessionManager.startReplay(capturePath)
        }

        fun requestReconnect() {
            sessionManager.requestReconnect()
        }

        fun attachSurface(surface: Surface) {
            sessionManager.attachSurface(surface)
        }

        fun detachSurface() {
            sessionManager.detachSurface()
        }

        fun sendMotionEvent(
            event: MotionEvent,
            surfaceWidth: Int,
            surfaceHeight: Int,
        ) {
            sessionManager.sendMotionEvent(event, surfaceWidth, surfaceHeight)
        }
    }
}
