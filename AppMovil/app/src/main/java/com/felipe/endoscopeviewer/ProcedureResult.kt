package com.felipe.endoscopeviewer

import android.content.Context
import kotlin.math.abs

/** Una misma instantánea para la pantalla final y el informe compartido. */
data class ProcedureResult(
    val selectedDepth: Int?,
    val measuredValue: Int?,
    val statuses: List<Status>
) {
    val difference: Int? get() = selectedDepth?.let { depth -> measuredValue?.let { abs(it - depth) } }

    data class Status(val label: String, val detail: String, val completed: Boolean)

    companion object {
        fun capture(context: Context): ProcedureResult {
            val selectedDepth = ClinicalCaseStore.selectedCm(context)
            val humidityAnswer = ProcedureSummaryStore.humidityAnswer(context)
            val clampAnswer = ProcedureSummaryStore.clampAnswer(context)
            val actions = listOf("Materiales", "Cargar el DIU", "Fijar medición", "Liberar", "Retiro Exitoso", "Cortar Hilos")
                .associateWith { action ->
                    val completed = ProcedureSummaryStore.actionCompleted(context, action)
                    Status(action, if (completed) "Completado" else "Pendiente", completed)
                }
            return ProcedureResult(
                selectedDepth,
                ProcedureSummaryStore.measuredValue(context),
                listOf(
                    Status("Caso clínico", selectedDepth?.let { "$it cm seleccionado" } ?: "Sin seleccionar", selectedDepth != null),
                    Status("Alarma de humedad", humidityAnswer, humidityAnswer != "Sin responder"),
                    actions.getValue("Materiales"),
                    Status("Sonido de la pinza", clampAnswer, clampAnswer != "Sin responder")
                ) + actions.values.filter { it.label != "Materiales" }
            )
        }
    }
}
