package com.felipe.endoscopeviewer

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.MultiCameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioSurfaceView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class UsbCameraFragment : MultiCameraFragment(), BleScaleListener, ICameraStateCallBack {
    private lateinit var cameraContainer: FrameLayout
    private lateinit var cameraOverlay: TextView
    private lateinit var cameraStatus: TextView
    private lateinit var switchCameraButton: Button
    private lateinit var bleStatus: TextView
    private lateinit var connectionBadge: TextView
    private lateinit var currentWeight: TextView
    private lateinit var lastWeight: TextView
    private lateinit var zoneText: TextView
    private lateinit var forceGauge: ForceGaugeView
    private lateinit var tareButton: Button
    private lateinit var tareProgress: ProgressBar
    private lateinit var bluetoothButton: Button
    private lateinit var measuredValueSpinner: Spinner
    private lateinit var procedureChecks: List<CheckBox>
    private lateinit var procedureProgress: TextView
    private lateinit var procedureNextButton: Button
    private lateinit var sensorInfoButton: ImageButton
    private lateinit var historyList: LinearLayout
    private lateinit var historySelectedText: TextView
    private lateinit var clearHistoryButton: Button
    private lateinit var historyTabButton: Button
    private lateinit var thresholdsTabButton: Button
    private lateinit var historyContent: View
    private lateinit var thresholdsContent: View
    private lateinit var thresholdsList: LinearLayout

    private var bleManager: BleScaleManager? = null
    private var bleDeviceDialog: AlertDialog? = null
    private var bleDeviceList: LinearLayout? = null
    private var bleScanStatus: TextView? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var isBleConnected = false
    private var latestScaleState: ScaleState? = null
    private var latestScaleStateAt = 0L
    private var telemetryMarkedStale = true
    private var lastCameraError: String? = null
    private var lastCameraErrorDeviceId: Int? = null
    private var tareUiActive = false
    private var tareFirmwareStarted = false
    private var tareStartedAt = 0L
    private var legacyTareZeroSamples = 0
    private var measuredValue: Int? = null
    private val tareTimeout = Runnable {
        if (tareUiActive) {
            finishTareUi(
                success = false,
                message = "No se recibió la confirmación de tara. Intenta nuevamente."
            )
        }
    }
    private val resetTareResult = Runnable { resetTareUi() }
    private val telemetryWatchdog = object : Runnable {
        override fun run() {
            if (!isBleConnected) return

            if (!hasRecentTelemetry() && !telemetryMarkedStale) {
                telemetryMarkedStale = true
                if (tareUiActive) {
                    finishTareUi(
                        success = false,
                        message = "Tara interrumpida: no llegan datos del simulador."
                    )
                }
                showTelemetryUnavailable()
                updateSensorInfoIcon()
            }
            uiHandler.postDelayed(this, TELEMETRY_WATCHDOG_INTERVAL_MS)
        }
    }
    private val cameraSlots = mutableMapOf<Int, Int>()
    private val connectedCameraIds = mutableSetOf<Int>()
    private var selectedDeviceId: Int? = null
    private var openingDeviceId: Int? = null
    private var activeCamera: MultiCameraClient.ICamera? = null
    private val pressHistory = mutableListOf<PressRecord>()
    private var nextHistoryId = 1L
    private var pressActive = false
    private var pressPeakGrams = 0f
    private var pressReleaseStartedAt = 0L
    private val historyTimeFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startBluetoothDeviceSelection()
        } else {
            onBleStatus("Se necesitan permisos Bluetooth para conectar el dispositivo.", false)
        }
    }

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        val root = inflater.inflate(R.layout.fragment_usb_camera, container, false)
        cameraContainer = root.findViewById(R.id.cameraViewContainer)
        cameraOverlay = root.findViewById(R.id.cameraStatusText)
        cameraStatus = root.findViewById(R.id.statusText)
        switchCameraButton = root.findViewById(R.id.switchCameraButton)
        bleStatus = root.findViewById(R.id.bleStatusText)
        connectionBadge = root.findViewById(R.id.connectionBadge)
        currentWeight = root.findViewById(R.id.currentWeightText)
        lastWeight = root.findViewById(R.id.lastWeightText)
        zoneText = root.findViewById(R.id.zoneText)
        forceGauge = root.findViewById(R.id.forceGauge)
        tareButton = root.findViewById(R.id.tareButton)
        tareProgress = root.findViewById(R.id.tareProgress)
        bluetoothButton = root.findViewById(R.id.bluetoothButton)
        measuredValueSpinner = root.findViewById(R.id.measuredValueSpinner)
        procedureChecks = listOf(
            root.findViewById(R.id.loadDiuCheck),
            root.findViewById(R.id.fixMeasurementCheck),
            root.findViewById(R.id.releaseCheck),
            root.findViewById(R.id.successfulRemovalCheck),
            root.findViewById(R.id.cutThreadsCheck)
        )
        procedureProgress = root.findViewById(R.id.procedureProgressText)
        procedureNextButton = root.findViewById(R.id.procedureNextButton)
        sensorInfoButton = root.findViewById(R.id.sensorInfoButton)
        historyList = root.findViewById(R.id.historyList)
        historySelectedText = root.findViewById(R.id.historySelectedText)
        clearHistoryButton = root.findViewById(R.id.clearHistoryButton)
        historyTabButton = root.findViewById(R.id.historyTabButton)
        thresholdsTabButton = root.findViewById(R.id.thresholdsTabButton)
        historyContent = root.findViewById(R.id.historyContent)
        thresholdsContent = root.findViewById(R.id.thresholdsContent)
        thresholdsList = root.findViewById(R.id.thresholdsList)

        listOf(
            switchCameraButton,
            bluetoothButton,
            tareButton,
            clearHistoryButton,
            historyTabButton,
            thresholdsTabButton
        ).forEach { it.backgroundTintList = null }

        bleManager = BleConnectionStore.acquire(requireContext(), this)
        measuredValueSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            listOf("Selecciona un valor") + (4..15).map(Int::toString)
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        measuredValueSpinner.setSelection(0, false)
        measuredValueSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                measuredValue = if (position == 0) null else position + 3
                updateProcedureState()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) = Unit
        })
        procedureChecks.forEach { checkBox ->
            checkBox.setOnCheckedChangeListener { _, _ -> updateProcedureState() }
        }
        procedureNextButton.setOnClickListener { finishProcedure() }
        switchCameraButton.setOnClickListener { selectNextCamera() }
        bluetoothButton.setOnClickListener { connectBluetooth() }
        sensorInfoButton.setOnClickListener { showSensorStatusDialog() }
        clearHistoryButton.setOnClickListener { clearPressHistory() }
        historyTabButton.setOnClickListener { showHistoryTab() }
        thresholdsTabButton.setOnClickListener { showThresholdsTab() }
        tareButton.setOnClickListener {
            if (!isBleConnected || tareUiActive || !isLoadCellUsable()) {
                return@setOnClickListener
            }
            // Deja de renovar cualquier movimiento antes de pedir la tara.
            // El firmware también detiene el motor al procesar TARE.
            if (bleManager?.sendTare() == true) {
                startTareUi()
            } else {
                showTareStatus(
                    "No se pudo iniciar la tara. Revisa la conexión.",
                    Color.rgb(190, 43, 43),
                    Color.rgb(255, 244, 244),
                    Color.rgb(242, 164, 164)
                )
            }
        }
        loadPressHistory()
        renderPressHistory()
        renderThresholds()
        showHistoryTab()
        updateZone('B', alarm = false, humidityDetected = false)
        updateProcedureState()
        updateSensorInfoIcon()
        return root
    }

    override fun onResume() {
        super.onResume()
        if (::cameraStatus.isInitialized) updateCameraControls()
    }

    override fun clear() {
        uiHandler.removeCallbacks(tareTimeout)
        uiHandler.removeCallbacks(resetTareResult)
        uiHandler.removeCallbacks(telemetryWatchdog)
        bleDeviceDialog?.dismiss()
        bleDeviceDialog = null
        bleDeviceList = null
        bleScanStatus = null
        BleConnectionStore.close()
        bleManager = null
        activeCamera?.setCameraStateCallBack(null)
        activeCamera = null
        super.clear()
    }

    private fun updateProcedureState() {
        if (!::procedureProgress.isInitialized) return
        val checkedCount = procedureChecks.count { it.isChecked }
        val hasMeasuredValue = measuredValue != null
        procedureProgress.text = when {
            !hasMeasuredValue -> "$checkedCount de ${procedureChecks.size} pasos listos · selecciona el valor medido"
            else -> "$checkedCount de ${procedureChecks.size} pasos listos · valor medido: $measuredValue"
        }
        procedureNextButton.isEnabled = hasMeasuredValue && checkedCount == procedureChecks.size
    }

    private fun finishProcedure() {
        val value = measuredValue ?: return
        ProcedureSummaryStore.saveMeasuredValue(requireContext(), value)
        procedureChecks.forEach { checkBox ->
            ProcedureSummaryStore.saveAction(
                requireContext(),
                checkBox.text.toString(),
                checkBox.isChecked
            )
        }
        startActivity(Intent(requireContext(), ProcedureSummaryActivity::class.java))
    }

    override fun generateCamera(ctx: Context, device: UsbDevice): MultiCameraClient.ICamera {
        val freeSlot = generateSequence(1) { it + 1 }
            .first { it !in cameraSlots.values }
        cameraSlots[device.deviceId] = freeSlot
        if (selectedDeviceId == null) selectedDeviceId = device.deviceId
        return CameraUVC(ctx, device)
    }

    override fun onCameraAttached(camera: MultiCameraClient.ICamera) {
        if (selectedDeviceId == null) {
            selectedDeviceId = camera.getUsbDevice().deviceId
        }
        updateCameraControls()
    }

    override fun onCameraConnected(camera: MultiCameraClient.ICamera) {
        val deviceId = camera.getUsbDevice().deviceId
        connectedCameraIds.add(deviceId)
        lastCameraError = null
        lastCameraErrorDeviceId = null
        if (selectedDeviceId == null) selectedDeviceId = deviceId

        if (selectedDeviceId == deviceId) {
            openSelectedCamera(camera)
        } else {
            updateCameraControls()
        }
    }

    override fun onCameraDisConnected(camera: MultiCameraClient.ICamera) {
        val deviceId = camera.getUsbDevice().deviceId
        connectedCameraIds.remove(deviceId)
        if (lastCameraErrorDeviceId == deviceId) {
            lastCameraError = null
            lastCameraErrorDeviceId = null
        }
        if (activeCamera === camera) {
            camera.closeCamera()
            activeCamera = null
            openingDeviceId = null
            showCameraMessage("Cámara ${slotOf(camera)} sin conexión")
        }
        updateCameraControls()
    }

    override fun onCameraDetached(camera: MultiCameraClient.ICamera) {
        val deviceId = camera.getUsbDevice().deviceId
        connectedCameraIds.remove(deviceId)
        if (lastCameraErrorDeviceId == deviceId) {
            lastCameraError = null
            lastCameraErrorDeviceId = null
        }
        camera.closeCamera()
        cameraSlots.remove(deviceId)

        if (selectedDeviceId == deviceId) {
            activeCamera = null
            openingDeviceId = null
            val replacement = sortedCameras().firstOrNull()
            selectedDeviceId = replacement?.getUsbDevice()?.deviceId
            if (replacement == null) {
                showCameraMessage("Conecta una cámara mediante OTG")
            } else if (connectedCameraIds.contains(replacement.getUsbDevice().deviceId)) {
                cameraContainer.postDelayed(
                    { if (isAdded) openSelectedCamera(replacement) },
                    CAMERA_SWITCH_DELAY_MS
                )
            } else {
                requestPermission(replacement.getUsbDevice())
            }
        }
        updateCameraControls()
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        if (!isAdded || self.getUsbDevice().deviceId != selectedDeviceId) return
        requireActivity().runOnUiThread {
            val slot = slotOf(self)
            when (code) {
                ICameraStateCallBack.State.OPENED -> {
                    openingDeviceId = null
                    lastCameraError = null
                    lastCameraErrorDeviceId = null
                    cameraOverlay.visibility = View.GONE
                    cameraStatus.text =
                        "${sortedCameras().size} cámara(s) detectada(s) · mostrando cámara $slot"
                }

                ICameraStateCallBack.State.CLOSED -> {
                    openingDeviceId = null
                    showCameraMessage("Cámara $slot cerrada")
                }

                ICameraStateCallBack.State.ERROR -> {
                    openingDeviceId = null
                    lastCameraError = msg ?: "revisa la conexión USB"
                    lastCameraErrorDeviceId = self.getUsbDevice().deviceId
                    showCameraMessage(
                        "No se pudo abrir la cámara $slot: ${msg ?: "revisa la conexión USB"}"
                    )
                }
            }
            updateCameraControls()
        }
    }

    private fun selectNextCamera() {
        val cameras = sortedCameras()
        if (cameras.size < 2) {
            cameraStatus.text = "Sólo hay una cámara USB conectada"
            return
        }

        val currentIndex = cameras.indexOfFirst {
            it.getUsbDevice().deviceId == selectedDeviceId
        }.coerceAtLeast(0)
        val nextCamera = cameras[(currentIndex + 1) % cameras.size]
        val nextDeviceId = nextCamera.getUsbDevice().deviceId
        val nextSlot = slotOf(nextCamera)

        activeCamera?.apply {
            setCameraStateCallBack(null)
            closeCamera()
        }
        activeCamera = null
        openingDeviceId = null
        selectedDeviceId = nextDeviceId
        showCameraMessage("Cambiando a cámara $nextSlot...")
        updateCameraControls()

        cameraContainer.postDelayed({
            if (!isAdded || selectedDeviceId != nextDeviceId) return@postDelayed
            if (connectedCameraIds.contains(nextDeviceId)) {
                openSelectedCamera(nextCamera)
            } else {
                requestPermission(nextCamera.getUsbDevice())
            }
        }, CAMERA_SWITCH_DELAY_MS)
    }

    private fun openSelectedCamera(camera: MultiCameraClient.ICamera) {
        val deviceId = camera.getUsbDevice().deviceId
        if (
            !isAdded ||
            selectedDeviceId != deviceId ||
            openingDeviceId == deviceId ||
            camera.isCameraOpened()
        ) {
            return
        }

        openingDeviceId = deviceId
        activeCamera = camera
        val slot = slotOf(camera)
        showCameraMessage("Abriendo cámara $slot...")

        val cameraView = AspectRatioSurfaceView(requireContext())
        cameraContainer.removeAllViews()
        cameraContainer.addView(
            cameraView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        (cameraOverlay.parent as? ViewGroup)?.removeView(cameraOverlay)
        cameraContainer.addView(
            cameraOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        cameraView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (!isAdded || selectedDeviceId != deviceId) return
                camera.setCameraStateCallBack(this@UsbCameraFragment)
                camera.openCamera(cameraView, cameraRequest())
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                camera.setRenderSize(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
    }

    private fun showCameraMessage(message: String) {
        cameraOverlay.text = message
        cameraOverlay.visibility = View.VISIBLE
    }

    private fun sortedCameras(): List<MultiCameraClient.ICamera> =
        getCameraMap().values.sortedBy(::slotOf)

    private fun slotOf(camera: MultiCameraClient.ICamera): Int =
        cameraSlots[camera.getUsbDevice().deviceId] ?: 1

    private fun updateCameraControls() {
        if (!::switchCameraButton.isInitialized) return
        val cameras = sortedCameras()
        switchCameraButton.isEnabled = cameras.size > 1

        val selectedId = selectedDeviceId
        val selectedSlot = selectedId?.let { cameraSlots[it] }
        val nextCamera = cameras.firstOrNull {
            it.getUsbDevice().deviceId != selectedId
        }
        switchCameraButton.text = if (nextCamera == null) {
            "Sin otra cámara"
        } else {
            "Ver cámara ${slotOf(nextCamera)}"
        }

        if (activeCamera?.isCameraOpened() != true) {
            cameraStatus.text = when (cameras.size) {
                0 -> "Conecta una cámara mediante OTG"
                1 -> "1 cámara USB detectada"
                else -> "${cameras.size} cámaras USB detectadas" +
                    (selectedSlot?.let { " · seleccionada cámara $it" } ?: "")
            }
        }
        updateSensorInfoIcon()
    }

    private fun cameraRequest(): CameraRequest = CameraRequest.Builder()
        .setPreviewWidth(640)
        .setPreviewHeight(480)
        .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_YUYV)
        .setRenderMode(CameraRequest.RenderMode.NORMAL)
        .setDefaultRotateType(RotateType.ANGLE_0)
        .setAspectRatioShow(true)
        .setCaptureRawImage(false)
        .setRawPreviewData(false)
        .create()

    private fun showHistoryTab() {
        historyContent.visibility = View.VISIBLE
        thresholdsContent.visibility = View.GONE
        clearHistoryButton.visibility = View.VISIBLE
        styleTab(historyTabButton, selected = true)
        styleTab(thresholdsTabButton, selected = false)
    }

    private fun showThresholdsTab() {
        historyContent.visibility = View.GONE
        thresholdsContent.visibility = View.VISIBLE
        clearHistoryButton.visibility = View.INVISIBLE
        styleTab(thresholdsTabButton, selected = true)
        styleTab(historyTabButton, selected = false)
    }

    private fun styleTab(button: Button, selected: Boolean) {
        val color = if (selected) {
            Color.WHITE
        } else {
            Color.rgb(75, 18, 181)
        }
        button.setBackgroundResource(
            if (selected) R.drawable.tab_selected_background
            else R.drawable.tab_unselected_background
        )
        button.backgroundTintList = null
        button.setTextColor(color)
        TextViewCompat.setCompoundDrawableTintList(button, ColorStateList.valueOf(color))
    }

    private fun renderThresholds() {
        thresholdsList.removeAllViews()
        addThresholdRow(
            "Registro de presión",
            String.format(
                Locale.getDefault(),
                "Comienza en ≥ %.2f g y termina en ≤ %.2f g durante %d ms",
                PRESS_START_GRAMS,
                PRESS_RELEASE_GRAMS,
                PRESS_RELEASE_TIME_MS
            ),
            Color.rgb(32, 201, 151)
        )
        addThresholdRow(
            "Azul",
            String.format(Locale.getDefault(), "0.00 g a < %.2f g", YELLOW_THRESHOLD_GRAMS),
            colorForZone('B')
        )
        addThresholdRow(
            "Amarillo",
            String.format(
                Locale.getDefault(),
                "≥ %.2f g a < %.2f g",
                YELLOW_THRESHOLD_GRAMS,
                GREEN_THRESHOLD_GRAMS
            ),
            colorForZone('Y')
        )
        addThresholdRow(
            "Verde",
            String.format(
                Locale.getDefault(),
                "≥ %.2f g a ≤ %.2f g",
                GREEN_THRESHOLD_GRAMS,
                RED_THRESHOLD_GRAMS
            ),
            colorForZone('G')
        )
        addThresholdRow(
            "Rojo · alarma",
            String.format(Locale.getDefault(), "> %.2f g", RED_THRESHOLD_GRAMS),
            colorForZone('R')
        )
    }

    private fun addThresholdRow(label: String, detail: String, color: Int) {
        val row = TextView(requireContext()).apply {
            text = "$label  ·  $detail"
            setTextColor(Color.rgb(22, 38, 61))
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(Color.rgb(248, 248, 251))
                setStroke(dp(1f), color)
            }
        }
        thresholdsList.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(5f)
            }
        )
    }

    private fun trackPress(currentGrams: Float) {
        val grams = currentGrams.coerceAtLeast(0f)
        if (!pressActive) {
            if (grams >= PRESS_START_GRAMS) {
                pressActive = true
                pressPeakGrams = grams
                pressReleaseStartedAt = 0L
            }
            return
        }

        pressPeakGrams = max(pressPeakGrams, grams)
        if (grams <= PRESS_RELEASE_GRAMS) {
            val now = SystemClock.elapsedRealtime()
            if (pressReleaseStartedAt == 0L) {
                pressReleaseStartedAt = now
            } else if (now - pressReleaseStartedAt >= PRESS_RELEASE_TIME_MS) {
                addPressRecord(pressPeakGrams)
                resetPressTracking()
            }
        } else {
            pressReleaseStartedAt = 0L
        }
    }

    private fun resetPressTracking() {
        pressActive = false
        pressPeakGrams = 0f
        pressReleaseStartedAt = 0L
    }

    private fun addPressRecord(peakGrams: Float) {
        val record = PressRecord(
            id = nextHistoryId++,
            timestamp = System.currentTimeMillis(),
            grams = peakGrams,
            zone = zoneFromGrams(peakGrams)
        )
        pressHistory.add(0, record)
        while (pressHistory.size > MAX_HISTORY_RECORDS) {
            pressHistory.removeAt(pressHistory.lastIndex)
        }
        savePressHistory()
        renderPressHistory()
        showHistoryRecord(record)
    }

    private fun renderPressHistory() {
        if (!::historyList.isInitialized) return
        historyList.removeAllViews()
        clearHistoryButton.isEnabled = pressHistory.isNotEmpty()

        if (pressHistory.isEmpty()) {
            historySelectedText.text = "Sin registros. Presiona y suelta el dispositivo."
            val emptyView = TextView(requireContext()).apply {
                text = "Aún no hay presiones registradas"
                setTextColor(Color.rgb(116, 123, 148))
                textSize = 12f
                gravity = Gravity.CENTER
                minHeight = dp(140f)
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    0,
                    R.drawable.ic_clipboard_green,
                    0,
                    0
                )
                compoundDrawablePadding = dp(12f)
                TextViewCompat.setCompoundDrawableTintList(
                    this,
                    ColorStateList.valueOf(Color.rgb(116, 137, 125))
                )
                setPadding(0, dp(18f), 0, dp(18f))
            }
            historyList.addView(
                emptyView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return
        }

        historySelectedText.text = "Toca un registro para ver todos sus valores."
        pressHistory.forEach { record ->
            val row = createHistoryRow(record)
            historyList.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(5f)
                }
            )
        }
    }

    private fun createHistoryRow(record: PressRecord): View {
        if (!isWideLandscape()) {
            val zoneColor = colorForZone(record.zone)
            return TextView(requireContext()).apply {
                text = String.format(
                    Locale.getDefault(),
                    "#%d   %.2f g   ·   %s   ·   %s",
                    record.id,
                    record.grams,
                    nameForZone(record.zone),
                    historyTimeFormat.format(Date(record.timestamp))
                )
                setTextColor(Color.rgb(22, 38, 61))
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                background = historyRowBackground(zoneColor)
                configureHistoryClick(record)
            }
        }

        val zoneColor = colorForZone(record.zone)
        val zoneLabel = SpannableString("●  ${nameForZone(record.zone)}").apply {
            setSpan(
                ForegroundColorSpan(zoneColor),
                0,
                1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58f)
            setPadding(dp(14f), 0, dp(10f), 0)
            background = historyRowBackground(Color.rgb(224, 224, 232))
            configureHistoryClick(record)

            addView(
                historyCell("#${record.id}", Color.rgb(75, 18, 181), bold = true),
                LinearLayout.LayoutParams(dp(62f), ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(
                historyCell(
                    String.format(Locale.getDefault(), "%.2f g", record.grams),
                    Color.rgb(22, 38, 61)
                ),
                LinearLayout.LayoutParams(dp(92f), ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(
                historyCell(zoneLabel, Color.rgb(78, 84, 112)),
                LinearLayout.LayoutParams(dp(110f), ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(
                historyCell(
                    historyTimeFormat.format(Date(record.timestamp)),
                    Color.rgb(103, 111, 143)
                ).apply {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(
                        R.drawable.ic_calendar_gray,
                        0,
                        0,
                        0
                    )
                    compoundDrawablePadding = dp(8f)
                },
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            addView(
                historyCell("›", Color.rgb(103, 111, 143)).apply {
                    gravity = Gravity.CENTER
                    textSize = 24f
                },
                LinearLayout.LayoutParams(dp(26f), ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    private fun historyCell(
        value: CharSequence,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(requireContext()).apply {
        text = value
        setTextColor(color)
        textSize = 12f
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun historyRowBackground(strokeColor: Int) = GradientDrawable().apply {
        cornerRadius = dp(12f).toFloat()
        setColor(Color.rgb(250, 250, 252))
        setStroke(dp(1f), strokeColor)
    }

    private fun View.configureHistoryClick(record: PressRecord) {
        isClickable = true
        isFocusable = true
        contentDescription =
            "Registro ${record.id}, ${record.grams} gramos, zona ${nameForZone(record.zone)}"
        setOnClickListener { showHistoryRecord(record) }
    }

    private fun isWideLandscape(): Boolean {
        val configuration = resources.configuration
        return configuration.smallestScreenWidthDp >= 600 &&
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun showHistoryRecord(record: PressRecord) {
        historySelectedText.text = String.format(
            Locale.getDefault(),
            "Registro #%d · Pico: %.2f g · Zona: %s · %s",
            record.id,
            record.grams,
            nameForZone(record.zone),
            historyTimeFormat.format(Date(record.timestamp))
        )
        historySelectedText.setTextColor(colorForZone(record.zone))
    }

    private fun clearPressHistory() {
        pressHistory.clear()
        nextHistoryId = 1L
        historyPreferences().edit().remove(HISTORY_KEY).apply()
        historySelectedText.setTextColor(Color.rgb(116, 123, 152))
        renderPressHistory()
    }

    private fun loadPressHistory() {
        pressHistory.clear()
        val serialized = historyPreferences().getString(HISTORY_KEY, null) ?: return
        runCatching {
            val array = JSONArray(serialized)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val zone = item.optString("zone", "B").firstOrNull() ?: 'B'
                pressHistory.add(
                    PressRecord(
                        id = item.getLong("id"),
                        timestamp = item.getLong("timestamp"),
                        grams = item.getDouble("grams").toFloat(),
                        zone = zone
                    )
                )
            }
            nextHistoryId = (pressHistory.maxOfOrNull { it.id } ?: 0L) + 1L
        }.onFailure {
            pressHistory.clear()
            nextHistoryId = 1L
            historyPreferences().edit().remove(HISTORY_KEY).apply()
        }
    }

    private fun savePressHistory() {
        val array = JSONArray()
        pressHistory.forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("timestamp", record.timestamp)
                    .put("grams", record.grams.toDouble())
                    .put("zone", record.zone.toString())
            )
        }
        historyPreferences().edit().putString(HISTORY_KEY, array.toString()).apply()
    }

    private fun historyPreferences() =
        requireContext().getSharedPreferences(HISTORY_PREFERENCES, Context.MODE_PRIVATE)

    private fun zoneFromGrams(grams: Float): Char = when {
        grams > RED_THRESHOLD_GRAMS -> 'R'
        grams >= GREEN_THRESHOLD_GRAMS -> 'G'
        grams >= YELLOW_THRESHOLD_GRAMS -> 'Y'
        else -> 'B'
    }

    private fun nameForZone(zone: Char): String = when (zone) {
        'Y' -> "Amarillo"
        'G' -> "Verde"
        'R' -> "Rojo"
        else -> "Azul"
    }

    private fun colorForZone(zone: Char): Int = when (zone) {
        'Y' -> Color.rgb(250, 204, 21)
        'G' -> Color.rgb(34, 197, 94)
        'R' -> Color.rgb(239, 68, 68)
        else -> Color.rgb(37, 99, 235)
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun hasRecentTelemetry(): Boolean {
        if (!isBleConnected || latestScaleState == null || latestScaleStateAt == 0L) return false
        val maximumAge = if (tareUiActive) {
            TARE_TELEMETRY_STALE_MS
        } else {
            TELEMETRY_STALE_MS
        }
        return SystemClock.elapsedRealtime() - latestScaleStateAt <= maximumAge
    }

    private fun isLoadCellUsable(
        status: LoadCellStatus? = latestScaleState?.loadCellStatus
    ): Boolean =
        hasRecentTelemetry() &&
            (status == LoadCellStatus.READY || status == LoadCellStatus.UNKNOWN)

    private fun showTelemetryUnavailable() {
        if (!::currentWeight.isInitialized) return

        currentWeight.text = "--"
        lastWeight.text = "Último: --"
        forceGauge.setValue(0f)
        zoneText.text = "SIN DATOS"
        zoneText.setTextColor(Color.rgb(176, 99, 0))
        zoneText.background = GradientDrawable().apply {
            cornerRadius = resources.displayMetrics.density * 10f
            setColor(Color.rgb(255, 247, 226))
        }
        currentWeight.setTextColor(Color.rgb(176, 99, 0))
        resetPressTracking()
        tareButton.isEnabled = false
    }

    private fun showLoadCellUnavailable(state: ScaleState) {
        currentWeight.text = "--"
        lastWeight.text = "Último: --"
        forceGauge.setValue(0f)

        if (state.humidityDetected) {
            updateZone('B', alarm = true, humidityDetected = true)
            return
        }

        val initializing = state.loadCellStatus == LoadCellStatus.INITIALIZING
        val invalid = state.loadCellStatus == LoadCellStatus.INVALID
        val textColor = if (initializing) Color.rgb(176, 99, 0) else Color.rgb(190, 43, 43)
        zoneText.text = when {
            initializing -> "INICIANDO"
            invalid -> "ERROR DATOS"
            else -> "SIN CELDA"
        }
        zoneText.setTextColor(textColor)
        zoneText.background = GradientDrawable().apply {
            cornerRadius = resources.displayMetrics.density * 10f
            setColor(
                if (initializing) Color.rgb(255, 247, 226)
                else Color.rgb(255, 237, 237)
            )
        }
        currentWeight.setTextColor(textColor)
    }

    private fun updateSensorInfoIcon() {
        if (!::sensorInfoButton.isInitialized) return

        val loadCellStatus = latestScaleState?.loadCellStatus
        val telemetryRecent = hasRecentTelemetry()
        val hasHardwareAlert =
            !isBleConnected ||
                (telemetryRecent &&
                    (loadCellStatus == LoadCellStatus.UNAVAILABLE ||
                        loadCellStatus == LoadCellStatus.INVALID ||
                        latestScaleState?.humidityDetected == true)) ||
                lastCameraError != null
        val color = when {
            hasHardwareAlert -> Color.rgb(190, 43, 43)
            isBleConnected && !telemetryRecent -> Color.rgb(196, 112, 0)
            loadCellStatus == LoadCellStatus.INITIALIZING -> Color.rgb(196, 112, 0)
            telemetryRecent && isLoadCellUsable(loadCellStatus) -> Color.rgb(35, 134, 78)
            else -> Color.rgb(66, 16, 177)
        }
        sensorInfoButton.imageTintList = ColorStateList.valueOf(color)
        sensorInfoButton.contentDescription = when {
            !isBleConnected -> "Ver estado del equipo; Bluetooth desconectado"
            hasHardwareAlert -> "Ver estado del equipo; hay una alerta"
            isBleConnected && !telemetryRecent ->
                "Ver estado de sensores; no llegan datos actuales"
            loadCellStatus == LoadCellStatus.INITIALIZING ->
                "Ver estado de sensores; celda inicializando"
            loadCellStatus == LoadCellStatus.UNKNOWN ->
                "Ver estado de sensores; celda con lectura activa"
            else -> "Ver estado de sensores"
        }
    }

    private fun showSensorStatusDialog() {
        val state = latestScaleState
        val telemetryRecent = hasRecentTelemetry()
        val context = requireContext()
        val normalColor = ContextCompat.getColor(context, R.color.equipment_status_ok)
        val warningColor = ContextCompat.getColor(context, R.color.equipment_status_warning)
        val errorColor = ContextCompat.getColor(context, R.color.equipment_status_error)

        val bluetoothStatus = when {
            telemetryRecent -> EquipmentStatusItem("Bluetooth", "Conectado", normalColor)
            isBleConnected -> EquipmentStatusItem("Bluetooth", "Esperando datos", warningColor)
            else -> EquipmentStatusItem("Bluetooth", "Desconectado", errorColor)
        }
        val loadCellStatus = when {
            !telemetryRecent || state == null ->
                EquipmentStatusItem("Celda de carga", "Sin datos", warningColor)
            state.loadCellStatus == LoadCellStatus.READY ->
                EquipmentStatusItem("Celda de carga", "Lista", normalColor)
            state.loadCellStatus == LoadCellStatus.INITIALIZING ->
                EquipmentStatusItem("Celda de carga", "Inicializando", warningColor)
            state.loadCellStatus == LoadCellStatus.UNAVAILABLE ->
                EquipmentStatusItem("Celda de carga", "No detectada", errorColor)
            state.loadCellStatus == LoadCellStatus.INVALID ->
                EquipmentStatusItem("Celda de carga", "Estado inválido", errorColor)
            else ->
                EquipmentStatusItem("Celda de carga", "Lectura activa", normalColor)
        }
        val humidityStatus = when {
            !telemetryRecent || state == null ->
                EquipmentStatusItem("Humedad", "Sin datos", warningColor)
            state.humidityDetected ->
                EquipmentStatusItem("Humedad", "Humedad detectada", errorColor)
            else -> EquipmentStatusItem("Humedad", "Sin humedad", normalColor)
        }

        val attachedCameras = sortedCameras().size
        val cameraStatus = when {
            lastCameraError != null ->
                EquipmentStatusItem("Cámara USB", "Error detectado", errorColor)
            activeCamera?.isCameraOpened() == true ->
                EquipmentStatusItem("Cámara USB", "Activa", normalColor)
            attachedCameras > 0 ->
                EquipmentStatusItem("Cámara USB", "Detectada · sin vista", warningColor)
            else ->
                EquipmentStatusItem("Cámara USB", "No conectada (opcional)", warningColor)
        }
        val motorStatus = when {
            !telemetryRecent || state == null ->
                EquipmentStatusItem("Motor", "Sin datos", warningColor)
            state.motorCentering ->
                EquipmentStatusItem(
                    "Motor",
                    "Centrando · posición ${state.motorPosition}",
                    normalColor
                )
            state.motorDirection < 0 ->
                EquipmentStatusItem(
                    "Motor",
                    "Moviendo a la izquierda · posición ${state.motorPosition}",
                    normalColor
                )
            state.motorDirection > 0 ->
                EquipmentStatusItem(
                    "Motor",
                    "Moviendo a la derecha · posición ${state.motorPosition}",
                    normalColor
                )
            !state.motorCanMoveLeft ->
                EquipmentStatusItem(
                    "Motor",
                    "Límite izquierdo · posición ${state.motorPosition}",
                    warningColor
                )
            !state.motorCanMoveRight ->
                EquipmentStatusItem(
                    "Motor",
                    "Límite derecho · posición ${state.motorPosition}",
                    warningColor
                )
            else ->
                EquipmentStatusItem(
                    "Motor",
                    "Detenido · posición ${state.motorPosition}",
                    normalColor
                )
        }

        val content = layoutInflater.inflate(R.layout.dialog_equipment_status, null)
        listOf(
            R.id.bluetoothEquipmentStatusRow to bluetoothStatus,
            R.id.loadCellEquipmentStatusRow to loadCellStatus,
            R.id.humidityEquipmentStatusRow to humidityStatus,
            R.id.cameraEquipmentStatusRow to cameraStatus,
            R.id.motorEquipmentStatusRow to motorStatus
        ).forEach { (rowId, status) ->
            bindEquipmentStatusRow(content.findViewById(rowId), status)
        }

        MaterialAlertDialogBuilder(context)
            .setIcon(R.drawable.ic_info_purple)
            .setTitle(R.string.equipment_status_title)
            .setView(content)
            .setPositiveButton(R.string.equipment_status_close, null)
            .show()
    }

    private fun bindEquipmentStatusRow(row: View, status: EquipmentStatusItem) {
        val dot = row.findViewById<View>(R.id.equipmentStatusDot)
        val name = row.findViewById<TextView>(R.id.equipmentStatusName)
        val detail = row.findViewById<TextView>(R.id.equipmentStatusDetail)

        dot.backgroundTintList = ColorStateList.valueOf(status.color)
        name.text = status.name
        detail.text = status.detail
        detail.setTextColor(status.color)
        row.contentDescription = "${status.name}: ${status.detail}"
    }

    override fun onBleStatus(message: String, connected: Boolean) {
        if (!::bleStatus.isInitialized) return
        val tareWasActive = tareUiActive
        isBleConnected = connected
        uiHandler.removeCallbacks(telemetryWatchdog)
        if (!connected) {
            latestScaleState = null
            latestScaleStateAt = 0L
            telemetryMarkedStale = true
            if (tareWasActive) {
                finishTareUi(
                    success = false,
                    message = "Tara interrumpida: se perdió la conexión Bluetooth."
                )
            }
            resetPressTracking()
        } else {
            telemetryMarkedStale = !hasRecentTelemetry()
            uiHandler.postDelayed(telemetryWatchdog, TELEMETRY_WATCHDOG_INTERVAL_MS)
        }
        if (!tareWasActive && !tareUiActive) showNeutralBleStatus(message)
        if (!hasRecentTelemetry()) {
            showTelemetryUnavailable()
        }
        tareButton.isEnabled = connected && !tareUiActive && isLoadCellUsable()
        bluetoothButton.text = if (connected) "Cambiar dispositivo" else "Seleccionar dispositivo"
        connectionBadge.text = if (connected) "● CONECTADO" else "○ SIN CONEXIÓN"
        val badgeColor = if (connected) Color.rgb(38, 166, 91) else Color.rgb(215, 53, 53)
        connectionBadge.setTextColor(badgeColor)
        connectionBadge.background = GradientDrawable().apply {
            cornerRadius = dp(22f).toFloat()
            setColor(
                if (connected) Color.rgb(241, 251, 244)
                else Color.rgb(255, 246, 246)
            )
            setStroke(
                dp(1f),
                if (connected) Color.rgb(149, 222, 176)
                else Color.rgb(244, 167, 167)
            )
        }
        updateSensorInfoIcon()
    }

    override fun onScaleState(state: ScaleState) {
        if (!::currentWeight.isInitialized || !isBleConnected) return
        latestScaleState = state
        latestScaleStateAt = SystemClock.elapsedRealtime()
        telemetryMarkedStale = false
        if (isLoadCellUsable(state.loadCellStatus)) {
            currentWeight.text = String.format(Locale.getDefault(), "%.2f g", state.currentGrams)
            lastWeight.text =
                String.format(Locale.getDefault(), "Último: %.2f g", state.lastGrams)
            forceGauge.setValue(state.currentGrams)
            updateZone(state.zone, state.alarm, state.humidityDetected)
            trackPress(state.currentGrams)
        } else {
            showLoadCellUnavailable(state)
            resetPressTracking()
        }
        updateTareUi(state)
        tareButton.isEnabled = isBleConnected && !tareUiActive && isLoadCellUsable()
        updateSensorInfoIcon()
    }

    override fun onBleDevicesChanged(devices: List<BleDeviceInfo>, scanning: Boolean) {
        val list = bleDeviceList ?: return
        val status = bleScanStatus ?: return

        status.text = when {
            scanning && devices.isEmpty() ->
                "Buscando dispositivos cercanos…"
            scanning ->
                "Buscando… ${devices.size} dispositivo(s) encontrado(s)"
            devices.isEmpty() ->
                "No se encontraron dispositivos. Verifica que estén encendidos."
            else ->
                "Toca el dispositivo específico que deseas conectar."
        }

        list.removeAllViews()
        devices.forEach { device ->
            val signal = when {
                device.rssi >= -60 -> "Excelente"
                device.rssi >= -75 -> "Buena"
                else -> "Débil"
            }
            val option = TextView(requireContext()).apply {
                text =
                    "${device.name}\nID: ${device.address}  ·  Señal: $signal (${device.rssi} dBm)"
                setTextColor(Color.rgb(20, 39, 68))
                textSize = 14f
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(Color.rgb(250, 249, 253))
                    setStroke(dp(1f), Color.rgb(211, 205, 224))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    bleManager?.connectToDevice(device)
                    bleDeviceDialog?.dismiss()
                }
            }
            list.addView(
                option,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8f)
                }
            )
        }
    }

    private fun startTareUi() {
        uiHandler.removeCallbacks(tareTimeout)
        uiHandler.removeCallbacks(resetTareResult)
        tareUiActive = true
        tareFirmwareStarted = false
        legacyTareZeroSamples = 0
        tareStartedAt = SystemClock.elapsedRealtime()

        tareProgress.visibility = View.VISIBLE
        tareButton.text = "Realizando…"
        tareButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        tareButton.isEnabled = false
        showTareStatus(
            "Tara en proceso · no toques el dispositivo",
            Color.rgb(75, 18, 181),
            Color.rgb(247, 243, 255),
            Color.rgb(205, 184, 243)
        )
        uiHandler.postDelayed(tareTimeout, TARE_TIMEOUT_MS)
    }

    private fun updateTareUi(state: ScaleState) {
        if (!isLoadCellUsable(state.loadCellStatus)) {
            if (tareUiActive) {
                finishTareUi(
                    success = false,
                    message = if (state.loadCellStatus == LoadCellStatus.INITIALIZING) {
                        "La celda se está inicializando. Espera antes de realizar la tara."
                    } else if (state.loadCellStatus == LoadCellStatus.INVALID) {
                        "No se puede realizar la tara: estado HX711 inválido."
                    } else {
                        "No se puede realizar la tara: HX711 no detectado."
                    }
                )
            }
            return
        }

        if (state.tareInProgress) {
            if (!tareUiActive) startTareUi()
            tareFirmwareStarted = true
            return
        }
        if (!tareUiActive) return

        if (state.hasTareStatus) {
            if (tareFirmwareStarted) {
                finishTareUi(
                    success = true,
                    message = "✓ Tara completada correctamente"
                )
            }
            return
        }

        // Compatibilidad con una versión anterior del firmware sin estado de tara.
        if (SystemClock.elapsedRealtime() - tareStartedAt < LEGACY_TARE_MIN_MS) return
        val isZero =
            abs(state.currentGrams) <= TARE_ZERO_CONFIRM_G &&
                abs(state.lastGrams) <= TARE_ZERO_CONFIRM_G
        legacyTareZeroSamples = if (isZero) legacyTareZeroSamples + 1 else 0
        if (legacyTareZeroSamples >= LEGACY_TARE_ZERO_SAMPLES) {
            finishTareUi(
                success = true,
                message = "✓ Tara completada correctamente"
            )
        }
    }

    private fun finishTareUi(success: Boolean, message: String) {
        if (!tareUiActive) return
        tareUiActive = false
        tareFirmwareStarted = false
        uiHandler.removeCallbacks(tareTimeout)

        tareProgress.visibility = View.GONE
        tareButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.ic_scale_purple,
            0,
            0,
            0
        )
        tareButton.text = if (success) "✓ Tara completada" else "Reintentar tara"
        tareButton.isEnabled = isBleConnected && !success && isLoadCellUsable()

        if (success) {
            showTareStatus(
                message,
                Color.rgb(35, 134, 78),
                Color.rgb(240, 251, 244),
                Color.rgb(155, 222, 178)
            )
            uiHandler.postDelayed(resetTareResult, TARE_RESULT_DISPLAY_MS)
        } else {
            showTareStatus(
                message,
                Color.rgb(190, 43, 43),
                Color.rgb(255, 244, 244),
                Color.rgb(242, 164, 164)
            )
        }
    }

    private fun resetTareUi() {
        if (!::tareButton.isInitialized || tareUiActive) return
        tareProgress.visibility = View.GONE
        tareButton.text = "Realizar tara"
        tareButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.ic_scale_purple,
            0,
            0,
            0
        )
        tareButton.isEnabled = isBleConnected && isLoadCellUsable()
        if (isBleConnected) {
            val message = when {
                isLoadCellUsable() -> "Dispositivo conectado · listo"
                !hasRecentTelemetry() -> "Dispositivo conectado · sin telemetría actual"
                latestScaleState?.loadCellStatus == LoadCellStatus.INITIALIZING ->
                    "Dispositivo conectado · HX711 inicializando"
                latestScaleState?.loadCellStatus == LoadCellStatus.UNAVAILABLE ->
                    "Dispositivo conectado · HX711 no disponible"
                latestScaleState?.loadCellStatus == LoadCellStatus.INVALID ->
                    "Dispositivo conectado · estado HX711 inválido"
                else -> "Dispositivo conectado · revisa el diagnóstico"
            }
            showNeutralBleStatus(message)
        }
    }

    private fun showNeutralBleStatus(message: String) {
        bleStatus.text = message
        bleStatus.setTextColor(Color.rgb(104, 112, 141))
        bleStatus.background = null
        bleStatus.setPadding(0, 0, 0, 0)
    }

    private fun showTareStatus(
        message: String,
        textColor: Int,
        backgroundColor: Int,
        strokeColor: Int
    ) {
        bleStatus.text = message
        bleStatus.setTextColor(textColor)
        bleStatus.setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
        bleStatus.background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(backgroundColor)
            setStroke(dp(1f), strokeColor)
        }
    }

    private fun connectBluetooth() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startBluetoothDeviceSelection()
        } else {
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startBluetoothDeviceSelection() {
        showBluetoothDeviceDialog()
        bleManager?.startScan()
    }

    private fun showBluetoothDeviceDialog() {
        bleDeviceDialog?.dismiss()

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(4f), dp(20f), 0)
        }
        val status = TextView(requireContext()).apply {
            text = "Buscando dispositivos cercanos…"
            setTextColor(Color.rgb(104, 112, 141))
            textSize = 13f
            setPadding(0, dp(4f), 0, dp(12f))
        }
        val list = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = true
            addView(
                list,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        content.addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        content.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(280f)
            )
        )

        val dialog = MaterialAlertDialogBuilder(requireContext())
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
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
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

    private fun updateZone(zone: Char, alarm: Boolean, humidityDetected: Boolean) {
        val (name, color) = when (zone) {
            'Y' -> "AMARILLO" to Color.rgb(250, 204, 21)
            'G' -> "VERDE" to Color.rgb(34, 197, 94)
            'R' -> "ROJO" to Color.rgb(239, 68, 68)
            else -> "AZUL" to Color.rgb(37, 99, 235)
        }
        zoneText.text = when {
            humidityDetected -> "HUMEDAD"
            alarm -> "ALARMA"
            else -> name
        }
        val displayedColor = when {
            humidityDetected -> Color.rgb(194, 104, 0)
            alarm -> Color.rgb(210, 48, 48)
            else -> color
        }
        val backgroundColor = when {
            humidityDetected -> Color.rgb(255, 246, 225)
            alarm || zone == 'R' -> Color.rgb(255, 237, 237)
            zone == 'Y' -> Color.rgb(255, 249, 220)
            zone == 'G' -> Color.rgb(234, 248, 238)
            else -> Color.rgb(235, 240, 255)
        }
        zoneText.setTextColor(displayedColor)
        zoneText.background = GradientDrawable().apply {
            cornerRadius = resources.displayMetrics.density * 10f
            setColor(backgroundColor)
        }
        currentWeight.setTextColor(Color.rgb(66, 16, 177))
    }

    private data class PressRecord(
        val id: Long,
        val timestamp: Long,
        val grams: Float,
        val zone: Char
    )

    private data class EquipmentStatusItem(
        val name: String,
        val detail: String,
        val color: Int
    )

    companion object {
        const val TAG = "usb-camera-viewer"
        private const val CAMERA_SWITCH_DELAY_MS = 700L
        private const val TELEMETRY_STALE_MS = 2_500L
        private const val TARE_TELEMETRY_STALE_MS = 10_000L
        private const val TELEMETRY_WATCHDOG_INTERVAL_MS = 500L
        private const val TARE_TIMEOUT_MS = 15_000L
        private const val TARE_RESULT_DISPLAY_MS = 3_000L
        private const val LEGACY_TARE_MIN_MS = 1_200L
        private const val TARE_ZERO_CONFIRM_G = 0.20f
        private const val LEGACY_TARE_ZERO_SAMPLES = 2
        private const val PRESS_START_GRAMS = 1f
        private const val PRESS_RELEASE_GRAMS = 0.5f
        private const val PRESS_RELEASE_TIME_MS = 250L
        private const val YELLOW_THRESHOLD_GRAMS = 40f
        private const val GREEN_THRESHOLD_GRAMS = 80f
        private const val RED_THRESHOLD_GRAMS = 95f
        private const val MAX_HISTORY_RECORDS = 50
        private const val HISTORY_PREFERENCES = "press_history"
        private const val HISTORY_KEY = "records"
    }
}
