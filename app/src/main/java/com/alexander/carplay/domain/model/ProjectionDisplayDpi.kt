package com.alexander.carplay.domain.model

object ProjectionDisplayDpi {
    const val DEFAULT: Int = 160
    val supportedValues: List<Int> = listOf(160, 240, 320, 480)

    fun normalize(value: Int): Int {
        return supportedValues.firstOrNull { it == value } ?: DEFAULT
    }
}
