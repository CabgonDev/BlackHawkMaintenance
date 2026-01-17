package com.cabgon.blackhawk.ai.chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementación extractiva.
 * - Recupera topK pasajes del índice local
 * - Redacta respuesta "humana" sin inventar nada
 * - Siempre regresa citas (manual/página)
 */
class OfflineRagEngineImpl(
    private val appContext: Context,
    private val retriever: LocalRetrieverAdapter
) : OfflineRagEngine {

    override suspend fun answer(
        question: String,
        history: List<ChatTurn>,
        packageId: String
    ): RagAnswer = withContext(Dispatchers.Default) {

        val q = question.trim()
        if (q.isEmpty()) {
            return@withContext RagAnswer(
                answer = "Dime qué necesitas consultar y lo busco en los manuales locales.",
                passages = emptyList()
            )
        }

        // Memoria corta: soporta preguntas tipo “¿y luego?” / “¿qué sigue?”
        val memory = history
            .takeLast(6)
            .joinToString(" ") { it.content.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()

        val combinedQuery = if (memory.isNotBlank() && !memory.contains(q, ignoreCase = true)) {
            "$q  $memory"
        } else {
            q
        }

        // Recuperación (topK) - un poco más alto para sintetizar mejor
        val passages = retriever.retrieve(question = combinedQuery, packageId = packageId, topK = 10)

        if (passages.isEmpty()) {
            val safe = buildString {
                append("No encontré soporte suficiente en los manuales locales cargados para responder con certeza.\n\n")
                append("Para afinar, dime:\n")
                append("• ¿Qué sistema/componente es (por ejemplo: hidráulico, fuel, electrical, rotor)?\n")
                append("• ¿Qué síntoma observas y en qué condición (en tierra, en vuelo, arranque, etc.)?\n")
                append("• Si tienes un número de parte (P/N) o ATA, inclúyelo.\n\n")
                append("Basado únicamente en manuales locales.")
            }
            return@withContext RagAnswer(answer = safe, passages = emptyList())
        }

        // Síntesis extractiva: tomamos pasajes útiles, sin duplicar por manual/página
        val top = passages
            .sortedByDescending { it.score }
            .distinctBy { "${it.manual}:${it.page}" }
            .take(6)

        val answerText = formatExtractiveAnswer(q, history, top)

        RagAnswer(answer = answerText, passages = top)
    }

    private fun formatExtractiveAnswer(
        question: String,
        history: List<ChatTurn>,
        passages: List<RagPassage>
    ): String {
        // 1) Limpieza y deduplicación
        val cleaned = passages
            .sortedByDescending { it.score }
            .distinctBy { "${it.manual}:${it.page}:${it.text.take(80)}" }
            .map { p -> p.copy(text = normalize(p.text)) }
            .filter { it.text.length >= 40 }

        // 2) Si la evidencia es débil, responde conservador y pide detalle
        val topScore = cleaned.firstOrNull()?.score ?: 0.0
        val lowConfidence = topScore < 0.15 || cleaned.size < 2

        // 3) Extraer "piezas" útiles (heurística simple)
        val keyPoints = cleaned
            .flatMap { splitIntoPoints(it.text).take(10) }
            .map { it.trim() }
            .filter { it.length in 25..240 }
            .distinct()
            .take(12)

        val (warnings, notes, procedural) = classifyPoints(keyPoints)

        // 4) Respuesta conversacional estilo técnico
        val intro = "Entendido. Con base únicamente en los manuales locales que tienes cargados, esto es lo más sólido que puedo afirmar para tu consulta:"

        val summary = if (procedural.isNotEmpty()) {
            buildString {
                append("Resumen:\n")
                procedural.take(5).forEach { append("• ").append(it).append("\n") }
            }.trimEnd()
        } else {
            "Resumen:\n• Encontré referencias relacionadas, pero los fragmentos recuperados no muestran un procedimiento completo."
        }

        val cautionsBlock = if (warnings.isNotEmpty()) {
            buildString {
                append("Precauciones / Cautions:\n")
                warnings.take(3).forEach { append("• ").append(it).append("\n") }
            }.trimEnd()
        } else ""

        val notesBlock = if (notes.isNotEmpty()) {
            buildString {
                append("Notas:\n")
                notes.take(3).forEach { append("• ").append(it).append("\n") }
            }.trimEnd()
        } else ""

        val whatNext = if (lowConfidence) {
            """
            Para darte una respuesta más precisa (sin salir de los manuales), dime:
            • ¿Qué sistema o componente es?
            • ¿Qué síntoma exacto observas y en qué condición (arranque, rodaje, hover, crucero)?
            • ¿Tienes un P/N, ATA o referencia del capítulo?
            """.trimIndent()
        } else {
            """
            Si me confirmas el sistema/componente y el síntoma exacto, puedo acotar la búsqueda y devolverte el procedimiento correcto con mejores citas.
            """.trimIndent()
        }

        return buildString {
            append(intro).append("\n\n")
            append(summary).append("\n\n")

            if (cautionsBlock.isNotBlank()) {
                append(cautionsBlock).append("\n\n")
            }
            if (notesBlock.isNotBlank()) {
                append(notesBlock).append("\n\n")
            }

            append(whatNext).append("\n\n")
            append("Basado únicamente en manuales locales (ver fuentes).")
        }
    }

    /** Normaliza texto: espacios, guiones, saltos raros */
    private fun normalize(s: String): String {
        return s
            .replace("\u00AD", "") // soft hyphen
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** Divide un fragmento en puntos usando heurística (puntos, dos puntos, saltos) */
    private fun splitIntoPoints(text: String): List<String> {
        val byLines = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        return if (byLines.size >= 3) {
            byLines
        } else {
            text.split(Regex("(?<=[\\.!\\?])\\s+"))
        }
    }

    /** Clasifica puntos en warnings/notes/procedural con heurística de keywords */
    private fun classifyPoints(points: List<String>): Triple<List<String>, List<String>, List<String>> {
        val warnings = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val procedural = mutableListOf<String>()

        for (p in points) {
            val u = p.uppercase()
            when {
                u.contains("WARNING") || u.contains("CAUTION") || u.contains("PELIGRO") || u.contains("PRECAU") -> warnings += p
                u.startsWith("NOTE") || u.contains(" NOTE ") || u.contains("NOTA") -> notes += p
                else -> procedural += p
            }
        }
        return Triple(warnings, notes, procedural)
    }
}
