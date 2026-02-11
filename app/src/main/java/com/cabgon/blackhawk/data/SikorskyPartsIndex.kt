package com.cabgon.blackhawk.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

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

        // ✅ Regla establecida: mínimo 3 alfanuméricos
        val alphaNumCount = raw.count { it.isLetterOrDigit() }
        if (alphaNumCount < 3) return emptyList()

        val norm = normalizePn(raw)
        val upper = raw.uppercase()

        if (norm.isNotBlank()) {
            val exact = queryExact(norm, limit)
            if (exact.isNotEmpty()) return exact
        }

        val like = queryLike(norm, upper, limit)
        if (like.isNotEmpty()) return like

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
        val (sql, args) = if (pnNorm.isNotBlank()) {
            """
                SELECT manual, page, fig, cagec, pn_raw, nsn, desc
                FROM parts
                WHERE (pn_norm LIKE '%' || ? || '%')
                   OR (pn_raw  LIKE '%' || ? || '%')
                   OR (nsn     LIKE '%' || ? || '%')
                ORDER BY manual, page
                LIMIT ?
            """.trimIndent() to arrayOf(pnNorm, rawUpper, rawUpper, limit.toString())
        } else {
            """
                SELECT manual, page, fig, cagec, pn_raw, nsn, desc
                FROM parts
                WHERE (pn_raw  LIKE '%' || ? || '%')
                   OR (nsn     LIKE '%' || ? || '%')
                ORDER BY manual, page
                LIMIT ?
            """.trimIndent() to arrayOf(rawUpper, rawUpper, limit.toString())
        }

        val hits = mutableListOf<SikorskyPartHit>()
        db.rawQuery(sql, args).use { c ->
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
        if (norm.isNotBlank()) queries += "\"${esc(norm)}\""
        if (tokens.isNotEmpty()) queries += tokens.take(6).joinToString(" AND ") { "\"${esc(it)}\"" }

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

        private const val PREFS_NAME = "sikorsky_parts_index_prefs"
        private const val KEY_LAST_VERSION_CODE = "last_version_code"

        fun openFromAssets(ctx: Context): SikorskyPartsIndex {
            val target = File(ctx.filesDir, TARGET_FILE_NAME)

            val prefs: SharedPreferences =
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val currentVersionCode = getVersionCode(ctx)
            val lastVersionCode = prefs.getLong(KEY_LAST_VERSION_CODE, -1L)

            val shouldRefresh = (lastVersionCode != currentVersionCode)

            if (!target.exists() || shouldRefresh) {
                runCatching { target.delete() }
                ctx.assets.open(ASSET_DB_PATH).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                prefs.edit().putLong(KEY_LAST_VERSION_CODE, currentVersionCode).apply()
            }

            val db = SQLiteDatabase.openDatabase(
                target.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            return SikorskyPartsIndex(db)
        }

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

        private fun getVersionCode(ctx: Context): Long {
            val pm = ctx.packageManager
            val pkg = ctx.packageName
            val pi = pm.getPackageInfo(pkg, 0)
            return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        }
    }
}

private fun android.database.Cursor.getStringOrNull(i: Int): String? =
    if (isNull(i)) null else getString(i)

private fun android.database.Cursor.getIntOrNull(i: Int): Int? =
    if (isNull(i)) null else getInt(i)

private fun android.database.Cursor.getDoubleOrNull(i: Int): Double? =
    if (isNull(i)) null else getDouble(i)
