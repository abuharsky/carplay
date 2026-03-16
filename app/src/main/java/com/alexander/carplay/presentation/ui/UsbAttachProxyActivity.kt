package com.alexander.carplay.presentation.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.alexander.carplay.platform.service.DongleService

/**
 * USB default-handler entry point. It wakes the foreground service without
 * surfacing the main UI, so adapter reconnects do not yank the user out of
 * whatever app they are currently using.
 */
class UsbAttachProxyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startDongleService()
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startDongleService()
        finish()
        overridePendingTransition(0, 0)
    }

    private fun startDongleService() {
        val serviceIntent = Intent(this, DongleService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }
}
