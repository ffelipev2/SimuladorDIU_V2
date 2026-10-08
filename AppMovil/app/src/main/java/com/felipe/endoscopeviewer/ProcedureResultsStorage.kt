package com.felipe.endoscopeviewer

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** Copia permanente en Descargas; compartir usa la URI del archivo guardado. */
object ProcedureResultsStorage {
    private const val RESULTS_FOLDER = "SimGyO-DIU"

    data class SavedReport(val uri: Uri, val displayName: String)

    fun saveToDownloads(context: Context, source: File, studentName: String, completedAt: Long, reportId: String): SavedReport {
        val filename = ProcedureReportFilename.create(studentName, completedAt, reportId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, source, filename)
        } else {
            saveLegacy(context, source, filename)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(context: Context, source: File, filename: String): SavedReport {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$RESULTS_FOLDER/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("No se pudo crear el PDF en Descargas")
        try {
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("No se pudo abrir el PDF")
            output.use { destination -> source.inputStream().use { it.copyTo(destination) } }
            val published = resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            check(published > 0) { "No se pudo publicar el PDF en Descargas" }
            // Android puede ajustar el nombre si ya existe un informe con la misma fecha.
            val actualName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: filename
            return SavedReport(uri, actualName)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, source: File, filename: String): SavedReport {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), RESULTS_FOLDER)
        check(directory.isDirectory || directory.mkdirs()) { "No se pudo crear la carpeta en Descargas" }
        val destination = File(directory, filename)
        check(destination.createNewFile()) { "Ya existe un informe con ese identificador" }
        try {
            source.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.results", destination)
            MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), arrayOf("application/pdf"), null)
            return SavedReport(uri, destination.name)
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }
}
