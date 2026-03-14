package com.alexander.carplay.data.automotive

import android.content.Context
import com.alexander.carplay.data.files.SharedDownloadsMirror
import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.incall.serversdk.interactive.media.CaMediaSource
import com.incall.serversdk.interactive.media.DoubleMediaProxy
import org.json.JSONObject
import java.util.concurrent.Executors

class DoubleMediaServerPublisher(
    context: Context,
    private val logStore: DiagnosticLogStore,
) {
    companion object {
        private const val SOURCE = "DoubleMedia"
        private const val PLAYING_ID = 1
        private const val SOURCE_TYPE = 25
        private const val SONG_ID = "test-song"
        private const val COVER_FILE_NAME = "cover.jpg"
    }

    private val sharedDownloadsMirror = SharedDownloadsMirror(context)
    private val executor = Executors.newSingleThreadExecutor()

    private var lastLyrics: String? = null
    private var lastArtistName: String? = null
    private var lastSongName: String? = null
    private var lastAlbumName: String? = null
    private var lastAppName: String? = null
    private var lastCoverBytes: ByteArray? = null

    fun onMediaMetadata(metadata: JSONObject) {
        executor.execute {
            if (metadata.length() == 1 && metadata.has("MediaSongPlayTime")) {
                return@execute
            }

            val mediaLyrics = metadata.optStringOrNull("MediaLyrics")
            val mediaArtistName = metadata.optStringOrNull("MediaArtistName")
            val mediaSongName = metadata.optStringOrNull("MediaSongName")
            val mediaAlbumName = metadata.optStringOrNull("MediaAlbumName")
            val mediaAppName = metadata.optStringOrNull("MediaAPPName")

            if (mediaAppName != null || (mediaLyrics != null && lastLyrics != mediaLyrics)) {
                lastLyrics = null
                lastSongName = null
                lastArtistName = null
                lastAlbumName = null
                lastCoverBytes = null
            }

            if (!mediaAppName.isNullOrBlank()) lastAppName = mediaAppName
            if (!mediaArtistName.isNullOrBlank()) lastArtistName = mediaArtistName
            if (!mediaSongName.isNullOrBlank()) lastSongName = mediaSongName
            if (!mediaAlbumName.isNullOrBlank()) lastAlbumName = mediaAlbumName
            if (!mediaLyrics.isNullOrBlank()) lastLyrics = mediaLyrics

            publishCurrentState()
        }
    }

    fun onAlbumCover(coverBytes: ByteArray) {
        if (coverBytes.isEmpty()) return
        executor.execute {
            lastCoverBytes = coverBytes.copyOf()
            publishCurrentState()
        }
    }

    private fun publishCurrentState() {
        val songName = (lastLyrics ?: lastSongName ?: lastAppName ?: "").ifBlank { " " }
        val singerName = (lastArtistName ?: "").ifBlank { " " }
        val programName = (lastAlbumName ?: lastAppName ?: "").ifBlank { " " }

        runCatching {
            val source = CaMediaSource().apply {
                playingId = PLAYING_ID
                this.programName = programName
                this.singerName = singerName
                this.songName = songName
                sourceType = SOURCE_TYPE
            }
            DoubleMediaProxy.getInstance().sendMediaSource(source)
            logStore.info(
                SOURCE,
                "Published source: song=$songName artist=$singerName program=$programName",
            )
        }.onFailure { error ->
            logStore.error(SOURCE, "Failed to publish media source", error)
        }

        val coverBytes = lastCoverBytes ?: return
        val coverFile = sharedDownloadsMirror.writeSharedFile(COVER_FILE_NAME, coverBytes)
        val coverPath = coverFile?.absolutePath?.replace("//", "/") ?: return

        runCatching {
            DoubleMediaProxy.getInstance().sendMediaAlbumPath(PLAYING_ID, SONG_ID, coverPath)
            logStore.info(SOURCE, "Published cover: $coverPath (${coverBytes.size} bytes)")
        }.onFailure { error ->
            logStore.error(SOURCE, "Failed to publish media cover", error)
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }
}
