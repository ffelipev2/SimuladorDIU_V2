package com.felipe.endoscopeviewer

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SpeculumPreparationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_speculum_preparation)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.speculumPreparationRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val nextButton = findViewById<Button>(R.id.speculumNextButton)
        findViewById<RadioGroup>(R.id.humidityAlarmRadioGroup)
            .setOnCheckedChangeListener { _, checkedId -> nextButton.isEnabled = checkedId != -1 }
        nextButton.setOnClickListener {
            val answer = if (findViewById<RadioGroup>(R.id.humidityAlarmRadioGroup).checkedRadioButtonId ==
                R.id.humidityAlarmYes) "Sí" else "No"
            ProcedureSummaryStore.saveHumidityAnswer(this, answer)
            AppDiagnostics.record("Preparacion de especulo completada")
            startActivity(Intent(this, MaterialChecklistActivity::class.java))
        }
        findViewById<Button>(R.id.previousButton).also { previousButton ->
            placeBackButtonBelowNext(previousButton, nextButton)
            previousButton.setOnClickListener { finish() }
        }
    }
}
