package com.felipe.endoscopeviewer

import android.content.Context

/** Datos de las confirmaciones que se muestran al cerrar el procedimiento. */
object ProcedureSummaryStore {
    private const val PREFERENCES = "procedure_summary"
    private const val HUMIDITY_ANSWER = "humidity_answer"
    private const val CLAMP_ANSWER = "clamp_answer"
    private const val MEASURED_VALUE = "measured_value"
    private const val ACTION_PREFIX = "action_"

    fun saveHumidityAnswer(context: Context, answer: String) = edit(context) { putString(HUMIDITY_ANSWER, answer) }
    fun saveClampAnswer(context: Context, answer: String) = edit(context) { putString(CLAMP_ANSWER, answer) }
    fun saveMeasuredValue(context: Context, value: Int) = edit(context) { putInt(MEASURED_VALUE, value) }
    fun saveAction(context: Context, action: String, completed: Boolean) =
        edit(context) { putBoolean(ACTION_PREFIX + action, completed) }

    fun humidityAnswer(context: Context): String = preferences(context).getString(HUMIDITY_ANSWER, "Sin responder") ?: "Sin responder"
    fun clampAnswer(context: Context): String = preferences(context).getString(CLAMP_ANSWER, "Sin responder") ?: "Sin responder"
    fun measuredValue(context: Context): Int? = preferences(context)
        .takeIf { it.contains(MEASURED_VALUE) }?.getInt(MEASURED_VALUE, 0)
    fun actionCompleted(context: Context, action: String): Boolean =
        preferences(context).getBoolean(ACTION_PREFIX + action, false)

    fun clear(context: Context) {
        preferences(context).edit().clear().apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private inline fun edit(context: Context, action: android.content.SharedPreferences.Editor.() -> Unit) {
        preferences(context).edit().apply(action).apply()
    }
}
