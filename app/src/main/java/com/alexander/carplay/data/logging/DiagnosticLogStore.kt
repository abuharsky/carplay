package com.alexander.carplay.data.logging

import android.util.Log
import com.alexander.carplay.domain.model.DiagnosticLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DiagnosticLogStore {
    companion object {
        const val TAG = "CarPlayDiag"
        private const val MAX_LOGS = 50
    }

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

        val entryMessage = buildString {
            append(message)
            throwable?.message?.takeIf { it.isNotBlank() }?.let {
                append(" | ")
                append(it)
            }
        }

        val entry = DiagnosticLogEntry(
            timestampMillis = System.currentTimeMillis(),
            source = source,
            message = entryMessage,
        )

        _logs.value = (_logs.value + entry).takeLast(MAX_LOGS)
    }
}
