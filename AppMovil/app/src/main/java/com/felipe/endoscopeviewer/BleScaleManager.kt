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

enum class LoadCellStatus {
    UNAVAILABLE,
    INITIALIZING,
    READY,
    UNKNOWN,
    INVALID
}

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
    val motorCentering: Boolean,
    val tareInProgress: Boolean,
    val hasTareStatus: Boolean,
    val loadCellStatus: LoadCellStatus
)

data class BleDeviceInfo(
    val name: String,
    val address: String,
    val rssi: Int
)

interface BleScaleListener {
    fun onBleStatus(message: String, connected: Boolean)
    fun onScaleState(state: ScaleState)
    fun onBleDevicesChanged(devices: List<BleDeviceInfo>, scanning: Boolean)
}

@SuppressLint("MissingPermission")
class BleScaleManager(
    context: Context,
    private var listener: BleScaleListener
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var connectionReady = false
    private var selectedDeviceName: String? = null
    private var selectedDevice: BluetoothDevice? = null
    private var connectionAttempts = 0
    private var lastStatus: Pair<String, Boolean>? = null
    private var lastScaleState: ScaleState? = null
    private val discoveredDevices = linkedMapOf<String, BleDeviceInfo>()

    private val scanTimeout = Runnable {
        if (!scanning) return@Runnable
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
        publishDevices(scanning = false)
        if (discoveredDevices.isEmpty()) {
            publishStatus(
                "No se encontraron dispositivos. Comprueba que estén encendidos.",
                connectionReady
            )
        } else {
            publishStatus(
                "Selecciona el dispositivo que deseas conectar.",
                connectionReady
            )
        }
    }

    // Algunos teléfonos fallan el primer enlace GATT aunque el dispositivo
    // sea visible. Reintentamos una vez sin obligar al usuario a tocarlo otra vez.
    private val connectionTimeout = Runnable {
        if (connectionReady || gatt == null) return@Runnable
        val device = selectedDevice ?: return@Runnable
        if (connectionAttempts >= MAX_CONNECTION_ATTEMPTS) {
            publishStatus("No se pudo completar la conexión Bluetooth.", false)
            return@Runnable
        }
        restartConnection(device)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            registerScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::registerScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            if (!scanning) return
            scanning = false
            publishDevices(scanning = false)
            publishStatus("Error al buscar Bluetooth ($errorCode).", connectionReady)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        private fun isCurrentConnection(gatt: BluetoothGatt): Boolean =
            this@BleScaleManager.gatt === gatt

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrentConnection(gatt)) {
                gatt.close()
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                publishGattStatus(
                    gatt,
                    "${deviceName(gatt.device.address)} conectado. Preparando datos...",
                    false
                )
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!gatt.requestMtu(185)) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val deviceToRetry = selectedDevice
                val shouldRetry =
                    !connectionReady &&
                        deviceToRetry != null &&
                        connectionAttempts < MAX_CONNECTION_ATTEMPTS
                commandCharacteristic = null
                connectionReady = false
                lastScaleState = null
                this@BleScaleManager.gatt = null
                gatt.close()
                if (shouldRetry) {
                    mainHandler.postDelayed(
                        { deviceToRetry?.let(::restartConnection) },
                        RETRY_DELAY_MS
                    )
                } else {
                    selectedDeviceName = null
                    selectedDevice = null
                    publishStatus("${deviceName(gatt.device.address)} desconectado.", false)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!isCurrentConnection(gatt)) return
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrentConnection(gatt)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                publishGattStatus(gatt, "No se pudieron leer los servicios Bluetooth.", false)
                return
            }

            val service: BluetoothGattService? = gatt.getService(SERVICE_UUID)
            val stateCharacteristic = service?.getCharacteristic(STATE_UUID)
            commandCharacteristic = service?.getCharacteristic(COMMAND_UUID)
            if (stateCharacteristic == null || commandCharacteristic == null) {
                publishGattStatus(
                    gatt,
                    "El ESP32 no tiene el firmware Bluetooth esperado.",
                    false
                )
                return
            }

            gatt.setCharacteristicNotification(stateCharacteristic, true)
            val descriptor = stateCharacteristic.getDescriptor(CLIENT_CONFIG_UUID)
            if (descriptor == null) {
                publishGattStatus(
                    gatt,
                    "No fue posible activar los datos en tiempo real.",
                    false
                )
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
            if (!isCurrentConnection(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                connectionReady = true
                connectionAttempts = 0
                mainHandler.removeCallbacks(connectionTimeout)
                publishGattStatus(gatt, "${deviceName(gatt.device.address)} conectado", true)
            } else {
                connectionReady = false
                publishGattStatus(gatt, "Falló la suscripción a los datos ($status).", false)
            }
        }

        @Deprecated("Usado por Android 12 y anteriores")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (!isCurrentConnection(gatt)) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                parseState(gatt, characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (!isCurrentConnection(gatt)) return
            parseState(gatt, value)
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

        stopScan()
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            publishStatus("Bluetooth BLE no está disponible.", false)
            return
        }

        discoveredDevices.clear()
        scanning = true
        publishDevices(scanning = true)
        publishStatus("Buscando dispositivos SimGyO por Bluetooth...", connectionReady)
        scanner.startScan(scanCallback)
        mainHandler.removeCallbacks(scanTimeout)
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    /** Permite entregar una conexión activa a la siguiente pantalla. */
    fun setListener(listener: BleScaleListener) {
        this.listener = listener
        mainHandler.post {
            lastStatus?.let { (message, connected) -> listener.onBleStatus(message, connected) }
            lastScaleState?.let(listener::onScaleState)
        }
    }

    fun connectToDevice(device: BleDeviceInfo) {
        val currentAdapter = adapter
        if (currentAdapter == null || currentAdapter.isEnabled != true) {
            publishStatus("Bluetooth no está disponible o está desactivado.", false)
            return
        }

        val remoteDevice = try {
            currentAdapter.getRemoteDevice(device.address)
        } catch (_: IllegalArgumentException) {
            publishStatus("El identificador Bluetooth seleccionado no es válido.", false)
            return
        }

        stopScan()
        closeConnection()
        selectedDeviceName = device.name
        selectedDevice = remoteDevice
        connectionAttempts = 0
        publishStatus("Conectando a ${device.name}...", false)
        openGattConnection(remoteDevice)
    }

    fun cancelScan() {
        if (!scanning) return
        stopScan()
        publishDevices(scanning = false)
        if (connectionReady) {
            publishStatus("${selectedDeviceName ?: "Dispositivo SimGyO"} conectado", true)
        } else {
            publishStatus("Selección Bluetooth cancelada.", false)
        }
    }

    private fun registerScanResult(result: ScanResult) {
        if (!scanning) return

        val advertisedServices = result.scanRecord?.serviceUuids.orEmpty()
        val advertisedName = result.scanRecord?.deviceName
        val isSimGyoDevice =
            advertisedServices.contains(ParcelUuid(SERVICE_UUID)) ||
                advertisedName in LEGACY_DEVICE_NAMES ||
                DEVICE_NAME_PREFIXES.any { prefix ->
                    advertisedName?.startsWith(prefix, ignoreCase = true) == true
                }
        if (!isSimGyoDevice) return

        val address = result.device.address
        val name = advertisedName
            ?: result.device.name
            ?: "SimGyO-DIU"
        val info = BleDeviceInfo(
            name = name,
            address = address,
            rssi = result.rssi
        )
        if (discoveredDevices[address] != info) {
            discoveredDevices[address] = info
            publishDevices(scanning = true)
        }
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

    /**
     * Centra el eje y, cuando termina, lo desplaza a una posición absoluta.
     * El firmware limita el valor para que nunca sobrepase el recorrido seguro.
     */
    fun sendClinicalCasePosition(targetHalfSteps: Long): Boolean = sendCommand(
        command = "CASE,$targetHalfSteps",
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
        mainHandler.removeCallbacks(connectionTimeout)
        commandCharacteristic = null
        connectionReady = false
        lastScaleState = null
        val previousGatt = gatt
        gatt = null
        previousGatt?.disconnect()
        previousGatt?.close()
        selectedDeviceName = null
        selectedDevice = null
        connectionAttempts = 0
    }

    private fun openGattConnection(device: BluetoothDevice) {
        connectionAttempts++
        gatt = device.connectGatt(
            appContext,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
        mainHandler.removeCallbacks(connectionTimeout)
        mainHandler.postDelayed(connectionTimeout, CONNECTION_TIMEOUT_MS)
    }

    private fun restartConnection(device: BluetoothDevice) {
        val previousGatt = gatt
        gatt = null
        commandCharacteristic = null
        connectionReady = false
        previousGatt?.disconnect()
        previousGatt?.close()
        publishStatus("Reintentando conexión con ${selectedDeviceName ?: "el dispositivo"}...", false)
        openGattConnection(device)
    }

    private fun parseState(sourceGatt: BluetoothGatt, value: ByteArray) {
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
        val hasTareStatus = fields.size > 11
        val tareInProgress = fields.getOrNull(11) == "1"
        val loadCellStatus = when (fields.getOrNull(12)) {
            null -> LoadCellStatus.UNKNOWN
            "0" -> LoadCellStatus.UNAVAILABLE
            "1" -> LoadCellStatus.INITIALIZING
            "2" -> LoadCellStatus.READY
            else -> LoadCellStatus.INVALID
        }
        mainHandler.post {
            if (gatt !== sourceGatt) return@post
            lastScaleState = ScaleState(
                currentGrams = current,
                lastGrams = last,
                zone = zone,
                alarm = alarm,
                humidityDetected = humidityDetected,
                motorDirection = motorDirection,
                motorPosition = motorPosition,
                motorCanMoveLeft = motorCanMoveLeft,
                motorCanMoveRight = motorCanMoveRight,
                motorCentering = motorCentering,
                tareInProgress = tareInProgress,
                hasTareStatus = hasTareStatus,
                loadCellStatus = loadCellStatus
            )
            listener.onScaleState(
                lastScaleState ?: return@post
            )
        }
    }

    private fun publishStatus(message: String, connected: Boolean) {
        lastStatus = message to connected
        mainHandler.post { listener.onBleStatus(message, connected) }
    }

    private fun publishGattStatus(
        sourceGatt: BluetoothGatt,
        message: String,
        connected: Boolean
    ) {
        mainHandler.post {
            if (gatt === sourceGatt || (!connected && gatt == null)) {
                lastStatus = message to connected
                listener.onBleStatus(message, connected)
            }
        }
    }

    private fun publishDevices(scanning: Boolean) {
        val devices = discoveredDevices.values.sortedByDescending { it.rssi }
        mainHandler.post { listener.onBleDevicesChanged(devices, scanning) }
    }

    private fun deviceName(address: String): String =
        discoveredDevices[address]?.name ?: selectedDeviceName ?: "Dispositivo SimGyO"

    companion object {
        private val LEGACY_DEVICE_NAMES = setOf("CeldaCarga-S3", "CeldaCarga-C3")
        private val DEVICE_NAME_PREFIXES = setOf("SimGyO-DIU-", "CeldaCarga-")
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val CONNECTION_TIMEOUT_MS = 8_000L
        private const val RETRY_DELAY_MS = 350L
        private const val MAX_CONNECTION_ATTEMPTS = 2
        private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        private val STATE_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        private val COMMAND_UUID = UUID.fromString("e3223119-9445-4e96-a4a1-85358c4046a2")
        private val CLIENT_CONFIG_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
