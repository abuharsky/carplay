package com.alexander.carplay.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.alexander.carplay.data.protocol.Cpc200Protocol
import com.alexander.carplay.data.protocol.Cpc200Protocol.AudioPacket
import com.alexander.carplay.domain.model.ProjectionAudioPlayerType
import com.alexander.carplay.domain.model.ProjectionDeviceSettings
import com.alexander.carplay.domain.model.ProjectionPlayerAudioSettings
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class AudioStreamManager(
    private val logStore: DiagnosticLogStore,
) {
    companion object {
        private const val SOURCE = "Audio"
        private const val ROUTE_STOP_GRACE_MS = 1_500L
    }

    private enum class StreamKey {
        MEDIA,
        NAVI,
        SIRI,
        PHONE,
        ALERT,
    }

    private data class AudioKey(
        val decodeType: Int,
        val audioType: Int,
    )

    private data class StreamFormat(
        val sampleRate: Int,
        val channelMask: Int,
        val usage: Int,
    )

    private data class ManagedTrack(
        val track: AudioTrack,
        val format: StreamFormat,
        val equalizer: Equalizer?,
        val bassBoost: BassBoost?,
        val loudnessEnhancer: LoudnessEnhancer?,
        var appliedSettings: ProjectionPlayerAudioSettings? = null,
    )

    private data class VolumeDirective(
        val gain: Float,
        val expiresAtMs: Long,
    )

    private val tracks = mutableMapOf<AudioKey, ManagedTrack>()
    private val categories = mutableMapOf<AudioKey, StreamKey>()
    private val protocolVolumeDirectives = mutableMapOf<AudioKey, VolumeDirective>()
    private val pendingOutputStopDeadlines = mutableMapOf<AudioKey, Long>()
    private val provisionalPlaybackKeys = mutableSetOf<AudioKey>()
    private var currentDeviceSettings: ProjectionDeviceSettings? = null

    fun updateDeviceSettings(settings: ProjectionDeviceSettings?) {
        val previousSettings = currentDeviceSettings
        currentDeviceSettings = settings
        if (
            previousSettings?.audioRoute != settings?.audioRoute ||
            previousSettings?.micRoute != settings?.micRoute
        ) {
            logStore.info(
                SOURCE,
                "Device audio route updated: route=${settings?.audioRoute ?: "default"}, mic=${settings?.micRoute ?: "default"}",
            )
        }
        tracks.forEach { (audioKey, managedTrack) ->
            categories[audioKey]?.let { category ->
                applyEffects(managedTrack, settingsFor(category))
            }
        }
    }

    fun handleAudioPacket(packet: AudioPacket) {
        cleanupExpiredVolumeDirectives()
        cleanupExpiredPendingStops()

        packet.command?.let { command ->
            handleCommand(command, AudioKey(packet.decodeType, packet.audioType))
            return
        }

        packet.volumeDuration?.let { duration ->
            handleVolumeDirective(
                audioKey = AudioKey(packet.decodeType, packet.audioType),
                volume = packet.volume,
                durationSeconds = duration,
            )
            return
        }

        if (packet.payload.size <= 4) return

        val audioKey = AudioKey(packet.decodeType, packet.audioType)
        val streamCategory = categories[audioKey]
        val stream = if (streamCategory != null) {
            tracks[audioKey] ?: prepareTrack(audioKey, streamCategory)
        } else {
            startProvisionalPlayback(audioKey)
        }
        if (stream == null) {
            logStore.error(
                SOURCE,
                "Unable to prepare AudioTrack for decodeType=${packet.decodeType} audioType=${packet.audioType}",
            )
            return
        }

        val playerSettings = streamCategory?.let(::settingsFor) ?: ProjectionPlayerAudioSettings()
        applyEffects(stream, playerSettings)
        val payload = applyGainIfNeeded(packet.payload, effectiveGainFor(audioKey, playerSettings))
        stream.track.write(payload, 0, payload.size)
    }

    fun release() {
        tracks.values.forEach { managed ->
            managed.track.pause()
            managed.track.flush()
            managed.equalizer?.release()
            managed.bassBoost?.release()
            managed.loudnessEnhancer?.release()
            managed.track.release()
        }
        tracks.clear()
        categories.clear()
        protocolVolumeDirectives.clear()
        pendingOutputStopDeadlines.clear()
        provisionalPlaybackKeys.clear()
        currentDeviceSettings = null
    }

    private fun handleCommand(
        command: Int,
        audioKey: AudioKey,
    ) {
        logStore.info(
            SOURCE,
            "Audio command ${describeAudioCommand(command)} decodeType=${audioKey.decodeType} audioType=${audioKey.audioType}",
        )
        when (command) {
            Cpc200Protocol.AudioCommand.OUTPUT_START -> startOutputStream(audioKey)
            Cpc200Protocol.AudioCommand.MEDIA_START -> startStream(audioKey, StreamKey.MEDIA)

            Cpc200Protocol.AudioCommand.OUTPUT_STOP -> stopOutputStream(audioKey)
            Cpc200Protocol.AudioCommand.MEDIA_STOP -> markStreamStopping(audioKey)

            Cpc200Protocol.AudioCommand.NAVI_START -> startStream(audioKey, StreamKey.NAVI)
            Cpc200Protocol.AudioCommand.NAVI_STOP -> markStreamStopping(audioKey)
            Cpc200Protocol.AudioCommand.SIRI_START -> startStream(audioKey, StreamKey.SIRI)
            Cpc200Protocol.AudioCommand.SIRI_STOP -> markStreamStopping(audioKey)
            Cpc200Protocol.AudioCommand.PHONE_START -> startStream(audioKey, StreamKey.PHONE)
            Cpc200Protocol.AudioCommand.PHONE_STOP -> markStreamStopping(audioKey)
            Cpc200Protocol.AudioCommand.PHONE_INCOMING -> startStream(audioKey, StreamKey.ALERT)
            Cpc200Protocol.AudioCommand.ALERT_START -> startStream(audioKey, StreamKey.ALERT)
            Cpc200Protocol.AudioCommand.ALERT_STOP -> markStreamStopping(audioKey)
        }
    }

    private fun startOutputStream(audioKey: AudioKey) {
        val existingCategory = categories[audioKey]
        if (existingCategory != null) {
            pendingOutputStopDeadlines.remove(audioKey)
            logStore.info(
                SOURCE,
                "Audio outputStart keeps existing route=$existingCategory key=${audioKey.decodeType}/${audioKey.audioType}",
            )
            val existingTrack = tracks[audioKey]
            if (existingTrack == null) {
                startStream(audioKey, existingCategory)
            } else if (existingTrack.track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                existingTrack.track.play()
            }
            return
        }
        startStream(audioKey, StreamKey.MEDIA)
    }

    private fun handleVolumeDirective(
        audioKey: AudioKey,
        volume: Float,
        durationSeconds: Float,
    ) {
        val durationMs = (durationSeconds.coerceAtLeast(0f) * 1_000f).roundToLong()
        if (durationMs <= 0L) {
            protocolVolumeDirectives.remove(audioKey)
            logStore.info(
                SOURCE,
                "Audio volume envelope cleared key=${audioKey.decodeType}/${audioKey.audioType}",
            )
            return
        }

        val normalizedGain = volume.coerceAtLeast(0f)
        protocolVolumeDirectives[audioKey] = VolumeDirective(
            gain = normalizedGain,
            expiresAtMs = System.currentTimeMillis() + durationMs,
        )
        logStore.info(
            SOURCE,
            "Audio volume envelope key=${audioKey.decodeType}/${audioKey.audioType} gain=${"%.2f".format(normalizedGain)} duration=${"%.2f".format(durationSeconds)}s",
        )
    }

    private fun cleanupExpiredVolumeDirectives() {
        if (protocolVolumeDirectives.isEmpty()) return
        val now = System.currentTimeMillis()
        val iterator = protocolVolumeDirectives.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now >= entry.value.expiresAtMs) {
                logStore.info(
                    SOURCE,
                    "Audio volume envelope expired key=${entry.key.decodeType}/${entry.key.audioType}",
                )
                iterator.remove()
            }
        }
    }

    private fun cleanupExpiredPendingStops() {
        if (pendingOutputStopDeadlines.isEmpty()) return
        val now = System.currentTimeMillis()
        val iterator = pendingOutputStopDeadlines.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now >= entry.value) {
                logStore.info(
                    SOURCE,
                    "Audio route stop grace expired key=${entry.key.decodeType}/${entry.key.audioType}",
                )
                iterator.remove()
                stopStream(entry.key)
            }
        }
    }

    private fun stopOutputStream(audioKey: AudioKey) {
        pendingOutputStopDeadlines.remove(audioKey)
        stopStream(audioKey)
    }

    private fun describeAudioCommand(command: Int): String = when (command) {
        Cpc200Protocol.AudioCommand.OUTPUT_START -> "outputStart"
        Cpc200Protocol.AudioCommand.OUTPUT_STOP -> "outputStop"
        Cpc200Protocol.AudioCommand.INPUT_CONFIG -> "inputConfig"
        Cpc200Protocol.AudioCommand.PHONE_START -> "phoneStart"
        Cpc200Protocol.AudioCommand.PHONE_STOP -> "phoneStop"
        Cpc200Protocol.AudioCommand.PHONE_INCOMING -> "phoneIncoming"
        Cpc200Protocol.AudioCommand.NAVI_START -> "naviStart"
        Cpc200Protocol.AudioCommand.NAVI_STOP -> "naviStop"
        Cpc200Protocol.AudioCommand.SIRI_START -> "siriStart"
        Cpc200Protocol.AudioCommand.SIRI_STOP -> "siriStop"
        Cpc200Protocol.AudioCommand.MEDIA_START -> "mediaStart"
        Cpc200Protocol.AudioCommand.MEDIA_STOP -> "mediaStop"
        Cpc200Protocol.AudioCommand.ALERT_START -> "alertStart"
        Cpc200Protocol.AudioCommand.ALERT_STOP -> "alertStop"
        else -> "unknown($command)"
    }

    private fun startStream(
        audioKey: AudioKey,
        streamKey: StreamKey,
    ) {
        pendingOutputStopDeadlines.remove(audioKey)
        categories[audioKey] = streamKey
        provisionalPlaybackKeys.remove(audioKey)
        val desiredFormat = decodeTypeToFormat(audioKey.decodeType, streamKey) ?: return
        val existingTrack = tracks[audioKey]
        if (existingTrack != null && existingTrack.format != desiredFormat) {
            logStore.info(
                SOURCE,
                "Audio track recreation required: key=${audioKey.decodeType}/${audioKey.audioType} " +
                    "oldUsage=${describeUsage(existingTrack.format.usage)} newUsage=${describeUsage(desiredFormat.usage)} " +
                    "oldRate=${existingTrack.format.sampleRate}Hz newRate=${desiredFormat.sampleRate}Hz",
            )
            releaseManagedTrack(existingTrack)
            tracks.remove(audioKey)
        }

        val managedTrack = tracks[audioKey] ?: prepareTrack(audioKey, streamKey) ?: return
        if (managedTrack.appliedSettings != settingsFor(streamKey) || managedTrack.track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            applyEffects(managedTrack, settingsFor(streamKey))
            managedTrack.track.play()
        }
        if (existingTrack != null && categories[audioKey] == streamKey && managedTrack.track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            logStore.info(
                SOURCE,
                "Audio stream active: $streamKey key=${audioKey.decodeType}/${audioKey.audioType} " +
                    "(${managedTrack.format.sampleRate}Hz usage=${describeUsage(managedTrack.format.usage)})",
            )
            return
        }
        logStore.info(
            SOURCE,
            "Audio stream started: $streamKey key=${audioKey.decodeType}/${audioKey.audioType} " +
                "(${managedTrack.format.sampleRate}Hz usage=${describeUsage(managedTrack.format.usage)})",
        )
    }

    private fun markStreamStopping(audioKey: AudioKey) {
        val existingCategory = categories[audioKey] ?: return
        pendingOutputStopDeadlines[audioKey] = System.currentTimeMillis() + ROUTE_STOP_GRACE_MS
        logStore.info(
            SOURCE,
            "Audio route stop armed: route=$existingCategory key=${audioKey.decodeType}/${audioKey.audioType}",
        )
    }

    private fun stopStream(audioKey: AudioKey) {
        val category = categories[audioKey]
        categories.remove(audioKey)
        provisionalPlaybackKeys.remove(audioKey)
        tracks.remove(audioKey)?.let { managed ->
            releaseManagedTrack(managed)
            logStore.info(
                SOURCE,
                "Audio stream stopped: route=${category ?: "UNKNOWN"} key=${audioKey.decodeType}/${audioKey.audioType} " +
                    "usage=${describeUsage(managed.format.usage)}",
            )
        }
    }

    private fun startProvisionalPlayback(audioKey: AudioKey): ManagedTrack? {
        val managedTrack = tracks[audioKey] ?: prepareTrack(audioKey, StreamKey.MEDIA) ?: return null
        applyEffects(managedTrack, ProjectionPlayerAudioSettings())
        managedTrack.track.play()
        if (provisionalPlaybackKeys.add(audioKey)) {
            logStore.info(
                SOURCE,
                "Audio payload accepted without route: provisional playback key=${audioKey.decodeType}/${audioKey.audioType}",
            )
        }
        return managedTrack
    }

    private fun prepareTrack(
        audioKey: AudioKey,
        streamKey: StreamKey,
    ): ManagedTrack? {
        val format = decodeTypeToFormat(audioKey.decodeType, streamKey) ?: return null
        val minBuffer = AudioTrack.getMinBufferSize(
            format.sampleRate,
            format.channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null

        val contentType = if (streamKey == StreamKey.PHONE) {
            AudioAttributes.CONTENT_TYPE_SPEECH
        } else {
            AudioAttributes.CONTENT_TYPE_MUSIC
        }
        val audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(format.usage)
                .setContentType(contentType)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(format.sampleRate)
                .setChannelMask(format.channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            minBuffer * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        val managedTrack = ManagedTrack(
            track = audioTrack,
            format = format,
            equalizer = createEqualizer(audioTrack.audioSessionId),
            bassBoost = createBassBoost(audioTrack.audioSessionId),
            loudnessEnhancer = createLoudnessEnhancer(audioTrack.audioSessionId),
        )
        applyEffects(managedTrack, settingsFor(streamKey))
        logStore.info(
            SOURCE,
            "Audio track prepared: route=$streamKey key=${audioKey.decodeType}/${audioKey.audioType} " +
                "rate=${format.sampleRate}Hz usage=${describeUsage(format.usage)} " +
                "content=${describeContentType(contentType)} session=${audioTrack.audioSessionId}",
        )
        return managedTrack.also { tracks[audioKey] = it }
    }

    private fun decodeTypeToFormat(
        decodeType: Int,
        streamKey: StreamKey,
    ): StreamFormat? {
        val usage = if (streamKey == StreamKey.PHONE) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_MEDIA
        }

        return when (decodeType) {
            Cpc200Protocol.AudioDecodeType.PCM_44_STEREO_A,
            Cpc200Protocol.AudioDecodeType.PCM_44_STEREO_B,
            -> StreamFormat(44_100, AudioFormat.CHANNEL_OUT_STEREO, usage)

            Cpc200Protocol.AudioDecodeType.PCM_8_MONO ->
                StreamFormat(8_000, AudioFormat.CHANNEL_OUT_MONO, usage)

            Cpc200Protocol.AudioDecodeType.PCM_48_STEREO ->
                StreamFormat(48_000, AudioFormat.CHANNEL_OUT_STEREO, usage)

            Cpc200Protocol.AudioDecodeType.PCM_16_MONO ->
                StreamFormat(16_000, AudioFormat.CHANNEL_OUT_MONO, usage)

            Cpc200Protocol.AudioDecodeType.PCM_24_MONO ->
                StreamFormat(24_000, AudioFormat.CHANNEL_OUT_MONO, usage)

            Cpc200Protocol.AudioDecodeType.PCM_16_STEREO ->
                StreamFormat(16_000, AudioFormat.CHANNEL_OUT_STEREO, usage)

            else -> null
        }
    }

    private fun settingsFor(streamKey: StreamKey): ProjectionPlayerAudioSettings {
        val playerType = when (streamKey) {
            StreamKey.MEDIA -> ProjectionAudioPlayerType.MEDIA
            StreamKey.NAVI -> ProjectionAudioPlayerType.NAVI
            StreamKey.SIRI -> ProjectionAudioPlayerType.SIRI
            StreamKey.PHONE -> ProjectionAudioPlayerType.PHONE
            StreamKey.ALERT -> ProjectionAudioPlayerType.ALERT
        }
        return currentDeviceSettings?.playerSettings?.get(playerType) ?: ProjectionPlayerAudioSettings()
    }

    private fun effectiveGainFor(
        audioKey: AudioKey,
        settings: ProjectionPlayerAudioSettings,
    ): Float {
        val protocolGain = protocolVolumeDirectives[audioKey]?.gain ?: 1f
        return (settings.gainMultiplier * protocolGain).coerceAtLeast(0f)
    }

    private fun describeUsage(usage: Int): String = when (usage) {
        AudioAttributes.USAGE_MEDIA -> "MEDIA"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        else -> usage.toString()
    }

    private fun describeContentType(contentType: Int): String = when (contentType) {
        AudioAttributes.CONTENT_TYPE_MUSIC -> "MUSIC"
        AudioAttributes.CONTENT_TYPE_SPEECH -> "SPEECH"
        else -> contentType.toString()
    }

    private fun createEqualizer(audioSessionId: Int): Equalizer? {
        return runCatching {
            Equalizer(0, audioSessionId).apply { enabled = true }
        }.getOrNull()
    }

    private fun createBassBoost(audioSessionId: Int): BassBoost? {
        return runCatching {
            BassBoost(0, audioSessionId).apply { enabled = false }
        }.getOrNull()
    }

    private fun createLoudnessEnhancer(audioSessionId: Int): LoudnessEnhancer? {
        return runCatching {
            LoudnessEnhancer(audioSessionId).apply { enabled = false }
        }.getOrNull()
    }

    private fun releaseManagedTrack(managed: ManagedTrack) {
        managed.track.pause()
        managed.track.flush()
        managed.equalizer?.release()
        managed.bassBoost?.release()
        managed.loudnessEnhancer?.release()
        managed.track.release()
    }

    private fun applyEffects(
        managedTrack: ManagedTrack,
        settings: ProjectionPlayerAudioSettings,
    ) {
        if (managedTrack.appliedSettings == settings) return

        managedTrack.bassBoost?.let { bassBoost ->
            runCatching {
                val strength = (settings.bassBoostPercent * 10).coerceIn(0, 1000)
                bassBoost.setStrength(strength.toShort())
                bassBoost.enabled = strength > 0
            }.onFailure { error ->
                logStore.error(SOURCE, "Unable to apply bass boost", error)
            }
        }

        managedTrack.loudnessEnhancer?.let { loudnessEnhancer ->
            runCatching {
                val gainMb = (settings.loudnessBoostPercent / 100f * 2000f).roundToInt()
                loudnessEnhancer.setTargetGain(gainMb)
                loudnessEnhancer.enabled = gainMb > 0
            }.onFailure { error ->
                logStore.error(SOURCE, "Unable to apply loudness enhancer", error)
            }
        }

        managedTrack.equalizer?.let { equalizer ->
            runCatching {
                val bandCount = equalizer.numberOfBands.toInt()
                if (bandCount <= 0) return@runCatching
                val range = equalizer.bandLevelRange
                val minLevel = range.getOrNull(0)?.toInt() ?: -1200
                val maxLevel = range.getOrNull(1)?.toInt() ?: 1200

                repeat(bandCount) { bandIndex ->
                    val mappedIndex = if (bandCount == 1) 0 else {
                        ((bandIndex.toFloat() / (bandCount - 1)) * (settings.eqBandsDb.size - 1)).roundToInt()
                    }
                    val bandDb = settings.eqBandsDb.getOrElse(mappedIndex) { 0f }
                    val bandLevel = (bandDb * 100).roundToInt().coerceIn(minLevel, maxLevel)
                    equalizer.setBandLevel(bandIndex.toShort(), bandLevel.toShort())
                }
                equalizer.enabled = settings.eqBandsDb.any { it != 0f }
            }.onFailure { error ->
                logStore.error(SOURCE, "Unable to apply equalizer settings", error)
            }
        }

        managedTrack.appliedSettings = settings
    }

    private fun applyGainIfNeeded(
        payload: ByteArray,
        gainMultiplier: Float,
    ): ByteArray {
        val effectiveGain = gainMultiplier.coerceAtLeast(0f)
        if (effectiveGain in 0.999f..1.001f) return payload

        val amplified = payload.copyOf()
        var index = 0
        while (index + 1 < amplified.size) {
            val sample = ((amplified[index + 1].toInt() shl 8) or (amplified[index].toInt() and 0xFF)).toShort()
            val boosted = (sample * effectiveGain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            amplified[index] = boosted.toByte()
            amplified[index + 1] = (boosted shr 8).toByte()
            index += 2
        }
        return amplified
    }
}
