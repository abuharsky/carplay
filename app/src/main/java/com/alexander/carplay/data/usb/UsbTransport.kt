package com.alexander.carplay.data.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.alexander.carplay.data.protocol.Cpc200Protocol
import java.io.IOException

interface UsbTransport {
    fun findKnownDevice(): UsbDevice?

    fun isKnownDevice(device: UsbDevice?): Boolean

    fun hasPermission(device: UsbDevice): Boolean

    fun requestPermission(device: UsbDevice)

    @Throws(IOException::class)
    fun open(device: UsbDevice): DongleConnectionSession
}

interface DongleConnectionSession {
    val description: String

    val isReplay: Boolean
        get() = false

    @Throws(IOException::class)
    fun readHeader(timeoutMs: Int): ByteArray?

    @Throws(IOException::class)
    fun readPayload(length: Int, timeoutMs: Int): ByteArray

    @Throws(IOException::class)
    fun readPayloadInto(
        target: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int,
    ): Int

    @Throws(IOException::class)
    fun write(
        data: ByteArray,
        timeoutMs: Int,
    )

    fun close()
}

class AndroidUsbTransport(
    context: Context,
    private val logStore: DiagnosticLogStore,
) : UsbTransport {
    companion object {
        const val ACTION_USB_PERMISSION = "com.alexander.carplay.USB_PERMISSION"
        private const val SOURCE = "USB"
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    override fun findKnownDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { isKnownDevice(it) }

    override fun isKnownDevice(device: UsbDevice?): Boolean {
        if (device == null) return false
        return device.vendorId == Cpc200Protocol.Vendor.ID &&
            (device.productId == Cpc200Protocol.Vendor.PID_PRIMARY ||
                device.productId == Cpc200Protocol.Vendor.PID_SECONDARY)
    }

    override fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    override fun requestPermission(device: UsbDevice) {
        val intent = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        logStore.info(SOURCE, "Requesting USB permission for ${device.deviceName}")
        usbManager.requestPermission(device, intent)
    }

    override fun open(device: UsbDevice): DongleConnectionSession {
        val connection = usbManager.openDevice(device)
            ?: throw IOException("Unable to open USB device ${device.deviceName}")

        val usbInterface = device.getInterface(0)
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            throw IOException("Unable to claim USB interface for ${device.deviceName}")
        }

        val inputEndpoint = findEndpoint(usbInterface, UsbConstants.USB_DIR_IN)
            ?: throw IOException("Bulk IN endpoint not found")
        val outputEndpoint = findEndpoint(usbInterface, UsbConstants.USB_DIR_OUT)
            ?: throw IOException("Bulk OUT endpoint not found")

        logStore.info(
            SOURCE,
            "USB open: ${device.deviceName}, pid=0x${device.productId.toString(16)}, iface=${usbInterface.id}",
        )

        return AndroidUsbConnectionSession(
            device = device,
            connection = connection,
            usbInterface = usbInterface,
            inputEndpoint = inputEndpoint,
            outputEndpoint = outputEndpoint,
        )
    }

    private fun findEndpoint(
        usbInterface: UsbInterface,
        direction: Int,
    ): UsbEndpoint? = (0 until usbInterface.endpointCount)
        .map { usbInterface.getEndpoint(it) }
        .firstOrNull { endpoint ->
            endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == direction
        }

    private inner class AndroidUsbConnectionSession(
        private val device: UsbDevice,
        private val connection: UsbDeviceConnection,
        private val usbInterface: UsbInterface,
        private val inputEndpoint: UsbEndpoint,
        private val outputEndpoint: UsbEndpoint,
    ) : DongleConnectionSession {
        override val description: String =
            "${device.deviceName} (0x${device.productId.toString(16)})"

        override fun readHeader(timeoutMs: Int): ByteArray? {
            val header = ByteArray(Cpc200Protocol.HEADER_SIZE)
            val actual = readExact(
                target = header,
                offset = 0,
                length = header.size,
                timeoutMs = timeoutMs,
                allowIdleTimeoutAtStart = true,
            )
            return when {
                actual == 0 -> null
                actual == header.size -> header
                else -> throw IOException("Header read mismatch: expected ${header.size}, got $actual")
            }
        }

        override fun readPayload(length: Int, timeoutMs: Int): ByteArray {
            val payload = ByteArray(length)
            val actual = readExact(
                target = payload,
                offset = 0,
                length = length,
                timeoutMs = timeoutMs,
                allowIdleTimeoutAtStart = false,
            )
            if (actual != length) {
                throw IOException("Payload read mismatch: expected $length, got $actual")
            }
            return payload
        }

        override fun readPayloadInto(
            target: ByteArray,
            offset: Int,
            length: Int,
            timeoutMs: Int,
        ): Int = readExact(
            target = target,
            offset = offset,
            length = length,
            timeoutMs = timeoutMs,
            allowIdleTimeoutAtStart = false,
        )

        override fun write(
            data: ByteArray,
            timeoutMs: Int,
        ) {
            var offset = 0
            while (offset < data.size) {
                val chunk = minOf(Cpc200Protocol.MAX_USB_CHUNK, data.size - offset)
                val actual = connection.bulkTransfer(outputEndpoint, data, offset, chunk, timeoutMs)
                when {
                    actual < 0 -> throw IOException("bulkTransfer OUT failed at offset=$offset")
                    actual == 0 -> throw IOException("bulkTransfer OUT timed out at offset=$offset")
                    else -> offset += actual
                }
            }
        }

        override fun close() {
            try {
                connection.releaseInterface(usbInterface)
            } catch (_: Throwable) {
            }
            connection.close()
        }

        private fun readExact(
            target: ByteArray,
            offset: Int,
            length: Int,
            timeoutMs: Int,
            allowIdleTimeoutAtStart: Boolean,
        ): Int {
            require(offset >= 0 && length >= 0 && offset + length <= target.size)

            var totalRead = 0
            var idleReads = 0
            while (totalRead < length) {
                val chunk = minOf(Cpc200Protocol.MAX_USB_CHUNK, length - totalRead)
                val actual = connection.bulkTransfer(
                    inputEndpoint,
                    target,
                    offset + totalRead,
                    chunk,
                    timeoutMs,
                )
                when {
                    actual > 0 -> {
                        totalRead += actual
                        idleReads = 0
                    }

                    actual == 0 || actual == -1 -> {
                        idleReads += 1
                        if (totalRead == 0 && allowIdleTimeoutAtStart) {
                            return 0
                        }
                        if (idleReads >= 3) {
                            throw IOException(
                                "bulkTransfer IN timed out after partial read ($totalRead/$length, last=$actual)",
                            )
                        }
                    }

                    else -> {
                        throw IOException("bulkTransfer IN failed with $actual")
                    }
                }
            }
            return totalRead
        }
    }
}
