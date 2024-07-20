package ru.bukharskii.carplay

import CrashCallback
import CrashHandler
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants.USB_DIR_OUT
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BasicMessageChannel
import io.flutter.plugin.common.BinaryCodec
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.view.TextureRegistry
import io.flutter.view.TextureRegistry.SurfaceTextureEntry
import java.lang.Exception
import java.nio.ByteBuffer

private const val ACTION_USB_PERMISSION = "ru.bukharskii.carplay.USB_PERMISSION"

private val pendingIntentFlag =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }

private fun pendingPermissionIntent(context: Context) =
    PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), pendingIntentFlag)

/** CarplayPlugin */
class CarplayPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var channel: MethodChannel

    private lateinit var textureRegistry: TextureRegistry
    private var surfaceTextureEntry: SurfaceTextureEntry? = null

    private var h264Renderer: H264Renderer? = null

    private var applicationContext: Context? = null
    private var usbManager: UsbManager? = null

    private var usbDevice: UsbDevice? = null
    private var usbDeviceConnection: UsbDeviceConnection? = null

    private var readLoopRunning = false

    private val executors: AppExecutors = AppExecutors.getInstance()

    private lateinit var binaryChannel: BasicMessageChannel<ByteBuffer>

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {

        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "carplay")
        channel.setMethodCallHandler(this)

        textureRegistry = flutterPluginBinding.textureRegistry
        applicationContext = flutterPluginBinding.applicationContext
        usbManager = applicationContext?.getSystemService(Context.USB_SERVICE) as UsbManager

        Thread.setDefaultUncaughtExceptionHandler(CrashHandler {
            log("[UncaughtException] "+it)
        })
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        usbManager = null
        applicationContext = null
    }

    fun log(message: String) {
        executors.mainThread().execute {
            channel.invokeMethod("onLogMessage", message)
        }
    }

    fun readingLoopMessageWithData(type: Int, data: ByteArray) {
        executors.mainThread().execute {
            channel.invokeMethod("onReadingLoopMessage", mapOf("type" to type, "data" to data))
        }
    }

    fun readingLoopMessage(type: Int) {
        executors.mainThread().execute {
            channel.invokeMethod("onReadingLoopMessage", mapOf("type" to type))
        }
    }

    fun readingLoopError(error: String) {
        executors.mainThread().execute {
            channel.invokeMethod("onReadingLoopError", error)
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {

            "createTexture" -> {
                surfaceTextureEntry = textureRegistry.createSurfaceTexture()

                var texture = surfaceTextureEntry!!.surfaceTexture()

                val width = call.argument<Int>("width")!!
                val height = call.argument<Int>("height")!!

                if (h264Renderer != null) {
                    h264Renderer?.stop()
                }

                h264Renderer = H264Renderer(applicationContext, width, height, texture,
                    surfaceTextureEntry!!.id().toInt(), LogCallback { m ->  log("[H264Renderer] " + m) })

                h264Renderer?.start()

                result.success(surfaceTextureEntry!!.id())
            }

            "removeTexture" -> {
                h264Renderer?.stop()
                h264Renderer = null

                surfaceTextureEntry?.release()

                result.success(null)
            }

            "resetH264Renderer" -> {
                h264Renderer?.reset()
                result.success(null)
            }

            "getDeviceList" -> {
                val manager =
                    usbManager ?: return result.error("IllegalState", "usbManager null", null)
                val usbDeviceList = manager.deviceList.entries.map {
                    mapOf(
                        "identifier" to it.key,
                        "vendorId" to it.value.vendorId,
                        "productId" to it.value.productId,
                        "configurationCount" to it.value.configurationCount,
                    )
                }
                result.success(usbDeviceList)
            }

            "getDeviceDescription" -> {
                val context = applicationContext ?: return result.error(
                    "IllegalState",
                    "applicationContext null",
                    null
                )
                val manager =
                    usbManager ?: return result.error("IllegalState", "usbManager null", null)
                val identifier = call.argument<Map<String, Any>>("device")!!["identifier"]!!;
                val device = manager.deviceList[identifier] ?: return result.error(
                    "IllegalState",
                    "usbDevice null",
                    null
                )
                val requestPermission = call.argument<Boolean>("requestPermission")!!;

                val hasPermission = manager.hasPermission(device)
                if (requestPermission && !hasPermission) {
                    val permissionReceiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            context.unregisterReceiver(this)
                            val granted =
                                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                            result.success(
                                mapOf(
                                    "manufacturer" to device.manufacturerName,
                                    "product" to device.productName,
                                    "serialNumber" to if (granted) device.serialNumber else null,
                                )
                            )
                        }
                    }
                    context.registerReceiver(
                        permissionReceiver,
                        IntentFilter(ACTION_USB_PERMISSION)
                    )
                    manager.requestPermission(device, pendingPermissionIntent(context))
                } else {
                    result.success(
                        mapOf(
                            "manufacturer" to device.manufacturerName,
                            "product" to device.productName,
                            "serialNumber" to if (hasPermission) device.serialNumber else null,
                        )
                    )
                }
            }

            "hasPermission" -> {
                val manager =
                    usbManager ?: return result.error("IllegalState", "usbManager null", null)
                val identifier = call.argument<String>("identifier")
                val device = manager.deviceList[identifier]
                result.success(manager.hasPermission(device))
            }

            "requestPermission" -> {
                val context = applicationContext ?: return result.error(
                    "IllegalState",
                    "applicationContext null",
                    null
                )
                val manager =
                    usbManager ?: return result.error("IllegalState", "usbManager null", null)
                val identifier = call.argument<String>("identifier")
                val device = manager.deviceList[identifier]
                if (manager.hasPermission(device)) {
                    result.success(true)
                } else {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            context.unregisterReceiver(this)
                            val usbDevice =
                                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                            val granted =
                                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            result.success(granted);
                        }
                    }
                    context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
                    manager.requestPermission(device, pendingPermissionIntent(context))
                }
            }

            "openDevice" -> {
                val manager =
                    usbManager ?: return result.error("IllegalState", "usbManager null", null)
                val identifier = call.argument<String>("identifier")
                usbDevice = manager.deviceList[identifier]
                usbDeviceConnection = manager.openDevice(usbDevice)
                result.success(true)
            }

            "closeDevice" -> {
                usbDeviceConnection?.close()
                usbDeviceConnection = null
                usbDevice = null
                result.success(null)
            }

            "resetDevice" -> {
                try {
                    var resetMethod = usbDeviceConnection?.javaClass?.getDeclaredMethod("resetDevice")
                    resetMethod?.invoke(usbDeviceConnection)

                    result.success(true)
                } catch (e: Exception) {
                    log("[Reset USB error] " + e.toString())
                    result.success(false)
                }

            }

            "getConfiguration" -> {
                val device =
                    usbDevice ?: return result.error("IllegalState", "usbDevice null", null)
                val index = call.argument<Int>("index")!!
                val configuration = device.getConfiguration(index)
                val map = configuration.toMap() + ("index" to index)
                result.success(map)
            }

            "setConfiguration" -> {
                val device =
                    usbDevice ?: return result.error("IllegalState", "usbDevice null", null)
                val connection = usbDeviceConnection ?: return result.error(
                    "IllegalState",
                    "usbDeviceConnection null",
                    null
                )
                val index = call.argument<Int>("index")!!
                val configuration = device.getConfiguration(index)
                result.success(connection.setConfiguration(configuration))
            }

            "claimInterface" -> {
                val device =
                    usbDevice ?: return result.error("IllegalState", "usbDevice null", null)
                val connection = usbDeviceConnection ?: return result.error(
                    "IllegalState",
                    "usbDeviceConnection null",
                    null
                )
                val id = call.argument<Int>("id")!!
                val alternateSetting = call.argument<Int>("alternateSetting")!!
                val usbInterface = device.findInterface(id, alternateSetting)
                result.success(connection.claimInterface(usbInterface, true))
            }

            "releaseInterface" -> {
                val device =
                    usbDevice ?: return result.error("IllegalState", "usbDevice null", null)
                val connection = usbDeviceConnection ?: return result.error(
                    "IllegalState",
                    "usbDeviceConnection null",
                    null
                )
                val id = call.argument<Int>("id")!!
                val alternateSetting = call.argument<Int>("alternateSetting")!!
                val usbInterface = device.findInterface(id, alternateSetting)
                result.success(connection.releaseInterface(usbInterface))
            }

            "stopReadingLoop" -> {
                readLoopRunning = false

                result.success(null)
            }

            "startReadingLoop" -> {
                if (readLoopRunning) {
                    return result.error("IllegalState", "readingLoop running", null)
                }

                val device =
                    usbDevice ?: return result.error("IllegalState", "usbDevice null", null)
                
                val connection = usbDeviceConnection
                    ?: return result.error(
                        "IllegalState",
                        "usbDeviceConnection null",
                        null
                    )

                val endpointMap = call.argument<Map<String, Any>>("endpoint")!!
                val endpoint =
                    device.findEndpoint(
                        endpointMap["endpointNumber"] as Int,
                        endpointMap["direction"] as Int
                    )!!
                    
                val timeout = call.argument<Int>("timeout")!!

                executors.usbIn().execute {

                    log("[READ LOOP] start")
                    readLoopRunning = true;

                    var streamingNotified = false

                    val headerBuffer = ByteBuffer.allocate(CarPlayMessageHeader.MESSAGE_LENGTH)
                    val header = CarPlayMessageHeader(0,0)

                    var actualLength = 0

                    while (readLoopRunning) {

                        // read header
                        headerBuffer.clear()
                        actualLength = readByChunks(connection, endpoint, headerBuffer.array(), 0, CarPlayMessageHeader.MESSAGE_LENGTH, timeout)

                        if (actualLength < 0) {
                            break
                        }

//                        log("[READ LOOP] read header, actualLength="+actualLength)

                        try {
                            header.readFromBuffer(headerBuffer)

                          // log("[READ LOOP] header, type="+header.type+", length="+header.length)
                            if (header.length > 0) {
                                // video data direct render
                                if (header.isVideoData) {
                                    h264Renderer?.processDataDirect(header.length, 20) { bytes, offset ->
                                        actualLength = readByChunks(connection, endpoint, bytes, offset, header.length, timeout)
                                    }

                                    if (actualLength < 0) {
                                        break
                                    }

                                    // notify once
                                    // video streaming started
                                    if (!streamingNotified) {
                                        streamingNotified = true
                                        readingLoopMessageWithData(header.type, ByteArray(0))
                                    }
                                } else {
                                    var bodyBytes = ByteArray(header.length)
                                    actualLength = readByChunks(connection, endpoint, bodyBytes, 0, header.length, timeout)

                                    if (actualLength < 0) {
                                        break
                                    }

                                    readingLoopMessageWithData(header.type, bodyBytes)
                                }
                            }
                            else {
                                readingLoopMessage(header.type)
                            }
                        } catch (e: Exception) {
                            readingLoopError(e.toString())
                            break
                        }
                    }

                    log("[READ LOOP] stop")

                    readLoopRunning = false;
                    readingLoopError("USBReadError readingLoopError error, return actualLength=-1")
                }

                result.success(null)
            }

            "bulkTransferIn" -> {
                val isVideoData = call.argument<Boolean>("isVideoData")!!
                val device =
                    usbDevice ?: return result.error("IllegalState", "usbDevice null", null)
                val connection = usbDeviceConnection
                    ?: return result.error(
                        "IllegalState",
                        "usbDeviceConnection null",
                        null
                    )
                val endpointMap = call.argument<Map<String, Any>>("endpoint")!!
                val maxLength = call.argument<Int>("maxLength")!!
                val endpoint =
                    device.findEndpoint(
                        endpointMap["endpointNumber"] as Int,
                        endpointMap["direction"] as Int
                    )!!
                val timeout = call.argument<Int>("timeout")!!

                executors.usbIn().execute {
                   // var buffer = readByChunks(connection, endpoint, maxLength, timeout);

                    executors.mainThread().execute {
//                        if (buffer == null) {
//                            result.error(
//                                "USBReadError",
//                                "bulkTransferIn error, return actuallength=-1",
//                                ""
//                            )
//                        } else {
//                            if (isVideoData) {
//                                h264Renderer?.processData(buffer.array(), 20, maxLength - 20)
//                                result.success(ByteArray(0))
//                            } else {
//                                result.success(buffer)
//                            }
//                        }
                    }
                }
            }

            "bulkTransferOut" -> {
                val device =
                    usbDevice ?: return result.error("IllegalState", "usbDevice null", null)
                val connection = usbDeviceConnection
                    ?: return result.error(
                        "IllegalState",
                        "usbDeviceConnection null",
                        null
                    )
                val endpointMap = call.argument<Map<String, Any>>("endpoint")!!
                val endpoint =
                    device.findEndpoint(
                        endpointMap["endpointNumber"] as Int,
                        endpointMap["direction"] as Int
                    )
                val timeout = call.argument<Int>("timeout")!!
                val data = call.argument<ByteArray>("data")!!

                executors.usbOut().execute {
                    val actualLength =
                        connection.bulkTransfer(endpoint, data, data.count(), timeout)

                    executors.mainThread().execute {
                        if (actualLength < 0) {
                            result.error("USBWriteError", "bulkTransferOut error, actualLength=-1", null)
                        } else {
                            result.success(actualLength)
                        }
                    }
                }
            }

            else -> {
                result.notImplemented()
            }
        }
    }
}

fun readByChunks(connection: UsbDeviceConnection, endpoint: UsbEndpoint, buffer: ByteArray, bufferOffset: Int, maxLength: Int, timeout: Int): Int {
    var limit = 16384
    var offset = 0

    while (offset < maxLength) {
        var lengthToRead = minOf(limit, maxLength - offset)
        var actualLength = connection.bulkTransfer(endpoint, buffer, bufferOffset + offset, lengthToRead, timeout)

        if (actualLength < 0) {
            return actualLength
        }
        else {
            offset += actualLength
        }
    }

    return maxLength
}

fun UsbDevice.findInterface(id: Int, alternateSetting: Int): UsbInterface? {
    for (i in 0..interfaceCount) {
        val usbInterface = getInterface(i)
        if (usbInterface.id == id && usbInterface.alternateSetting == alternateSetting) {
            return usbInterface
        }
    }
    return null
}

fun UsbDevice.findEndpoint(endpointNumber: Int, direction: Int): UsbEndpoint? {
    for (i in 0..interfaceCount) {
        val usbInterface = getInterface(i)
        for (j in 0..usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(j)
            if (endpoint.endpointNumber == endpointNumber && endpoint.direction == direction) {
                return endpoint
            }
        }
    }
    return null
}

fun UsbConfiguration.toMap() = mapOf(
    "id" to id,
    "interfaces" to List(interfaceCount) { getInterface(it).toMap() }
)

fun UsbInterface.toMap() = mapOf(
    "id" to id,
    "alternateSetting" to alternateSetting,
    "endpoints" to List(endpointCount) { getEndpoint(it).toMap() }
)

fun UsbEndpoint.toMap() = mapOf(
    "endpointNumber" to endpointNumber,
    "direction" to direction,
    "maxPacketSize" to maxPacketSize
)
