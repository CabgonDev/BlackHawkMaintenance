package com.cabgon.blackhawk.util

import android.content.Context
import java.io.File

/**
 * Cache local para PDFs grandes (manuales) descargados desde Firebase Storage.
 *
 * - NO borra al cambiar de paquete.
 * - Guarda en filesDir (privado) para offline sin permisos.
 * - Usa manualId como clave estable para metadata (updated/size), para que no se rompa
 *   si cambia la ruta local o remote.
 */
object ManualCache {

    private const val ROOT_DIR = "manual_cache"
    private const val META_PREFS = "manual_cache_meta"

    /**
     * Archivo local donde debe vivir el manual.
     * localRelativePath ejemplo:
     *  - "iads/manuals/uh60l_general.pdf"
     *  - "sikorsky/xyz/abc.pdf"
     */
    fun localFileFor(context: Context, manualId: String, localRelativePath: String): File {
        val base = File(context.filesDir, ROOT_DIR)
        if (!base.exists()) base.mkdirs()

        // Creamos subdirectorios según localRelativePath
        val safeRel = localRelativePath
            .replace("\\", "/")
            .trimStart('/')
        require(safeRel.isNotBlank()) { "Ruta local de manual vacía" }
        require(safeRel.split('/').none { it == "." || it == ".." }) {
            "Ruta local de manual inválida"
        }
        val baseCanonical = base.canonicalFile
        val out = File(baseCanonical, safeRel).canonicalFile
        require(out.path.startsWith(baseCanonical.path + File.separator)) {
            "Ruta local de manual fuera del caché"
        }

        out.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }

        return out
    }

    fun getLocalIfExists(context: Context, manualId: String, localRelativePath: String): File? {
        val f = localFileFor(context, manualId, localRelativePath)
        DownloadedFileCommit.recover(f)
        return if (f.exists() && f.length() > 0) f else null
    }

    fun getSavedUpdatedTime(context: Context, manualId: String): Long {
        return context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            .getLong(keyUpdatedTime(manualId), 0L)
    }

    fun setSavedUpdatedTime(context: Context, manualId: String, updatedTimeMillis: Long) {
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(keyUpdatedTime(manualId), updatedTimeMillis)
            .apply()
    }

    fun getSavedSize(context: Context, manualId: String): Long {
        return context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            .getLong(keySize(manualId), 0L)
    }

    fun setSavedSize(context: Context, manualId: String, sizeBytes: Long) {
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(keySize(manualId), sizeBytes)
            .apply()
    }

    private fun keyUpdatedTime(manualId: String) = "u_$manualId"
    private fun keySize(manualId: String) = "s_$manualId"
}
