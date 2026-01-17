// app/src/main/java/com/cabgon/blackhawk/ai/LocalRetriever.kt
package com.cabgon.blackhawk.ai

import com.cabgon.blackhawk.data.RAGIndex

class LocalRetriever(private val index: RAGIndex) {

    // Sanitiza: quita control chars y deja sólo lo seguro para nuestra búsqueda local
    private fun sanitize(q: String): String =
        q.replace(Regex("[^A-Za-z0-9/_\\-\\.\\s]"), " ")
            .replace(Regex("\\p{Cntrl}"), " ")
            .trim()

    // Tokeniza (por si queremos un fallback manual con AND)
    private fun tokenize(english: String): List<String> =
        english.uppercase()
            .replace(Regex("[^A-Z0-9/_\\-\\.\\s]"), " ")
            .trim()
            .split(Regex("[\\s/\\\\:_\\-.]+"))
            .filter { it.isNotBlank() }
            .take(8)

    // Construye un MATCH seguro con AND (sólo para fallback)
    private fun buildMatchQueryAND(english: String): String =
        tokenize(english).joinToString(" AND ") { "\"$it\"" }

    /**
     * Búsqueda local robusta (no crashea con símbolos ni cuando no hay resultados).
     * - Sanitiza la consulta
     * - Delega al RAGIndex estable (que ya hace frase → AND → LIKE)
     * - Si algo sale mal, retorna lista vacía
     */
    fun retrieve(englishQuery: String, limit: Int = 8): List<RAGIndex.Hit> {
        val safe = sanitize(englishQuery)
        if (safe.isBlank()) return emptyList()

        // Camino principal: usa el RAGIndex endurecido
        val primary = try {
            index.searchEnglish(safe, limit = limit)
        } catch (_: Exception) {
            emptyList()
        }
        if (primary.isNotEmpty()) return primary

        // Fallback opcional: AND tokens (por si el caller quiere forzar coincidencias estrictas)
        return try {
            val andQuery = buildMatchQueryAND(safe)
            if (andQuery.isBlank()) emptyList()
            else index.searchEnglish(andQuery, limit = limit)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
