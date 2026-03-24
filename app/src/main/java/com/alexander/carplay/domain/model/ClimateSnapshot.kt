package com.alexander.carplay.domain.model

data class ClimateSnapshot(
    val driverTemp: Float = 22f,
    val passengerTemp: Float = 22f,
    val cabinTemp: Float? = null,
    val outsideTemp: Float? = null,
    val fanSpeed: Int = 0,
    val fanDirection: Int = 1,
    val acOn: Int = 0,
    val autoMode: Int = 0,
    val recirculation: Int = 0,
    val dualState: Int = 0,
    val driverSeatHeat: Int = 0,
    val driverSeatVent: Int = 0,
    val passengerSeatHeat: Int = 0,
    val passengerSeatVent: Int = 0,
    val mirrorAutoFold: Int = 0,
    val mirrorRearAssist: Int = 0,
    val doorLight: Int = 0,
    val isConnected: Boolean = false,
)
