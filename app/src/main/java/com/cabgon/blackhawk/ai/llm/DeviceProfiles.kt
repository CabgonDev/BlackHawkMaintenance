package com.cabgon.blackhawk.ai.llm

import android.app.ActivityManager
import android.content.Context
import kotlin.math.roundToInt

object DeviceProfiles {

    enum class Profile { BASE, PRO }

    data class RagBudget(
        val maxPassages: Int,
        val maxCharsPerPassage: Int
    )

    fun totalRamGb(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gb = mi.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return gb.roundToInt().coerceAtLeast(1)
    }

    fun pickProfile(context: Context): Profile {
        val gb = totalRamGb(context)
        return if (gb <= 6) Profile.BASE else Profile.PRO
    }

    /**
     * Objetivo:
     * - Respuestas más largas sin sensación de "timeout"
     * - Ajuste conservador en BASE, más amplio en PRO
     */
    fun buildLlmConfig(context: Context, modelPath: String): LlmConfig {
        val ramGb = totalRamGb(context)

        return when (pickProfile(context)) {
            Profile.BASE -> {
                // Para 4–6GB: subimos un poco contexto y tokens, manteniendo threads bajos
                val nCtx = if (ramGb <= 4) 768 else 896
                LlmConfig(
                    modelPath = modelPath,
                    nCtx = nCtx,
                    nThreads = 2,
                    maxTokens = 192,
                    temperature = 0.20f,
                    topP = 0.90f,
                    timeoutMs = 600_000L
                )
            }

            Profile.PRO -> {
                // Para 8GB+ típicamente: mejor contexto y tokens para respuestas técnicas
                val nCtx = if (ramGb >= 10) 1536 else 1280
                val threads = if (ramGb >= 10) 6 else 4

                LlmConfig(
                    modelPath = modelPath,
                    nCtx = nCtx,
                    nThreads = threads,
                    maxTokens = 256,
                    temperature = 0.20f,
                    topP = 0.90f,
                    timeoutMs = 600_000L
                )
            }
        }
    }

    /**
     * RAG: subimos un poco el presupuesto para mejorar contexto sin saturar.
     */
    fun ragBudget(context: Context): RagBudget {
        val ramGb = totalRamGb(context)
        return when (pickProfile(context)) {
            Profile.BASE -> RagBudget(
                maxPassages = if (ramGb <= 4) 1 else 2,
                maxCharsPerPassage = if (ramGb <= 4) 220 else 260
            )
            Profile.PRO -> RagBudget(
                maxPassages = 3,
                maxCharsPerPassage = 280
            )
        }
    }

    fun debugString(context: Context, modelBytes: Long): String {
        val profile = pickProfile(context)
        val ramGb = totalRamGb(context)

        val cfg = buildLlmConfig(context, modelPath = "model.gguf")
        val rag = ragBudget(context)

        val modelGb = modelBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val timeoutS = (cfg.timeoutMs / 1000L).coerceAtLeast(1)

        return buildString {
            append("Perfil=")
            append(profile.name)
            append(" RAM≈")
            append(ramGb)
            append("GB Modelo≈")
            append(String.format("%.2f", modelGb))
            append("GB | nCtx=")
            append(cfg.nCtx)
            append(" th=")
            append(cfg.nThreads)
            append(" maxTok=")
            append(cfg.maxTokens)
            append(" temp=")
            append(String.format("%.2f", cfg.temperature))
            append(" topP=")
            append(String.format("%.2f", cfg.topP))
            append(" timeout=")
            append(timeoutS)
            append("s | RAG p=")
            append(rag.maxPassages)
            append(" chars=")
            append(rag.maxCharsPerPassage)
        }
    }
}
