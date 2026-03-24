package com.alexander.carplay.data.automotive.climate

import android.car.Car
import android.car.VehicleAreaType
import android.car.hardware.CarPropertyValue
import android.car.hardware.cabin.CarCabinManager
import android.car.hardware.hvac.CarHvacManager
import android.car.hardware.property.CarPropertyManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.alexander.carplay.data.logging.DiagnosticLogStore
import com.alexander.carplay.domain.model.ClimateSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ClimateController(
    context: Context,
    private val logStore: DiagnosticLogStore? = null,
) {
    companion object {
        private const val DEFAULT_CLIENT = "default"
        private const val PROP_TEMP_CURRENT = 675289370
        private const val PROP_TEMP_SETPOINT = 358614275
        private const val PROP_OUTSIDE_TEMP = 289408270
        private const val PROP_FAN_SPEED = 356517140
        private const val PROP_FAN_DIRECTION = 356517121
        private const val PROP_AC_ON = 356517125
        private const val PROP_RECIRCULATION = 356517128
        private const val PROP_AUTO_MODE = 356517130
        private const val PROP_DUAL_STATE = 557848851
        private const val PROP_SEAT_HEAT = 356517131
        private const val PROP_SEAT_VENT = 356517139
        private const val PROP_DOME_LIGHT = 557845031
        private const val PROP_DOOR_LIGHT = 557845032
        private const val PROP_MIRROR_AUTOFOLD = 557844928
        private const val PROP_MIRROR_REAR_ASSIST = 557844929

        private const val AREA_DRIVER = 1
        private const val AREA_PASSENGER = 4
        private const val AREA_FRONT_ALL = 15
        private const val AREA_HVAC_GLOBAL = 0
        private const val CABIN_TEMP_SCALE = 0.2f
        private const val OUTSIDE_TEMP_SCALE = 0.2f
        private const val OUTSIDE_TEMP_FALLBACK_SCALE = 0.5f
        private val AREA_GLOBAL: Int = VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val snapshotFlow = MutableStateFlow(ClimateSnapshot())
    private val connectionLock = Any()
    private val connectedClients = linkedSetOf<String>()

    private var car: Car? = null
    private var hvacManager: CarHvacManager? = null
    private var cabinManager: CarCabinManager? = null
    private var propertyManager: CarPropertyManager? = null
    private var serviceConnection: ServiceConnection? = null
    private var cabinTempC: Float? = null
    private var outsideTempC: Float? = null

    val snapshot: StateFlow<ClimateSnapshot> = snapshotFlow.asStateFlow()

    fun connect(clientId: String = DEFAULT_CLIENT) {
        synchronized(connectionLock) {
            val added = connectedClients.add(clientId)
            if (!added) {
                if (car != null) {
                    ClimateLogger.info("Car API already retained by $clientId")
                    if (isConnectedInternal()) {
                        updateConnectionState(true)
                    }
                    return
                }
                ClimateLogger.info("Car API reconnect requested by retained client $clientId")
            }

            if (car != null) {
                ClimateLogger.info("Car API retain +1 ($clientId); active=${connectedClients.joinToString()}")
                if (isConnectedInternal()) {
                    updateConnectionState(true)
                }
                return
            }
        }

        ClimateLogger.info("Connecting to Car API; active=${connectedClients.joinToString()}")
        val connection = object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?,
            ) {
                ClimateLogger.info("Car ServiceConnection.onServiceConnected: $name")
                try {
                    hvacManager = car?.getCarManager(Car.HVAC_SERVICE) as? CarHvacManager
                    cabinManager = car?.getCarManager(Car.CABIN_SERVICE) as? CarCabinManager
                    propertyManager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                    hvacManager?.registerCallback(hvacCallback)
                    cabinManager?.registerCallback(cabinCallback)
                    ClimateLogger.info(
                        "Car API connected. HVAC=${hvacManager != null}, Cabin=${cabinManager != null}, Property=${propertyManager != null}",
                    )
                    handler.post {
                        loadInitialSnapshot()
                        dumpAvailableProperties()
                    }
                } catch (error: Exception) {
                    ClimateLogger.error("Failed to initialize car managers", error)
                    updateConnectionState(false)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                ClimateLogger.info("Car ServiceConnection.onServiceDisconnected: $name")
                hvacManager = null
                cabinManager = null
                propertyManager = null
                updateConnectionState(false)
            }
        }

        serviceConnection = connection
        try {
            car = Car.createCar(appContext, connection)
            ClimateLogger.info("Car object created, calling connect()")
            car?.connect()
            ClimateLogger.info("Car.connect() called. isConnected=${car?.isConnected}, isConnecting=${car?.isConnecting}")
        } catch (error: Exception) {
            ClimateLogger.error("Car.connect() failed", error)
            car = null
            hvacManager = null
            cabinManager = null
            serviceConnection = null
            updateConnectionState(false)
        }
    }

    fun disconnect(clientId: String = DEFAULT_CLIENT) {
        synchronized(connectionLock) {
            if (!connectedClients.remove(clientId)) {
                return
            }
            if (connectedClients.isNotEmpty()) {
                ClimateLogger.info("Car API retain -1 ($clientId); still active=${connectedClients.joinToString()}")
                return
            }
        }

        ClimateLogger.info("Disconnecting Car API")
        try {
            hvacManager?.unregisterCallback(hvacCallback)
            cabinManager?.unregisterCallback(cabinCallback)
        } catch (_: Exception) {
        }
        try {
            car?.disconnect()
        } catch (error: Exception) {
            ClimateLogger.error("Car.disconnect() failed", error)
        }
        car = null
        hvacManager = null
        cabinManager = null
        propertyManager = null
        serviceConnection = null
        updateConnectionState(false)
    }

    fun setSeatHeatLevel(
        isDriver: Boolean,
        level: Int,
        reason: String = "direct set",
    ) {
        val side = if (isDriver) "DRIVER" else "PASSENGER"
        val area = if (isDriver) AREA_DRIVER else AREA_PASSENGER
        val normalized = level.coerceIn(0, 3)
        val current = if (isDriver) snapshotFlow.value.driverSeatHeat else snapshotFlow.value.passengerSeatHeat
        if (current == normalized) return
        ClimateLogger.action("SEAT_HEAT_$side", "$reason current=$current -> next=$normalized")
        setHvacInt(PROP_SEAT_HEAT, area, normalized)
    }

    fun setSeatVentLevel(
        isDriver: Boolean,
        level: Int,
        reason: String = "direct set",
    ) {
        val side = if (isDriver) "DRIVER" else "PASSENGER"
        val area = if (isDriver) AREA_DRIVER else AREA_PASSENGER
        val normalized = level.coerceIn(0, 3)
        val current = if (isDriver) snapshotFlow.value.driverSeatVent else snapshotFlow.value.passengerSeatVent
        if (current == normalized) return
        ClimateLogger.action("SEAT_VENT_$side", "$reason current=$current -> next=$normalized")
        setHvacInt(PROP_SEAT_VENT, area, normalized)
    }

    fun applySeatComfort(
        isDriver: Boolean,
        heatLevel: Int,
        ventLevel: Int,
        reason: String = "seat comfort apply",
    ) {
        val normalizedHeat = heatLevel.coerceIn(0, 3)
        val normalizedVent = ventLevel.coerceIn(0, 3)
        if (normalizedHeat > 0 && normalizedVent > 0) {
            ClimateLogger.error("Invalid seat comfort request: heat=$normalizedHeat vent=$normalizedVent")
            return
        }

        // Clear the opposite mode first to avoid overlapping heat and ventilation.
        if (normalizedHeat > 0) {
            setSeatVentLevel(isDriver = isDriver, level = 0, reason = "$reason / clear vent")
            setSeatHeatLevel(isDriver = isDriver, level = normalizedHeat, reason = "$reason / apply heat")
        } else if (normalizedVent > 0) {
            setSeatHeatLevel(isDriver = isDriver, level = 0, reason = "$reason / clear heat")
            setSeatVentLevel(isDriver = isDriver, level = normalizedVent, reason = "$reason / apply vent")
        } else {
            setSeatHeatLevel(isDriver = isDriver, level = 0, reason = "$reason / stop heat")
            setSeatVentLevel(isDriver = isDriver, level = 0, reason = "$reason / stop vent")
        }
    }

    fun cycleSeatHeat(isDriver: Boolean) {
        val side = if (isDriver) "DRIVER" else "PASSENGER"
        val area = if (isDriver) AREA_DRIVER else AREA_PASSENGER
        val current = getHvacInt(PROP_SEAT_HEAT, area)
        val next = when (current) {
            0 -> 3
            1 -> 0
            2 -> 1
            3 -> 2
            else -> 0
        }
        ClimateLogger.action("SEAT_HEAT_$side", "current=$current -> next=$next")
        setHvacInt(PROP_SEAT_HEAT, area, next)
    }

    fun cycleSeatVent(isDriver: Boolean) {
        val side = if (isDriver) "DRIVER" else "PASSENGER"
        val area = if (isDriver) AREA_DRIVER else AREA_PASSENGER
        val current = getHvacInt(PROP_SEAT_VENT, area)
        val next = when (current) {
            0 -> 3
            1 -> 0
            2 -> 1
            3 -> 2
            else -> 0
        }
        ClimateLogger.action("SEAT_VENT_$side", "current=$current -> next=$next")
        setHvacInt(PROP_SEAT_VENT, area, next)
    }

    fun toggleDoorLight() {
        val current = getCabinInt(PROP_DOOR_LIGHT, AREA_GLOBAL)
        val next = if (current == 2) 1 else 2
        ClimateLogger.action("DOOR_LIGHT", "current=$current -> next=$next")
        setCabinInt(PROP_DOOR_LIGHT, AREA_GLOBAL, next)
    }

    fun toggleMirrorAutoFold() {
        val current = getCabinInt(PROP_MIRROR_AUTOFOLD, AREA_GLOBAL)
        val next = if (current == 2) 1 else 2
        ClimateLogger.action("MIRROR_AUTOFOLD", "current=$current -> next=$next")
        setCabinInt(PROP_MIRROR_AUTOFOLD, AREA_GLOBAL, next)
    }

    fun toggleMirrorRearAssist() {
        val current = getCabinInt(PROP_MIRROR_REAR_ASSIST, AREA_GLOBAL)
        val next = if (current == 2) 1 else 2
        ClimateLogger.action("MIRROR_REAR_ASSIST", "current=$current -> next=$next")
        setCabinInt(PROP_MIRROR_REAR_ASSIST, AREA_GLOBAL, next)
    }

    private val hvacCallback = object : CarHvacManager.CarHvacEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            ClimateLogger.signal(propName(value.propertyId), value.propertyId, value.areaId, value.value)
            handler.post { handleHvacPropertyChanged(value) }
        }

        override fun onErrorEvent(
            propertyId: Int,
            zone: Int,
        ) {
            ClimateLogger.error("HVAC callback error: prop=${propName(propertyId)} id=$propertyId zone=$zone")
        }
    }

    private val cabinCallback = object : CarCabinManager.CarCabinEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            ClimateLogger.signal(propName(value.propertyId), value.propertyId, value.areaId, value.value)
            handler.post { handleCabinPropertyChanged(value) }
        }

        override fun onErrorEvent(
            propertyId: Int,
            zone: Int,
        ) {
            ClimateLogger.error("Cabin callback error: prop=${propName(propertyId)} id=$propertyId zone=$zone")
        }
    }

    private fun loadInitialSnapshot() {
        ClimateLogger.info("Loading initial values from Car API")
        cabinTempC = readScaledHvacTemperatureOrNull(
            propId = PROP_TEMP_CURRENT,
            areaId = AREA_DRIVER,
            primaryScale = CABIN_TEMP_SCALE,
        )
        outsideTempC = readScaledHvacTemperatureOrNull(
            propId = PROP_OUTSIDE_TEMP,
            areaId = AREA_HVAC_GLOBAL,
            primaryScale = OUTSIDE_TEMP_SCALE,
            fallbackScale = OUTSIDE_TEMP_FALLBACK_SCALE,
        )
        snapshotFlow.value = ClimateSnapshot(
            driverTemp = getHvacFloat(PROP_TEMP_SETPOINT, AREA_DRIVER),
            passengerTemp = getHvacFloat(PROP_TEMP_SETPOINT, AREA_PASSENGER),
            cabinTemp = cabinTempC,
            outsideTemp = outsideTempC,
            fanSpeed = getHvacInt(PROP_FAN_SPEED, AREA_FRONT_ALL),
            fanDirection = getHvacInt(PROP_FAN_DIRECTION, AREA_FRONT_ALL),
            acOn = getHvacInt(PROP_AC_ON, AREA_FRONT_ALL),
            autoMode = getHvacInt(PROP_AUTO_MODE, AREA_FRONT_ALL),
            recirculation = getHvacInt(PROP_RECIRCULATION, AREA_FRONT_ALL),
            dualState = getHvacInt(PROP_DUAL_STATE, AREA_GLOBAL),
            driverSeatHeat = getHvacInt(PROP_SEAT_HEAT, AREA_DRIVER),
            driverSeatVent = getHvacInt(PROP_SEAT_VENT, AREA_DRIVER),
            passengerSeatHeat = getHvacInt(PROP_SEAT_HEAT, AREA_PASSENGER),
            passengerSeatVent = getHvacInt(PROP_SEAT_VENT, AREA_PASSENGER),
            mirrorAutoFold = getCabinInt(PROP_MIRROR_AUTOFOLD, AREA_GLOBAL),
            mirrorRearAssist = getCabinInt(PROP_MIRROR_REAR_ASSIST, AREA_GLOBAL),
            doorLight = getCabinInt(PROP_DOOR_LIGHT, AREA_GLOBAL),
            isConnected = isConnectedInternal(),
        )
    }

    private fun handleHvacPropertyChanged(prop: CarPropertyValue<*>) {
        when (prop.propertyId) {
            PROP_TEMP_CURRENT -> {
                if (prop.areaId != AREA_DRIVER) return
                val value = prop.value.asScaledTemperatureValue(
                    primaryScale = CABIN_TEMP_SCALE,
                ) ?: return
                cabinTempC = value
                updateSnapshotIfChanged({ it.cabinTemp }, value) {
                    it.copy(cabinTemp = value, isConnected = isConnectedInternal())
                }
            }
            PROP_TEMP_SETPOINT -> {
                val value = prop.value.asFloatValue() ?: return
                if (prop.areaId == AREA_DRIVER) {
                    updateSnapshotIfChanged({ it.driverTemp }, value) {
                        it.copy(driverTemp = value, isConnected = isConnectedInternal())
                    }
                } else if (prop.areaId == AREA_PASSENGER) {
                    updateSnapshotIfChanged({ it.passengerTemp }, value) {
                        it.copy(passengerTemp = value, isConnected = isConnectedInternal())
                    }
                }
            }
            PROP_OUTSIDE_TEMP -> {
                val value = prop.value.asScaledTemperatureValue(
                    primaryScale = OUTSIDE_TEMP_SCALE,
                    fallbackScale = OUTSIDE_TEMP_FALLBACK_SCALE,
                ) ?: return
                outsideTempC = value
                updateSnapshotIfChanged({ it.outsideTemp }, value) {
                    it.copy(outsideTemp = value, isConnected = isConnectedInternal())
                }
            }
            PROP_FAN_SPEED -> {
                val value = (prop.value as? Int) ?: return
                updateSnapshotIfChanged({ it.fanSpeed }, value) {
                    it.copy(fanSpeed = value, isConnected = isConnectedInternal())
                }
            }
            PROP_FAN_DIRECTION -> {
                val value = (prop.value as? Int) ?: return
                updateSnapshotIfChanged({ it.fanDirection }, value) {
                    it.copy(fanDirection = value, isConnected = isConnectedInternal())
                }
            }
            PROP_AC_ON -> {
                val value = (prop.value as? Int) ?: return
                updateSnapshotIfChanged({ it.acOn }, value) {
                    it.copy(acOn = value, isConnected = isConnectedInternal())
                }
            }
            PROP_AUTO_MODE -> {
                val value = (prop.value as? Int) ?: return
                updateSnapshotIfChanged({ it.autoMode }, value) {
                    it.copy(autoMode = value, isConnected = isConnectedInternal())
                }
            }
            PROP_RECIRCULATION -> {
                val value = (prop.value as? Int) ?: return
                updateSnapshotIfChanged({ it.recirculation }, value) {
                    it.copy(recirculation = value, isConnected = isConnectedInternal())
                }
            }
            PROP_DUAL_STATE -> {
                val value = (prop.value as? Int) ?: return
                updateSnapshotIfChanged({ it.dualState }, value) {
                    it.copy(dualState = value, isConnected = isConnectedInternal())
                }
            }
            PROP_SEAT_HEAT -> {
                val value = (prop.value as? Int) ?: return
                if (prop.areaId == AREA_DRIVER) {
                    updateSnapshotIfChanged({ it.driverSeatHeat }, value) {
                        it.copy(driverSeatHeat = value, isConnected = isConnectedInternal())
                    }
                } else if (prop.areaId == AREA_PASSENGER) {
                    updateSnapshotIfChanged({ it.passengerSeatHeat }, value) {
                        it.copy(passengerSeatHeat = value, isConnected = isConnectedInternal())
                    }
                }
            }
            PROP_SEAT_VENT -> {
                val value = (prop.value as? Int) ?: return
                if (prop.areaId == AREA_DRIVER) {
                    updateSnapshotIfChanged({ it.driverSeatVent }, value) {
                        it.copy(driverSeatVent = value, isConnected = isConnectedInternal())
                    }
                } else if (prop.areaId == AREA_PASSENGER) {
                    updateSnapshotIfChanged({ it.passengerSeatVent }, value) {
                        it.copy(passengerSeatVent = value, isConnected = isConnectedInternal())
                    }
                }
            }
            else -> updateConnectionState(isConnectedInternal())
        }
    }

    private fun handleCabinPropertyChanged(prop: CarPropertyValue<*>) {
        when (prop.propertyId) {
            PROP_MIRROR_AUTOFOLD -> {
                val value = (prop.value as? Int) ?: return
                updateSnapshotIfChanged({ it.mirrorAutoFold }, value) {
                    it.copy(mirrorAutoFold = value, isConnected = isConnectedInternal())
                }
            }
            PROP_MIRROR_REAR_ASSIST -> {
                val value = (prop.value as? Int) ?: return
                updateSnapshotIfChanged({ it.mirrorRearAssist }, value) {
                    it.copy(mirrorRearAssist = value, isConnected = isConnectedInternal())
                }
            }
            PROP_DOOR_LIGHT -> {
                val value = (prop.value as? Int) ?: return
                updateSnapshotIfChanged({ it.doorLight }, value) {
                    it.copy(doorLight = value, isConnected = isConnectedInternal())
                }
            }
            else -> updateConnectionState(isConnectedInternal())
        }
    }

    private fun updateConnectionState(connected: Boolean) {
        snapshotFlow.update { current ->
            if (current.isConnected == connected) current else current.copy(isConnected = connected)
        }
    }

    fun dumpAvailableProperties() {
        val log = logStore ?: return
        val src = "ClimateDump"

        log.info(src, "=== PROPERTY DUMP START ===")

        try {
            val hvac = hvacManager
            if (hvac != null) {
                val hvacProps = hvac.propertyList
                log.info(src, "HVAC properties (${hvacProps.size}):")
                for (config in hvacProps) {
                    val id = config.propertyId
                    val areas = config.areaIds
                    val areasStr = areas.joinToString()
                    val values = areas.map { area ->
                        try {
                            val type = config.propertyType
                            val v = when {
                                type == Float::class.java || type == java.lang.Float::class.java ->
                                    hvac.getFloatProperty(id, area)
                                type == Int::class.java || type == java.lang.Integer::class.java ->
                                    hvac.getIntProperty(id, area)
                                type == Boolean::class.java || type == java.lang.Boolean::class.java ->
                                    hvac.getBooleanProperty(id, area)
                                else -> "type=${type?.simpleName}"
                            }
                            "area=$area->$v"
                        } catch (e: Exception) {
                            "area=$area->ERR(${e.message?.take(40)})"
                        }
                    }
                    log.info(src, "  HVAC id=$id (0x${id.toString(16)}) areas=[$areasStr] ${values.joinToString(" | ")}")
                }
            } else {
                log.info(src, "HVAC manager is null")
            }
        } catch (e: Exception) {
            log.error(src, "HVAC dump failed", e)
        }

        try {
            val cabin = cabinManager
            if (cabin != null) {
                val cabinProps = cabin.propertyList
                log.info(src, "Cabin properties (${cabinProps.size}):")
                for (config in cabinProps) {
                    val id = config.propertyId
                    val areas = config.areaIds
                    val areasStr = areas.joinToString()
                    val values = areas.map { area ->
                        try {
                            val type = config.propertyType
                            val v = when {
                                type == Float::class.java || type == java.lang.Float::class.java ->
                                    cabin.getFloatProperty(id, area)
                                type == Int::class.java || type == java.lang.Integer::class.java ->
                                    cabin.getIntProperty(id, area)
                                type == Boolean::class.java || type == java.lang.Boolean::class.java ->
                                    cabin.getBooleanProperty(id, area)
                                else -> "type=${type?.simpleName}"
                            }
                            "area=$area->$v"
                        } catch (e: Exception) {
                            "area=$area->ERR(${e.message?.take(40)})"
                        }
                    }
                    log.info(src, "  CABIN id=$id (0x${id.toString(16)}) areas=[$areasStr] ${values.joinToString(" | ")}")
                }
            } else {
                log.info(src, "Cabin manager is null")
            }
        } catch (e: Exception) {
            log.error(src, "Cabin dump failed", e)
        }

        try {
            val prop = propertyManager
            if (prop != null) {
                val propList = prop.propertyList
                log.info(src, "PropertyManager properties (${propList.size}):")
                for (config in propList) {
                    val id = config.propertyId
                    val areas = config.areaIds
                    val areasStr = areas.joinToString()
                    val values = areas.map { area ->
                        try {
                            val cpv = prop.getProperty<Any>(config.propertyType, id, area)
                            "area=$area->${cpv?.value}"
                        } catch (e: Exception) {
                            "area=$area->ERR(${e.message?.take(40)})"
                        }
                    }
                    log.info(src, "  PROP id=$id (0x${id.toString(16)}) areas=[$areasStr] ${values.joinToString(" | ")}")
                }
            } else {
                log.info(src, "PropertyManager is null")
            }
        } catch (e: Exception) {
            log.error(src, "PropertyManager dump failed", e)
        }

        log.info(src, "=== PROPERTY DUMP END ===")
    }

    private fun isConnectedInternal(): Boolean = hvacManager != null || cabinManager != null || propertyManager != null

    private fun getHvacInt(
        propId: Int,
        areaId: Int,
    ): Int {
        return try {
            val result = hvacManager?.getIntProperty(propId, areaId) ?: -1
            ClimateLogger.read(propName(propId), propId, areaId, result)
            result
        } catch (error: Exception) {
            ClimateLogger.error("getHvacInt(${propName(propId)}, ${areaName(areaId)}) failed", error)
            -1
        }
    }

    private fun getHvacFloat(
        propId: Int,
        areaId: Int,
    ): Float {
        return try {
            val result = hvacManager?.getFloatProperty(propId, areaId) ?: -1f
            ClimateLogger.read(propName(propId), propId, areaId, result)
            result
        } catch (error: Exception) {
            ClimateLogger.error("getHvacFloat(${propName(propId)}, ${areaName(areaId)}) failed", error)
            -1f
        }
    }

    private fun readScaledHvacTemperatureOrNull(
        propId: Int,
        areaId: Int,
        primaryScale: Float,
        fallbackScale: Float? = null,
    ): Float? {
        return try {
            val raw = hvacManager?.getIntProperty(propId, areaId)
            ClimateLogger.read(propName(propId), propId, areaId, raw)
            raw?.let { decodeScaledTemperature(it, primaryScale, fallbackScale) }
        } catch (error: Exception) {
            ClimateLogger.error("readScaledHvacTemperatureOrNull(${propName(propId)}, ${areaName(areaId)}) failed", error)
            null
        }
    }

    private fun setHvacInt(
        propId: Int,
        areaId: Int,
        value: Int,
    ) {
        ClimateLogger.write(propName(propId), propId, areaId, value)
        try {
            hvacManager?.setIntProperty(propId, areaId, value)
        } catch (error: Exception) {
            ClimateLogger.error("setHvacInt(${propName(propId)}, ${areaName(areaId)}, $value) failed", error)
        }
    }

    private fun getCabinInt(
        propId: Int,
        areaId: Int,
    ): Int {
        return try {
            val result = cabinManager?.getIntProperty(propId, areaId) ?: -1
            ClimateLogger.read(propName(propId), propId, areaId, result)
            result
        } catch (error: Exception) {
            ClimateLogger.error("getCabinInt(${propName(propId)}, ${areaName(areaId)}) failed", error)
            -1
        }
    }

    private fun setCabinInt(
        propId: Int,
        areaId: Int,
        value: Int,
    ) {
        ClimateLogger.write(propName(propId), propId, areaId, value)
        try {
            cabinManager?.setIntProperty(propId, areaId, value)
        } catch (error: Exception) {
            ClimateLogger.error("setCabinInt(${propName(propId)}, ${areaName(areaId)}, $value) failed", error)
        }
    }

    private fun propName(id: Int): String =
        when (id) {
            PROP_TEMP_CURRENT -> "TEMP_CURRENT"
            PROP_TEMP_SETPOINT -> "TEMP_SETPOINT"
            PROP_OUTSIDE_TEMP -> "OUTSIDE_TEMP"
            PROP_FAN_SPEED -> "FAN_SPEED"
            PROP_FAN_DIRECTION -> "FAN_DIRECTION"
            PROP_AC_ON -> "AC_ON"
            PROP_RECIRCULATION -> "RECIRCULATION"
            PROP_AUTO_MODE -> "AUTO_MODE"
            PROP_DUAL_STATE -> "DUAL_STATE"
            PROP_SEAT_HEAT -> "SEAT_HEAT"
            PROP_SEAT_VENT -> "SEAT_VENT"
            PROP_DOME_LIGHT -> "DOME_LIGHT"
            PROP_DOOR_LIGHT -> "DOOR_LIGHT"
            PROP_MIRROR_AUTOFOLD -> "MIRROR_AUTOFOLD"
            PROP_MIRROR_REAR_ASSIST -> "MIRROR_REAR_ASSIST"
            else -> "UNKNOWN($id)"
        }

    private fun areaName(id: Int): String =
        when (id) {
            AREA_DRIVER -> "DRIVER"
            AREA_PASSENGER -> "PASSENGER"
            AREA_FRONT_ALL -> "FRONT_ALL"
            AREA_HVAC_GLOBAL -> "HVAC_GLOBAL"
            AREA_GLOBAL -> "GLOBAL"
            else -> "AREA($id)"
        }

    private inline fun <T> updateSnapshotIfChanged(
        selector: (ClimateSnapshot) -> T,
        newValue: T,
        transform: (ClimateSnapshot) -> ClimateSnapshot,
    ) {
        snapshotFlow.update { current ->
            if (selector(current) == newValue) {
                val connected = isConnectedInternal()
                if (current.isConnected == connected) current else current.copy(isConnected = connected)
            } else {
                transform(current)
            }
        }
    }

    private fun isReasonableTemperature(value: Float): Boolean = value.isFinite() && value in -60f..85f

    private fun decodeScaledTemperature(
        rawValue: Int,
        primaryScale: Float,
        fallbackScale: Float? = null,
    ): Float? {
        val primary = rawValue * primaryScale
        if (isReasonableTemperature(primary)) return primary
        val fallback = fallbackScale?.let { rawValue * it }
        return fallback?.takeIf(::isReasonableTemperature)
    }

    private fun Any?.asScaledTemperatureValue(
        primaryScale: Float,
        fallbackScale: Float? = null,
    ): Float? {
        val rawValue = (this as? Number)?.toInt() ?: return null
        return decodeScaledTemperature(rawValue, primaryScale, fallbackScale)
    }

    private fun Any?.asFloatValue(): Float? {
        val value = (this as? Number)?.toFloat() ?: return null
        return value.takeIf { it.isFinite() }
    }
}
