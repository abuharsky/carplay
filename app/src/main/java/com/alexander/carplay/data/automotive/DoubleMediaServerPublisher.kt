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
    private enum class MetadataFieldState {
        MISSING,
        BLANK,
        VALUE,
    }

    private data class MetadataField(
        val state: MetadataFieldState,
        val value: String? = null,
    ) {
        val hasValue: Boolean
            get() = state == MetadataFieldState.VALUE && !value.isNullOrBlank()

        val isBlank: Boolean
            get() = state == MetadataFieldState.BLANK
    }

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
    private var lastCoverPath: String? = null

    fun onMediaMetadata(metadata: JSONObject) {
        executor.execute {
            if (metadata.length() == 1 && metadata.has("MediaSongPlayTime")) {
                return@execute
            }

            val mediaLyrics = metadata.readStringField("MediaLyrics")
            val mediaArtistName = metadata.readStringField("MediaArtistName")
            val mediaSongName = metadata.readStringField("MediaSongName")
            val mediaAlbumName = metadata.readStringField("MediaAlbumName")
            val mediaAppName = metadata.readStringField("MediaAPPName")

            val sourceChanged =
                (mediaAppName.hasValue && mediaAppName.value != lastAppName) ||
                    (mediaAlbumName.hasValue && mediaAlbumName.value != lastAlbumName)
            val trackChanged =
                (mediaLyrics.hasValue && mediaLyrics.value != lastLyrics) ||
                    (mediaArtistName.hasValue && mediaArtistName.value != lastArtistName) ||
                    (mediaSongName.hasValue && mediaSongName.value != lastSongName)
            val trackExplicitlyCleared =
                mediaLyrics.isBlank ||
                    mediaArtistName.isBlank ||
                    mediaSongName.isBlank
            val sourceChangedWithoutFreshTrack =
                sourceChanged &&
                    !mediaLyrics.hasValue &&
                    !mediaArtistName.hasValue &&
                    !mediaSongName.hasValue

            if (trackChanged || trackExplicitlyCleared || sourceChangedWithoutFreshTrack) {
                clearTrackPresentation()
            }

            mediaAppName.applyTo(
                currentValue = lastAppName,
                onChange = { lastAppName = it },
            )
            mediaArtistName.applyTo(
                currentValue = lastArtistName,
                onChange = { lastArtistName = it },
            )
            mediaSongName.applyTo(
                currentValue = lastSongName,
                onChange = { lastSongName = it },
            )
            mediaAlbumName.applyTo(
                currentValue = lastAlbumName,
                onChange = { lastAlbumName = it },
            )
            mediaLyrics.applyTo(
                currentValue = lastLyrics,
                onChange = { lastLyrics = it },
            )

            publishCurrentState()
        }
    }

    fun onAlbumCover(coverBytes: ByteArray) {
        if (coverBytes.isEmpty()) return
        executor.execute {
            lastCoverBytes = coverBytes.copyOf()
            val coverFile = sharedDownloadsMirror.writeSharedFile(COVER_FILE_NAME, coverBytes)
            lastCoverPath = coverFile?.absolutePath?.replace("//", "/")
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

        if (lastCoverBytes == null) return
        val coverPath = lastCoverPath ?: return

        runCatching {
            DoubleMediaProxy.getInstance().sendMediaAlbumPath(PLAYING_ID, SONG_ID, coverPath)
            logStore.info(SOURCE, "Published cover: $coverPath (${lastCoverBytes?.size ?: 0} bytes)")
        }.onFailure { error ->
            logStore.error(SOURCE, "Failed to publish media cover", error)
        }
    }

    private fun clearTrackPresentation() {
        lastLyrics = null
        lastSongName = null
        lastArtistName = null
        lastCoverBytes = null
        lastCoverPath = null
    }

    private fun MetadataField.applyTo(
        currentValue: String?,
        onChange: (String?) -> Unit,
    ) {
        when (state) {
            MetadataFieldState.MISSING -> Unit
            MetadataFieldState.BLANK -> onChange(null)
            MetadataFieldState.VALUE -> {
                if (value != currentValue) {
                    onChange(value)
                }
            }
        }
    }

    private fun JSONObject.readStringField(key: String): MetadataField {
        if (!has(key) || isNull(key)) return MetadataField(MetadataFieldState.MISSING)
        val value = optString(key).trim()
        return if (value.isEmpty()) {
            MetadataField(MetadataFieldState.BLANK)
        } else {
            MetadataField(MetadataFieldState.VALUE, value)
        }
    }
}
