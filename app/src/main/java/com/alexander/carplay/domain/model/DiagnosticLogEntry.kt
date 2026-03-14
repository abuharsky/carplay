package com.alexander.carplay.domain.model

data class DiagnosticLogEntry(
    val timestampMillis: Long,
    val source: String,
    val message: String,
)

