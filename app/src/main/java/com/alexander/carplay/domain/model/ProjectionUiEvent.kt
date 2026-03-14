package com.alexander.carplay.domain.model

sealed interface ProjectionUiEvent {
    data object OpenSettings : ProjectionUiEvent
}
