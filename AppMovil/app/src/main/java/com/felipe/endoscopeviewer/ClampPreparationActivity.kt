package com.felipe.endoscopeviewer

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ClampPreparationActivity : AppCompatActivity(), BleScaleListener {
    private lateinit var nextButton: Button
    private lateinit var tareStatus: TextView
    private lateinit var tareProgress: ProgressBar
    private var bleManager: BleScaleManager? = null
    private var tareStarted = false
    private var tareObservedInFirmware = false
    private var handoffBleConnection = false
    private val handler = Handler(Looper.getMainLooper())
    private val tareTimeout = Runnable {
        if (tareStarted) showTareFailure("La tara tardó demasiado. Inténtala nuevamente.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clamp_preparation)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.clampPreparationRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        nextButton = findViewById(R.id.clampNextButton)
        tareStatus = findViewById(R.id.clampTareStatus)
        tareProgress = findViewById(R.id.clampTareProgress)
        bleManager = BleConnectionStore.acquire(this, this)
        findViewById<RadioGroup>(R.id.clampSoundRadioGroup)
            .setOnCheckedChangeListener { _, checkedId -> nextButton.isEnabled = checkedId != -1 }
        nextButton.setOnClickListener { beginTare() }
        findViewById<Button>(R.id.previousButton).also { previousButton ->
            placeBackButtonBelowNext(previousButton, nextButton)
            previousButton.setOnClickListener { finish() }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (isFinishing && !handoffBleConnection) BleConnectionStore.close()
        super.onDestroy()
    }

    private fun beginTare() {
        if (tareStarted) return
        val answer = if (findViewById<RadioGroup>(R.id.clampSoundRadioGroup).checkedRadioButtonId ==
            R.id.clampSoundYes) "Sí" else "No"
        ProcedureSummaryStore.saveClampAnswer(this, answer)
        if (bleManager?.sendTare() != true) {
            showTareFailure("No se pudo iniciar la tara. Revisa la conexión Bluetooth.")
            return
        }
        tareStarted = true
        AppDiagnostics.record("Tara iniciada desde preparacion de pinzamiento")
        tareObservedInFirmware = false
        nextButton.isEnabled = false
        nextButton.text = "Realizando tara…"
        tareStatus.visibility = View.VISIBLE
        tareProgress.visibility = View.VISIBLE
        tareStatus.text = "Tara en proceso. No manipules el simulador."
        handler.postDelayed(tareTimeout, TARE_TIMEOUT_MS)
    }

    override fun onBleStatus(message: String, connected: Boolean) {
        if (tareStarted && !connected) {
            showTareFailure("La conexión se perdió durante la tara.")
        }
    }

    override fun onScaleState(state: ScaleState) {
        if (!tareStarted) return
        if (state.tareInProgress) {
            tareObservedInFirmware = true
            tareStatus.text = "Tara en proceso. No manipules el simulador."
            return
        }
        if (state.hasTareStatus && tareObservedInFirmware) completeTare()
    }

    override fun onBleDevicesChanged(devices: List<BleDeviceInfo>, scanning: Boolean) = Unit

    private fun completeTare() {
        if (!tareStarted) return
        tareStarted = false
        handler.removeCallbacks(tareTimeout)
        tareProgress.visibility = View.GONE
        tareStatus.text = "✓ Tara completada correctamente"
        nextButton.text = "Continuando…"
        AppDiagnostics.record("Tara completada; abriendo simulacion")
        handler.postDelayed({
            handoffBleConnection = true
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, TARE_SUCCESS_DELAY_MS)
    }

    private fun showTareFailure(message: String) {
        AppDiagnostics.record("Tara fallida: $message")
        tareStarted = false
        tareObservedInFirmware = false
        handler.removeCallbacks(tareTimeout)
        tareProgress.visibility = View.GONE
        tareStatus.visibility = View.VISIBLE
        tareStatus.text = message
        nextButton.text = "Reintentar tara"
        nextButton.isEnabled = true
    }

    companion object {
        private const val TARE_TIMEOUT_MS = 15_000L
        private const val TARE_SUCCESS_DELAY_MS = 900L
    }
}
