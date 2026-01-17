// app/src/main/java/com/cabgon/blackhawk/ai/LocalReasoner.kt
package com.cabgon.blackhawk.ai

import com.cabgon.blackhawk.data.RAGIndex
import java.util.Locale

object LocalReasoner {

    enum class Mode { CONCISE, PRO }

    data class Answer(
        val textEs: String,
        val citations: List<Citation>
    ) {
        data class Citation(val manual: String, val page: Int)
    }

    suspend fun synthesize(
        translator: Translator,
        englishQuery: String,
        hits: List<RAGIndex.Hit>,
        mode: Mode = Mode.CONCISE
    ): Answer {
        return when (mode) {
            Mode.CONCISE -> synthConcise(hits)
            Mode.PRO     -> synthPro(hits)
        }
    }

    private fun synthConcise(hits: List<RAGIndex.Hit>): Answer {
        if (hits.isEmpty()) {
            return Answer("No encontré respuesta en los manuales.", emptyList())
        }
        val best = hits.first()
        val bestManual = basename(best.manual)
        val cuerpo = buildString {
            appendLine(if (best.snippet.isBlank()) "Referencia relevante encontrada." else cleanSnippet(best.snippet))
            append("Fuente: $bestManual, pág. ${best.page.coerceAtLeast(1)}")
        }
        val citations = hits.take(5)
            .map { Answer.Citation(basename(it.manual), it.page.coerceAtLeast(1)) }
            .distinct()
        return Answer(cuerpo, citations)
    }

    // === PRO: 3 causas sin referencias en línea; referencias solo en la lista final (clicable) ===
    private fun synthPro(hits: List<RAGIndex.Hit>): Answer {
        if (hits.isEmpty()) {
            val fallback = """
                Resumen:
                • No se hallaron coincidencias en los manuales para la consulta dada.

                Siguiente acción sugerida:
                1) Reformula la consulta con un término más específico (componente/sistema).
                2) Si es un número de parte, usa el formato exacto (ej. “M85049/31-17W”) o el NSN con guiones.
            """.trimIndent()
            return Answer(fallback, emptyList())
        }

        val all = hits.joinToString("  ") { it.snippet }.uppercase(Locale.ROOT)
        val hasWarning = all.contains("WARNING")
        val hasCaution = all.contains("CAUTION")
        val torques = Regex("""(\d+(?:\.\d+)?)\s*(N[\u00B7\- ]?M|LB[\- ]?FT|IN[\- ]?LB|LBF[\u00B7\- ]?IN)""")
            .findAll(all).map { it.value }.distinct().take(6).toList()

        val top3 = hits.take(3)
        val resumen = "Resumen:\n• Se encontraron referencias relevantes para tu consulta."

        val pasos = buildList {
            add("Revisa el procedimiento específico en el manual indicado.")
            if (torques.isNotEmpty()) add("Aplica los torques especificados: ${torques.joinToString(", ")}.")
            add("Verifica funcionamiento y registra en bitácora.")
        }

        val seguridad = buildList {
            if (hasWarning) add("Atiende ‘WARNING’: riesgo para el personal si se omite.")
            if (hasCaution) add("Atiende ‘CAUTION’: posible daño al equipo si se omite.")
        }

        val cuerpo = buildString {
            appendLine(resumen)

            // Causas probables (SOLO el contenido; SIN “(Fuente: …)”)
            appendLine("\nCausas probables:")
            if (top3.isEmpty()) {
                appendLine("• No se identificaron causas en los manuales consultados.")
            } else {
                top3.forEachIndexed { i, h ->
                    val cause = cleanSnippet(h.snippet).ifBlank { "Referencia relevante encontrada." }
                    appendLine("• Causa ${i + 1}: $cause")
                }
            }

            // Pasos sugeridos
            appendLine("\nPasos sugeridos:")
            pasos.forEachIndexed { i, s -> appendLine("${i + 1}) $s") }

            // Seguridad (si aplica)
            if (seguridad.isNotEmpty()) {
                appendLine("\nSeguridad:")
                seguridad.forEach { appendLine("• $it") }
            }

            // ❌ Ya no imprimimos “Referencias” aquí.
            // Las referencias quedarán solo en la lista final (Recycler) para que sean clicables.
        }

        val citations = top3
            .map { Answer.Citation(basename(it.manual), it.page.coerceAtLeast(1)) }
            .distinct()

        return Answer(cuerpo, citations)
    }

    private fun cleanSnippet(sn: String): String =
        sn.replace("[", "").replace("]", "")
            .replace(Regex("\\s+"), " ").trim()
            .let { if (it.length <= 300) it else it.take(297) + "…" }

    private fun basename(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')
}
