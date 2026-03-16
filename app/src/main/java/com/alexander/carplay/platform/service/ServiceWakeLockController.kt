package com.alexander.carplay.platform.service

import android.content.Context
import android.os.PowerManager
import com.alexander.carplay.data.logging.DiagnosticLogStore

class ServiceWakeLockController(
    context: Context,
    private val logStore: DiagnosticLogStore,
) {
    companion object {
        private const val SOURCE = "Power"
    }

    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:CarPlaySession")
        .apply { setReferenceCounted(false) }

    fun setActive(
        active: Boolean,
        reason: String,
    ) {
        if (active) {
            if (!wakeLock.isHeld) {
                wakeLock.acquire()
                logStore.info(SOURCE, "Partial wake lock acquired: $reason")
            }
        } else if (wakeLock.isHeld) {
            wakeLock.release()
            logStore.info(SOURCE, "Partial wake lock released: $reason")
        }
    }

    fun release(reason: String) {
        if (wakeLock.isHeld) {
            wakeLock.release()
            logStore.info(SOURCE, "Partial wake lock released: $reason")
        }
    }
}
