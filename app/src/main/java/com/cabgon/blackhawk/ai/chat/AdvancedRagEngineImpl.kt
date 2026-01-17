package com.cabgon.blackhawk.ai.chat

import android.content.Context
import android.util.Log
import com.cabgon.blackhawk.ai.Translator
import com.cabgon.blackhawk.ai.llm.DeviceProfiles
import com.cabgon.blackhawk.ai.llm.LocalLlmClient

class AdvancedRagEngineImpl(
    private val retriever: LocalRetrieverAdapter,
    private val llm: LocalLlmClient,
    private val translator: Translator,
    private val appContext: Context
) : OfflineRagEngine {

    override suspend fun warmUp() {
        runCatching { translator.ensureModels() }
        runCatching { llm.warmUp() }
    }

    override suspend fun answer(
        question: String,
        history: List<ChatTurn>,
        packageId: String
    ): RagAnswer {
        // Compatibilidad: llama a la versión streaming acumulando.
        val sb = StringBuilder(2048)
        val res = answerStreaming(question, history, packageId) { sb.append(it) }
        return res
    }

    override suspend fun answerStreaming(
        question: String,
        history: List<ChatTurn>,
        packageId: String,
        onChunk: (String) -> Unit
    ): RagAnswer {

        val budget = DeviceProfiles.ragBudget(appContext)
        val profile = DeviceProfiles.pickProfile(appContext)

        val qEn = runCatching { translator.esToEn(question) }.getOrDefault(question)

        val passagesRaw = retriever.retrieve(
            question = qEn,
            packageId = packageId,
            topK = 6
        )

        if (passagesRaw.isEmpty()) {
            val no = "No encontré información suficiente en los manuales locales para responder con certeza. Si me indicas ATA/sistema o capítulo, lo acoto mejor."
            onChunk(no)
            return RagAnswer(answer = no, passages = emptyList())
        }

        val passages = passagesRaw
            .sortedByDescending { it.score }
            .distinctBy { "${it.manual}:${it.page}" }
            .take(budget.maxPassages)

        val contextBlock = buildContextBlock(passages, budget.maxCharsPerPassage)

        // Pedimos salida en español para poder streamear sin traducción post.
        val system = if (profile == DeviceProfiles.Profile.BASE) {
            "You are a technical assistant for UH-60L maintenance manuals. Answer ONLY from CONTEXT. If insufficient, say what's missing. Do NOT invent references. Output in Spanish."
        } else {
            "You are a UH-60L maintenance assistant. Answer ONLY from CONTEXT. If insufficient, say what's missing. Do NOT invent references. Output in Spanish (professional aviation maintenance terminology)."
        }

        val user = """
            CONTEXT:
            $contextBlock

            QUESTION:
            $qEn

            OUTPUT RULES:
            - Provide a technical answer in Spanish.
            - Be concise but complete.
            - Do not cite pages in the prose. Do not invent references.
        """.trimIndent()

        val prompt = PromptTemplates.applyLlama3(system = system, user = user)

        Log.d(
            "LLM",
            "generateStream() start template=LLAMA3 ctxChars=${contextBlock.length} qLen=${qEn.length} passages=${passages.size}"
        )

        val out = runCatching {
            llm.generateStream(prompt) { chunk ->
                onChunk(chunk)
            }
        }.getOrNull().orEmpty().trim()

        val bodyEs = if (out.isBlank() || out.startsWith("ERROR:") || out.contains("[ERROR:")) {
            Log.e("LLM", "LLM failed/timeout: '$out'")
            buildExtractiveFallbackEs(passages)
        } else {
            out
        }

        val refsEs = buildReferencesEs(passages)

        val final = bodyEs.trim() + "\n\n" + refsEs

        return RagAnswer(
            answer = final,
            passages = passages
        )
    }

    private fun buildContextBlock(passages: List<RagPassage>, maxCharsPerPassage: Int): String {
        return passages.joinToString("\n\n") { p ->
            val clipped = p.text
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(maxCharsPerPassage)

            "[${p.manual} p.${p.page}] $clipped"
        }
    }

    private fun buildExtractiveFallbackEs(passages: List<RagPassage>): String {
        val bullets = passages.mapNotNull { p ->
            val s = p.text.trim().replace(Regex("\\s+"), " ")
            if (s.length < 60) null else "• ${s.take(220)}"
        }

        return buildString {
            append("Encontré referencias relacionadas en los manuales locales, pero el modo avanzado no devolvió una respuesta completa a tiempo.\n\n")
            append("Lo más relevante recuperado:\n")
            if (bullets.isNotEmpty()) append(bullets.joinToString("\n"))
            else append("• Hay fragmentos relevantes, pero son demasiado cortos para resumir aquí.")
        }
    }

    private fun buildReferencesEs(passages: List<RagPassage>): String {
        val lines = passages
            .distinctBy { "${it.manual}:${it.page}" }
            .map { p -> "• ${p.manual} — Página ${p.page}" }

        return buildString {
            append("REFERENCIAS (manual/página):\n")
            append(lines.joinToString("\n"))
        }
    }
}
