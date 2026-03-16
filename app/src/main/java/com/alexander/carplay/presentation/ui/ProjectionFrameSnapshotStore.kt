package com.alexander.carplay.presentation.ui

import android.graphics.Bitmap
import kotlin.math.abs

internal data class ProjectionFrameSnapshot(
    val bitmap: Bitmap,
    val timestampMillis: Long,
    val width: Int,
    val height: Int,
)

internal object ProjectionFrameSnapshotStore {
    private const val DEFAULT_MAX_AGE_MS = 15_000L
    private const val MAX_ASPECT_DELTA = 0.08f

    private val lock = Any()
    private var snapshot: ProjectionFrameSnapshot? = null

    fun save(bitmap: Bitmap) {
        if (bitmap.width <= 1 || bitmap.height <= 1) return
        synchronized(lock) {
            snapshot = ProjectionFrameSnapshot(
                bitmap = bitmap,
                timestampMillis = System.currentTimeMillis(),
                width = bitmap.width,
                height = bitmap.height,
            )
        }
    }

    fun clear() {
        synchronized(lock) {
            snapshot = null
        }
    }

    fun peekFresh(
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MS,
    ): ProjectionFrameSnapshot? = synchronized(lock) {
        val current = snapshot ?: return null
        if (current.bitmap.isRecycled) return null
        if (System.currentTimeMillis() - current.timestampMillis > maxAgeMillis) {
            return null
        }
        if (targetWidth != null && targetHeight != null && targetWidth > 0 && targetHeight > 0) {
            val currentAspect = current.width.toFloat() / current.height.toFloat()
            val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()
            if (abs(currentAspect - targetAspect) > MAX_ASPECT_DELTA) {
                return null
            }
        }
        current
    }
}
