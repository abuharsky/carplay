package com.alexander.carplay.data.protocol

import android.content.Context

data class ProjectionSessionConfig(
    val androidWorkMode: Boolean = false,
    val width: Int,
    val height: Int,
    val fps: Int = 60,
    val dpi: Int = 160,
    val format: Int = 5,
    val packetMax: Int = 49_152,
    val iBoxVersion: Int = 2,
    val phoneWorkMode: Int = 2,
    val nightMode: Boolean = false,
    val handDriveMode: Int = 0,
    val chargeModeEnabled: Boolean = true,
    val boxName: String = "Carlink",
    val mediaDelay: Int = 300,
    val audioTransferOn: Boolean = true,
    val wifi5g: Boolean = true,
    val useBoxMic: Boolean = true,
    val screenPhysicalWidthMm: Int = 250,
    val screenPhysicalHeightMm: Int = 100,
) {
    companion object {
        private const val DEFAULT_STREAM_WIDTH = 1920
        private const val DEFAULT_STREAM_HEIGHT = 720
        private const val DEFAULT_STREAM_FPS = 60

        fun fromContext(@Suppress("UNUSED_PARAMETER") context: Context): ProjectionSessionConfig {
            return ProjectionSessionConfig(
                androidWorkMode = false,
                width = DEFAULT_STREAM_WIDTH,
                height = DEFAULT_STREAM_HEIGHT,
                fps = DEFAULT_STREAM_FPS,
                audioTransferOn = true,
                useBoxMic = true,
            )
        }
    }
}
