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
    fun `official command ids match firmware reference`() {
        assertThat(Cpc200Protocol.Command.START_RECORD_AUDIO).isEqualTo(1)
        assertThat(Cpc200Protocol.Command.STOP_RECORD_AUDIO).isEqualTo(2)
        assertThat(Cpc200Protocol.Command.USE_CAR_MIC).isEqualTo(7)
        assertThat(Cpc200Protocol.Command.USE_BOX_MIC).isEqualTo(8)
        assertThat(Cpc200Protocol.Command.REQUEST_KEY_FRAME).isEqualTo(12)
        assertThat(Cpc200Protocol.Command.USE_PHONE_MIC).isEqualTo(21)
        assertThat(Cpc200Protocol.Command.USE_BLUETOOTH_AUDIO).isEqualTo(22)
        assertThat(Cpc200Protocol.Command.USE_BOX_TRANS_AUDIO).isEqualTo(23)
        assertThat(Cpc200Protocol.Command.USE_24G_WIFI).isEqualTo(24)
        assertThat(Cpc200Protocol.Command.USE_5G_WIFI).isEqualTo(25)
        assertThat(Cpc200Protocol.Command.START_BLE_ADV).isEqualTo(30)
        assertThat(Cpc200Protocol.Command.STOP_BLE_ADV).isEqualTo(31)
        assertThat(Cpc200Protocol.Command.START_AUTO_CONNECT).isEqualTo(1002)
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

    @Test
    fun `audioInput serializes mono pcm payload like working plugin`() {
        val message = Cpc200Protocol.audioInput(shortArrayOf(100, -200))
        val header = Cpc200Protocol.parseHeader(message.copyOfRange(0, Cpc200Protocol.HEADER_SIZE))
        val payload = message.copyOfRange(Cpc200Protocol.HEADER_SIZE, message.size)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        assertThat(header.type).isEqualTo(Cpc200Protocol.MessageType.AUDIO)
        assertThat(buffer.int).isEqualTo(Cpc200Protocol.AudioDecodeType.PCM_16_MONO)
        assertThat(buffer.float).isEqualTo(0f)
        assertThat(buffer.int).isEqualTo(3)
        assertThat(buffer.short.toInt()).isEqualTo(100)
        assertThat(buffer.short.toInt()).isEqualTo(-200)
    }

    @Test
    fun `parseMediaData decodes metadata json payload`() {
        val payloadBody = """{"MediaSongName":"Song","MediaArtistName":"Artist"}"""
            .toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        val payload = ByteBuffer.allocate(4 + payloadBody.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1)
            .put(payloadBody)
            .array()

        val mediaData = Cpc200Protocol.parseMediaData(payload)

        assertThat(mediaData).isInstanceOf(Cpc200Protocol.MediaDataPayload.Metadata::class.java)
        val metadata = mediaData as Cpc200Protocol.MediaDataPayload.Metadata
        assertThat(metadata.rawJson).contains("\"MediaSongName\":\"Song\"")
        assertThat(metadata.rawJson).contains("\"MediaArtistName\":\"Artist\"")
    }

    @Test
    fun `parseMediaData decodes album cover bytes`() {
        val coverBytes = byteArrayOf(1, 2, 3, 4)
        val payload = ByteBuffer.allocate(4 + coverBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(3)
            .put(coverBytes)
            .array()

        val mediaData = Cpc200Protocol.parseMediaData(payload)

        assertThat(mediaData).isInstanceOf(Cpc200Protocol.MediaDataPayload.AlbumCover::class.java)
        val albumCover = mediaData as Cpc200Protocol.MediaDataPayload.AlbumCover
        assertThat(albumCover.bytes.toList()).containsExactlyElementsIn(coverBytes.toList()).inOrder()
    }
}
