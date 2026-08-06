package com.felipe.endoscopeviewer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.nio.charset.StandardCharsets
import java.util.UUID

data class ScaleState(
    val currentGrams: Float,
    val lastGrams: Float,
    val zone: Char,
    val alarm: Boolean,
    val humidityDetected: Boolean,
    val motorDirection: Int,
    val motorPosition: Long,
    val motorCanMoveLeft: Boolean,
    val motorCanMoveRight: Boolean,
    val motorCentering: Boolean
)

interface BleScaleListener {
    fun onBleStatus(message: String, connected: Boolean)
    fun onScaleState(state: ScaleState)
}

@SuppressLint("MissingPermission")
class BleScaleManager(
    context: Context,
    private val listener: BleScaleListener
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false

    private val scanTimeout = Runnable {
        if (!scanning) return@Runnable
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
        publishStatus("No se encontró el dispositivo. Comprueba que el ESP32 esté encendido.", false)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val advertisedServices = result.scanRecord?.serviceUuids.orEmpty()
            val advertisedName = result.scanRecord?.deviceName
            val isScale = advertisedServices.contains(ParcelUuid(SERVICE_UUID)) ||
                advertisedName in DEVICE_NAMES
            if (!isScale) return

            stopScan()
            publishStatus("Dispositivo encontrado. Conectando...", false)
            gatt?.close()
            gatt = result.device.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            publishStatus("Error al buscar Bluetooth ($errorCode).", false)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                publishStatus("Bluetooth conectado. Preparando datos...", false)
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!gatt.requestMtu(185)) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                commandCharacteristic = null
                publishStatus("Dispositivo Bluetooth desconectado.", false)
                gatt.close()
                if (this@BleScaleManager.gatt === gatt) this@BleScaleManager.gatt = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                publishStatus("No se pudieron leer los servicios Bluetooth.", false)
                return
            }

            val service: BluetoothGattService? = gatt.getService(SERVICE_UUID)
            val stateCharacteristic = service?.getCharacteristic(STATE_UUID)
            commandCharacteristic = service?.getCharacteristic(COMMAND_UUID)
            if (stateCharacteristic == null || commandCharacteristic == null) {
                publishStatus("El ESP32 no tiene el firmware Bluetooth esperado.", false)
                return
            }

            gatt.setCharacteristicNotification(stateCharacteristic, true)
            val descriptor = stateCharacteristic.getDescriptor(CLIENT_CONFIG_UUID)
            if (descriptor == null) {
                publishStatus("No fue posible activar los datos en tiempo real.", false)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                publishStatus("Dispositivo conectado", true)
            } else {
                publishStatus("Falló la suscripción a los datos ($status).", false)
            }
        }

        @Deprecated("Usado por Android 12 y anteriores")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                parseState(characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            parseState(value)
        }
    }

    fun startScan() {
        if (adapter == null) {
            publishStatus("Este teléfono no tiene Bluetooth.", false)
            return
        }
        if (adapter?.isEnabled != true) {
            publishStatus("Activa Bluetooth en el teléfono y vuelve a pulsar Conectar.", false)
            return
        }

        closeConnection()
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            publishStatus("Bluetooth BLE no está disponible.", false)
            return
        }

        scanning = true
        publishStatus("Buscando el dispositivo por Bluetooth...", false)
        scanner.startScan(scanCallback)
        mainHandler.removeCallbacks(scanTimeout)
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    fun sendTare(): Boolean = sendCommand(
        command = "TARE",
        writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    )

    fun sendMotor(direction: Int): Boolean = sendCommand(
        command = "MOTOR,${direction.coerceIn(-1, 1)}",
        writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    )

    fun sendCenter(): Boolean = sendCommand(
        command = "CENTER",
        writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    )

    private fun sendCommand(command: String, writeType: Int): Boolean {
        val characteristic = commandCharacteristic ?: return false
        val currentGatt = gatt ?: return false
        val payload = command.toByteArray(StandardCharsets.UTF_8)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeCharacteristic(
                characteristic,
                payload,
                writeType
            ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(characteristic)
        }
    }

    fun close() {
        stopScan()
        closeConnection()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun stopScan() {
        if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
        mainHandler.removeCallbacks(scanTimeout)
    }

    private fun closeConnection() {
        commandCharacteristic = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private fun parseState(value: ByteArray) {
        val fields = value.toString(StandardCharsets.UTF_8).trim().split(',')
        if (fields.size < 5 || fields[0] != "S") return

        val current = fields[1].toFloatOrNull() ?: return
        val last = fields[2].toFloatOrNull() ?: return
        val zone = fields[3].firstOrNull() ?: return
        val alarm = fields[4] == "1"
        val humidityDetected = fields.getOrNull(5) == "1"
        val motorDirection = fields.getOrNull(6)?.toIntOrNull() ?: 0
        val motorPosition = fields.getOrNull(7)?.toLongOrNull() ?: 0L
        val motorCanMoveLeft = fields.getOrNull(8)?.let { it == "1" } ?: true
        val motorCanMoveRight = fields.getOrNull(9)?.let { it == "1" } ?: true
        val motorCentering = fields.getOrNull(10) == "1"
        mainHandler.post {
            listener.onScaleState(
                ScaleState(
                    currentGrams = current,
                    lastGrams = last,
                    zone = zone,
                    alarm = alarm,
                    humidityDetected = humidityDetected,
                    motorDirection = motorDirection,
                    motorPosition = motorPosition,
                    motorCanMoveLeft = motorCanMoveLeft,
                    motorCanMoveRight = motorCanMoveRight,
                    motorCentering = motorCentering
                )
            )
        }
    }

    private fun publishStatus(message: String, connected: Boolean) {
        mainHandler.post { listener.onBleStatus(message, connected) }
    }

    companion object {
        private val DEVICE_NAMES = setOf("CeldaCarga-S3", "CeldaCarga-C3")
        private const val SCAN_TIMEOUT_MS = 12_000L
        private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        private val STATE_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        private val COMMAND_UUID = UUID.fromString("e3223119-9445-4e96-a4a1-85358c4046a2")
        private val CLIENT_CONFIG_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
