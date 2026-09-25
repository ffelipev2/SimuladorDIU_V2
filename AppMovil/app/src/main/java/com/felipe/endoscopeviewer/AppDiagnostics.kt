package com.felipe.endoscopeviewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro persistente y exportable de diagnóstico.
 *
 * Se mantiene dentro del almacenamiento privado de la aplicación y sólo se
 * comparte cuando la persona usuaria lo solicita desde Estado del equipo.
 */
object AppDiagnostics {
    private const val LOG_FILE_NAME = "simgyo-diagnostics.log"
    private const val MAX_LOG_SIZE_BYTES = 256 * 1024L
    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private lateinit var appContext: Context
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            appContext = context.applicationContext
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                record(
                    "FATAL ${thread.name}: ${throwable.javaClass.simpleName}: " +
                        (throwable.message ?: "sin detalle"),
                    throwable
                )
                previousHandler?.uncaughtException(thread, throwable)
            }
            initialized = true
            record("Aplicacion iniciada")
        }
    }

    fun record(message: String, throwable: Throwable? = null) {
        if (!initialized) return
        synchronized(lock) {
            runCatching {
                val file = logFile()
                if (file.length() > MAX_LOG_SIZE_BYTES) {
                    val retained = file.readText(Charsets.UTF_8).takeLast(MAX_LOG_SIZE_BYTES.toInt() / 2)
                    file.writeText(retained, Charsets.UTF_8)
                }
                file.appendText(
                    buildString {
                        append(timestampFormat.format(Date()))
                        append(" | ")
                        append(message)
                        append('\n')
                        if (throwable != null) {
                            append(throwable.stackTraceText())
                            append('\n')
                        }
                    },
                    Charsets.UTF_8
                )
            }
        }
    }

    fun createExportIntent(): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "text/plain"
        putExtra(Intent.EXTRA_TITLE, "simgyo-registros-${fileTimestamp()}.txt")
    }

    fun exportTo(context: Context, uri: Uri): Boolean = runCatching {
        val report = buildReport(context.applicationContext)
        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8).use { writer ->
            requireNotNull(writer) { "No se pudo abrir el archivo seleccionado" }
            writer.write(report)
        }
        record("Registros de diagnostico exportados")
    }.isSuccess

    private fun buildReport(context: Context): String = buildString {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toLong()
        } ?: 0L
        appendLine("SimGyO DIU - registros de diagnostico")
        appendLine("Generado: ${timestampFormat.format(Date())}")
        appendLine("Version: ${packageInfo?.versionName ?: "desconocida"} ($versionCode)")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine()
        appendLine("--- Eventos registrados ---")
        append(logFile().takeIf(File::exists)?.readText(Charsets.UTF_8) ?: "Sin eventos registrados.")
    }

    private fun logFile(): File = File(appContext.filesDir, LOG_FILE_NAME)

    private fun fileTimestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun Throwable.stackTraceText(): String = StringWriter().also { writer ->
        printStackTrace(PrintWriter(writer))
    }.toString()
}
