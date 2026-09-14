package com.felipe.endoscopeviewer

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs

class ProcedureSummaryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_procedure_summary)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.procedureSummaryRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val selectedDepth = ClinicalCaseStore.selectedCm(this) ?: 0
        val measuredValue = ProcedureSummaryStore.measuredValue(this) ?: 0
        val difference = abs(measuredValue - selectedDepth)
        findViewById<TextView>(R.id.summaryDifferenceText).text =
            "Diferencia registrada: $difference cm\nProfundidad seleccionada: $selectedDepth cm"
        findViewById<TextView>(R.id.summaryMeasuredText).text = "Número medido: $measuredValue cm"

        val states = findViewById<LinearLayout>(R.id.summaryStatesList)
        addStatus(states, "Caso clínico", "$selectedDepth cm seleccionado", completed = selectedDepth > 0)
        addStatus(states, "Alarma de humedad", ProcedureSummaryStore.humidityAnswer(this), completed = true)
        addStatus(states, "Materiales", "Completados", ProcedureSummaryStore.actionCompleted(this, "Materiales"))
        addStatus(states, "Sonido de la pinza", ProcedureSummaryStore.clampAnswer(this), completed = true)
        listOf("Cargar el DIU", "Fijar medición", "Liberar", "Retiro Exitoso", "Cortar Hilos").forEach { action ->
            addStatus(
                states,
                action,
                if (ProcedureSummaryStore.actionCompleted(this, action)) "Completado" else "Pendiente",
                ProcedureSummaryStore.actionCompleted(this, action)
            )
        }
        findViewById<Button>(R.id.summaryFinishButton).setOnClickListener { finish() }
    }

    private fun addStatus(container: LinearLayout, label: String, detail: String, completed: Boolean) {
        val color = if (completed) Color.rgb(35, 122, 67) else Color.rgb(180, 35, 47)
        val row = TextView(this).apply {
            text = "${if (completed) "✓" else "•"}  $label · $detail"
            setTextColor(Color.rgb(23, 33, 58))
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.rgb(250, 249, 253))
                setStroke(dp(1), color)
            }
        }
        container.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
