package com.alexander.carplay.domain.model

enum class ProjectionTouchAction(
    val protocolValue: Int,
) {
    UP(0),
    DOWN(1),
    MOVE(2),
}

data class TouchContact(
    val x: Float,
    val y: Float,
    val action: ProjectionTouchAction,
    val id: Int,
)

