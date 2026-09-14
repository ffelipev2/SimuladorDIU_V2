package com.felipe.endoscopeviewer

import android.content.Context

/** Conserva una única conexión BLE mientras se avanza por el simulador. */
object BleConnectionStore {
    private var manager: BleScaleManager? = null

    fun acquire(context: Context, listener: BleScaleListener): BleScaleManager {
        val activeManager = manager
        return if (activeManager != null) {
            activeManager.setListener(listener)
            activeManager
        } else {
            BleScaleManager(context.applicationContext, listener).also { manager = it }
        }
    }

    fun close() {
        manager?.close()
        manager = null
    }
}
