package com.cabgon.blackhawk.data

import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/** Resultado de búsqueda del índice Sikorsky de Números de Parte. */
data class SikorskyPartHit(
    val manualKey: String,
    val assetPath: String,
    val page: Int,
    val fig: Int?,
    val cagec: String?,
    val partNumber: String?,
    val nsn: String?,
    val description: String?,
    val score: Double? = null
)

class SikorskyPartsIndex(private val db: SQLiteDatabase) : Closeable {

    fun search(query: String, limit: Int = 25): List<SikorskyPartHit> {
        val raw = query.trim()
        if (raw.isBlank()) return emptyList()

        val norm = normalizePn(raw)
        val upper = raw.uppercase()

        // 1) Exact match por PN normalizado
        if (norm.isNotBlank()) {
            val exact = queryExact(norm, limit)
            if (exact.isNotEmpty()) return exact
        }

        // 2) Contains / LIKE robusto (cubre variantes y entrada parcial)
        val like = queryLike(norm, upper, limit)
        if (like.isNotEmpty()) return like

        // 3) FTS (fallback): pn_raw/nsn/desc
        return queryFts(raw, norm, limit)
    }

    private fun queryExact(pnNorm: String, limit: Int): List<SikorskyPartHit> {
        val sql = """
            SELECT manual, page, fig, cagec, pn_raw, nsn, desc
            FROM parts
            WHERE pn_norm = ?
            ORDER BY manual, page
            LIMIT ?
        """.trimIndent()

        val hits = mutableListOf<SikorskyPartHit>()
        db.rawQuery(sql, arrayOf(pnNorm, limit.toString())).use { c ->
            while (c.moveToNext()) {
                val manualKey = c.getString(0)
                val asset = manualKeyToAssetPath(manualKey) ?: continue
                hits += SikorskyPartHit(
                    manualKey = manualKey,
                    assetPath = asset,
                    page = c.getInt(1),
                    fig = c.getIntOrNull(2),
                    cagec = c.getStringOrNull(3),
                    partNumber = c.getStringOrNull(4),
                    nsn = c.getStringOrNull(5),
                    description = c.getStringOrNull(6),
                    score = null
                )
            }
        }
        return hits
    }

    private fun queryLike(pnNorm: String, rawUpper: String, limit: Int): List<SikorskyPartHit> {
        val sql = """
            SELECT manual, page, fig, cagec, pn_raw, nsn, desc
            FROM parts
            WHERE (pn_norm LIKE '%' || ? || '%')
               OR (pn_raw  LIKE '%' || ? || '%')
               OR (nsn     LIKE '%' || ? || '%')
            ORDER BY manual, page
            LIMIT ?
        """.trimIndent()

        val hits = mutableListOf<SikorskyPartHit>()
        db.rawQuery(sql, arrayOf(pnNorm, rawUpper, rawUpper, limit.toString())).use { c ->
            while (c.moveToNext()) {
                val manualKey = c.getString(0)
                val asset = manualKeyToAssetPath(manualKey) ?: continue
                hits += SikorskyPartHit(
                    manualKey = manualKey,
                    assetPath = asset,
                    page = c.getInt(1),
                    fig = c.getIntOrNull(2),
                    cagec = c.getStringOrNull(3),
                    partNumber = c.getStringOrNull(4),
                    nsn = c.getStringOrNull(5),
                    description = c.getStringOrNull(6),
                    score = null
                )
            }
        }
        return hits
    }

    private fun queryFts(raw: String, norm: String, limit: Int): List<SikorskyPartHit> {
        val safeRaw = raw
            .replace(Regex("[^A-Za-z0-9/_\\-\\s]"), " ")
            .replace(Regex("\\p{Cntrl}"), " ")
            .trim()
        if (safeRaw.isBlank()) return emptyList()

        val tokens = safeRaw
            .split(Regex("[\\s/\\\\:_-]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(8)

        val esc = { s: String -> s.replace("\"", "\"\"") }

        val queries = mutableListOf<String>()

        // Intento 1: PN normalizado como frase
        if (norm.isNotBlank()) {
            queries += "\"${esc(norm)}\""
        }

        // Intento 2: AND tokens
        if (tokens.isNotEmpty()) {
            queries += tokens.take(6).joinToString(" AND ") { "\"${esc(it)}\"" }
        }

        val sql = """
            SELECT p.manual,
                   p.page,
                   p.fig,
                   p.cagec,
                   p.pn_raw,
                   p.nsn,
                   p.desc,
                   bm25(parts_fts) AS sc
            FROM parts_fts
            JOIN parts p ON p.id = parts_fts.rowid
            WHERE parts_fts MATCH ?
            ORDER BY sc
            LIMIT ?
        """.trimIndent()

        for (q in queries.distinct()) {
            val hits = mutableListOf<SikorskyPartHit>()
            try {
                db.rawQuery(sql, arrayOf(q, limit.toString())).use { c ->
                    while (c.moveToNext()) {
                        val manualKey = c.getString(0)
                        val asset = manualKeyToAssetPath(manualKey) ?: continue
                        hits += SikorskyPartHit(
                            manualKey = manualKey,
                            assetPath = asset,
                            page = c.getInt(1),
                            fig = c.getIntOrNull(2),
                            cagec = c.getStringOrNull(3),
                            partNumber = c.getStringOrNull(4),
                            nsn = c.getStringOrNull(5),
                            description = c.getStringOrNull(6),
                            score = c.getDoubleOrNull(7)
                        )
                    }
                }
            } catch (_: Exception) {
                // Si la query rompe la sintaxis FTS, ignoramos y probamos la siguiente.
            }
            if (hits.isNotEmpty()) return hits
        }

        return emptyList()
    }

    override fun close() {
        runCatching { db.close() }
    }

    companion object {
        private const val ASSET_DB_PATH = "sikorsky/index/sikorsky_parts_index.db"
        private const val TARGET_FILE_NAME = "sikorsky_parts_index.db"

        fun openFromAssets(ctx: Context): SikorskyPartsIndex {
            val target = File(ctx.filesDir, TARGET_FILE_NAME)
            if (!target.exists()) {
                ctx.assets.open(ASSET_DB_PATH).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }

            val db = SQLiteDatabase.openDatabase(
                target.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            return SikorskyPartsIndex(db)
        }

        /** Normaliza un PN eliminando separadores: solo A-Z y 0-9, en mayúsculas. */
        fun normalizePn(input: String): String {
            val t = input.trim().uppercase()
            val sb = StringBuilder(t.length)
            for (ch in t) {
                if (ch in 'A'..'Z' || ch in '0'..'9') sb.append(ch)
            }
            return sb.toString()
        }

        private fun manualKeyToAssetPath(key: String): String? {
            return when (key.lowercase()) {
                "maintenance_repair_parts_and_special" ->
                    "sikorsky/manuals/MAINTENANCE REPAIR PARTS AND SPECIAL.pdf"
                "avionics_repair_parts_and_special" ->
                    "sikorsky/manuals/AVIONICS REPAIR PARTS AND SPECIAL.pdf"
                else -> null
            }
        }
    }
}

private fun android.database.Cursor.getStringOrNull(i: Int): String? =
    if (isNull(i)) null else getString(i)

private fun android.database.Cursor.getIntOrNull(i: Int): Int? =
    if (isNull(i)) null else getInt(i)

private fun android.database.Cursor.getDoubleOrNull(i: Int): Double? =
    if (isNull(i)) null else getDouble(i)
