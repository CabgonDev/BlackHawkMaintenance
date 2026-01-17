package com.cabgon.blackhawk.ai.chat

data class ChatTurn(
    val role: String,   // "user" | "assistant"
    val content: String
)

data class RagPassage(
    val manual: String,
    val page: Int,
    val text: String,
    val score: Double
)

data class RagAnswer(
    val answer: String,
    val passages: List<RagPassage>
)
