package com.alexander.carplay.data.executor

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

class CarPlayExecutors {
    private val threadIndex = AtomicInteger(1)

    val usbRead: ExecutorService = Executors.newSingleThreadExecutor(namedFactory("usb-read"))
    val usbWrite: ExecutorService = Executors.newSingleThreadExecutor(namedFactory("usb-write"))
    val codecInput: ExecutorService = Executors.newSingleThreadExecutor(namedFactory("codec-input"))
    val codecOutput: ExecutorService = Executors.newSingleThreadExecutor(namedFactory("codec-output"))
    val session: ExecutorService = Executors.newSingleThreadExecutor(namedFactory("session"))
    val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(namedFactory("scheduler"))
    val mainThread: Executor = Executor { runnable -> Handler(Looper.getMainLooper()).post(runnable) }

    fun shutdown() {
        usbRead.shutdownNow()
        usbWrite.shutdownNow()
        codecInput.shutdownNow()
        codecOutput.shutdownNow()
        session.shutdownNow()
        scheduler.shutdownNow()
    }

    private fun namedFactory(prefix: String): ThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, "carplay-$prefix-${threadIndex.getAndIncrement()}").apply {
            isDaemon = true
        }
    }
}

