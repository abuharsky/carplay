package com.alexander.carplay.data.protocol

import android.content.Context
import android.graphics.Point
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.WindowManager

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
        private const val DEFAULT_STREAM_FPS = 60
        private const val SIZE_ALIGNMENT = 16

        fun fromContext(context: Context): ProjectionSessionConfig {
            val (displayWidth, displayHeight) = resolveLandscapeDisplaySize(context)
            return ProjectionSessionConfig(
                androidWorkMode = false,
                width = displayWidth,
                height = displayHeight,
                fps = DEFAULT_STREAM_FPS,
                audioTransferOn = true,
                useBoxMic = true,
            )
        }

        private fun resolveLandscapeDisplaySize(context: Context): Pair<Int, Int> {
            val windowManager = context.getSystemService(WindowManager::class.java)
            val (rawWidth, rawHeight) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager?.maximumWindowMetrics?.bounds
                val metrics = context.resources.displayMetrics
                (bounds?.width() ?: metrics.widthPixels) to (bounds?.height() ?: metrics.heightPixels)
            } else {
                val point = Point()
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay?.getRealSize(point)
                val metrics = context.resources.displayMetrics
                val width = if (point.x > 0) point.x else metrics.widthPixels
                val height = if (point.y > 0) point.y else metrics.heightPixels
                width to height
            }

            val landscapeWidth = maxOf(rawWidth, rawHeight)
            val landscapeHeight = minOf(rawWidth, rawHeight)
            return clampToDecoderSupport(landscapeWidth, landscapeHeight, DEFAULT_STREAM_FPS)
        }

        private fun clampToDecoderSupport(
            requestedWidth: Int,
            requestedHeight: Int,
            fps: Int,
        ): Pair<Int, Int> {
            val capabilities = findAvcVideoCapabilities()
            if (capabilities == null) {
                return alignDown(requestedWidth, SIZE_ALIGNMENT) to alignDown(requestedHeight, SIZE_ALIGNMENT)
            }

            val widthAlignment = maxOf(SIZE_ALIGNMENT, capabilities.widthAlignment)
            val heightAlignment = maxOf(SIZE_ALIGNMENT, capabilities.heightAlignment)
            val alignedWidth = alignDown(requestedWidth, widthAlignment)
            val alignedHeight = alignDown(requestedHeight, heightAlignment)

            if (capabilities.areSizeAndRateSupported(alignedWidth, alignedHeight, fps.toDouble())) {
                return alignedWidth to alignedHeight
            }

            val maxWidth = capabilities.supportedWidths.upper
            val maxHeight = capabilities.supportedHeights.upper
            val initialScale = minOf(
                1.0,
                maxWidth.toDouble() / alignedWidth.toDouble(),
                maxHeight.toDouble() / alignedHeight.toDouble(),
            )

            val aspectRatio = alignedWidth.toDouble() / alignedHeight.toDouble()
            var candidateWidth = alignDown((alignedWidth * initialScale).toInt(), widthAlignment)
            var candidateHeight = alignDown((alignedHeight * initialScale).toInt(), heightAlignment)

            while (candidateWidth >= widthAlignment && candidateHeight >= heightAlignment) {
                if (capabilities.areSizeAndRateSupported(candidateWidth, candidateHeight, fps.toDouble())) {
                    return candidateWidth to candidateHeight
                }

                val nextWidth = alignDown(candidateWidth - widthAlignment, widthAlignment)
                if (nextWidth < widthAlignment) break
                candidateWidth = nextWidth
                candidateHeight = alignDown((candidateWidth / aspectRatio).toInt(), heightAlignment)
                    .coerceAtLeast(heightAlignment)
            }

            return alignedWidth to alignedHeight
        }

        private fun findAvcVideoCapabilities(): MediaCodecInfo.VideoCapabilities? {
            return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { codecInfo ->
                    !codecInfo.isEncoder && codecInfo.supportedTypes.any {
                        it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
                    }
                }
                ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                ?.videoCapabilities
        }

        private fun alignDown(value: Int, alignment: Int): Int {
            return maxOf(alignment, value - (value % alignment))
        }
    }
}
