package com.alexander.carplay.data.logging

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.util.Locale

object ProcessDiagnostics {
    private const val SOURCE = "Process"
    private const val PREFS_NAME = "process_diagnostics"
    private const val KEY_PREVIOUS_SESSION_ID = "previous_session_id"
    private const val KEY_PREVIOUS_START_WALL_MS = "previous_start_wall_ms"
    private const val KEY_PREVIOUS_START_ELAPSED_MS = "previous_start_elapsed_ms"
    private const val KEY_PREVIOUS_PID = "previous_pid"
    private const val KEY_PREVIOUS_PROCESS_NAME = "previous_process_name"
    private const val KEY_PREVIOUS_EXIT_RECORDED = "previous_exit_recorded"
    private const val KEY_PREVIOUS_EXIT_REASON = "previous_exit_reason"
    private const val KEY_PREVIOUS_EXIT_WALL_MS = "previous_exit_wall_ms"

    @Volatile
    private var initialized = false

    @Volatile
    private var exitRecorded = false

    private var sessionId: String = "unknown"
    private var processName: String = "unknown"
    private var processPid: Int = -1
    private var processStartWallMs: Long = 0L
    private var processStartElapsedMs: Long = 0L

    fun initialize(
        context: Context,
        logStore: DiagnosticLogStore,
    ) {
        if (initialized) return

        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val previousSessionId = prefs.getString(KEY_PREVIOUS_SESSION_ID, null)
        val previousStartWallMs = prefs.getLong(KEY_PREVIOUS_START_WALL_MS, 0L)
        val previousStartElapsedMs = prefs.getLong(KEY_PREVIOUS_START_ELAPSED_MS, 0L)
        val previousPid = prefs.getInt(KEY_PREVIOUS_PID, -1)
        val previousProcessName = prefs.getString(KEY_PREVIOUS_PROCESS_NAME, null)
        val previousExitRecorded = prefs.getBoolean(KEY_PREVIOUS_EXIT_RECORDED, true)
        val previousExitReason = prefs.getString(KEY_PREVIOUS_EXIT_REASON, null)
        val previousExitWallMs = prefs.getLong(KEY_PREVIOUS_EXIT_WALL_MS, 0L)

        processPid = Process.myPid()
        processName = resolveProcessName(appContext)
        processStartWallMs = System.currentTimeMillis()
        processStartElapsedMs = SystemClock.elapsedRealtime()
        sessionId = buildString {
            append(processStartWallMs)
            append("-")
            append(processPid)
        }

        prefs.edit()
            .putString(KEY_PREVIOUS_SESSION_ID, sessionId)
            .putLong(KEY_PREVIOUS_START_WALL_MS, processStartWallMs)
            .putLong(KEY_PREVIOUS_START_ELAPSED_MS, processStartElapsedMs)
            .putInt(KEY_PREVIOUS_PID, processPid)
            .putString(KEY_PREVIOUS_PROCESS_NAME, processName)
            .putBoolean(KEY_PREVIOUS_EXIT_RECORDED, false)
            .remove(KEY_PREVIOUS_EXIT_REASON)
            .remove(KEY_PREVIOUS_EXIT_WALL_MS)
            .apply()

        initialized = true
        exitRecorded = false

        if (previousSessionId != null && !previousExitRecorded) {
            logStore.info(
                SOURCE,
                buildString {
                    append("Previous process ended without recorded shutdown")
                    append(": session=").append(previousSessionId)
                    append(" pid=").append(previousPid)
                    append(" process=").append(previousProcessName ?: "unknown")
                    if (previousStartWallMs > 0L) {
                        append(" startedAt=").append(previousStartWallMs)
                    }
                    if (previousStartElapsedMs > 0L) {
                        append(" startedElapsed=").append(previousStartElapsedMs)
                    }
                    previousExitReason?.let { reason ->
                        append(" lastExitReason=").append(reason)
                    }
                    if (previousExitWallMs > 0L) {
                        append(" lastExitAt=").append(previousExitWallMs)
                    }
                },
            )
        }

        logStore.info(
            SOURCE,
            "Process started: ${describeCurrentProcess()} uptimeMs=${SystemClock.uptimeMillis()}",
        )
        installUncaughtExceptionHandler(logStore, appContext)
    }

    fun recordExpectedExit(
        context: Context,
        logStore: DiagnosticLogStore,
        reason: String,
    ) {
        if (!initialized || exitRecorded) return
        exitRecorded = true
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PREVIOUS_EXIT_RECORDED, true)
            .putString(KEY_PREVIOUS_EXIT_REASON, reason)
            .putLong(KEY_PREVIOUS_EXIT_WALL_MS, System.currentTimeMillis())
            .apply()
        logStore.info(SOURCE, "Recorded expected exit: $reason | ${describeCurrentProcess()}")
    }

    fun recordAbnormalExit(
        context: Context,
        logStore: DiagnosticLogStore,
        reason: String,
    ) {
        if (!initialized) return
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PREVIOUS_EXIT_RECORDED, false)
            .putString(KEY_PREVIOUS_EXIT_REASON, reason)
            .putLong(KEY_PREVIOUS_EXIT_WALL_MS, System.currentTimeMillis())
            .apply()
        logStore.info(SOURCE, "Recorded abnormal exit marker: $reason | ${describeCurrentProcess()}")
    }

    fun logTrimMemory(
        logStore: DiagnosticLogStore,
        source: String,
        level: Int,
    ) {
        logStore.info(
            source,
            "onTrimMemory level=${describeTrimLevel(level)} ($level) | ${describeCurrentProcess()}",
        )
    }

    fun logLowMemory(
        logStore: DiagnosticLogStore,
        source: String,
    ) {
        logStore.info(source, "onLowMemory | ${describeCurrentProcess()}")
    }

    fun describeCurrentProcess(): String {
        return "session=$sessionId pid=$processPid process=$processName"
    }

    private fun installUncaughtExceptionHandler(
        logStore: DiagnosticLogStore,
        context: Context,
    ) {
        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (currentHandler is ProcessLoggingExceptionHandler) return

        Thread.setDefaultUncaughtExceptionHandler(
            ProcessLoggingExceptionHandler(
                appContext = context.applicationContext,
                logStore = logStore,
                delegate = currentHandler,
            ),
        )
    }

    private fun resolveProcessName(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ApplicationProcessNameHolder.get() ?: context.packageName
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val processPid = Process.myPid()
        val matchedProcess = activityManager
            ?.runningAppProcesses
            ?.firstOrNull { it.pid == processPid }
            ?.processName
        return matchedProcess ?: context.packageName
    }

    private fun describeTrimLevel(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "TRIM_MEMORY_COMPLETE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "TRIM_MEMORY_RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "TRIM_MEMORY_RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "TRIM_MEMORY_RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "TRIM_MEMORY_BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "TRIM_MEMORY_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "TRIM_MEMORY_UI_HIDDEN"
        else -> String.format(Locale.US, "TRIM_%d", level)
    }

    private class ProcessLoggingExceptionHandler(
        private val appContext: Context,
        private val logStore: DiagnosticLogStore,
        private val delegate: Thread.UncaughtExceptionHandler?,
        ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(
            thread: Thread,
            throwable: Throwable,
        ) {
            recordAbnormalExit(appContext, logStore, "uncaught:${throwable::class.java.simpleName}")
            logStore.error(
                SOURCE,
                "Uncaught exception on thread=${thread.name} | ${describeCurrentProcess()}",
                throwable,
            )
            delegate?.uncaughtException(thread, throwable)
        }
    }

    private object ApplicationProcessNameHolder {
        fun get(): String? = try {
            android.app.Application.getProcessName()
        } catch (_: Throwable) {
            null
        }
    }
}
