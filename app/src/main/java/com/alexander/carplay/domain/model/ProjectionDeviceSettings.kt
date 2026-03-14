package com.alexander.carplay.domain.model

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
    ALERT("Alerts"),
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

data class ProjectionDeviceSettings(
    val deviceId: String,
    val audioRoute: ProjectionAudioRoute = ProjectionAudioRoute.ADAPTER,
    val micRoute: ProjectionMicRoute = ProjectionMicRoute.ADAPTER,
    val selectedPlayer: ProjectionAudioPlayerType = ProjectionAudioPlayerType.MEDIA,
    val playerSettings: Map<ProjectionAudioPlayerType, ProjectionPlayerAudioSettings> =
        ProjectionAudioPlayerType.entries.associateWith { ProjectionPlayerAudioSettings() },
    val micSettings: ProjectionMicSettings = ProjectionMicSettings(),
) {
    companion object {
        const val DEFAULT_DEVICE_ID = "__default__"

        fun defaults(deviceId: String): ProjectionDeviceSettings = ProjectionDeviceSettings(deviceId = deviceId)
    }
}
