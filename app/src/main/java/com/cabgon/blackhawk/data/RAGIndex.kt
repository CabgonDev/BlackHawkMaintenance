package com.cabgon.blackhawk.data

import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.util.Locale

class RAGIndex(private val db: SQLiteDatabase) : Closeable {

    data class Hit(val manual: String, val page: Int, val snippet: String, val score: Double)

    /**
     * Búsqueda general (texto). Mantengo tu lógica original para no romper nada.
     */
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
            .map { it.lowercase(Locale.US) }
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
        } catch (_: Exception) {}

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
        } catch (_: Exception) {}

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
        } catch (_: Exception) {}

        // D/E) LIKE fallback (solo para texto general)
        return try {
            val head = tokensKey.firstOrNull() ?: tokensAll.firstOrNull() ?: safeRaw
            val likeSql = """
                SELECT manual, page, '' AS sn, 0.0 AS sc
                FROM pages_fts
                WHERE manual LIKE '%' || ? || '%' OR content LIKE '%' || ? || '%'
                LIMIT ?
            """.trimIndent()

            val res = mutableListOf<Hit>()
            db.rawQuery(likeSql, arrayOf(head, head, limit.toString())).use { cur ->
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

    /**
     * ✅ Búsqueda ESPECIAL para NÚMEROS DE PARTE (PN/NSN-like).
     */
    fun searchPartNumberPages(
        query: String,
        allowedFilenames: Set<String>,
        limitPages: Int = 200
    ): List<Hit> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val tokens = extractPartTokens(q)
        if (tokens.isEmpty()) return emptyList()

        val nearQuery = buildNearQuery(tokens, nearDistance = 2)
        val andQuery = tokens.joinToString(" AND ") { "\"${it.replace("\"", "\"\"")}\"" }

        val sql = buildString {
            append(
                """
                SELECT manual,
                       page,
                       snippet(pages_fts, -1, '[', ']', ' … ', 10) AS sn,
                       bm25(pages_fts) AS sc
                FROM pages_fts
                WHERE pages_fts MATCH ?
                """.trimIndent()
            )
            if (allowedFilenames.isNotEmpty()) {
                append(" AND (")
                append(allowedFilenames.joinToString(" OR ") { "manual LIKE '%' || ? || '%'" })
                append(")")
            }
            append(" ORDER BY sc LIMIT ?")
        }

        fun run(match: String): List<Hit> {
            val args = ArrayList<String>(1 + allowedFilenames.size + 1)
            args.add(match)
            allowedFilenames.forEach { args.add(it) }
            args.add(limitPages.toString())

            val out = ArrayList<Hit>(minOf(limitPages, 32))
            db.rawQuery(sql, args.toTypedArray()).use { cur ->
                while (cur.moveToNext()) {
                    out += Hit(
                        manual = cur.getString(0),
                        page = cur.getInt(1),
                        snippet = cur.getString(2) ?: "",
                        score = cur.getDouble(3)
                    )
                }
            }
            return out
        }

        return try {
            val r1 = run(nearQuery)
            if (r1.isNotEmpty()) r1 else run(andQuery)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractPartTokens(input: String): List<String> {
        val s = input
            .replace('\u2010', '-')
            .replace('\u2011', '-')
            .replace('\u2012', '-')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace('\u2212', '-')
            .replace('\u00A0', ' ')
            .uppercase(Locale.US)

        val raw = Regex("[A-Z0-9]+").findAll(s).map { it.value }.toList()
        if (raw.isEmpty()) return emptyList()

        val big = raw.filter { it.length >= 4 }
        val small = raw.filter { it.length in 2..3 }

        val out = ArrayList<String>()
        if (big.isNotEmpty()) {
            out.addAll(big.take(3))
            out.addAll(small.take(2))
        } else {
            out.addAll(raw.take(4))
        }

        val seen = HashSet<String>()
        return out.filter { seen.add(it) }
    }

    private fun buildNearQuery(tokens: List<String>, nearDistance: Int): String {
        if (tokens.isEmpty()) return ""
        if (tokens.size == 1) return "\"${tokens[0].replace("\"", "\"\"")}\""

        var q = "\"${tokens[0].replace("\"", "\"\"")}\""
        for (i in 1 until tokens.size) {
            q += " NEAR/$nearDistance \"${tokens[i].replace("\"", "\"\"")}\""
        }
        return q
    }

    override fun close() {
        runCatching { db.close() }
    }

    companion object {

        /**
         * ✅ Opción 2 (APK ligero):
         * Ya NO copiamos desde assets. El índice debe existir en filesDir (descargado por AiUpdateManager).
         *
         * indexAssetPath se sigue usando como "id lógico" para obtener el filename:
         *  - "iads/index/blackhawk_iads_fts.db" -> filesDir/"blackhawk_iads_fts.db"
         *  - "sikorsky/index/blackhawk_sikorsky_fts.db" -> filesDir/"blackhawk_sikorsky_fts.db"
         */
        fun openFromFilesOrNull(ctx: Context, indexAssetPath: String): RAGIndex? {
            val target = File(ctx.filesDir, indexAssetPath.substringAfterLast("/"))
            if (!target.exists() || target.length() <= 0L) return null

            val db = SQLiteDatabase.openDatabase(
                target.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            return RAGIndex(db)
        }
    }
}