package com.felipe.endoscopeviewer

import android.content.Context

/** Persistencia del caso para las pantallas posteriores del entrenamiento. */
object ClinicalCaseStore {
    private const val PREFERENCES_NAME = "clinical_case"
    private const val CASE_CM_KEY = "selected_case_cm"

    fun saveSelectedCm(context: Context, centimeters: Int) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(CASE_CM_KEY, centimeters)
            .apply()
    }

    fun selectedCm(context: Context): Int? {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return preferences.takeIf { it.contains(CASE_CM_KEY) }?.getInt(CASE_CM_KEY, 0)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
