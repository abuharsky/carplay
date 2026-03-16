package com.alexander.carplay.data.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.alexander.carplay.data.logging.DiagnosticLogStore
import kotlin.math.max

class MicrophoneInputManager(
    private val logStore: DiagnosticLogStore,
    private val onPcmData: (ShortArray) -> Unit,
) {
    companion object {
        private const val SOURCE = "Mic"
        private const val INPUT_SAMPLE_RATE = 16_000
        private const val MIN_GAIN_LOG_INTERVAL_MS = 1_500L
        private const val MIN_GAIN_LOG_DELTA = 0.10f
    }

    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSizeInBytes = max(
        AudioRecord.getMinBufferSize(INPUT_SAMPLE_RATE, channelConfig, audioEncoding),
        INPUT_SAMPLE_RATE / 5,
    )

    @Volatile
    private var gainMultiplier = 1f

    @Volatile
    private var isRecording = false

    @Volatile
    private var lastLoggedGainMultiplier = Float.NaN

    @Volatile
    private var lastGainLogAtMs = 0L

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null

    fun updateGain(gainMultiplier: Float) {
        this.gainMultiplier = gainMultiplier.coerceAtLeast(0f)
        val now = System.currentTimeMillis()
        val gainDelta = kotlin.math.abs(this.gainMultiplier - lastLoggedGainMultiplier)
        val shouldLog = lastLoggedGainMultiplier.isNaN() ||
            gainDelta >= MIN_GAIN_LOG_DELTA ||
            now - lastGainLogAtMs >= MIN_GAIN_LOG_INTERVAL_MS
        if (shouldLog) {
            lastLoggedGainMultiplier = this.gainMultiplier
            lastGainLogAtMs = now
            logStore.info(SOURCE, "Microphone gain updated to ${"%.2f".format(this.gainMultiplier)}x")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) return

        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioEncoding)
                        .setSampleRate(INPUT_SAMPLE_RATE)
                        .setChannelMask(channelConfig)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSizeInBytes)
                .build()
        } catch (t: Throwable) {
            logStore.error(SOURCE, "Unable to create AudioRecord", t)
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            logStore.error(SOURCE, "AudioRecord did not initialize")
            recorder.release()
            return
        }

        try {
            recorder.startRecording()
        } catch (t: Throwable) {
            logStore.error(SOURCE, "Unable to start microphone capture", t)
            recorder.release()
            return
        }

        audioRecord = recorder
        isRecording = true
        recordThread = Thread(
            {
                val buffer = ShortArray(bufferSizeInBytes / 2)
                try {
                    while (isRecording && !Thread.currentThread().isInterrupted) {
                        val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (read > 0) {
                            val chunk = buffer.copyOf(read)
                            applyGain(chunk)
                            onPcmData(chunk)
                        } else if (read < 0) {
                            logStore.error(SOURCE, "AudioRecord.read failed with code=$read")
                            break
                        }
                    }
                } catch (t: Throwable) {
                    if (isRecording) {
                        logStore.error(SOURCE, "Microphone capture loop failed", t)
                    }
                } finally {
                    stopInternal(logReason = "capture loop finished")
                }
            },
            "carplay-mic-input",
        ).also { thread ->
            thread.isDaemon = true
            thread.start()
        }

        logStore.info(
            SOURCE,
            "Microphone capture started ${INPUT_SAMPLE_RATE}Hz mono gain=${"%.2f".format(gainMultiplier)}x",
        )
        lastLoggedGainMultiplier = gainMultiplier
        lastGainLogAtMs = System.currentTimeMillis()
    }

    fun stop(reason: String = "stop requested") {
        stopInternal(reason)
    }

    private fun applyGain(samples: ShortArray) {
        val gain = gainMultiplier
        if (gain == 1f) return
        for (index in samples.indices) {
            val amplified = (samples[index] * gain).toInt().coerceIn(
                Short.MIN_VALUE.toInt(),
                Short.MAX_VALUE.toInt(),
            )
            samples[index] = amplified.toShort()
        }
    }

    private fun stopInternal(logReason: String) {
        if (!isRecording && audioRecord == null && recordThread == null) return

        isRecording = false
        recordThread?.interrupt()

        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }

        try {
            audioRecord?.release()
        } catch (_: Throwable) {
        }
        audioRecord = null

        val currentThread = Thread.currentThread()
        val thread = recordThread
        if (thread != null && thread !== currentThread) {
            try {
                thread.join(200)
            } catch (_: InterruptedException) {
                currentThread.interrupt()
            }
        }
        recordThread = null
        logStore.info(SOURCE, "Microphone capture stopped: $logReason")
    }
}
