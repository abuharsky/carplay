package com.alexander.carplay.domain.model

import android.content.Context
import com.alexander.carplay.CarPlayApp

data class ProjectionDebugHeadUnitSpec(
    val containerScale: Float,
    /** Full simulated screen width (px). */
    val totalWidth: Int,
    /** Full simulated screen height (px). */
    val totalHeight: Int,
    /** Display density to use for layout calculations (simulated, not host). */
    val simulatedDensity: Float,
)

/**
 * Returns a simulated head-unit display spec when running on a non-automotive
 * device (e.g. Samsung phone for development). Returns null on a real car.
 */
object ProjectionDebugHeadUnitMode {
    private const val CONTAINER_SCALE = 0.6f
    private const val SIMULATED_WIDTH = 1920
    private const val SIMULATED_HEIGHT = 720
    private const val SIMULATED_DENSITY = 1.0f

    fun resolve(context: Context): ProjectionDebugHeadUnitSpec? {
        val appHasAndroidCarRuntime = runCatching {
            (context.applicationContext as? CarPlayApp)?.appContainer?.hasAndroidCarRuntime
        }.getOrNull()

        return if (appHasAndroidCarRuntime == true || (appHasAndroidCarRuntime == null && hasAndroidCarRuntime())) {
            null
        } else {
            ProjectionDebugHeadUnitSpec(
                containerScale = CONTAINER_SCALE,
                totalWidth = SIMULATED_WIDTH,
                totalHeight = SIMULATED_HEIGHT,
                simulatedDensity = SIMULATED_DENSITY,
            )
        }
    }

    private fun hasAndroidCarRuntime(): Boolean {
        return runCatching {
            Class.forName("android.car.Car")
            Class.forName("android.car.hardware.hvac.CarHvacManager\$CarHvacEventCallback")
            true
        }.getOrDefault(false)
    }
}
