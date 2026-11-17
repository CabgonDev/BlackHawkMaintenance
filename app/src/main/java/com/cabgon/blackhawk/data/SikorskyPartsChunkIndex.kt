package com.cabgon.blackhawk.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

class SikorskyPartsChunkIndex private constructor(
    private val db: SQLiteDatabase
) : Closeable {

    companion object {
        // Archivo “instalado” dentro de filesDir (lo abrimos desde ahí)
        private const val DB_NAME = "sikorsky_parts_chunks_fts.db"

        // Archivo real que YA tienes en assets
        private const val ASSET_DB_PATH = "index/sikorsky_parts_chunks_fts4.db"

        fun open(context: Context): SikorskyPartsChunkIndex {
            val dbFile = File(context.filesDir, DB_NAME)
            ensureInstalled(context, dbFile)

            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            return SikorskyPartsChunkIndex(db)
        }

        private fun ensureInstalled(context: Context, dbFile: File) {
            if (dbFile.exists() && dbFile.length() > 0L) return

            // Copia atómica: primero tmp, luego rename
            val tmp = File(dbFile.absolutePath + ".tmp")
            tmp.parentFile?.mkdirs()

            context.assets.open(ASSET_DB_PATH).use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        out.write(buf, 0, r)
                    }
                    out.flush()
                }
            }

            if (!tmp.renameTo(dbFile)) {
                // fallback
                tmp.copyTo(dbFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    data class FtsRow(
        val source: String,
        val page1Start: Int,
        val page1End: Int,
        val snippet: String
    )

    /**
     * Búsqueda FTS directa (rápida).
     * @param sourceFilter si no es null, filtra por nombre exacto de PDF.
     * @param limit máximo de filas a devolver (sube esto si quieres “TODOS”).
     */
    fun search(query: String, sourceFilter: String? = null, limit: Int = 5000): List<FtsRow> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val sql: String
        val args: Array<String>

        if (sourceFilter.isNullOrBlank()) {
            sql = """
                SELECT source, page1_start, page1_end, snippet
                FROM chunks_fts
                WHERE chunks_fts MATCH ?
                LIMIT ?
            """.trimIndent()
            args = arrayOf(q, limit.toString())
        } else {
            sql = """
                SELECT source, page1_start, page1_end, snippet
                FROM chunks_fts
                WHERE chunks_fts MATCH ?
                  AND source = ?
                LIMIT ?
            """.trimIndent()
            args = arrayOf(q, sourceFilter, limit.toString())
        }

        val out = ArrayList<FtsRow>(minOf(limit, 512))
        db.rawQuery(sql, args).use { c ->
            val iSource = c.getColumnIndexOrThrow("source")
            val iS = c.getColumnIndexOrThrow("page1_start")
            val iE = c.getColumnIndexOrThrow("page1_end")
            val iSnip = c.getColumnIndexOrThrow("snippet")

            while (c.moveToNext()) {
                out.add(
                    FtsRow(
                        source = c.getString(iSource),
                        page1Start = c.getInt(iS),
                        page1End = c.getInt(iE),
                        snippet = c.getString(iSnip) ?: ""
                    )
                )
            }
        }
        return out
    }

    override fun close() {
        runCatching { db.close() }
    }
}
