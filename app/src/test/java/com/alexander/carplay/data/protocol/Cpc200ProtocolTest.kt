package com.alexander.carplay.data.protocol

import com.alexander.carplay.domain.model.ProjectionTouchAction
import com.alexander.carplay.domain.model.TouchContact
import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

class Cpc200ProtocolTest {
    @Test
    fun `header roundtrip preserves payload length and type`() {
        val message = Cpc200Protocol.wrapMessage(
            Cpc200Protocol.MessageType.COMMAND,
            byteArrayOf(1, 2, 3, 4),
        )

        val header = Cpc200Protocol.parseHeader(message.copyOfRange(0, Cpc200Protocol.HEADER_SIZE))

        assertThat(header.type).isEqualTo(Cpc200Protocol.MessageType.COMMAND)
        assertThat(header.length).isEqualTo(4)
    }

    @Test
    fun `parseHeader rejects invalid typeCheck`() {
        val brokenHeader = ByteBuffer.allocate(Cpc200Protocol.HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(Cpc200Protocol.MAGIC)
            .putInt(0)
            .putInt(Cpc200Protocol.MessageType.HEARTBEAT)
            .putInt(0)
            .array()

        assertThrows(IllegalArgumentException::class.java) {
            Cpc200Protocol.parseHeader(brokenHeader)
        }
    }

    @Test
    fun `sendFile writes name length, null terminated path and content length`() {
        val message = Cpc200Protocol.sendFile("/tmp/test_file", byteArrayOf(0x12, 0x34))
        val payload = message.copyOfRange(Cpc200Protocol.HEADER_SIZE, message.size)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        val nameLength = buffer.int
        val nameBytes = ByteArray(nameLength)
        buffer.get(nameBytes)
        val contentLength = buffer.int
        val contentBytes = ByteArray(contentLength)
        buffer.get(contentBytes)

        assertThat(String(nameBytes, Charsets.US_ASCII)).isEqualTo("/tmp/test_file\u0000")
        assertThat(contentLength).isEqualTo(2)
        assertThat(contentBytes.toList()).containsExactly(0x12.toByte(), 0x34.toByte()).inOrder()
    }

    @Test
    fun `boxSettings serializes minimal connection json`() {
        val config = ProjectionSessionConfig(
            width = 1280,
            height = 720,
        )

        val message = Cpc200Protocol.boxSettings(config)
        val payload = String(
            message.copyOfRange(Cpc200Protocol.HEADER_SIZE, message.size),
            Charsets.UTF_8,
        )

        assertThat(payload).contains("\"mediaDelay\":300")
        assertThat(payload).contains("\"androidAutoSizeW\":1280")
        assertThat(payload).contains("\"androidAutoSizeH\":720")
        assertThat(payload).doesNotContain("wifiName")
        assertThat(payload).doesNotContain("\"aaW\":")
    }

    @Test
    fun `multiTouch writes touch contacts sequentially`() {
        val message = Cpc200Protocol.multiTouch(
            listOf(
                TouchContact(0.1f, 0.2f, ProjectionTouchAction.DOWN, 0),
                TouchContact(0.6f, 0.7f, ProjectionTouchAction.MOVE, 1),
            ),
        )
        val payload = message.copyOfRange(Cpc200Protocol.HEADER_SIZE, message.size)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        assertThat(buffer.float).isWithin(0.0001f).of(0.1f)
        assertThat(buffer.float).isWithin(0.0001f).of(0.2f)
        assertThat(buffer.int).isEqualTo(ProjectionTouchAction.DOWN.protocolValue)
        assertThat(buffer.int).isEqualTo(0)
        assertThat(buffer.float).isWithin(0.0001f).of(0.6f)
        assertThat(buffer.float).isWithin(0.0001f).of(0.7f)
        assertThat(buffer.int).isEqualTo(ProjectionTouchAction.MOVE.protocolValue)
        assertThat(buffer.int).isEqualTo(1)
    }

    @Test
    fun `parseDeviceIdentifier trims trailing nulls`() {
        val raw = "D0:6B:78:53:8E:0C\u0000".toByteArray(Charsets.UTF_8)

        val parsed = Cpc200Protocol.parseDeviceIdentifier(raw)

        assertThat(parsed).isEqualTo("D0:6B:78:53:8E:0C")
    }

    @Test
    fun `selectDevice writes normalized ascii mac payload`() {
        val message = Cpc200Protocol.selectDevice("d0:6b:78:53:8e:0c")
        val header = Cpc200Protocol.parseHeader(message.copyOfRange(0, Cpc200Protocol.HEADER_SIZE))
        val payload = message.copyOfRange(Cpc200Protocol.HEADER_SIZE, message.size)

        assertThat(header.type).isEqualTo(Cpc200Protocol.MessageType.SELECT_BT_DEVICE)
        assertThat(String(payload, Charsets.US_ASCII)).isEqualTo("D0:6B:78:53:8E:0C")
    }
}
