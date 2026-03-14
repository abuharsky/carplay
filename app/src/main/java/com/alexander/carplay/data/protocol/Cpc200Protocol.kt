package com.alexander.carplay.data.protocol

import com.alexander.carplay.domain.model.TouchContact
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale

object Cpc200Protocol {
    const val MAGIC = 0x55AA55AA
    const val HEADER_SIZE = 16
    const val VIDEO_SUB_HEADER_SIZE = 20
    const val AUDIO_SUB_HEADER_SIZE = 12
    const val MAX_USB_CHUNK = 16_384

    object Vendor {
        const val ID = 0x1314
        const val PID_PRIMARY = 0x1520
        const val PID_SECONDARY = 0x1521
    }

    object MessageType {
        const val OPEN = 0x01
        const val PLUGGED = 0x02
        const val PHASE = 0x03
        const val UNPLUGGED = 0x04
        const val TOUCH = 0x05
        const val VIDEO = 0x06
        const val AUDIO = 0x07
        const val COMMAND = 0x08
        const val LOGO_TYPE = 0x09
        const val BT_ADDRESS = 0x0A
        const val SELECT_BT_DEVICE = 0x11
        const val BT_PIN = 0x0C
        const val BT_DEVICE_NAME = 0x0D
        const val WIFI_DEVICE_NAME = 0x0E
        const val DISCONNECT_PHONE = 0x0F
        const val BT_PAIRED_LIST = 0x12
        const val MANUFACTURER_INFO = 0x14
        const val CLOSE_DONGLE = 0x15
        const val MULTI_TOUCH = 0x17
        const val HICAR = 0x18
        const val BOX_SETTINGS = 0x19
        const val SELECTED_DEVICE = 0x23
        const val ACTIVE_DEVICE = 0x24
        const val UNKNOWN_37 = 0x25
        const val UNKNOWN_38 = 0x26
        const val MEDIA_DATA = 0x2A
        const val NAVI_VIDEO = 0x2C
        const val SEND_FILE = 0x99
        const val HEARTBEAT = 0xAA
        const val SOFTWARE_VERSION = 0xCC
    }

    object Command {
        const val REQUEST_HOST_UI = 3
        const val SIRI = 5
        const val MIC = 7
        const val FRAME_REQUEST = 12
        const val BOX_MIC = 15
        const val NIGHT_MODE_ON = 16
        const val NIGHT_MODE_OFF = 17
        const val AUDIO_TRANSFER_ON = 22
        const val AUDIO_TRANSFER_OFF = 23
        const val WIFI_24 = 24
        const val WIFI_5 = 25
        const val HOME = 200
        const val REQUEST_VIDEO_FOCUS = 500
        const val RELEASE_VIDEO_FOCUS = 501
        const val WIFI_ENABLE = 1000
        const val AUTO_CONNECT_ENABLE = 1001
        const val WIFI_CONNECT = 1002
        const val SCANNING_DEVICE = 1003
        const val DEVICE_FOUND = 1004
        const val DEVICE_NOT_FOUND = 1005
        const val CONNECT_DEVICE_FAILED = 1006
        const val BT_CONNECTED = 1007
        const val BT_DISCONNECTED = 1008
        const val WIFI_CONNECTED = 1009
        const val WIFI_DISCONNECTED = 1010
        const val BT_PAIR_START = 1011
        const val WIFI_PAIR = 1012
    }

    object PhoneType {
        const val ANDROID_MIRROR = 1
        const val CARPLAY = 3
        const val IPHONE_MIRROR = 4
        const val ANDROID_AUTO = 5
        const val HICAR = 6
    }

    object AudioDecodeType {
        const val PCM_44_STEREO_A = 1
        const val PCM_44_STEREO_B = 2
        const val PCM_8_MONO = 3
        const val PCM_48_STEREO = 4
        const val PCM_16_MONO = 5
        const val PCM_24_MONO = 6
        const val PCM_16_STEREO = 7
    }

    object AudioCommand {
        const val OUTPUT_START = 1
        const val OUTPUT_STOP = 2
        const val INPUT_CONFIG = 3
        const val PHONE_START = 4
        const val PHONE_STOP = 5
        const val NAVI_START = 6
        const val NAVI_STOP = 7
        const val SIRI_START = 8
        const val SIRI_STOP = 9
        const val MEDIA_START = 10
        const val MEDIA_STOP = 11
        const val ALERT_START = 12
        const val ALERT_STOP = 13
    }

    data class Header(
        val length: Int,
        val type: Int,
    )

    data class PluggedInfo(
        val phoneType: Int,
        val wifiState: Int?,
    )

    data class OpenInfo(
        val width: Int,
        val height: Int,
        val fps: Int,
        val format: Int,
        val packetMax: Int,
        val iBoxVersion: Int,
        val phoneWorkMode: Int,
    )

    data class VideoMeta(
        val width: Int,
        val height: Int,
        val flags: Int,
        val dataLength: Int,
        val unknown: Int,
    )

    data class AudioPacket(
        val decodeType: Int,
        val volume: Float,
        val audioType: Int,
        val payload: ByteArray,
        val command: Int?,
    )

    data class DashboardData(
        val subtype: Int,
        val payloadText: String,
    )

    fun alignTo16(value: Int): Int = (value + 15) and 0xFFFFFFF0.toInt()

    fun buildHeader(
        type: Int,
        payloadLength: Int,
    ): ByteArray = ByteBuffer
        .allocate(HEADER_SIZE)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(MAGIC)
        .putInt(payloadLength)
        .putInt(type)
        .putInt(type.inv())
        .array()

    fun wrapMessage(
        type: Int,
        payload: ByteArray = byteArrayOf(),
    ): ByteArray = buildHeader(type, payload.size) + payload

    fun heartbeat(): ByteArray = buildHeader(MessageType.HEARTBEAT, 0)

    fun disconnectPhone(): ByteArray = buildHeader(MessageType.DISCONNECT_PHONE, 0)

    fun command(commandId: Int): ByteArray = wrapMessage(
        MessageType.COMMAND,
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(commandId).array(),
    )

    fun selectDevice(macAddress: String): ByteArray = wrapMessage(
        MessageType.SELECT_BT_DEVICE,
        normalizeDeviceIdentifier(macAddress).toByteArray(Charsets.US_ASCII),
    )

    fun open(config: ProjectionSessionConfig): ByteArray {
        val payload = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(config.width)
            .putInt(config.height)
            .putInt(config.fps)
            .putInt(config.format)
            .putInt(config.packetMax)
            .putInt(config.iBoxVersion)
            .putInt(config.phoneWorkMode)
            .array()
        return wrapMessage(MessageType.OPEN, payload)
    }

    fun sendFile(
        fileName: String,
        content: ByteArray,
    ): ByteArray {
        val nameBytes = (fileName + "\u0000").toByteArray(Charsets.US_ASCII)
        val payload = ByteBuffer.allocate(8 + nameBytes.size + content.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(nameBytes.size)
            .put(nameBytes)
            .putInt(content.size)
            .put(content)
            .array()
        return wrapMessage(MessageType.SEND_FILE, payload)
    }

    fun sendNumber(
        fileName: String,
        value: Int,
    ): ByteArray = sendFile(
        fileName,
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(),
    )

    fun sendBoolean(
        fileName: String,
        enabled: Boolean,
    ): ByteArray = sendNumber(fileName, if (enabled) 1 else 0)

    fun sendString(
        fileName: String,
        value: String,
    ): ByteArray = sendFile(fileName, value.toByteArray(Charsets.US_ASCII))

    fun boxSettings(config: ProjectionSessionConfig): ByteArray {
        val payload = buildBoxSettingsJson(config).toByteArray(Charsets.UTF_8)

        return wrapMessage(MessageType.BOX_SETTINGS, payload)
    }

    fun extendedBoxSettings(config: ExtendedBoxSettingsConfig): ByteArray {
        val payload = buildExtendedBoxSettingsJson(config).toByteArray(Charsets.UTF_8)
        return wrapMessage(MessageType.BOX_SETTINGS, payload)
    }

    fun airplayConfig(config: ProjectionSessionConfig): ByteArray = sendFile(
        "/etc/airplay.conf",
        generateAirplayConfig(config).toByteArray(Charsets.UTF_8),
    )

    fun oemIcon(config: ProjectionSessionConfig): ByteArray? =
        config.oemBranding.oemIconPng?.let { sendFile("/etc/oem_icon.png", it) }

    fun icon120(config: ProjectionSessionConfig): ByteArray? =
        config.oemBranding.icon120Png?.let { sendFile("/etc/icon_120x120.png", it) }

    fun icon180(config: ProjectionSessionConfig): ByteArray? =
        config.oemBranding.icon180Png?.let { sendFile("/etc/icon_180x180.png", it) }

    fun icon256(config: ProjectionSessionConfig): ByteArray? =
        config.oemBranding.icon256Png?.let { sendFile("/etc/icon_256x256.png", it) }

    fun multiTouch(contacts: List<TouchContact>): ByteArray {
        val payload = ByteBuffer.allocate(contacts.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        contacts.forEach { contact ->
            payload.putFloat(contact.x)
            payload.putFloat(contact.y)
            payload.putInt(contact.action.protocolValue)
            payload.putInt(contact.id)
        }
        return wrapMessage(MessageType.MULTI_TOUCH, payload.array())
    }

    fun parseHeader(bytes: ByteArray): Header {
        require(bytes.size == HEADER_SIZE) { "Header must be 16 bytes." }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.int
        require(magic == MAGIC) { "Invalid magic: 0x${magic.toUInt().toString(16)}" }
        val length = buffer.int
        val type = buffer.int
        val typeCheck = buffer.int
        require(typeCheck == type.inv()) { "Invalid type check for message 0x${type.toString(16)}" }
        return Header(length = length, type = type)
    }

    fun parsePlugged(payload: ByteArray): PluggedInfo {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val phoneType = buffer.int
        val wifiState = if (payload.size >= 8) buffer.int else null
        return PluggedInfo(phoneType, wifiState)
    }

    fun parseOpen(payload: ByteArray): OpenInfo {
        require(payload.size >= 28) { "Open payload must be at least 28 bytes." }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return OpenInfo(
            width = buffer.int,
            height = buffer.int,
            fps = buffer.int,
            format = buffer.int,
            packetMax = buffer.int,
            iBoxVersion = buffer.int,
            phoneWorkMode = buffer.int,
        )
    }

    fun parseCommand(payload: ByteArray): Int =
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int

    fun parseDeviceIdentifier(payload: ByteArray): String =
        normalizeDeviceIdentifier(payload.toString(Charsets.UTF_8))

    fun normalizeDeviceIdentifier(value: String): String {
        val trimmed = value.trimEnd('\u0000').trim()
        return if (MAC_ADDRESS_PATTERN.matches(trimmed)) {
            trimmed.uppercase(Locale.US)
        } else {
            trimmed
        }
    }

    fun parsePhase(payload: ByteArray): Int =
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int

    fun parseVideoMeta(buffer: ByteArray, offset: Int): VideoMeta {
        val wrapped = ByteBuffer.wrap(buffer, offset, VIDEO_SUB_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        return VideoMeta(
            width = wrapped.int,
            height = wrapped.int,
            flags = wrapped.int,
            dataLength = wrapped.int,
            unknown = wrapped.int,
        )
    }

    fun parseAudioPacket(payload: ByteArray): AudioPacket {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val decodeType = buffer.int
        val volume = buffer.float
        val audioType = buffer.int
        val remaining = ByteArray(buffer.remaining())
        buffer.get(remaining)
        val command = if (remaining.size == 1) remaining[0].toInt() and 0xFF else null
        return AudioPacket(
            decodeType = decodeType,
            volume = volume,
            audioType = audioType,
            payload = remaining,
            command = command,
        )
    }

    fun parseDashboardData(payload: ByteArray): DashboardData {
        require(payload.size >= 4) { "DashboardData payload must be at least 4 bytes." }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val subtype = buffer.int
        val body = ByteArray(buffer.remaining())
        buffer.get(body)
        val payloadText = body.toString(Charsets.UTF_8).trim('\u0000').trim()
        return DashboardData(
            subtype = subtype,
            payloadText = payloadText,
        )
    }

    fun describePhoneType(phoneType: Int): String = when (phoneType) {
        PhoneType.ANDROID_MIRROR -> "AndroidMirror"
        PhoneType.CARPLAY -> "CarPlay"
        PhoneType.IPHONE_MIRROR -> "iPhoneMirror"
        PhoneType.ANDROID_AUTO -> "AndroidAuto"
        PhoneType.HICAR -> "HiCar"
        else -> "Unknown($phoneType)"
    }

    fun describeMessageType(type: Int): String = when (type) {
        MessageType.OPEN -> "Open"
        MessageType.PLUGGED -> "Plugged"
        MessageType.PHASE -> "Phase"
        MessageType.UNPLUGGED -> "Unplugged"
        MessageType.TOUCH -> "Touch"
        MessageType.VIDEO -> "Video"
        MessageType.AUDIO -> "Audio"
        MessageType.COMMAND -> "Command"
        MessageType.LOGO_TYPE -> "LogoType"
        MessageType.BT_ADDRESS -> "BluetoothAddress"
        MessageType.SELECT_BT_DEVICE -> "SelectBtDevice"
        MessageType.BT_PIN -> "BluetoothPin"
        MessageType.BT_DEVICE_NAME -> "BluetoothDeviceName"
        MessageType.WIFI_DEVICE_NAME -> "WifiDeviceName"
        MessageType.DISCONNECT_PHONE -> "DisconnectPhone"
        MessageType.BT_PAIRED_LIST -> "BluetoothPairedList"
        MessageType.MANUFACTURER_INFO -> "ManufacturerInfo"
        MessageType.CLOSE_DONGLE -> "CloseDongle"
        MessageType.MULTI_TOUCH -> "MultiTouch"
        MessageType.HICAR -> "HiCar"
        MessageType.BOX_SETTINGS -> "BoxSettings"
        MessageType.SELECTED_DEVICE -> "SelectedDevice"
        MessageType.ACTIVE_DEVICE -> "ActiveDevice"
        MessageType.UNKNOWN_37 -> "Unknown37"
        MessageType.UNKNOWN_38 -> "Unknown38"
        MessageType.MEDIA_DATA -> "MediaData"
        MessageType.NAVI_VIDEO -> "NaviVideo"
        MessageType.SEND_FILE -> "SendFile"
        MessageType.HEARTBEAT -> "HeartBeat"
        MessageType.SOFTWARE_VERSION -> "SoftwareVersion"
        else -> "Message(0x${type.toString(16)})"
    }

    fun describeCommand(commandId: Int): String = when (commandId) {
        Command.REQUEST_HOST_UI -> "requestHostUi"
        Command.SIRI -> "siri"
        Command.MIC -> "mic"
        Command.FRAME_REQUEST -> "frameRequest"
        Command.BOX_MIC -> "boxMic"
        Command.NIGHT_MODE_ON -> "nightModeOn"
        Command.NIGHT_MODE_OFF -> "nightModeOff"
        Command.AUDIO_TRANSFER_ON -> "audioTransferOn"
        Command.AUDIO_TRANSFER_OFF -> "audioTransferOff"
        Command.WIFI_24 -> "wifi24g"
        Command.WIFI_5 -> "wifi5g"
        Command.HOME -> "home"
        Command.REQUEST_VIDEO_FOCUS -> "requestVideoFocus"
        Command.RELEASE_VIDEO_FOCUS -> "releaseVideoFocus"
        Command.WIFI_ENABLE -> "wifiEnable"
        Command.AUTO_CONNECT_ENABLE -> "autoConnectEnable"
        Command.WIFI_CONNECT -> "wifiConnect"
        Command.SCANNING_DEVICE -> "scanningDevice"
        Command.DEVICE_FOUND -> "deviceFound"
        Command.DEVICE_NOT_FOUND -> "deviceNotFound"
        Command.CONNECT_DEVICE_FAILED -> "connectDeviceFailed"
        Command.BT_CONNECTED -> "btConnected"
        Command.BT_DISCONNECTED -> "btDisconnected"
        Command.WIFI_CONNECTED -> "wifiConnected"
        Command.WIFI_DISCONNECTED -> "wifiDisconnected"
        Command.BT_PAIR_START -> "btPairStart"
        Command.WIFI_PAIR -> "wifiPair"
        else -> "Command($commandId)"
    }

    private val MAC_ADDRESS_PATTERN = Regex("[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}")

    private fun sanitizeAdapterText(input: String): String =
        input.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == ' ' }
            .trim()
            .ifBlank { "CarPlayDiag" }
            .take(16)

    private fun buildBoxSettingsJson(config: ProjectionSessionConfig): String {
        return buildString {
            append('{')
            appendJsonField("mediaDelay", config.mediaDelay)
            append(',')
            appendJsonField("syncTime", System.currentTimeMillis() / 1000)
            append(',')
            appendJsonField("androidAutoSizeW", config.width)
            append(',')
            appendJsonField("androidAutoSizeH", config.height)
            append('}')
        }
    }

    private fun buildExtendedBoxSettingsJson(config: ExtendedBoxSettingsConfig): String {

        val gnssCapability = if (config.gnssCapability) 3 else 0
        val injection =
            buildString {
                append("a\"; ")
                append("/usr/sbin/riddleBoxCfg -s GNSSCapability $gnssCapability; ")
                append("/usr/sbin/riddleBoxCfg -s DashboardInfo 5; ")
                append("/usr/sbin/riddleBoxCfg -s AdvancedFeatures 1; ")
                append("rm -f /etc/RiddleBoxData/AIEIPIEREngines.datastore; ")
                append("/usr/sbin/riddleBoxCfg --upConfig; ")
                append("echo \"")
            }

        val json =
            JSONObject().apply {
                put("wifiName", injection)
            }

        return json.toString()
    }

    private fun generateAirplayConfig(config: ProjectionSessionConfig): String = buildString {
        append("oemIconVisible = ")
        append(if (config.oemBranding.visible) 1 else 0)
        append('\n')
        append("name = ")
        append(config.oemBranding.name)
        append('\n')
        append("model = ")
        append(config.oemBranding.model)
        append('\n')
        append("oemIconPath = /etc/oem_icon.png\n")
        append("oemIconLabel = ")
        append(config.oemBranding.label)
        append('\n')
    }

    private fun MutableList<String>.addJsonField(
        key: String,
        value: Any,
    ) {
        add(
            buildString {
                appendJsonField(key, value)
            },
        )
    }

    private fun StringBuilder.appendJsonField(
        key: String,
        value: Any,
    ) {
        append('"')
        append(key)
        append("\":")
        when (value) {
            is String -> {
                append('"')
                append(value.escapeJsonString())
                append('"')
            }

            else -> append(value)
        }
    }

    private fun String.escapeJsonString(): String = buildString(length) {
        this@escapeJsonString.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}
