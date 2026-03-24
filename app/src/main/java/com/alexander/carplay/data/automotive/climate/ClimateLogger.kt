package com.alexander.carplay.data.automotive.climate

import android.util.Log

internal object ClimateLogger {
    private const val TAG = "ClimateBar"

    fun log(scope: String, message: String) {
        Log.d("${TAG}_$scope", message)
    }

    fun info(message: String) {
        Log.d(TAG, message)
    }

    fun signal(
        propName: String,
        propId: Int,
        areaId: Int,
        value: Any?,
    ) {
        log("SIGNAL", "prop=$propName id=$propId area=$areaId value=$value")
    }

    fun action(
        button: String,
        detail: String = "",
    ) {
        log("ACTION", "button=$button $detail".trim())
    }

    fun read(
        propName: String,
        propId: Int,
        areaId: Int,
        result: Any?,
    ) {
        log("READ", "prop=$propName id=$propId area=$areaId -> $result")
    }

    fun write(
        propName: String,
        propId: Int,
        areaId: Int,
        value: Any?,
    ) {
        log("WRITE", "prop=$propName id=$propId area=$areaId <- $value")
    }

    fun error(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }
}
