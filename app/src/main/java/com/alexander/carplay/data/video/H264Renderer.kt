package com.alexander.carplay.data.video

import android.media.MediaCodec
import android.media.MediaCodec.CodecException
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.alexander.carplay.data.executor.CarPlayExecutors
import com.alexander.carplay.data.logging.DiagnosticLogStore
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

class H264Renderer(
    private val executors: CarPlayExecutors,
    private val logStore: DiagnosticLogStore,
) : LogCallback {
    companion object {
        private const val SOURCE = "Codec"
        private const val INITIAL_RING_BUFFER = 4 * 1024 * 1024
        private const val MAX_INPUT_SIZE_BYTES = 2 * 1024 * 1024
    }

    private val lock = Any()
    private val callbackThread = HandlerThread("carplay-codec-callback").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val ringBuffer = PacketRingByteBuffer(INITIAL_RING_BUFFER).apply {
        setLogCallback(this@H264Renderer)
    }
    private val availableInputBufferIndexes = ArrayDeque<Int>()
    private val presentationTimeUs = AtomicLong(0L)

    @Volatile
    private var currentSurface: Surface? = null
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var codec: MediaCodec? = null

    fun attachSurface(surface: Surface) {
        executors.codecOutput.execute {
            synchronized(lock) {
                currentSurface = surface
                if (codec == null) {
                    createCodecLocked()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        codec?.setOutputSurface(surface)
                        log("Output surface swapped without codec recreation")
                    } catch (t: Throwable) {
                        logStore.error(SOURCE, "setOutputSurface failed, recreating codec", t)
                        recreateCodecLocked()
                    }
                } else {
                    recreateCodecLocked()
                }
            }
            if (ringBuffer.availablePacketsToRead() > 0) {
                executors.codecInput.execute { drainCodecInputBuffers() }
            }
        }
    }

    fun detachSurface() {
        synchronized(lock) {
            currentSurface = null
        }
        log("Surface detached; waiting for next attach")
    }

    fun updateVideoFormat(
        width: Int,
        height: Int,
    ) {
        executors.codecOutput.execute {
            synchronized(lock) {
                if (configuredWidth == width && configuredHeight == height) {
                    return@execute
                }
                configuredWidth = width
                configuredHeight = height
                log("Video format updated to ${width}x$height")
                if (codec != null && currentSurface != null) {
                    recreateCodecLocked()
                } else {
                    createCodecLocked()
                }
            }
            if (ringBuffer.availablePacketsToRead() > 0) {
                executors.codecInput.execute { drainCodecInputBuffers() }
            }
        }
    }

    fun processDataDirect(
        length: Int,
        skipBytes: Int,
        callback: PacketRingByteBuffer.DirectWriteCallback,
    ) {
        ringBuffer.directWriteToBuffer(length, skipBytes, callback)
        executors.codecInput.execute { drainCodecInputBuffers() }
    }

    fun softReset() {
        executors.codecOutput.execute {
            synchronized(lock) {
                try {
                    codec?.flush()
                    codec?.start()
                    log("Codec soft reset (flush/start)")
                } catch (t: Throwable) {
                    logStore.error(SOURCE, "Soft codec reset failed, recreating", t)
                    recreateCodecLocked()
                }
            }
        }
    }

    fun hardReset(reason: String) {
        log("Hard codec reset requested: $reason")
        executors.codecOutput.execute {
            synchronized(lock) {
                recreateCodecLocked()
            }
        }
    }

    fun release() {
        synchronized(lock) {
            releaseCodecLocked()
        }
        callbackThread.quitSafely()
    }

    override fun log(message: String) {
        logStore.info(SOURCE, message)
    }

    private fun createCodecLocked() {
        val surface = currentSurface ?: return
        if (configuredWidth <= 0 || configuredHeight <= 0) return
        if (codec != null) return
        if (!surface.isValid) return

        var createdCodec: MediaCodec? = null
        try {
            createdCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                configuredWidth,
                configuredHeight,
            ).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE_BYTES)
            }

            createdCodec.setCallback(createCodecCallback(), callbackHandler)
            createdCodec.configure(format, surface, null, 0)
            createdCodec.start()
            codec = createdCodec
            availableInputBufferIndexes.clear()
            log("Codec configured for ${configuredWidth}x$configuredHeight")
        } catch (t: Throwable) {
            val details = if (t is CodecException) {
                "Codec creation failed: code=${t.errorCode} recoverable=${t.isRecoverable} transient=${t.isTransient} diagnostic=${t.diagnosticInfo}"
            } else {
                "Codec creation failed"
            }
            logStore.error(SOURCE, details, t)
            try {
                createdCodec?.stop()
            } catch (_: Throwable) {
            }
            try {
                createdCodec?.release()
            } catch (_: Throwable) {
            }
            codec = null
            availableInputBufferIndexes.clear()
        }
    }

    private fun recreateCodecLocked() {
        releaseCodecLocked()
        createCodecLocked()
    }

    private fun releaseCodecLocked() {
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
        availableInputBufferIndexes.clear()
        ringBuffer.reset()
    }

    private fun createCodecCallback(): MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(
            codec: MediaCodec,
            index: Int,
        ) {
            executors.codecInput.execute {
                synchronized(lock) {
                    if (this@H264Renderer.codec !== codec) return@execute
                    availableInputBufferIndexes.addLast(index)
                }
                drainCodecInputBuffers()
            }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            executors.codecOutput.execute {
                try {
                    codec.releaseOutputBuffer(index, info.size > 0)
                } catch (t: Throwable) {
                    logStore.error(SOURCE, "Failed to release output buffer", t)
                }
            }
        }

        override fun onError(
            codec: MediaCodec,
            e: CodecException,
        ) {
            logStore.error(SOURCE, "MediaCodec callback error: ${e.diagnosticInfo}", e)
            hardReset("callback error")
        }

        override fun onOutputFormatChanged(
            codec: MediaCodec,
            format: MediaFormat,
        ) {
            log("Output format changed: $format")
        }
    }

    private fun drainCodecInputBuffers() {
        synchronized(lock) {
            if (codec == null) {
                createCodecLocked()
            }

            val targetCodec = codec ?: return
            while (availableInputBufferIndexes.isNotEmpty() && ringBuffer.availablePacketsToRead() > 0) {
                val inputBufferIndex = availableInputBufferIndexes.removeFirst()
                val packet = ringBuffer.readPacket()
                if (!packet.hasRemaining()) {
                    targetCodec.queueInputBuffer(inputBufferIndex, 0, 0, nextPtsUs(), 0)
                    continue
                }

                val codecBuffer = targetCodec.getInputBuffer(inputBufferIndex) ?: continue
                codecBuffer.clear()
                val size = packet.remaining()
                if (size > codecBuffer.remaining()) {
                    logStore.error(
                        SOURCE,
                        "Input packet too large for codec buffer ($size > ${codecBuffer.remaining()})",
                    )
                    hardReset("input packet overflow")
                    return
                }

                putByteBuffer(codecBuffer, packet)
                targetCodec.queueInputBuffer(inputBufferIndex, 0, size, nextPtsUs(), 0)
            }
        }
    }

    private fun nextPtsUs(): Long = presentationTimeUs.addAndGet(33_333L)

    private fun putByteBuffer(
        destination: ByteBuffer,
        source: ByteBuffer,
    ) {
        destination.put(source)
    }
}
