package com.alexander.carplay.domain.model

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Calculates optimal layout when video + climate bar must share a screen
 * that is not tall enough for both at full size.
 *
 * Three mechanisms absorb the height deficit:
 * 1. Climate bar reduction — bar slightly shorter than ideal 54 dp (target 50 dp)
 * 2. Video crop — a few pixels hidden from top and bottom via graphicsLayer
 * 3. Video compression — remaining content displayed in slightly less space
 *    (TextureView stretches to fill; ≤ 1.5 % compression is imperceptible)
 */
data class ClimateBarLayoutFit(
    /** Actual climate bar height to render (dp). */
    val climateBarHeightDp: Int,
    /** OPEN height to send to the iPhone (px, already 16-aligned). */
    val videoStreamHeightPx: Int,
    /** Video pixels to hide from top via graphicsLayer scale+clip. */
    val videoClipTopPx: Int = 0,
    /** Video pixels to hide from bottom via graphicsLayer scale+clip. */
    val videoClipBottomPx: Int = 0,
) {
    companion object {
        /** Ideal (full-size) climate bar height in dp. */
        const val IDEAL_HEIGHT_DP = 54
        /** Target bar height when space is tight. */
        private const val TIGHT_BAR_TARGET_DP = 50
        private const val MIN_HEIGHT_DP = 44
        private const val SIZE_ALIGNMENT = 16
        /** Maximum total video crop (top + bottom) in video pixels. */
        private const val MAX_CROP_PX = 16
        /** Maximum acceptable vertical video compression before we fall back. */
        private const val MAX_COMPRESSION = 0.025
        /** Share of the remaining deficit (after bar reduction) absorbed by crop. */
        private const val CROP_SHARE = 0.55f

        // CarPlay uses 960-point logical width; large layout requires ≥ 344 points height.
        private const val CARPLAY_POINTS_WIDTH = 960
        private const val LARGE_LAYOUT_MIN_POINTS_HEIGHT = 344

        /**
         * Minimum OPEN height (px, 16-aligned) for iPhone to render the large
         * CarPlay layout at the given screen width.
         *
         * Formula: alignUp(⌈344 × screenWidth / 960⌉, 16)
         */
        fun largeLayoutThresholdPx(screenWidthPx: Int): Int {
            val scale = screenWidthPx.toDouble() / CARPLAY_POINTS_WIDTH
            val raw = ceil(LARGE_LAYOUT_MIN_POINTS_HEIGHT * scale).toInt()
            return alignUp(raw, SIZE_ALIGNMENT)
        }

        /**
         * Calculate optimal fit parameters for climate-bar-enabled layout.
         *
         * @param screenWidthPx  physical landscape width in pixels
         * @param screenHeightPx physical landscape height in pixels
         * @param density         display density (dp → px factor)
         */
        fun calculate(
            screenWidthPx: Int,
            screenHeightPx: Int,
            density: Float,
        ): ClimateBarLayoutFit {
            val threshold = largeLayoutThresholdPx(screenWidthPx)
            val idealBarPx = (IDEAL_HEIGHT_DP * density).roundToInt()

            // Threshold exceeds screen — large layout impossible regardless.
            if (threshold >= screenHeightPx) {
                return fallback(screenHeightPx, idealBarPx)
            }

            // Plenty of room — no optimisation needed.
            if (threshold + idealBarPx <= screenHeightPx) {
                val openHeight = alignDown(screenHeightPx - idealBarPx, SIZE_ALIGNMENT)
                return ClimateBarLayoutFit(
                    climateBarHeightDp = IDEAL_HEIGHT_DP,
                    videoStreamHeightPx = openHeight,
                )
            }

            // --- Tight fit: force OPEN to threshold for large layout ---
            val deficitPx = threshold + idealBarPx - screenHeightPx

            // 1. Bar reduction (up to IDEAL − TIGHT_TARGET dp)
            val maxBarReductionDp = (IDEAL_HEIGHT_DP - TIGHT_BAR_TARGET_DP)
                .coerceAtMost((deficitPx / density).roundToInt())
            val barDp = (IDEAL_HEIGHT_DP - maxBarReductionDp).coerceIn(MIN_HEIGHT_DP, IDEAL_HEIGHT_DP)
            val barReductionPx = ((IDEAL_HEIGHT_DP - barDp) * density).roundToInt()
            val remaining = deficitPx - barReductionPx

            // 2. Video crop — ~55 % of remaining deficit, split evenly top/bottom
            val cropTotalPx = if (remaining > 0) {
                (remaining * CROP_SHARE).roundToInt().coerceAtMost(MAX_CROP_PX)
            } else {
                0
            }
            val cropTopPx = cropTotalPx / 2
            val cropBottomPx = cropTotalPx - cropTopPx

            // 3. Compression — whatever is left (implicit in the display)
            val visibleContentPx = threshold - cropTotalPx
            val displayAreaPx = screenHeightPx - (idealBarPx - barReductionPx)
            val compression = if (visibleContentPx > 0) {
                1.0 - displayAreaPx.toDouble() / visibleContentPx
            } else {
                0.0
            }

            if (compression > MAX_COMPRESSION) {
                return fallback(screenHeightPx, idealBarPx)
            }

            return ClimateBarLayoutFit(
                climateBarHeightDp = barDp,
                videoStreamHeightPx = threshold,
                videoClipTopPx = cropTopPx,
                videoClipBottomPx = cropBottomPx,
            )
        }

        /** Standard fall-back: full ideal bar, OPEN = screen − bar (aligned). */
        private fun fallback(screenHeightPx: Int, idealBarPx: Int): ClimateBarLayoutFit {
            val openHeight = alignDown(
                (screenHeightPx - idealBarPx).coerceAtLeast(SIZE_ALIGNMENT),
                SIZE_ALIGNMENT,
            )
            return ClimateBarLayoutFit(
                climateBarHeightDp = IDEAL_HEIGHT_DP,
                videoStreamHeightPx = openHeight,
            )
        }

        private fun alignDown(value: Int, alignment: Int): Int {
            return maxOf(alignment, value - (value % alignment))
        }

        private fun alignUp(value: Int, alignment: Int): Int {
            val rem = value % alignment
            return if (rem == 0) value else value + (alignment - rem)
        }
    }
}
