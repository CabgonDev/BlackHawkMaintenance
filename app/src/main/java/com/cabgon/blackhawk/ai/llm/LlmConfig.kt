package com.cabgon.blackhawk.ai.llm

data class LlmConfig(
    val modelPath: String,
    val nCtx: Int = 1024,
    val nThreads: Int = 4,
    val maxTokens: Int = 96,
    val temperature: Float = 0.25f,
    val topP: Float = 0.9f,
    val timeoutMs: Long = 600_000L // 10 minutos
)
