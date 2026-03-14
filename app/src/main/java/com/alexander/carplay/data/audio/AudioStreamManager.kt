package com.alexander.carplay.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.alexander.carplay.data.protocol.Cpc200Protocol
import com.alexander.carplay.data.protocol.Cpc200Protocol.AudioPacket

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
    )

    private val tracks = mutableMapOf<StreamKey, ManagedTrack>()
    private var activeStream: StreamKey = StreamKey.MEDIA

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

        stream.track.write(packet.payload, 0, packet.payload.size)
    }

    fun release() {
        tracks.values.forEach { managed ->
            managed.track.pause()
            managed.track.flush()
            managed.track.release()
        }
        tracks.clear()
    }

    private fun handleCommand(
        command: Int,
        decodeType: Int,
    ) {
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
        return ManagedTrack(audioTrack, format).also { tracks[streamKey] = it }
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
}
