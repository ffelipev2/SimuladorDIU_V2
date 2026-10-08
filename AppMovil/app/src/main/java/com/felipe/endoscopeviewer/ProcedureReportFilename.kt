package com.felipe.endoscopeviewer

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Nombre legible del estudiante más fecha e identificador único del informe. */
object ProcedureReportFilename {
    fun create(studentName: String, completedAt: Long, reportId: String): String {
        val normalized = Normalizer.normalize(studentName, Normalizer.Form.NFC)
            .replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')
        val name = StringBuilder()
        var byteCount = 0
        var index = 0
        // Mantiene el archivo por debajo del límite habitual de 255 bytes sin cortar caracteres Unicode.
        while (index < normalized.length) {
            val end = index + Character.charCount(normalized.codePointAt(index))
            val character = normalized.substring(index, end)
            val size = character.toByteArray(Charsets.UTF_8).size
            if (byteCount + size > 140) break
            name.append(character)
            byteCount += size
            index = end
        }
        val safeName = name.toString().trim('_').ifBlank { "estudiante" }
        val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(completedAt))
        return "SimGyO_resultados_${safeName}_${date}_$reportId.pdf"
    }
}
