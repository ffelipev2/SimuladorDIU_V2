package com.felipe.endoscopeviewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.UUID

/** El guardado continúa al girar la pantalla y conserva el archivo para compartir. */
class ProcedureResultsViewModel(application: Application) : AndroidViewModel(application) {
    sealed class DeliveryState {
        object Idle : DeliveryState()
        object Saving : DeliveryState()
        data class Saved(val report: ProcedureResultsStorage.SavedReport, val studentName: String) : DeliveryState()
        object Failed : DeliveryState()
        object Completed : DeliveryState()
    }

    private val mutableState = MutableLiveData<DeliveryState>(DeliveryState.Idle)
    val state: LiveData<DeliveryState> get() = mutableState

    fun restore(report: ProcedureResultsStorage.SavedReport, studentName: String) {
        if (mutableState.value == DeliveryState.Idle) mutableState.value = DeliveryState.Saved(report, studentName)
    }

    fun forgetMissingReport() {
        mutableState.value = DeliveryState.Idle
    }

    fun consumeSavedReport(): DeliveryState.Saved? {
        val saved = mutableState.value as? DeliveryState.Saved ?: return null
        mutableState.value = DeliveryState.Completed
        return saved
    }

    fun save(result: ProcedureResult, studentName: String, completedAt: Long) {
        if (mutableState.value == DeliveryState.Saving || mutableState.value == DeliveryState.Completed) return
        mutableState.value = DeliveryState.Saving
        val context = getApplication<Application>()
        val reportId = UUID.randomUUID().toString()
        Thread({
            val saved = runCatching {
                val temporary = ProcedureResultsPdf.create(context, result, studentName, completedAt, reportId)
                try {
                    ProcedureResultsStorage.saveToDownloads(context, temporary, studentName, completedAt, reportId)
                } finally {
                    temporary.delete()
                }
            }
            saved.fold(onSuccess = { report ->
                mutableState.postValue(DeliveryState.Saved(report, studentName))
            }, onFailure = { error ->
                AppDiagnostics.record("No se pudo guardar el informe PDF: ${error.javaClass.simpleName}")
                mutableState.postValue(DeliveryState.Failed)
            })
        }, "save-procedure-results").start()
    }
}
