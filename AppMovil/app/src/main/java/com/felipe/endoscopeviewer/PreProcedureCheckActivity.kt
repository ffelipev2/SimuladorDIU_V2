package com.felipe.endoscopeviewer

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class PreProcedureCheckActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pre_procedure_check)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.preProcedureRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val nextButton = findViewById<Button>(R.id.preProcedureNextButton)
        nextButton.setOnClickListener {
            AppDiagnostics.record("Evaluacion previa completada")
            startActivity(Intent(this, SpeculumPreparationActivity::class.java))
        }
        findViewById<Button>(R.id.previousButton).also { previousButton ->
            placeBackButtonBelowNext(previousButton, nextButton)
            previousButton.setOnClickListener { finish() }
        }
    }
}
