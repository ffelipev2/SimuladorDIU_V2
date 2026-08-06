package com.felipe.endoscopeviewer

import android.Manifest
import android.content.Context
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
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
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
    private lateinit var bluetoothButton: Button
    private lateinit var motorLeftButton: Button
    private lateinit var motorCenterButton: Button
    private lateinit var motorRightButton: Button
    private lateinit var motorStatus: TextView
    private lateinit var historyList: LinearLayout
    private lateinit var historySelectedText: TextView
    private lateinit var clearHistoryButton: Button
    private lateinit var historyTabButton: Button
    private lateinit var thresholdsTabButton: Button
    private lateinit var historyContent: View
    private lateinit var thresholdsContent: View
    private lateinit var thresholdsList: LinearLayout

    private var bleManager: BleScaleManager? = null
    private val motorControlHandler = Handler(Looper.getMainLooper())
    private var motorHoldDirection = 0
    private var isBleConnected = false
    private val motorKeepAlive = object : Runnable {
        override fun run() {
            val direction = motorHoldDirection
            if (direction == 0) return
            bleManager?.sendMotor(direction)
            motorControlHandler.postDelayed(this, MOTOR_KEEP_ALIVE_MS)
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
            bleManager?.startScan()
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
        bluetoothButton = root.findViewById(R.id.bluetoothButton)
        motorLeftButton = root.findViewById(R.id.motorLeftButton)
        motorCenterButton = root.findViewById(R.id.motorCenterButton)
        motorRightButton = root.findViewById(R.id.motorRightButton)
        motorStatus = root.findViewById(R.id.motorStatusText)
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
            motorLeftButton,
            motorCenterButton,
            motorRightButton,
            clearHistoryButton,
            historyTabButton,
            thresholdsTabButton
        ).forEach { it.backgroundTintList = null }

        bleManager = BleScaleManager(requireContext(), this)
        switchCameraButton.setOnClickListener { selectNextCamera() }
        bluetoothButton.setOnClickListener { connectBluetooth() }
        clearHistoryButton.setOnClickListener { clearPressHistory() }
        historyTabButton.setOnClickListener { showHistoryTab() }
        thresholdsTabButton.setOnClickListener { showThresholdsTab() }
        tareButton.setOnClickListener {
            // Deja de renovar cualquier movimiento antes de pedir la tara.
            // El firmware también detiene el motor al procesar TARE.
            stopMotorControl(sendStop = false)
            bleStatus.text = if (bleManager?.sendTare() == true) {
                "Realizando tara... no toques el dispositivo."
            } else {
                "El dispositivo no está conectado."
            }
        }
        configureMotorHoldButton(motorLeftButton, -1)
        configureMotorHoldButton(motorRightButton, 1)
        motorCenterButton.setOnClickListener { centerMotor() }
        loadPressHistory()
        renderPressHistory()
        renderThresholds()
        showHistoryTab()
        updateZone('B', alarm = false, humidityDetected = false)
        return root
    }

    override fun onResume() {
        super.onResume()
        if (::cameraStatus.isInitialized) updateCameraControls()
    }

    override fun onPause() {
        stopMotorControl()
        super.onPause()
    }

    override fun clear() {
        stopMotorControl()
        bleManager?.close()
        bleManager = null
        activeCamera?.setCameraStateCallBack(null)
        activeCamera = null
        super.clear()
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

    override fun onBleStatus(message: String, connected: Boolean) {
        if (!::bleStatus.isInitialized) return
        isBleConnected = connected
        if (!connected) {
            resetPressTracking()
            stopMotorControl(sendStop = false)
        }
        bleStatus.text = message
        tareButton.isEnabled = connected
        motorLeftButton.isEnabled = connected
        motorCenterButton.isEnabled = connected
        motorRightButton.isEnabled = connected
        if (!connected) motorStatus.text = "Conecta Bluetooth para controlar la extensión"
        bluetoothButton.text = if (connected) "Reconectar dispositivo" else "Conectar dispositivo"
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
    }

    override fun onScaleState(state: ScaleState) {
        if (!::currentWeight.isInitialized) return
        currentWeight.text = String.format("%.2f g", state.currentGrams)
        lastWeight.text = String.format("Último: %.2f g", state.lastGrams)
        forceGauge.setValue(state.currentGrams)
        updateZone(state.zone, state.alarm, state.humidityDetected)
        updateMotorState(state)
        trackPress(state.currentGrams)
    }

    private fun configureMotorHoldButton(button: Button, direction: Int) {
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startMotorControl(direction)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    stopMotorControl()
                    view.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                    stopMotorControl()
                    true
                }

                else -> true
            }
        }
    }

    private fun startMotorControl(direction: Int) {
        if (!isBleConnected) {
            motorStatus.text = "El dispositivo no está conectado"
            return
        }

        motorHoldDirection = direction.coerceIn(-1, 1)
        motorControlHandler.removeCallbacks(motorKeepAlive)
        bleManager?.sendMotor(motorHoldDirection)
        motorControlHandler.postDelayed(motorKeepAlive, MOTOR_KEEP_ALIVE_MS)
        motorStatus.text = if (motorHoldDirection < 0) {
            "Moviendo a la izquierda..."
        } else {
            "Moviendo a la derecha..."
        }
    }

    private fun stopMotorControl(sendStop: Boolean = true) {
        motorControlHandler.removeCallbacks(motorKeepAlive)
        motorHoldDirection = 0
        if (sendStop && isBleConnected) bleManager?.sendMotor(0)
        if (::motorStatus.isInitialized && isBleConnected) motorStatus.text = "Extensión detenida"
    }

    private fun centerMotor() {
        if (!isBleConnected) {
            motorStatus.text = "El dispositivo no está conectado"
            return
        }

        stopMotorControl(sendStop = false)
        motorStatus.text = if (bleManager?.sendCenter() == true) {
            "Centrando eje..."
        } else {
            "No se pudo enviar la orden de centrado"
        }
    }

    private fun updateMotorState(state: ScaleState) {
        if (!::motorStatus.isInitialized) return

        val reachedHeldLimit =
            (motorHoldDirection < 0 && !state.motorCanMoveLeft) ||
                (motorHoldDirection > 0 && !state.motorCanMoveRight)
        if (reachedHeldLimit) stopMotorControl()

        motorLeftButton.isEnabled = isBleConnected && state.motorCanMoveLeft
        motorCenterButton.isEnabled =
            isBleConnected && !state.motorCentering && state.motorPosition != 0L
        motorRightButton.isEnabled = isBleConnected && state.motorCanMoveRight
        motorStatus.text = when {
            state.motorCentering ->
                "Centrando eje · posición ${state.motorPosition}"
            !state.motorCanMoveLeft ->
                "Límite izquierdo · posición ${state.motorPosition}"
            !state.motorCanMoveRight ->
                "Límite derecho · posición ${state.motorPosition}"
            state.motorDirection < 0 ->
                "Moviendo a la izquierda · posición ${state.motorPosition}"
            state.motorDirection > 0 ->
                "Moviendo a la derecha · posición ${state.motorPosition}"
            state.motorPosition == 0L ->
                "Eje centrado · posición 0"
            else -> "Extensión detenida · posición ${state.motorPosition}"
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
            bleManager?.startScan()
        } else {
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
        }
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

    companion object {
        const val TAG = "usb-camera-viewer"
        private const val CAMERA_SWITCH_DELAY_MS = 700L
        private const val MOTOR_KEEP_ALIVE_MS = 250L
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
