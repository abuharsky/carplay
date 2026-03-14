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

class AudioStreamManager(
    private val logStore: DiagnosticLogStore,
) {
    companion object {
        private const val SOURCE = "Audio"
    }

    private enum class StreamKey {
        MEDIA,
        NAVI,
        SIRI,
        PHONE,
        ALERT,
    }

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
    )

    private val tracks = mutableMapOf<StreamKey, ManagedTrack>()
    private var activeStream: StreamKey = StreamKey.MEDIA
    private var currentDeviceSettings: ProjectionDeviceSettings? = null

    fun updateDeviceSettings(settings: ProjectionDeviceSettings?) {
        currentDeviceSettings = settings
        logStore.info(
            SOURCE,
            "Device audio settings applied: route=${settings?.audioRoute ?: "default"}, mic=${settings?.micRoute ?: "default"}",
        )
        tracks.forEach { (streamKey, managedTrack) ->
            applyEffects(managedTrack, settingsFor(streamKey))
        }
    }

    fun handleAudioPacket(packet: AudioPacket) {
        packet.command?.let { command ->
            handleCommand(command, packet.decodeType)
            return
        }

        if (packet.payload.size <= 4) return

        val stream = tracks[activeStream] ?: prepareTrack(activeStream, packet.decodeType)
        if (stream == null) {
            logStore.error(SOURCE, "Unable to prepare AudioTrack for decodeType=${packet.decodeType}")
            return
        }

        val playerSettings = settingsFor(activeStream)
        applyEffects(stream, playerSettings)
        val payload = applyGainIfNeeded(packet.payload, playerSettings.gainMultiplier)
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
        currentDeviceSettings = null
    }

    private fun handleCommand(
        command: Int,
        decodeType: Int,
    ) {
        logStore.info(SOURCE, "Audio command ${describeAudioCommand(command)} decodeType=$decodeType")
        when (command) {
            Cpc200Protocol.AudioCommand.OUTPUT_START,
            Cpc200Protocol.AudioCommand.MEDIA_START,
            -> startStream(StreamKey.MEDIA, decodeType)

            Cpc200Protocol.AudioCommand.OUTPUT_STOP,
            Cpc200Protocol.AudioCommand.MEDIA_STOP,
            -> stopStream(StreamKey.MEDIA)

            Cpc200Protocol.AudioCommand.NAVI_START -> startStream(StreamKey.NAVI, decodeType)
            Cpc200Protocol.AudioCommand.NAVI_STOP -> stopStream(StreamKey.NAVI)
            Cpc200Protocol.AudioCommand.SIRI_START -> startStream(StreamKey.SIRI, decodeType)
            Cpc200Protocol.AudioCommand.SIRI_STOP -> stopStream(StreamKey.SIRI)
            Cpc200Protocol.AudioCommand.PHONE_START -> startStream(StreamKey.PHONE, decodeType)
            Cpc200Protocol.AudioCommand.PHONE_STOP -> stopStream(StreamKey.PHONE)
            Cpc200Protocol.AudioCommand.ALERT_START -> startStream(StreamKey.ALERT, decodeType)
            Cpc200Protocol.AudioCommand.ALERT_STOP -> stopStream(StreamKey.ALERT)
        }
    }

    private fun describeAudioCommand(command: Int): String = when (command) {
        Cpc200Protocol.AudioCommand.OUTPUT_START -> "outputStart"
        Cpc200Protocol.AudioCommand.OUTPUT_STOP -> "outputStop"
        Cpc200Protocol.AudioCommand.INPUT_CONFIG -> "inputConfig"
        Cpc200Protocol.AudioCommand.PHONE_START -> "phoneStart"
        Cpc200Protocol.AudioCommand.PHONE_STOP -> "phoneStop"
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
        streamKey: StreamKey,
        decodeType: Int,
    ) {
        val managedTrack = tracks[streamKey] ?: prepareTrack(streamKey, decodeType) ?: return
        activeStream = streamKey
        managedTrack.track.play()
        logStore.info(SOURCE, "Audio stream started: $streamKey (${managedTrack.format.sampleRate}Hz)")
    }

    private fun stopStream(streamKey: StreamKey) {
        tracks.remove(streamKey)?.let { managed ->
            managed.track.pause()
            managed.track.flush()
            managed.equalizer?.release()
            managed.bassBoost?.release()
            managed.loudnessEnhancer?.release()
            managed.track.release()
            logStore.info(SOURCE, "Audio stream stopped: $streamKey")
        }
        if (activeStream == streamKey) {
            activeStream = StreamKey.MEDIA
        }
    }

    private fun prepareTrack(
        streamKey: StreamKey,
        decodeType: Int,
    ): ManagedTrack? {
        val format = decodeTypeToFormat(decodeType, streamKey) ?: return null
        val minBuffer = AudioTrack.getMinBufferSize(
            format.sampleRate,
            format.channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null

        val audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(format.usage)
                .setContentType(
                    if (streamKey == StreamKey.PHONE) {
                        AudioAttributes.CONTENT_TYPE_SPEECH
                    } else {
                        AudioAttributes.CONTENT_TYPE_MUSIC
                    },
                )
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
        return managedTrack.also { tracks[streamKey] = it }
    }

    private fun decodeTypeToFormat(
        decodeType: Int,
        streamKey: StreamKey,
    ): StreamFormat? {
        val usage = when (streamKey) {
            StreamKey.MEDIA -> AudioAttributes.USAGE_MEDIA
            StreamKey.NAVI -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            StreamKey.SIRI -> AudioAttributes.USAGE_ASSISTANT
            StreamKey.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            StreamKey.ALERT -> AudioAttributes.USAGE_NOTIFICATION_EVENT
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

    private fun applyEffects(
        managedTrack: ManagedTrack,
        settings: ProjectionPlayerAudioSettings,
    ) {
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
    }

    private fun applyGainIfNeeded(
        payload: ByteArray,
        gainMultiplier: Float,
    ): ByteArray {
        if (gainMultiplier <= 1.001f) return payload

        val amplified = payload.copyOf()
        var index = 0
        while (index + 1 < amplified.size) {
            val sample = ((amplified[index + 1].toInt() shl 8) or (amplified[index].toInt() and 0xFF)).toShort()
            val boosted = (sample * gainMultiplier).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            amplified[index] = boosted.toByte()
            amplified[index + 1] = (boosted shr 8).toByte()
            index += 2
        }
        return amplified
    }
}
