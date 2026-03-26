package com.alexander.carplay.domain.model

object ProjectionCarPlaySafeAreaBottomDp {
    const val DEFAULT: Int = 0
    val supportedValues: List<Int> = (0..96 step 4).toList()

    fun normalize(value: Int): Int {
        return supportedValues.firstOrNull { it == value } ?: DEFAULT
    }
}
