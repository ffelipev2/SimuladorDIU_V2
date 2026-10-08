package com.felipe.endoscopeviewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Informe A4 que se genera en la caché privada, sin permisos de almacenamiento. */
object ProcedureResultsPdf {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f
    private const val RIGHT = PAGE_WIDTH - MARGIN
    private val ink = Color.rgb(23, 33, 58)
    private val muted = Color.rgb(94, 100, 128)
    private val purple = Color.rgb(66, 16, 177)
    private val green = Color.rgb(35, 122, 67)

    fun create(context: Context, result: ProcedureResult, studentName: String, completedAt: Long, reportId: String): File {
        val directory = File(context.cacheDir, "results")
        check(directory.isDirectory || directory.mkdirs()) { "No se pudo crear la carpeta del informe" }
        // Conserva los informes recientes para que otras apps terminen de leerlos.
        val expiredBefore = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { it.isFile && it.name.startsWith("SimGyO_resultados_") && it.lastModified() < expiredBefore }
            ?.forEach { it.delete() }
        val filenameDate = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(completedAt))
        val file = File.createTempFile("SimGyO_resultados_${filenameDate}_", ".pdf", directory)
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create())
            try {
                drawReport(page.canvas, result, studentName, completedAt, reportId)
            } finally {
                document.finishPage(page)
            }
            file.outputStream().use { document.writeTo(it) }
            return file
        } catch (error: Exception) {
            file.delete()
            throw error
        } finally {
            document.close()
        }
    }

    private fun drawReport(canvas: Canvas, result: ProcedureResult, studentName: String, completedAt: Long, reportId: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(value: String, x: Float, baseline: Float, size: Float, color: Int = ink, bold: Boolean = false) {
            paint.color = color
            paint.textSize = size
            paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            canvas.drawText(value, x, baseline, paint)
        }
        fun panel(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
            paint.color = color
            canvas.drawRoundRect(RectF(left, top, right, bottom), 8f, 8f, paint)
        }

        canvas.drawColor(Color.WHITE)
        panel(MARGIN, 35f, MARGIN + 4f, 77f, green)
        text("SimGyO-DIU", MARGIN + 16f, 55f, 20f, purple, bold = true)
        text("Entrenamiento en inserción de DIU", MARGIN + 16f, 74f, 11f, muted)
        text("Resultados del entrenamiento", MARGIN, 111f, 22f, bold = true)
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "CL")).format(Date(completedAt))
        text("Fecha: $date", MARGIN, 135f, 11f, muted)
        text("ID del informe: $reportId", MARGIN, 147f, 8f, muted)

        text("NOMBRE Y APELLIDO", MARGIN, 160f, 9f, muted, bold = true)
        paint.textSize = 13f
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val nameLines = wrap(studentName, paint, RIGHT - MARGIN)
        nameLines.forEachIndexed { index, line -> text(line, MARGIN, 179f + index * 17f, 13f) }
        val cardTop = 195f + (nameLines.size - 1) * 17f
        panel(MARGIN, cardTop, RIGHT, cardTop + 78f, Color.rgb(245, 242, 252))
        val columnWidth = (RIGHT - MARGIN) / 3f
        listOf(
            "Profundidad real" to result.selectedDepth,
            "Medida registrada" to result.measuredValue,
            "Diferencia absoluta" to result.difference
        ).forEachIndexed { index, (label, value) ->
            val x = MARGIN + 14f + index * columnWidth
            text(label, x, cardTop + 24f, 10f, muted)
            text(value?.let { "$it cm" } ?: "Sin registrar", x, cardTop + 53f, if (value == null) 13f else 22f, purple, bold = true)
        }

        text("ESTADO DE LAS ACCIONES", MARGIN, cardTop + 108f, 10f, muted, bold = true)
        result.statuses.forEachIndexed { index, status ->
            val top = cardTop + 122f + index * 43f
            panel(MARGIN, top, RIGHT, top + 38f, Color.rgb(250, 249, 253))
            val statusColor = if (status.completed) green else Color.rgb(180, 35, 47)
            panel(MARGIN, top + 8f, MARGIN + 3f, top + 30f, statusColor)
            text(status.label, MARGIN + 13f, top + 15f, 11f, bold = true)
            text(status.detail, MARGIN + 13f, top + 30f, 10f, muted)
            val stateText = if (status.completed) "Registrado" else "Pendiente"
            paint.textSize = 10f
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            text(stateText, RIGHT - 13f - paint.measureText(stateText), top + 23f, 10f, statusColor)
        }
        text("Confirmaciones registradas durante la práctica.", MARGIN, 770f, 10f, muted)
        paint.color = Color.rgb(224, 226, 233)
        canvas.drawLine(MARGIN, 789f, RIGHT, 789f, paint)
        text("SimGyO-DIU | Resultados del estudiante", MARGIN, 809f, 9f, muted)
        text("1 / 1", RIGHT - 24f, 809f, 9f, muted)
    }

    private fun wrap(value: String, paint: Paint, width: Float): List<String> {
        val lines = mutableListOf<String>()
        var remaining = value.trim()
        while (remaining.isNotEmpty()) {
            val count = paint.breakText(remaining, true, width, null).coerceAtLeast(1)
            val end = if (count < remaining.length) remaining.lastIndexOf(' ', count).takeIf { it > 0 } ?: count else count
            lines += remaining.substring(0, end).trim()
            remaining = remaining.substring(end).trimStart()
        }
        return lines.ifEmpty { listOf("Sin nombre") }
    }
}
