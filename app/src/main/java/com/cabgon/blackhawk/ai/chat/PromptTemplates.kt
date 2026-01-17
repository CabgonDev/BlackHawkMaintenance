package com.cabgon.blackhawk.ai.chat

object PromptTemplates {

    fun applyLlama3(system: String, user: String): String {
        return buildString {
            append("<|begin_of_text|>")
            append("<|start_header_id|>system<|end_header_id|>\n")
            append(system.trim())
            append("<|eot_id|>")
            append("<|start_header_id|>user<|end_header_id|>\n")
            append(user.trim())
            append("<|eot_id|>")
            append("<|start_header_id|>assistant<|end_header_id|>\n")
        }
    }
}
