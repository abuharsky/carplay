package com.alexander.carplay.data.automotive

import com.incall.serversdk.power.PowerConstant

data class AutomotivePowerSnapshot(
    val powerServiceConnected: Boolean = false,
    val accState: Int? = null,
    val powerState: Int? = null,
    val currentScreenSignal: Int? = null,
    val welcomeScreenState: Int? = null,
    val screenStates: Map<Int, Int> = emptyMap(),
    val longPressPower: Boolean? = null,
)

object AutomotivePowerPolicy {
    fun isReadyForAutoConnect(snapshot: AutomotivePowerSnapshot): Boolean {
        return isVehicleActive(snapshot)
    }

    fun isVehicleActive(snapshot: AutomotivePowerSnapshot): Boolean {
        snapshot.accState?.let { accState ->
            return accState == PowerConstant.ACC_ON || accState == PowerConstant.ACC_START
        }

        snapshot.powerState?.let { powerState ->
            return powerState == PowerConstant.POWER_WORKING
        }

        return true
    }

    fun describeAccState(state: Int?): String = when (state) {
        null -> "UNKNOWN"
        PowerConstant.ACC_IDEL -> "ACC_IDEL"
        PowerConstant.ACC_OFF -> "ACC_OFF"
        PowerConstant.ACC_ON -> "ACC_ON"
        PowerConstant.ACC_START -> "ACC_START"
        else -> "ACC_$state"
    }

    fun describePowerState(state: Int?): String = when (state) {
        null -> "UNKNOWN"
        PowerConstant.POWER_WORKING -> "POWER_WORKING"
        PowerConstant.POWER_UNWOKGING -> "POWER_UNWOKGING"
        PowerConstant.POWER_WORKING_PAUSE -> "POWER_WORKING_PAUSE"
        else -> "POWER_$state"
    }

    fun describeScreenType(screenType: Int): String = when (screenType) {
        PowerConstant.SCREEN_TYPE_STANDBY -> "SCREEN_TYPE_STANDBY"
        PowerConstant.SCREEN_TYPE_WELCOME -> "SCREEN_TYPE_WELCOME"
        PowerConstant.SCREEN_TYPE_OFF -> "SCREEN_TYPE_OFF"
        PowerConstant.SCREEN_TYPE_POWER_OFF -> "SCREEN_TYPE_POWER_OFF"
        PowerConstant.SCREEN_TYPE_SEND_PENN -> "SCREEN_TYPE_SEND_PENN"
        PowerConstant.SCREEN_TYPE_WORK -> "SCREEN_TYPE_WORK"
        else -> "SCREEN_TYPE_$screenType"
    }

    fun describeScreenVisibility(state: Int?): String = when (state) {
        null -> "UNKNOWN"
        PowerConstant.SCREEN_SHOW -> "SCREEN_SHOW"
        PowerConstant.SCREEN_HIDE -> "SCREEN_HIDE"
        else -> "SCREEN_$state"
    }

    fun describeSnapshot(snapshot: AutomotivePowerSnapshot): String {
        val parts = mutableListOf<String>()
        parts += "gate=${if (isVehicleActive(snapshot)) "OPEN" else "CLOSED"}"
        parts += "service=${if (snapshot.powerServiceConnected) "CONNECTED" else "DISCONNECTED"}"
        parts += "acc=${describeAccState(snapshot.accState)}"
        parts += "power=${describePowerState(snapshot.powerState)}"
        parts += "curScreen=${describeScreenVisibility(snapshot.currentScreenSignal)}"
        parts += "wel=${describeScreenVisibility(snapshot.welcomeScreenState)}"

        if (snapshot.screenStates.isNotEmpty()) {
            parts += snapshot.screenStates.entries
                .sortedBy { it.key }
                .joinToString(
                    prefix = "screens=",
                    separator = ", ",
                ) { entry ->
                    "${describeScreenType(entry.key)}=${describeScreenVisibility(entry.value)}"
                }
        }

        snapshot.longPressPower?.let { value ->
            parts += "longPressPower=$value"
        }

        return parts.joinToString(" | ")
    }
}
