package com.alexander.carplay.presentation.climate

import com.alexander.carplay.data.automotive.climate.ClimateController
import com.alexander.carplay.data.automotive.climate.SeatAutoComfortController
import com.alexander.carplay.domain.model.ClimateSnapshot

class ClimateBarState(
    private val controller: ClimateController,
    private val seatAutoComfortController: SeatAutoComfortController,
    private val snapshot: ClimateSnapshot,
) {
    val driverTemp: Float
        get() = snapshot.driverTemp
    val passengerTemp: Float
        get() = snapshot.passengerTemp
    val cabinTemp: Float?
        get() = snapshot.cabinTemp
    val outsideTemp: Float?
        get() = snapshot.outsideTemp
    val fanSpeed: Int
        get() = snapshot.fanSpeed
    val fanDirection: Int
        get() = snapshot.fanDirection
    val driverSeatHeat: Int
        get() = snapshot.driverSeatHeat
    val driverSeatVent: Int
        get() = snapshot.driverSeatVent
    val passengerSeatHeat: Int
        get() = snapshot.passengerSeatHeat
    val passengerSeatVent: Int
        get() = snapshot.passengerSeatVent
    val mirrorAutoFold: Int
        get() = snapshot.mirrorAutoFold
    val mirrorRearAssist: Int
        get() = snapshot.mirrorRearAssist
    val doorLight: Int
        get() = snapshot.doorLight
    val isConnected: Boolean
        get() = snapshot.isConnected

    fun onDriverSeatHeatClick() {
        seatAutoComfortController.suspendSeatAutomation(
            isDriver = true,
            reason = "manual driver seat heat click",
        )
        controller.cycleSeatHeat(isDriver = true)
    }

    fun onDriverSeatVentClick() {
        seatAutoComfortController.suspendSeatAutomation(
            isDriver = true,
            reason = "manual driver seat vent click",
        )
        controller.cycleSeatVent(isDriver = true)
    }

    fun onPassengerSeatHeatClick() {
        seatAutoComfortController.suspendSeatAutomation(
            isDriver = false,
            reason = "manual passenger seat heat click",
        )
        controller.cycleSeatHeat(isDriver = false)
    }

    fun onPassengerSeatVentClick() {
        seatAutoComfortController.suspendSeatAutomation(
            isDriver = false,
            reason = "manual passenger seat vent click",
        )
        controller.cycleSeatVent(isDriver = false)
    }

    fun onDoorLightClick() = controller.toggleDoorLight()

    fun onMirrorAutoFoldClick() = controller.toggleMirrorAutoFold()

    fun onMirrorRearAssistClick() = controller.toggleMirrorRearAssist()
}
