package com.alexander.carplay.data.protocol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.WindowManager
import androidx.appcompat.content.res.AppCompatResources
import com.alexander.carplay.R
import java.io.ByteArrayOutputStream

data class NaviScreenInfo(
    val naviW: Int,
    val naviH: Int,
    val fps: Int,
    val iBoxNaviType: Int = 0,
)

data class ExtendedBoxSettingsConfig(
    val enabled: Boolean = true,
    val gnssCapability: Boolean = true,
) {
    fun isActive(): Boolean = enabled

    companion object {
        fun template(
        ): ExtendedBoxSettingsConfig = ExtendedBoxSettingsConfig(
            enabled = false,
            gnssCapability = true,
        )
    }
}

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
    val useBluetoothAudio: Boolean = false,
    val wifi5g: Boolean = true,
    val useAdapterMic: Boolean = true,
    val screenPhysicalWidthMm: Int = 250,
    val screenPhysicalHeightMm: Int = 100,
    val oemBranding: OemBrandingConfig = OemBrandingConfig(),
    val extendedBoxSettings: ExtendedBoxSettingsConfig = ExtendedBoxSettingsConfig(),
) {
    companion object {
        private const val DEFAULT_STREAM_FPS = 60
        private const val DEFAULT_DPI = 160
        private const val SIZE_ALIGNMENT = 16
        private const val DEFAULT_BOX_NAME = "Carlink"

        fun fromContext(context: Context): ProjectionSessionConfig {
            val (displayWidth, displayHeight) = resolveLandscapeDisplaySize(context)
            val boxName = DEFAULT_BOX_NAME
            return ProjectionSessionConfig(
                androidWorkMode = false,
                width = displayWidth,
                height = displayHeight,
                fps = DEFAULT_STREAM_FPS,
                dpi = DEFAULT_DPI,
                boxName = boxName,
                useBluetoothAudio = false,
                useAdapterMic = true,
                oemBranding = buildDefaultOemBranding(context, boxName),
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

        private fun buildDefaultOemBranding(
            context: Context,
            boxName: String,
        ): OemBrandingConfig {
            return OemBrandingConfig(
                visible = true,
                label = "Настр. CarPlay",
                name = boxName,
                model = "Magic-Car-Link-1.00",
                oemIconPng = renderBrandingPng(context, 256),
                icon120Png = renderBrandingPng(context, 120),
                icon180Png = renderBrandingPng(context, 180),
                icon256Png = renderBrandingPng(context, 256),
            )
        }

        private fun renderBrandingPng(
            context: Context,
            sizePx: Int,
        ): ByteArray {
            val background = requireNotNull(AppCompatResources.getDrawable(context, R.drawable.ic_launcher_background))
            val foreground = requireNotNull(AppCompatResources.getDrawable(context, R.drawable.ic_oem_icon_foreground))

            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            background.setBounds(0, 0, sizePx, sizePx)
            background.draw(canvas)
            foreground.setBounds(0, 0, sizePx, sizePx)
            foreground.draw(canvas)

            return ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        }
    }
}

data class OemBrandingConfig(
    val visible: Boolean = true,
    val label: String = "Настр. CarPlay",
    val name: String = "Carlink",
    val model: String = "Magic-Car-Link-1.00",
    val oemIconPng: ByteArray? = null,
    val icon120Png: ByteArray? = null,
    val icon180Png: ByteArray? = null,
    val icon256Png: ByteArray? = null,
)
