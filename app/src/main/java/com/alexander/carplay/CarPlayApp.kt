package com.alexander.carplay

import android.app.Application
import com.alexander.carplay.app.di.AppContainer

class CarPlayApp : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}

