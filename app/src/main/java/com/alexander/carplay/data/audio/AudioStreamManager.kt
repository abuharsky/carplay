package com.alexander.carplay.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Process
import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.alexander.carplay.data.protocol.Cpc200Protocol
import com.alexander.carplay.data.protocol.Cpc200Protocol.AudioPacket
import com.alexander.carplay.domain.model.ProjectionAudioPlayerType
import com.alexander.carplay.domain.model.ProjectionDeviceSettings
import com.alexander.carplay.domain.model.ProjectionPlayerAudioSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class AudioStreamManager(
    private val logStore: DiagnosticLogStore,
) {
    companion object {
        private const val SOURCE = "Audio"
        // Max queued chunks per track (~30s at typical packet rate)
        private const val QUEUE_CAPACITY = 1000
        // How long the playback thread waits for a new chunk before polling again
        private const val POLL_TIMEOUT_MS = 5L
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
        val queue: LinkedBlockingQueue<ByteArray>,
        val equalizer: Equalizer?,
        val bassBoost: BassBoost?,
        val loudnessEnhancer: LoudnessEnhancer?,
        var appliedSettings: ProjectionPlayerAudioSettings? = null,
        val bytesEnqueued: AtomicLong = AtomicLong(0L),
        val packetsEnqueued: AtomicInteger = AtomicInteger(0),
        val droppedPackets: AtomicInteger = AtomicInteger(0),
    )

    private data class VolumeDirective(
        val gain: Float,
        val expiresAtMs: Long,
    )

    // One persistent track per stream type — lives for the entire session
    private val streamTracks = ConcurrentHashMap<StreamKey, ManagedTrack>()

    // Which StreamKey is currently the active writer for each AudioKey
    private val activeStreamKey = mutableMapOf<AudioKey, StreamKey>()

    // Minimum playback duration before deactivation is honoured
    private val streamActivatedAtMs = mutableMapOf<Pair<AudioKey, StreamKey>, Long>()
    private val pendingDeactivations = mutableMapOf<Pair<AudioKey, StreamKey>, Long>()

    private val protocolVolumeDirectives = mutableMapOf<AudioKey, VolumeDirective>()
    private var currentDeviceSettings: ProjectionDeviceSettings? = null

    // Single audio playback thread drains all track queues into their AudioTracks
    @Volatile
    private var playbackThreadRunning = false
    private var playbackThread: Thread? = null

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
        streamTracks.forEach { (streamKey, managedTrack) ->
            applyEffects(managedTrack, settingsFor(streamKey))
        }
    }

    fun handleAudioPacket(packet: AudioPacket) {
        processPendingDeactivations()
        cleanupExpiredVolumeDirectives()

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
        val streamKey = activeStreamKey[audioKey] ?: StreamKey.MEDIA
        val track = getOrCreateTrack(audioKey.decodeType, streamKey)
        if (track == null) {
            logStore.error(
                SOURCE,
                "Unable to prepare AudioTrack for decodeType=${packet.decodeType} audioType=${packet.audioType}",
            )
            return
        }

        val settings = settingsFor(streamKey)
        applyEffects(track, settings)
        val payload = applyGainIfNeeded(
            packet.payload,
            effectiveGainFor(audioKey, settings),
        )

        // Non-blocking enqueue — USB thread never blocks here
        val accepted = track.queue.offer(payload)
        if (accepted) {
            track.bytesEnqueued.addAndGet(payload.size.toLong())
            track.packetsEnqueued.incrementAndGet()
        } else {
            val dropped = track.droppedPackets.incrementAndGet()
            if (dropped % 10 == 1) {
                logStore.info(
                    SOURCE,
                    "Audio queue full, dropping packet: route=$streamKey dropped=${track.droppedPackets.get()}",
                )
            }
        }
    }

    fun release() {
        stopPlaybackThread()
        streamTracks.values.forEach { releaseManagedTrack(it) }
        streamTracks.clear()
        activeStreamKey.clear()
        streamActivatedAtMs.clear()
        pendingDeactivations.clear()
        protocolVolumeDirectives.clear()
        currentDeviceSettings = null
    }

    private fun startPlaybackThreadIfNeeded() {
        if (playbackThreadRunning) return
        playbackThreadRunning = true
        playbackThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (playbackThreadRunning) {
                var wroteAnything = false
                // ConcurrentHashMap — safe to iterate without lock
                for (managed in streamTracks.values) {
                    val chunk = managed.queue.poll() ?: continue  // non-blocking
                    managed.track.write(chunk, 0, chunk.size)
                    wroteAnything = true
                }
                if (!wroteAnything) {
                    Thread.sleep(POLL_TIMEOUT_MS)  // single sleep only when all queues empty
                }
            }
        }, "carplay-audio-playback").apply { isDaemon = true; start() }
        logStore.info(SOURCE, "Audio playback thread started")
    }

    private fun stopPlaybackThread() {
        playbackThreadRunning = false
        playbackThread?.join(500)
        playbackThread = null
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
            Cpc200Protocol.AudioCommand.OUTPUT_START -> handleOutputStart(audioKey)
            Cpc200Protocol.AudioCommand.OUTPUT_STOP -> handleOutputStop(audioKey)
            Cpc200Protocol.AudioCommand.MEDIA_START -> activateStream(audioKey, StreamKey.MEDIA)
            Cpc200Protocol.AudioCommand.MEDIA_STOP -> deactivateStream(audioKey, StreamKey.MEDIA)
            Cpc200Protocol.AudioCommand.NAVI_START -> activateStream(audioKey, StreamKey.NAVI)
            Cpc200Protocol.AudioCommand.NAVI_STOP -> deactivateStream(audioKey, StreamKey.NAVI)
            Cpc200Protocol.AudioCommand.SIRI_START -> activateStream(audioKey, StreamKey.SIRI)
            Cpc200Protocol.AudioCommand.SIRI_STOP -> deactivateStream(audioKey, StreamKey.SIRI)
            Cpc200Protocol.AudioCommand.PHONE_START -> activateStream(audioKey, StreamKey.PHONE)
            Cpc200Protocol.AudioCommand.PHONE_STOP -> deactivateStream(audioKey, StreamKey.PHONE)
            Cpc200Protocol.AudioCommand.PHONE_INCOMING -> activateStream(audioKey, StreamKey.ALERT)
            Cpc200Protocol.AudioCommand.ALERT_START -> activateStream(audioKey, StreamKey.ALERT)
            Cpc200Protocol.AudioCommand.ALERT_STOP -> deactivateStream(audioKey, StreamKey.ALERT)
        }
    }

    private fun handleOutputStart(audioKey: AudioKey) {
        val current = activeStreamKey[audioKey]
        if (current != null) {
            logStore.info(
                SOURCE,
                "Audio outputStart keeps existing route=$current key=${audioKey.decodeType}/${audioKey.audioType}",
            )
            streamTracks[current]?.let { track ->
                if (track.track.playState != AudioTrack.PLAYSTATE_PLAYING) track.track.play()
            }
        } else {
            activateStream(audioKey, StreamKey.MEDIA)
        }
    }

    private fun handleOutputStop(audioKey: AudioKey) {
        val current = activeStreamKey[audioKey] ?: return
        logStore.info(
            SOURCE,
            "Audio outputStop: route=$current key=${audioKey.decodeType}/${audioKey.audioType} (track kept alive)",
        )
        activeStreamKey.remove(audioKey)
    }

    private fun activateStream(audioKey: AudioKey, streamKey: StreamKey) {
        activeStreamKey[audioKey] = streamKey
        val pairKey = audioKey to streamKey
        streamActivatedAtMs[pairKey] = System.currentTimeMillis()
        pendingDeactivations.remove(pairKey)
        val track = getOrCreateTrack(audioKey.decodeType, streamKey) ?: return
        applyEffects(track, settingsFor(streamKey))
        if (track.track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.track.play()
        }
        startPlaybackThreadIfNeeded()
        logStore.info(
            SOURCE,
            "Audio stream active: $streamKey key=${audioKey.decodeType}/${audioKey.audioType} " +
                "(${track.format.sampleRate}Hz usage=${describeUsage(track.format.usage)})",
        )
    }

    private fun deactivateStream(audioKey: AudioKey, streamKey: StreamKey) {
        val minDurationMs = when (streamKey) {
            StreamKey.SIRI -> 500L
            StreamKey.PHONE -> 500L
            StreamKey.NAVI -> 300L
            else -> 0L
        }
        val pairKey = audioKey to streamKey
        val activatedAt = streamActivatedAtMs[pairKey] ?: 0L
        val elapsed = System.currentTimeMillis() - activatedAt
        val remaining = minDurationMs - elapsed

        val track = streamTracks[streamKey]
        val statsMsg = if (track != null) {
            val bps = track.format.sampleRate.toLong() * 2 *
                if (track.format.channelMask == AudioFormat.CHANNEL_OUT_STEREO) 2L else 1L
            val enqueued = track.bytesEnqueued.get()
            " enqueued=${track.packetsEnqueued.get()}pkt/${enqueued}b " +
                "queued=${track.queue.size} durationMs=${enqueued * 1000 / maxOf(1L, bps)} " +
                "dropped=${track.droppedPackets.get()}"
        } else ""

        if (remaining > 0) {
            pendingDeactivations[pairKey] = System.currentTimeMillis() + remaining
            logStore.info(
                SOURCE,
                "Audio stream stop deferred: $streamKey key=${audioKey.decodeType}/${audioKey.audioType} remaining=${remaining}ms$statsMsg",
            )
        } else {
            if (activeStreamKey[audioKey] == streamKey) {
                activeStreamKey.remove(audioKey)
            }
            streamActivatedAtMs.remove(pairKey)
            // Queue is NOT cleared — playback thread drains remaining buffered audio naturally
            logStore.info(
                SOURCE,
                "Audio stream deactivated: $streamKey key=${audioKey.decodeType}/${audioKey.audioType} (draining queue)$statsMsg",
            )
        }
    }

    private fun processPendingDeactivations() {
        if (pendingDeactivations.isEmpty()) return
        val now = System.currentTimeMillis()
        val iterator = pendingDeactivations.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now >= entry.value) {
                iterator.remove()
                val (audioKey, streamKey) = entry.key
                streamActivatedAtMs.remove(entry.key)
                if (activeStreamKey[audioKey] == streamKey) {
                    activeStreamKey.remove(audioKey)
                }
                logStore.info(
                    SOURCE,
                    "Audio stream deactivated (deferred): $streamKey key=${audioKey.decodeType}/${audioKey.audioType}",
                )
            }
        }
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

    private fun getOrCreateTrack(decodeType: Int, streamKey: StreamKey): ManagedTrack? {
        val existing = streamTracks[streamKey]
        if (existing != null) {
            val desiredFormat = decodeTypeToFormat(decodeType, streamKey) ?: return null
            if (existing.format == desiredFormat) return existing
            logStore.info(
                SOURCE,
                "Audio track format change: route=$streamKey " +
                    "oldRate=${existing.format.sampleRate}Hz newRate=${desiredFormat.sampleRate}Hz",
            )
            releaseManagedTrack(existing)
            streamTracks.remove(streamKey)
        }
        return createTrack(decodeType, streamKey)
    }

    private fun createTrack(decodeType: Int, streamKey: StreamKey): ManagedTrack? {
        val format = decodeTypeToFormat(decodeType, streamKey) ?: return null
        val minBuffer = AudioTrack.getMinBufferSize(
            format.sampleRate,
            format.channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null

        // Small buffer — queue absorbs bursts, small buffer reduces output latency
        val bufferSize = minBuffer * 2

        val contentType = if (streamKey == StreamKey.PHONE) {
            AudioAttributes.CONTENT_TYPE_SPEECH
        } else {
            AudioAttributes.CONTENT_TYPE_MUSIC
        }
        val audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(format.usage)
                .setContentType(contentType)
                .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(format.sampleRate)
                .setChannelMask(format.channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        val managedTrack = ManagedTrack(
            track = audioTrack,
            format = format,
            queue = LinkedBlockingQueue(QUEUE_CAPACITY),
            equalizer = createEqualizer(audioTrack.audioSessionId),
            bassBoost = createBassBoost(audioTrack.audioSessionId),
            loudnessEnhancer = createLoudnessEnhancer(audioTrack.audioSessionId),
        )
        applyEffects(managedTrack, settingsFor(streamKey))
        audioTrack.play()
        logStore.info(
            SOURCE,
            "Audio track created: route=$streamKey " +
                "rate=${format.sampleRate}Hz usage=${describeUsage(format.usage)} " +
                "content=${describeContentType(contentType)} session=${audioTrack.audioSessionId}",
        )
        streamTracks[streamKey] = managedTrack
        return managedTrack
    }

    private fun decodeTypeToFormat(
        decodeType: Int,
        streamKey: StreamKey,
    ): StreamFormat? {
        val usage = AudioAttributes.USAGE_MEDIA

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

            else -> {
                logStore.error(
                    SOURCE,
                    "Unknown decodeType=$decodeType streamKey=$streamKey — no track created",
                )
                null
            }
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

    private fun createEqualizer(audioSessionId: Int): Equalizer? =
        runCatching { Equalizer(0, audioSessionId).apply { enabled = true } }.getOrNull()

    private fun createBassBoost(audioSessionId: Int): BassBoost? =
        runCatching { BassBoost(0, audioSessionId).apply { enabled = false } }.getOrNull()

    private fun createLoudnessEnhancer(audioSessionId: Int): LoudnessEnhancer? =
        runCatching { LoudnessEnhancer(audioSessionId).apply { enabled = false } }.getOrNull()

    private fun releaseManagedTrack(managed: ManagedTrack) {
        managed.queue.clear()
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
            }.onFailure { logStore.error(SOURCE, "Unable to apply bass boost", it) }
        }

        managedTrack.loudnessEnhancer?.let { loudnessEnhancer ->
            runCatching {
                val gainMb = (settings.loudnessBoostPercent / 100f * 2000f).roundToInt()
                loudnessEnhancer.setTargetGain(gainMb)
                loudnessEnhancer.enabled = gainMb > 0
            }.onFailure { logStore.error(SOURCE, "Unable to apply loudness enhancer", it) }
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
            }.onFailure { logStore.error(SOURCE, "Unable to apply equalizer settings", it) }
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
            val boosted = (sample * effectiveGain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            amplified[index] = boosted.toByte()
            amplified[index + 1] = (boosted shr 8).toByte()
            index += 2
        }
        return amplified
    }
}
