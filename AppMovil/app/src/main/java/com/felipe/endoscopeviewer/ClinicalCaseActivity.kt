package com.felipe.endoscopeviewer

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToLong

class ClinicalCaseActivity : AppCompatActivity(), BleScaleListener {
    private lateinit var bluetoothButton: Button
    private lateinit var sensorInfoButton: ImageButton
    private lateinit var connectionStatus: TextView
    private lateinit var selectionStatus: TextView
    private lateinit var nextButton: Button
    private lateinit var caseButtons: List<Button>

    private var bleManager: BleScaleManager? = null
    private var bleDeviceDialog: AlertDialog? = null
    private var bleDeviceList: LinearLayout? = null
    private var bleScanStatus: TextView? = null
    private var isBleConnected = false
    private var latestScaleState: ScaleState? = null
    private var selectedCaseCm: Int? = null
    private var pendingTargetHalfSteps: Long? = null
    private var keepBleConnection = false

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) startBluetoothDeviceSelection()
        else Toast.makeText(this, "Se requieren permisos Bluetooth para conectar.", Toast.LENGTH_LONG).show()
    }

    private val diagnosticsExportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        val exported = AppDiagnostics.exportTo(this, uri)
        Toast.makeText(
            this,
            if (exported) "Registros guardados correctamente." else "No se pudieron guardar los registros.",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clinical_case)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.clinicalCaseRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bluetoothButton = findViewById(R.id.caseBluetoothButton)
        sensorInfoButton = findViewById(R.id.caseSensorInfoButton)
        connectionStatus = findViewById(R.id.caseConnectionStatus)
        selectionStatus = findViewById(R.id.caseSelectionStatus)
        nextButton = findViewById(R.id.caseNextButton)
        caseButtons = listOf(
            findViewById(R.id.case5Button),
            findViewById(R.id.case8Button),
            findViewById(R.id.case12Button)
        )

        bleManager = BleConnectionStore.acquire(this, this)
        bluetoothButton.setOnClickListener { connectBluetooth() }
        sensorInfoButton.setOnClickListener { showSensorStatusDialog() }
        caseButtons.zip(CASES).forEach { (button, clinicalCase) ->
            button.setOnClickListener { selectClinicalCase(clinicalCase) }
        }
        nextButton.setOnClickListener {
            keepBleConnection = true
            AppDiagnostics.record("Caso clinico confirmado; avanzando a evaluacion previa")
            startActivity(Intent(this, PreProcedureCheckActivity::class.java))
        }
        findViewById<Button>(R.id.previousButton).also { previousButton ->
            placeBackButtonBelowNext(previousButton, nextButton)
            previousButton.setOnClickListener { finish() }
        }
        updateCaseButtons()
        updateSensorInfoIcon()
    }

    override fun onResume() {
        super.onResume()
        // Al volver a esta pantalla, el boton Volver debe cerrar la conexion.
        keepBleConnection = false
    }

    override fun onDestroy() {
        bleDeviceDialog?.dismiss()
        if (isFinishing && !keepBleConnection) BleConnectionStore.close()
        super.onDestroy()
    }

    private fun connectBluetooth() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startBluetoothDeviceSelection()
        else bluetoothPermissionLauncher.launch(missing.toTypedArray())
    }

    private fun startBluetoothDeviceSelection() {
        showBluetoothDeviceDialog()
        bleManager?.startScan()
    }

    private fun showBluetoothDeviceDialog() {
        bleDeviceDialog?.dismiss()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val status = TextView(this).apply {
            text = "Buscando dispositivos cercanos…"
            setTextColor(Color.rgb(104, 112, 141))
            textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(list, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        content.addView(status)
        content.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(280)
        ))

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Seleccionar dispositivo")
            .setView(content)
            .setPositiveButton("Buscar de nuevo", null)
            .setNegativeButton("Cancelar", null)
            .create()
        bleDeviceList = list
        bleScanStatus = status
        bleDeviceDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                bleManager?.startScan()
            }
        }
        dialog.setOnDismissListener {
            bleManager?.cancelScan()
            if (bleDeviceDialog === dialog) {
                bleDeviceDialog = null
                bleDeviceList = null
                bleScanStatus = null
            }
        }
        dialog.show()
        onBleDevicesChanged(emptyList(), scanning = true)
    }

    private fun selectClinicalCase(clinicalCase: ClinicalCase) {
        if (!isBleConnected) return
        // La respuesta visual pertenece a la selección del usuario, no al
        // resultado asíncrono de la escritura Bluetooth.
        selectedCaseCm = clinicalCase.centimeters
        pendingTargetHalfSteps = clinicalCase.targetHalfSteps
        ClinicalCaseStore.saveSelectedCm(this, clinicalCase.centimeters)
        selectionStatus.text =
            "Caso ${clinicalCase.number} seleccionado. Centrando y ajustando el eje…"
        nextButton.isEnabled = false
        updateCaseButtons()

        val sent = bleManager?.sendClinicalCasePosition(clinicalCase.targetHalfSteps) == true
        if (!sent) {
            // Mantiene el caso marcado, pero impide continuar hasta que se
            // pueda enviar una nueva selección al simulador.
            pendingTargetHalfSteps = Long.MIN_VALUE
            selectionStatus.text = "Caso seleccionado, pero no se pudo enviar la posición al simulador. Inténtalo otra vez."
            updateCaseButtons()
            Toast.makeText(this, "No se pudo enviar la posición al simulador.", Toast.LENGTH_LONG).show()
            return
        }
    }

    private fun updateCaseButtons() {
        caseButtons.zip(CASES).forEach { (button, clinicalCase) ->
            button.isEnabled = isBleConnected
            button.isSelected = selectedCaseCm == clinicalCase.centimeters
            button.setTextColor(
                if (button.isSelected) Color.rgb(88, 209, 132) else Color.WHITE
            )
            button.text = if (button.isSelected) {
                "✓ Caso clínico ${clinicalCase.number}"
            } else {
                "Caso clínico ${clinicalCase.number}"
            }
            button.background = caseButtonBackground(
                selected = button.isSelected,
                enabled = button.isEnabled
            )
        }
        if (!isBleConnected) {
            selectionStatus.text = "Los casos se habilitarán al conectar el simulador."
        }
        nextButton.isEnabled =
            isBleConnected && selectedCaseCm != null && pendingTargetHalfSteps == null
    }

    override fun onBleStatus(message: String, connected: Boolean) {
        isBleConnected = connected
        connectionStatus.text = message
        connectionStatus.setTextColor(if (connected) Color.rgb(35, 134, 78) else Color.rgb(180, 53, 53))
        bluetoothButton.text = if (connected) "Cambiar dispositivo Bluetooth" else "Conectar por Bluetooth"
        updateCaseButtons()
        updateSensorInfoIcon()
    }

    override fun onScaleState(state: ScaleState) {
        latestScaleState = state
        val target = pendingTargetHalfSteps
        if (target != null &&
            state.motorPosition == target &&
            state.motorDirection == 0 &&
            !state.motorCentering
        ) {
            pendingTargetHalfSteps = null
            selectionStatus.text = "Caso seleccionado. El eje está listo."
            nextButton.isEnabled = true
        }
        updateSensorInfoIcon()
    }

    override fun onBleDevicesChanged(devices: List<BleDeviceInfo>, scanning: Boolean) {
        val list = bleDeviceList ?: return
        val status = bleScanStatus ?: return
        status.text = when {
            scanning && devices.isEmpty() -> "Buscando dispositivos cercanos…"
            scanning -> "Buscando… ${devices.size} dispositivo(s) encontrado(s)"
            devices.isEmpty() -> "No se encontraron dispositivos. Verifica que estén encendidos."
            else -> "Toca el dispositivo que deseas conectar."
        }
        list.removeAllViews()
        devices.forEach { device ->
            val option = TextView(this).apply {
                text = "${device.name}\nID: ${device.address} · Señal: ${device.rssi} dBm"
                setTextColor(Color.rgb(20, 39, 68))
                textSize = 14f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.rgb(250, 249, 253))
                    setStroke(dp(1), Color.rgb(211, 205, 224))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    bleManager?.connectToDevice(device)
                    bleDeviceDialog?.dismiss()
                }
            }
            list.addView(option, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun updateSensorInfoIcon() {
        val state = latestScaleState
        val color = when {
            !isBleConnected -> Color.rgb(190, 43, 43)
            state?.humidityDetected == true || state?.loadCellStatus in setOf(
                LoadCellStatus.UNAVAILABLE, LoadCellStatus.INVALID
            ) -> Color.rgb(190, 43, 43)
            state?.loadCellStatus == LoadCellStatus.INITIALIZING -> Color.rgb(196, 112, 0)
            else -> Color.rgb(35, 134, 78)
        }
        sensorInfoButton.imageTintList = ColorStateList.valueOf(color)
    }

    private fun showSensorStatusDialog() {
        val normal = ContextCompat.getColor(this, R.color.equipment_status_ok)
        val warning = ContextCompat.getColor(this, R.color.equipment_status_warning)
        val error = ContextCompat.getColor(this, R.color.equipment_status_error)
        val state = latestScaleState
        val statuses = listOf(
            EquipmentStatus("Bluetooth", if (isBleConnected) "Conectado" else "Desconectado", if (isBleConnected) normal else error),
            EquipmentStatus("Celda de carga", when (state?.loadCellStatus) {
                LoadCellStatus.READY -> "Lista"
                LoadCellStatus.INITIALIZING -> "Inicializando"
                LoadCellStatus.UNAVAILABLE -> "No detectada"
                LoadCellStatus.INVALID -> "Estado inválido"
                else -> "Sin datos"
            }, when (state?.loadCellStatus) {
                LoadCellStatus.READY -> normal
                LoadCellStatus.UNAVAILABLE, LoadCellStatus.INVALID -> error
                else -> warning
            }),
            EquipmentStatus("Sensor de humedad", when {
                state == null -> "Sin datos"
                state.humidityDetected -> "Humedad detectada"
                else -> "Sin humedad"
            }, when {
                state == null -> warning
                state.humidityDetected -> error
                else -> normal
            }),
            EquipmentStatus("Motor", when {
                state == null -> "Sin datos"
                state.motorCentering -> "Centrando · posición ${state.motorPosition}"
                state.motorDirection != 0 -> "Posicionando · posición ${state.motorPosition}"
                else -> "Listo · posición ${state.motorPosition}"
            }, if (state == null) warning else normal)
        )
        val content = layoutInflater.inflate(R.layout.dialog_equipment_status, null)
        val rowIds = listOf(
            R.id.bluetoothEquipmentStatusRow,
            R.id.loadCellEquipmentStatusRow,
            R.id.humidityEquipmentStatusRow,
            R.id.motorEquipmentStatusRow
        )
        rowIds.zip(statuses).forEach { (rowId, status) ->
            val row = content.findViewById<View>(rowId)
            row.findViewById<View>(R.id.equipmentStatusDot).backgroundTintList = ColorStateList.valueOf(status.color)
            row.findViewById<TextView>(R.id.equipmentStatusName).text = status.name
            row.findViewById<TextView>(R.id.equipmentStatusDetail).apply {
                text = status.detail
                setTextColor(status.color)
            }
        }
        content.findViewById<View>(R.id.cameraEquipmentStatusRow).visibility = View.GONE
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_info_purple)
            .setTitle(R.string.equipment_status_title)
            .setView(content)
            .setNeutralButton("Guardar registros") { _, _ -> exportDiagnostics() }
            .setPositiveButton(R.string.equipment_status_close, null)
            .show()
    }

    private fun exportDiagnostics() {
        AppDiagnostics.record("Se solicito exportar registros desde Estado del equipo")
        diagnosticsExportLauncher.launch(AppDiagnostics.createExportIntent())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToLong().toInt()

    private fun caseButtonBackground(selected: Boolean, enabled: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            val color = when {
                !enabled -> Color.rgb(152, 156, 167)
                selected -> Color.rgb(211, 47, 47)
                else -> Color.rgb(75, 18, 181)
            }
            setColor(color)
            if (selected) setStroke(dp(2), Color.rgb(120, 0, 0))
        }

    private data class ClinicalCase(val number: Int, val centimeters: Int, val targetHalfSteps: Long)
    private data class EquipmentStatus(val name: String, val detail: String, val color: Int)

    companion object {
        // A mayor profundidad se necesita menor apertura: 12 cm es el centro.
        // 5 cm usa el recorrido máximo y 8 cm ocupa 4/7 de ese recorrido.
        private const val MAX_CASE_CM = 12
        private const val MIN_CASE_CM = 5
        private const val MAX_CASE_HALF_STEPS = 1816L
        private val CASES = listOf(
            ClinicalCase(1, 5, MAX_CASE_HALF_STEPS),
            ClinicalCase(
                2,
                8,
                (MAX_CASE_HALF_STEPS * (MAX_CASE_CM - 8f) / (MAX_CASE_CM - MIN_CASE_CM))
                    .roundToLong()
            ),
            ClinicalCase(3, 12, 0L)
        )
    }
}
