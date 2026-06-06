package com.dem.telemetry

import android.app.Application

class TelemetryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogStore.initialize(this)
    }
}
