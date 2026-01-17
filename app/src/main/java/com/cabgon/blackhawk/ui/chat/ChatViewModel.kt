package com.cabgon.blackhawk.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cabgon.blackhawk.data.chat.ChatRepository
import com.cabgon.blackhawk.data.chat.ChatSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(
    private val repository: ChatRepository,
    private val packageIdProvider: () -> String
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessageUi>>(emptyList())
    val messages: StateFlow<List<ChatMessageUi>> = _messages

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking

    // NUEVO: etiqueta dinámica del indicador
    private val _thinkingLabel = MutableStateFlow("")
    val thinkingLabel: StateFlow<String> = _thinkingLabel

    private var session: ChatSessionEntity? = null
    private var started = false

    fun warmUpEngine() {
        viewModelScope.launch {
            runCatching { repository.warmUpEngine() }
        }
    }

    fun start() {
        if (started) return
        started = true

        viewModelScope.launch {
            runCatching {
                val pkg = packageIdProvider().trim()
                session = repository.getOrCreateSession(pkg)
                refresh()
            }.onFailure {
                started = false
                systemMessage("No pude iniciar el chat. Revisa la base de datos o el paquete seleccionado.")
            }
        }
    }

    fun send(text: String) {
        val s = session ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            _isThinking.value = true
            _thinkingLabel.value = "Inicializando..."

            // Placeholder local para ver streaming inmediatamente (aunque sea lento al inicio)
            val tempAssistantId = -System.currentTimeMillis()
            addOrReplaceTempAssistant(tempAssistantId, "")

            // Job que cambia la etiqueta por fases si el primer chunk tarda
            var gotFirstChunk = false
            val phaseJob: Job = viewModelScope.launch {
                delay(700)  // si a los ~0.7s no hay chunk, probablemente está en RAG/contexto
                if (!gotFirstChunk && _isThinking.value) _thinkingLabel.value = "Consultando Manuales…"

                delay(1200) // si aún no hay chunk, probablemente está evaluando prompt/modelo
                if (!gotFirstChunk && _isThinking.value) _thinkingLabel.value = "Preparando contexto…"
            }

            try {
                repository.sendUserMessage(s.id, trimmed)
                refresh()

                repository.generateAssistantAnswerStreaming(s) { chunk ->
                    if (chunk.isEmpty()) return@generateAssistantAnswerStreaming

                    if (!gotFirstChunk) {
                        gotFirstChunk = true
                        _thinkingLabel.value = "Generando respuesta…"
                        phaseJob.cancel()
                    }

                    appendToTempAssistant(tempAssistantId, chunk)
                }

                refresh()
            } catch (_: Throwable) {
                systemMessage("Ocurrió un error generando la respuesta. Verifica que el modelo/índice esté disponible.")
            } finally {
                phaseJob.cancel()
                removeTempAssistant(tempAssistantId)
                _thinkingLabel.value = ""
                _isThinking.value = false
            }
        }
    }

    fun systemMessage(text: String) {
        val s = session

        if (s == null) {
            val current = _messages.value.toMutableList()
            current.add(
                ChatMessageUi(
                    id = -System.currentTimeMillis(),
                    role = "system",
                    content = text,
                    sources = emptyList()
                )
            )
            _messages.value = current
            return
        }

        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            try {
                repository.sendAssistantMessage(s.id, trimmed)
                refresh()
            } catch (_: Throwable) {
                val current = _messages.value.toMutableList()
                current.add(
                    ChatMessageUi(
                        id = -System.currentTimeMillis(),
                        role = "system",
                        content = trimmed,
                        sources = emptyList()
                    )
                )
                _messages.value = current
            }
        }
    }

    private suspend fun refresh() {
        val s = session ?: return

        val msgs = repository.loadMessages(s.id)

        val ui = msgs.map { m ->
            val sources = if (m.role == "assistant") {
                repository.loadSources(m.id).map {
                    ChatSourceUi(
                        manual = it.manual,
                        page = it.page,
                        snippet = it.snippet
                    )
                }
            } else emptyList()

            ChatMessageUi(
                id = m.id,
                role = m.role,
                content = m.content,
                sources = sources
            )
        }

        _messages.value = ui
    }

    private fun addOrReplaceTempAssistant(tempId: Long, content: String) {
        val current = _messages.value.toMutableList()
        val idx = current.indexOfFirst { it.id == tempId }
        val item = ChatMessageUi(
            id = tempId,
            role = "assistant",
            content = content,
            sources = emptyList()
        )
        if (idx >= 0) current[idx] = item else current.add(item)
        _messages.value = current
    }

    private fun appendToTempAssistant(tempId: Long, chunk: String) {
        // Actualizamos en Main para que el Recycler refresque sin saltos
        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                val current = _messages.value.toMutableList()
                val idx = current.indexOfFirst { it.id == tempId }
                if (idx < 0) return@withContext

                val prev = current[idx]
                current[idx] = prev.copy(content = prev.content + chunk)
                _messages.value = current
            }
        }
    }

    private fun removeTempAssistant(tempId: Long) {
        val current = _messages.value.toMutableList()
        val idx = current.indexOfFirst { it.id == tempId }
        if (idx >= 0) {
            current.removeAt(idx)
            _messages.value = current
        }
    }
}
