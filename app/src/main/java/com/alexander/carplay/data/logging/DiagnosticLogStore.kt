package com.alexander.carplay.data.logging

import android.content.Context
import android.util.Log
import com.alexander.carplay.data.files.SharedDownloadsMirror
import com.alexander.carplay.domain.model.DiagnosticLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DiagnosticLogStore(
    context: Context,
) {
    companion object {
        const val TAG = "CarPlayDiag"
        private const val MAX_LOGS = 50
        private val FILE_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("HH:mm:ss.SSS", Locale.US)
            .withZone(ZoneId.systemDefault())
    }

    private val sharedDownloadsMirror = SharedDownloadsMirror(context)
    private val _logs = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val logs: StateFlow<List<DiagnosticLogEntry>> = _logs.asStateFlow()

    fun info(source: String, message: String) {
        log(source, message, null)
    }

    fun error(
        source: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        log(source, message, throwable, error = true)
    }

    private fun log(
        source: String,
        message: String,
        throwable: Throwable?,
        error: Boolean = false,
    ) {
        if (error) {
            Log.e(TAG, "[$source] $message", throwable)
        } else {
            Log.d(TAG, "[$source] $message")
        }

        val timestamp = System.currentTimeMillis()
        sharedDownloadsMirror.appendLog(
            buildString {
                append(FILE_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestamp)))
                append(" > [")
                append(source)
                append("] ")
                append(message)
                throwable?.message?.takeIf { it.isNotBlank() }?.let {
                    append(" | ")
                    append(it)
                }
                append('\n')
            },
        )

        val entryMessage = buildString {
            append(message)
            throwable?.message?.takeIf { it.isNotBlank() }?.let {
                append(" | ")
                append(it)
            }
        }

        val entry = DiagnosticLogEntry(
            timestampMillis = timestamp,
            source = source,
            message = entryMessage,
        )

        _logs.value = (_logs.value + entry).takeLast(MAX_LOGS)
    }
}
