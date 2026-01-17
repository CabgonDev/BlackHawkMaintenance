package com.cabgon.blackhawk.data

import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

class RAGIndex(private val db: SQLiteDatabase) : Closeable {

    data class Hit(val manual: String, val page: Int, val snippet: String, val score: Double)

    fun searchEnglish(query: String, limit: Int = 8): List<Hit> {
        if (query.isBlank()) return emptyList()

        val safeRaw = query
            .replace(Regex("[^A-Za-z0-9/_\\-\\s]"), " ")
            .replace(Regex("\\p{Cntrl}"), " ")
            .trim()

        if (safeRaw.isBlank()) return emptyList()

        val stop = setOf(
            "what", "which", "who", "when", "where", "why", "how",
            "is", "are", "was", "were", "be", "been", "being",
            "do", "does", "did",
            "the", "a", "an", "and", "or", "to", "of", "in", "on", "for", "with", "without", "about",
            "this", "that", "these", "those",
            "it", "its", "as", "at", "by", "from",
            "serve", "serves", "used", "use", "using"
        )

        val tokensAll = safeRaw
            .split(Regex("[\\s/\\\\:_-]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(10)

        val tokensKey = tokensAll
            .map { it.lowercase() }
            .filter { it.length >= 2 && it !in stop }
            .take(6)
            .map { it.replace("\"", "\"\"") }

        val qPhrase = "\"${safeRaw.replace("\"", "\"\"")}\""
        val qAnd = tokensKey.joinToString(" AND ") { "\"$it\"" }.ifBlank { qPhrase }
        val qOr = tokensKey.joinToString(" OR ") { "\"$it\"" }.ifBlank { qPhrase }

        val sqlFTS = """
            SELECT manual,
                   page,
                   snippet(pages_fts, -1, '[', ']', ' … ', 10) AS sn,
                   bm25(pages_fts) AS sc
            FROM pages_fts
            WHERE pages_fts MATCH ?
            ORDER BY sc
            LIMIT ?
        """.trimIndent()

        val hits = mutableListOf<Hit>()

        // A) FTS frase
        try {
            db.rawQuery(sqlFTS, arrayOf(qPhrase, limit.toString())).use { cur ->
                while (cur.moveToNext()) {
                    hits += Hit(
                        manual = cur.getString(0),
                        page = cur.getInt(1),
                        snippet = cur.getString(2) ?: "",
                        score = cur.getDouble(3)
                    )
                }
            }
            if (hits.isNotEmpty()) return hits
        } catch (_: Exception) { }

        // B) FTS AND tokens
        try {
            db.rawQuery(sqlFTS, arrayOf(qAnd, limit.toString())).use { cur ->
                while (cur.moveToNext()) {
                    hits += Hit(
                        manual = cur.getString(0),
                        page = cur.getInt(1),
                        snippet = cur.getString(2) ?: "",
                        score = cur.getDouble(3)
                    )
                }
            }
            if (hits.isNotEmpty()) return hits
        } catch (_: Exception) { }

        // C) FTS OR tokens
        try {
            db.rawQuery(sqlFTS, arrayOf(qOr, limit.toString())).use { cur ->
                while (cur.moveToNext()) {
                    hits += Hit(
                        manual = cur.getString(0),
                        page = cur.getInt(1),
                        snippet = cur.getString(2) ?: "",
                        score = cur.getDouble(3)
                    )
                }
            }
            if (hits.isNotEmpty()) return hits
        } catch (_: Exception) { }

        // D) LIKE robusto
        try {
            val likeSql = """
                SELECT manual, page, '' AS sn, 0.0 AS sc
                FROM pages_fts
                WHERE manual LIKE '%' || ? || '%' OR content LIKE '%' || ? || '%'
                LIMIT ?
            """.trimIndent()

            val res = mutableListOf<Hit>()
            db.rawQuery(likeSql, arrayOf(safeRaw, safeRaw, limit.toString())).use { cur ->
                while (cur.moveToNext()) {
                    res += Hit(
                        manual = cur.getString(0),
                        page = cur.getInt(1),
                        snippet = "",
                        score = 0.0
                    )
                }
            }
            if (res.isNotEmpty()) return res
        } catch (_: Exception) { }

        // E) Fallback token
        return try {
            val head = tokensKey.firstOrNull() ?: tokensAll.firstOrNull() ?: safeRaw
            val likeSql2 = """
                SELECT manual, page, '' AS sn, 0.0 AS sc
                FROM pages_fts
                WHERE manual LIKE '%' || ? || '%' OR content LIKE '%' || ? || '%'
                LIMIT ?
            """.trimIndent()

            val res = mutableListOf<Hit>()
            db.rawQuery(likeSql2, arrayOf(head, head, limit.toString())).use { cur ->
                while (cur.moveToNext()) {
                    res += Hit(
                        manual = cur.getString(0),
                        page = cur.getInt(1),
                        snippet = "",
                        score = 0.0
                    )
                }
            }
            res
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun close() {
        // Cierre explícito para evitar leaks (CloseGuard warnings)
        runCatching { db.close() }
    }

    companion object {
        fun openFromAssets(ctx: Context, indexAssetPath: String): RAGIndex {
            val target = File(ctx.filesDir, indexAssetPath.substringAfterLast("/"))
            if (!target.exists()) {
                target.parentFile?.mkdirs()
                ctx.assets.open(indexAssetPath).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            android.util.Log.d("RAG", "open index at: ${target.absolutePath} exists=${target.exists()} size=${target.length()}")

            val db = SQLiteDatabase.openDatabase(
                target.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            return RAGIndex(db)
        }
    }
}
