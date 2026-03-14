package com.alexander.carplay.data.replay

import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.alexander.carplay.data.protocol.Cpc200Protocol
import com.alexander.carplay.data.usb.DongleConnectionSession
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class CaptureReplaySession(
    capturePath: String,
    private val logStore: DiagnosticLogStore,
) : DongleConnectionSession {
    companion object {
        private const val SOURCE = "Replay"
    }

    private val captureFile = File(capturePath)
    private val input = BufferedInputStream(FileInputStream(captureFile))
    private var closed = false

    override val description: String = "Replay ${captureFile.name}"
    override val isReplay: Boolean = true

    override fun readHeader(timeoutMs: Int): ByteArray? {
        ensureOpen()
        val header = ByteArray(Cpc200Protocol.HEADER_SIZE)
        val actual = readFully(header, 0, header.size, allowEofAtStart = true)
        return when {
            actual == 0 -> null
            actual == header.size -> header
            else -> throw EOFException("Unexpected EOF while reading replay header")
        }
    }

    override fun readPayload(
        length: Int,
        timeoutMs: Int,
    ): ByteArray {
        ensureOpen()
        val payload = ByteArray(length)
        val actual = readFully(payload, 0, length, allowEofAtStart = false)
        if (actual != length) {
            throw EOFException("Unexpected EOF while reading replay payload ($actual/$length)")
        }
        return payload
    }

    override fun readPayloadInto(
        target: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int,
    ): Int {
        ensureOpen()
        return readFully(target, offset, length, allowEofAtStart = false)
    }

    override fun write(
        data: ByteArray,
        timeoutMs: Int,
    ) {
        if (data.size < Cpc200Protocol.HEADER_SIZE) {
            logStore.info(SOURCE, "Ignoring short outgoing replay message (${data.size} bytes)")
            return
        }

        runCatching {
            val header = Cpc200Protocol.parseHeader(data.copyOfRange(0, Cpc200Protocol.HEADER_SIZE))
            when (header.type) {
                Cpc200Protocol.MessageType.HEARTBEAT -> Unit
                Cpc200Protocol.MessageType.COMMAND -> {
                    val payload = data.copyOfRange(
                        Cpc200Protocol.HEADER_SIZE,
                        Cpc200Protocol.HEADER_SIZE + header.length,
                    )
                    val command = Cpc200Protocol.parseCommand(payload)
                    logStore.info(SOURCE, "Outgoing command ignored during replay: $command")
                }

                else -> logStore.info(
                    SOURCE,
                    "Outgoing message ignored during replay: type=0x${header.type.toString(16)} length=${header.length}",
                )
            }
        }.onFailure { throwable ->
            logStore.error(SOURCE, "Failed to inspect outgoing replay message", throwable)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        input.close()
    }

    private fun readFully(
        target: ByteArray,
        offset: Int,
        length: Int,
        allowEofAtStart: Boolean,
    ): Int {
        var totalRead = 0
        while (totalRead < length) {
            val actual = input.read(target, offset + totalRead, length - totalRead)
            when {
                actual > 0 -> totalRead += actual
                actual < 0 && totalRead == 0 && allowEofAtStart -> return 0
                actual < 0 -> throw EOFException("Unexpected EOF in replay stream")
            }
        }
        return totalRead
    }

    private fun ensureOpen() {
        if (closed) {
            throw IOException("Replay session already closed")
        }
    }
}
