package com.felipe.endoscopeviewer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.textfield.TextInputLayout

class ProcedureSummaryActivity : AppCompatActivity() {
    private var completedAt = 0L
    private var closingSimulation = false
    private lateinit var result: ProcedureResult
    private lateinit var deliveryModel: ProcedureResultsViewModel

    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveAndFinish()
        else Toast.makeText(this, R.string.summary_storage_permission_required, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        completedAt = savedInstanceState?.getLong("completed_at") ?: System.currentTimeMillis()
        setContentView(R.layout.activity_procedure_summary)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.procedureSummaryRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        result = ProcedureResult.capture(this)
        deliveryModel = ViewModelProvider(this)[ProcedureResultsViewModel::class.java]
        val savedUri = savedInstanceState?.getString("saved_report_uri")
        val savedFilename = savedInstanceState?.getString("saved_report_filename")
        val savedName = savedInstanceState?.getString("saved_report_student_name")
        if (savedUri != null && savedFilename != null && savedName != null) {
            deliveryModel.restore(ProcedureResultsStorage.SavedReport(Uri.parse(savedUri), savedFilename), savedName)
        }
        if (savedInstanceState?.getBoolean("simulation_finished") == true ||
            deliveryModel.state.value == ProcedureResultsViewModel.DeliveryState.Completed
        ) {
            finishSimulation()
            return
        }
        findViewById<TextView>(R.id.summaryDifferenceText).text =
            "Diferencia registrada: ${result.difference?.let { "$it cm" } ?: "Sin registrar"}\n" +
                "Profundidad real: ${result.selectedDepth?.let { "$it cm" } ?: "Sin seleccionar"}"
        findViewById<TextView>(R.id.summaryMeasuredText).text =
            "Número medido: ${result.measuredValue?.let { "$it cm" } ?: "Sin registrar"}"

        val states = findViewById<LinearLayout>(R.id.summaryStatesList)
        result.statuses.forEach { status ->
            addStatus(states, status.label, status.detail, status.completed)
        }
        val nameInput = findViewById<EditText>(R.id.summaryStudentName)
        val nameLayout = findViewById<TextInputLayout>(R.id.summaryStudentNameLayout)
        nameInput.doAfterTextChanged { name ->
            if (!name.isNullOrBlank()) {
                nameLayout.error = null
                nameLayout.isErrorEnabled = false
            }
            updateDeliveryUi(deliveryModel.state.value)
        }
        findViewById<Button>(R.id.summarySaveButton).setOnClickListener { saveAndFinish() }
        deliveryModel.state.observe(this) { state ->
            updateDeliveryUi(state)
            if (state is ProcedureResultsViewModel.DeliveryState.Saved && !closingSimulation) {
                val available = runCatching {
                    contentResolver.openFileDescriptor(state.report.uri, "r")?.use { true } ?: false
                }.getOrDefault(false)
                if (!available) {
                    deliveryModel.forgetMissingReport()
                    Toast.makeText(this, R.string.summary_saved_pdf_missing, Toast.LENGTH_LONG).show()
                    return@observe
                }
                val saved = deliveryModel.consumeSavedReport() ?: return@observe
                finishSimulation(saved)
            }
        }
        findViewById<Button>(R.id.summaryFinishButton).setOnClickListener { finishSimulation() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("completed_at", completedAt)
        outState.putBoolean("simulation_finished", closingSimulation)
        (deliveryModel.state.value as? ProcedureResultsViewModel.DeliveryState.Saved)?.let { saved ->
            outState.putString("saved_report_uri", saved.report.uri.toString())
            outState.putString("saved_report_filename", saved.report.displayName)
            outState.putString("saved_report_student_name", saved.studentName)
        }
        super.onSaveInstanceState(outState)
    }

    private fun studentName(): String = findViewById<EditText>(R.id.summaryStudentName)
        .text.toString().trim().replace(Regex("\\s+"), " ")

    private fun saveAndFinish() {
        val nameInput = findViewById<EditText>(R.id.summaryStudentName)
        val nameLayout = findViewById<TextInputLayout>(R.id.summaryStudentNameLayout)
        val studentName = studentName()
        if (studentName.isBlank()) {
            nameLayout.error = getString(R.string.summary_student_name_required)
            nameInput.requestFocus()
            return
        }
        nameLayout.error = null
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        deliveryModel.save(result, studentName, completedAt)
    }

    private fun updateDeliveryUi(state: ProcedureResultsViewModel.DeliveryState?) {
        val saving = state == ProcedureResultsViewModel.DeliveryState.Saving
        val busy = saving || state == ProcedureResultsViewModel.DeliveryState.Completed || closingSimulation
        findViewById<Button>(R.id.summarySaveButton).apply {
            isEnabled = !busy
            setText(if (saving) R.string.summary_saving_pdf else R.string.summary_save_and_finish)
        }
        findViewById<Button>(R.id.summaryFinishButton).isEnabled = !busy
        findViewById<EditText>(R.id.summaryStudentName).isEnabled = !busy
        findViewById<TextView>(R.id.summarySavedReportStatus).apply {
            text = when {
                saving -> getString(R.string.summary_saving_pdf)
                state == ProcedureResultsViewModel.DeliveryState.Failed -> getString(R.string.summary_pdf_error)
                else -> getString(R.string.summary_save_hint)
            }
            setTextColor(when {
                state == ProcedureResultsViewModel.DeliveryState.Failed -> Color.rgb(180, 35, 47)
                else -> Color.rgb(94, 100, 128)
            })
        }
    }

    private fun finishSimulation(saved: ProcedureResultsViewModel.DeliveryState.Saved? = null) {
        if (closingSimulation) return
        closingSimulation = true
        findViewById<Button>(R.id.summarySaveButton).isEnabled = false
        findViewById<Button>(R.id.summaryFinishButton).isEnabled = false
        AppDiagnostics.record("Procedimiento finalizado; cerrando Bluetooth y regresando al inicio")
        BleConnectionStore.close()
        ProcedureSummaryStore.clear(this)
        ClinicalCaseStore.clear(this)
        val startIntent = Intent(this, SplashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        if (saved == null) {
            startActivity(startIntent)
            return
        }
        Toast.makeText(this, R.string.summary_pdf_saved, Toast.LENGTH_LONG).show()
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, saved.report.uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.summary_share_subject, saved.studentName))
            putExtra(Intent.EXTRA_TEXT, getString(R.string.summary_share_message, saved.studentName))
            clipData = ClipData.newRawUri("Resultados SimGyO-DIU", saved.report.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            // La opción de compartir queda sobre el inicio, sin volver al resumen al cancelarla.
            startActivities(arrayOf(startIntent, Intent.createChooser(sendIntent, getString(R.string.summary_share_results))))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.summary_no_share_app, Toast.LENGTH_LONG).show()
            startActivity(startIntent)
        }
    }

    private fun addStatus(container: LinearLayout, label: String, detail: String, completed: Boolean) {
        val tabletLandscape = resources.configuration.smallestScreenWidthDp >= 600 &&
            resources.configuration.screenWidthDp >= 800 &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val color = if (completed) Color.rgb(35, 122, 67) else Color.rgb(180, 35, 47)
        val row = TextView(this).apply {
            text = if (tabletLandscape) {
                "${if (completed) "✓" else "•"}  $label\n$detail"
            } else {
                "${if (completed) "✓" else "•"}  $label · $detail"
            }
            setTextColor(Color.rgb(23, 33, 58))
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(if (tabletLandscape) 62 else 48)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.rgb(250, 249, 253))
                setStroke(dp(1), color)
            }
        }
        if (tabletLandscape) {
            val last = container.getChildAt(container.childCount - 1) as? LinearLayout
            val gridRow = if (last != null && last.childCount == 1) last else LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                container.addView(this, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) })
            }
            gridRow.addView(row, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (gridRow.childCount > 0) marginStart = dp(12)
            })
        } else {
            container.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
