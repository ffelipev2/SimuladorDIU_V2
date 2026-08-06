package com.felipe.endoscopeviewer

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MaterialChecklistActivity : AppCompatActivity() {
    private lateinit var materialChecks: List<CheckBox>
    private lateinit var progressText: TextView
    private lateinit var nextButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_material_checklist)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.checklistRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        materialChecks = listOf(
            findViewById(R.id.material1Check),
            findViewById(R.id.material2Check),
            findViewById(R.id.material3Check),
            findViewById(R.id.material4Check),
            findViewById(R.id.material5Check),
            findViewById(R.id.material6Check)
        )
        progressText = findViewById(R.id.materialProgressText)
        nextButton = findViewById(R.id.materialNextButton)

        materialChecks.forEach { checkBox ->
            checkBox.setOnCheckedChangeListener { _, _ -> updateChecklistState() }
        }
        nextButton.setOnClickListener {
            if (!materialChecks.all { it.isChecked }) return@setOnClickListener
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        updateChecklistState()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        updateChecklistState()
    }

    private fun updateChecklistState() {
        val checkedCount = materialChecks.count { it.isChecked }
        progressText.text = getString(
            R.string.material_progress,
            checkedCount,
            materialChecks.size
        )
        nextButton.isEnabled = checkedCount == materialChecks.size
    }
}
