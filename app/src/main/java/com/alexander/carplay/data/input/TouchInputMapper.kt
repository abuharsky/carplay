package com.alexander.carplay.data.input

import android.view.MotionEvent
import com.alexander.carplay.domain.model.ProjectionTouchAction
import com.alexander.carplay.domain.model.TouchContact
import kotlin.math.abs

class TouchInputMapper {
    companion object {
        private const val MOVE_THRESHOLD = 0.003f
    }

    private data class ActiveTouch(
        val pointerId: Int,
        var x: Float,
        var y: Float,
        var action: ProjectionTouchAction,
    )

    private val activeTouches = linkedMapOf<Int, ActiveTouch>()

    fun map(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ): List<TouchContact>? {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return null

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            -> handlePointerDown(event, surfaceWidth, surfaceHeight)

            MotionEvent.ACTION_MOVE -> handleMove(event, surfaceWidth, surfaceHeight)

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            -> handlePointerUp(event, surfaceWidth, surfaceHeight)

            MotionEvent.ACTION_CANCEL -> cancelTouches()
            else -> null
        }
    }

    private fun handlePointerDown(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ): List<TouchContact> {
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)
        val x = normalize(event.getX(actionIndex), surfaceWidth)
        val y = normalize(event.getY(actionIndex), surfaceHeight)

        activeTouches[pointerId] = ActiveTouch(
            pointerId = pointerId,
            x = x,
            y = y,
            action = ProjectionTouchAction.DOWN,
        )

        activeTouches.values
            .filter { it.pointerId != pointerId && it.action != ProjectionTouchAction.UP }
            .forEach { it.action = ProjectionTouchAction.MOVE }

        return snapshotAndAdvance()
    }

    private fun handleMove(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ): List<TouchContact>? {
        var hasMeaningfulMove = false

        repeat(event.pointerCount) { index ->
            val pointerId = event.getPointerId(index)
            val x = normalize(event.getX(index), surfaceWidth)
            val y = normalize(event.getY(index), surfaceHeight)
            val current = activeTouches[pointerId]

            if (current == null) {
                activeTouches[pointerId] = ActiveTouch(
                    pointerId = pointerId,
                    x = x,
                    y = y,
                    action = ProjectionTouchAction.DOWN,
                )
                hasMeaningfulMove = true
            } else {
                val dx = abs(current.x - x)
                val dy = abs(current.y - y)
                if (dx >= MOVE_THRESHOLD || dy >= MOVE_THRESHOLD) {
                    current.x = x
                    current.y = y
                    current.action = ProjectionTouchAction.MOVE
                    hasMeaningfulMove = true
                }
            }
        }

        return if (hasMeaningfulMove) snapshotAndAdvance() else null
    }

    private fun handlePointerUp(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ): List<TouchContact> {
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)
        val x = normalize(event.getX(actionIndex), surfaceWidth)
        val y = normalize(event.getY(actionIndex), surfaceHeight)

        val current = activeTouches[pointerId]
        if (current != null) {
            current.x = x
            current.y = y
            current.action = ProjectionTouchAction.UP
        } else {
            activeTouches[pointerId] = ActiveTouch(
                pointerId = pointerId,
                x = x,
                y = y,
                action = ProjectionTouchAction.UP,
            )
        }

        return snapshotAndAdvance()
    }

    private fun cancelTouches(): List<TouchContact>? {
        if (activeTouches.isEmpty()) return null
        activeTouches.values.forEach { it.action = ProjectionTouchAction.UP }
        return snapshotAndAdvance()
    }

    private fun snapshotAndAdvance(): List<TouchContact> {
        val snapshot = activeTouches.values.mapIndexed { index, touch ->
            TouchContact(
                x = touch.x,
                y = touch.y,
                action = touch.action,
                id = index,
            )
        }

        activeTouches.entries.removeIf { it.value.action == ProjectionTouchAction.UP }
        activeTouches.values.forEach { active ->
            if (active.action == ProjectionTouchAction.DOWN) {
                active.action = ProjectionTouchAction.MOVE
            }
        }
        return snapshot
    }

    private fun normalize(
        value: Float,
        max: Int,
    ): Float = (value / max.toFloat()).coerceIn(0f, 1f)
}
