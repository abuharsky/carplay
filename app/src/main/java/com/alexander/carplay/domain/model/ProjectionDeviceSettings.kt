package com.alexander.carplay.domain.model

import kotlin.math.roundToInt

enum class ProjectionAudioRoute {
    ADAPTER,
    CAR_BLUETOOTH,
}

enum class ProjectionMicRoute {
    ADAPTER,
    PHONE,
}

enum class ProjectionAudioPlayerType(
    val title: String,
) {
    MEDIA("Media"),
    NAVI("Navigation"),
    SIRI("Siri"),
    PHONE("Call"),
    ALERT("Alert"),
}

enum class ProjectionEqPreset(
    val bandsDb: List<Float>,
) {
    FLAT(List(10) { 0f }),
    ACOUSTIC(listOf(4f, 3f, 2f, 1f, 0f, 0f, 1f, 2f, 3f, 4f)),
    CLASSICAL(listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 1f, 3f, 4f)),
    DANCE(listOf(5f, 4f, 2f, 0f, 0f, 1f, 3f, 4f, 5f, 5f)),
    ELECTRONIC(listOf(4f, 3f, 1f, 0f, -1f, 1f, 2f, 4f, 5f, 5f)),
    HIP_HOP(listOf(5f, 4f, 2f, 1f, -1f, -1f, 1f, 2f, 3f, 4f)),
    JAZZ(listOf(4f, 3f, 1f, 2f, -1f, -1f, 0f, 2f, 3f, 4f)),
    POP(listOf(-1f, 1f, 3f, 4f, 3f, 1f, -1f, -1f, 0f, 1f)),
    ROCK(listOf(5f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f, 5f)),
    LOUDNESS(listOf(4f, 3f, 2f, 1f, 0f, 0f, 1f, 2f, 3f, 4f)),
    BASS(listOf(6f, 5f, 4f, 3f, 1f, 0f, -1f, -2f, -2f, -2f)),
    VOCAL(listOf(-2f, -1f, 0f, 2f, 4f, 5f, 4f, 2f, 0f, -1f)),
    SPOKEN_WORD(listOf(-4f, -3f, -2f, 1f, 4f, 5f, 5f, 3f, 0f, -1f)),
    TREBLE_BOOSTER(listOf(-4f, -4f, -2f, 0f, 1f, 3f, 5f, 6f, 6f, 6f)),
    BRIGHT(listOf(-2f, -2f, -1f, 0f, 1f, 2f, 4f, 5f, 6f, 6f)),
    CUSTOM(List(10) { 0f });

    companion object {
        fun detect(bandsDb: List<Float>): ProjectionEqPreset {
            return entries.firstOrNull { preset ->
                preset != CUSTOM && preset.bandsDb == bandsDb
            } ?: CUSTOM
        }
    }
}

data class ProjectionPlayerAudioSettings(
    val gainMultiplier: Float = 1f,
    val loudnessBoostPercent: Int = 0,
    val bassBoostPercent: Int = 0,
    val eqPreset: ProjectionEqPreset = ProjectionEqPreset.FLAT,
    val eqBandsDb: List<Float> = List(10) { 0f },
)

data class ProjectionMicSettings(
    val gainMultiplier: Float = 1f,
)

data class ProjectionSeatAutoModeSettings(
    val enabled: Boolean = false,
    val thresholdC: Int,
    val startLevel: Int = 3,
    val durationMinutes: Int = 15,
) {
    companion object {
        fun heatDefaults(): ProjectionSeatAutoModeSettings = ProjectionSeatAutoModeSettings(
            enabled = false,
            thresholdC = 8,
            startLevel = 3,
            durationMinutes = 15,
        )

        fun ventDefaults(): ProjectionSeatAutoModeSettings = ProjectionSeatAutoModeSettings(
            enabled = false,
            thresholdC = 28,
            startLevel = 3,
            durationMinutes = 15,
        )
    }
}

data class ProjectionSeatAutoComfortSettings(
    val heat: ProjectionSeatAutoModeSettings = ProjectionSeatAutoModeSettings.heatDefaults(),
    val vent: ProjectionSeatAutoModeSettings = ProjectionSeatAutoModeSettings.ventDefaults(),
)

data class ProjectionDeviceSettings(
    val deviceId: String,
    val audioRoute: ProjectionAudioRoute = ProjectionAudioRoute.ADAPTER,
    val micRoute: ProjectionMicRoute = ProjectionMicRoute.ADAPTER,
    val selectedPlayer: ProjectionAudioPlayerType = ProjectionAudioPlayerType.MEDIA,
    val playerSettings: Map<ProjectionAudioPlayerType, ProjectionPlayerAudioSettings> =
        ProjectionAudioPlayerType.entries.associateWith { ProjectionPlayerAudioSettings() },
    val micSettings: ProjectionMicSettings = ProjectionMicSettings(),
    val driverSeatAutoComfort: ProjectionSeatAutoComfortSettings = ProjectionSeatAutoComfortSettings(),
    val passengerSeatAutoComfort: ProjectionSeatAutoComfortSettings = ProjectionSeatAutoComfortSettings(),
) {
    companion object {
        const val DEFAULT_DEVICE_ID = "__default__"

        fun defaults(deviceId: String): ProjectionDeviceSettings = ProjectionDeviceSettings(deviceId = deviceId)
    }
}

data class ProjectionSeatAutoDecayStage(
    val minutes: Int,
    val level: Int,
)

fun buildProjectionSeatAutoDecayStages(
    startLevel: Int,
    totalMinutes: Int,
): List<ProjectionSeatAutoDecayStage> {
    val total = totalMinutes.coerceAtLeast(1)
    val normalizedLevel = startLevel.coerceIn(1, 3)
    val activeLevels = when (normalizedLevel) {
        3 -> listOf(3, 2, 1)
        2 -> listOf(2, 1)
        else -> listOf(1)
    }

    if (total <= activeLevels.size) {
        return activeLevels.take(total).map { level ->
            ProjectionSeatAutoDecayStage(minutes = 1, level = level)
        }
    }

    return when (normalizedLevel) {
        3 -> {
            val level3 = ((total * 0.60f).roundToInt()).coerceIn(1, total - 2)
            val level2 = ((total * 0.25f).roundToInt()).coerceIn(1, total - level3 - 1)
            val level1 = (total - level3 - level2).coerceAtLeast(1)
            listOf(
                ProjectionSeatAutoDecayStage(minutes = level3, level = 3),
                ProjectionSeatAutoDecayStage(minutes = level2, level = 2),
                ProjectionSeatAutoDecayStage(minutes = level1, level = 1),
            )
        }

        2 -> {
            val level2 = ((total * 0.75f).roundToInt()).coerceIn(1, total - 1)
            val level1 = (total - level2).coerceAtLeast(1)
            listOf(
                ProjectionSeatAutoDecayStage(minutes = level2, level = 2),
                ProjectionSeatAutoDecayStage(minutes = level1, level = 1),
            )
        }

        else -> listOf(ProjectionSeatAutoDecayStage(minutes = total, level = 1))
    }
}

fun resolveProjectionSeatAutoLevel(
    startLevel: Int,
    totalMinutes: Int,
    elapsedMillis: Long,
): Int? {
    val elapsed = elapsedMillis.coerceAtLeast(0L)
    var elapsedBoundary = 0L
    buildProjectionSeatAutoDecayStages(startLevel = startLevel, totalMinutes = totalMinutes).forEach { stage ->
        elapsedBoundary += stage.minutes * 60_000L
        if (elapsed < elapsedBoundary) {
            return stage.level
        }
    }
    return null
}

fun nextProjectionSeatAutoTransitionDelayMillis(
    startLevel: Int,
    totalMinutes: Int,
    elapsedMillis: Long,
): Long? {
    val elapsed = elapsedMillis.coerceAtLeast(0L)
    var elapsedBoundary = 0L
    buildProjectionSeatAutoDecayStages(startLevel = startLevel, totalMinutes = totalMinutes).forEach { stage ->
        elapsedBoundary += stage.minutes * 60_000L
        if (elapsed < elapsedBoundary) {
            return (elapsedBoundary - elapsed).coerceAtLeast(1L)
        }
    }
    return null
}
