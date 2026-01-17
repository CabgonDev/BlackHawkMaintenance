package com.cabgon.blackhawk.ui.chat

data class ChatMessageUi(
    val id: Long,
    val role: String,
    val content: String,
    val sources: List<ChatSourceUi> = emptyList()
)

data class ChatSourceUi(
    val manual: String,
    val page: Int,
    val snippet: String
)
