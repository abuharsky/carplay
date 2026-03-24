package com.alexander.carplay

import android.app.Application
import com.alexander.carplay.app.di.AppContainer
import com.alexander.carplay.data.logging.ProcessDiagnostics

class CarPlayApp : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        ProcessDiagnostics.initialize(this, appContainer.logStore)
        appContainer.seatAutoComfortController.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::appContainer.isInitialized) {
            ProcessDiagnostics.logTrimMemory(appContainer.logStore, "App", level)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::appContainer.isInitialized) {
            ProcessDiagnostics.logLowMemory(appContainer.logStore, "App")
        }
    }

    override fun onTerminate() {
        if (::appContainer.isInitialized) {
            appContainer.seatAutoComfortController.stop()
            ProcessDiagnostics.recordExpectedExit(this, appContainer.logStore, "application onTerminate")
        }
        super.onTerminate()
    }
}
