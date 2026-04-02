package com.alexander.carplay.domain.model

data class NowPlayingSnapshot(
    val songName: String?,
    val artistName: String?,
    val albumName: String?,
    val playStatus: Int?,
    val durationSeconds: Int?,
    val playTimeSeconds: Int?,
)
