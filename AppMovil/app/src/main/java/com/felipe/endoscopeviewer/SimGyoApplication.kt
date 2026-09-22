package com.felipe.endoscopeviewer

import android.app.Application

class SimGyoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppDiagnostics.initialize(this)
    }
}
