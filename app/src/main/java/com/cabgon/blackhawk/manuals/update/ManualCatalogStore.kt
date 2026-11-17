package com.cabgon.blackhawk.manuals.update

import android.content.Context
import android.util.Log
import com.cabgon.blackhawk.data.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Catálogo local de manuales OTA por paquete.
 *
 * Fuente principal para UI:
 * - Si existe catálogo OTA válido -> se usa primero.
 * - Si no existe -> fallback a PackageManager.manuals(pkg).
 *
 * Además mezcla OTA + fallback estático para no perder manuales base durante la transición.
 */
object ManualCatalogStore {

    private const val TAG = "MANUAL_CATALOG"
    private const val ROOT_DIR = "manual_catalog"

    data class ManifestCatalog(
        val version: Long,
        val manuals: List<PackageManager.ManualMeta>
    )

    fun resolveManuals(context: Context, pkg: PackageManager.Pkg): List<PackageManager.ManualMeta> {
        val fallback = PackageManager.manuals(pkg)
        val ota = loadCatalog(context, pkg)?.manuals.orEmpty()
        if (ota.isEmpty()) return fallback

        val merged = LinkedHashMap<String, PackageManager.ManualMeta>()

        // OTA manda primero para respetar orden, títulos nuevos y altas OTA.
        for (m in ota) {
            merged[dedupeKey(m)] = sanitize(m)
        }

        // Fallback del APK para no perder manuales base si el manifest viene parcial.
        for (m in fallback) {
            merged.putIfAbsent(dedupeKey(m), sanitize(m))
        }

        return merged.values.toList()
    }

    fun loadCatalog(context: Context, pkg: PackageManager.Pkg): ManifestCatalog? {
        val file = catalogFile(context, pkg)
        if (!file.exists() || file.length() <= 0L) return null

        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val version = json.optLong("version", -1L).coerceAtLeast(-1L)
            val manuals = parseManualsArray(json.optJSONArray("manuals"))
            ManifestCatalog(version = version, manuals = manuals)
        }.onFailure { e ->
            Log.e(TAG, "loadCatalog FAIL pkg=$pkg file='${file.absolutePath}'", e)
        }.getOrNull()
    }

    fun saveCatalog(
        context: Context,
        pkg: PackageManager.Pkg,
        version: Long,
        manuals: List<PackageManager.ManualMeta>
    ) {
        val normalized = manuals
            .map(::sanitize)
            .distinctBy(::dedupeKey)

        val root = rootDir(context)
        if (!root.exists()) root.mkdirs()

        val out = catalogFile(context, pkg)
        val tmp = File(out.absolutePath + ".tmp")

        val json = JSONObject().apply {
            put("package", pkg.name)
            put("version", version)
            put("manuals", JSONArray().apply {
                normalized.forEach { m ->
                    put(JSONObject().apply {
                        put("title", m.title)
                        put("remotePath", m.remotePath)
                        put("id", m.id)
                        put("localRelativePath", m.localRelativePath)
                    })
                }
            })
        }

        runCatching {
            tmp.writeText(json.toString(2), Charsets.UTF_8)
            if (out.exists()) out.delete()
            tmp.renameTo(out)
            Log.d(TAG, "saveCatalog OK pkg=$pkg version=$version manuals=${normalized.size} file='${out.absolutePath}'")
        }.onFailure { e ->
            Log.e(TAG, "saveCatalog FAIL pkg=$pkg file='${out.absolutePath}'", e)
            runCatching { tmp.delete() }
        }
    }

    fun clearCatalog(context: Context, pkg: PackageManager.Pkg) {
        runCatching { catalogFile(context, pkg).delete() }
            .onFailure { e -> Log.e(TAG, "clearCatalog FAIL pkg=$pkg", e) }
    }

    fun parseManifest(bytes: ByteArray): ManifestCatalog {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val version = json.optLong("version", -1L)
        val manuals = parseManualsArray(json.optJSONArray("manuals"))
        return ManifestCatalog(version = version, manuals = manuals)
    }

    private fun parseManualsArray(array: JSONArray?): List<PackageManager.ManualMeta> {
        if (array == null) return emptyList()

        val out = ArrayList<PackageManager.ManualMeta>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue

            val remotePath = obj.optString("remotePath").trim()
                .ifBlank { obj.optString("path").trim() }
            if (remotePath.isBlank()) continue

            val title = obj.optString("title").trim()
                .ifBlank { remotePath.substringAfterLast('/').substringBeforeLast('.') }

            val localRelativePath = obj.optString("localRelativePath").trim()
                .ifBlank { remotePath }

            val explicitId = obj.optString("id").trim().ifBlank { null }

            out += if (explicitId != null) {
                PackageManager.ManualMeta(
                    title = title,
                    remotePath = remotePath,
                    id = explicitId,
                    localRelativePath = localRelativePath
                )
            } else {
                PackageManager.ManualMeta(
                    title = title,
                    remotePath = remotePath,
                    localRelativePath = localRelativePath
                )
            }
        }

        return out
            .map(::sanitize)
            .distinctBy(::dedupeKey)
    }

    private fun catalogFile(context: Context, pkg: PackageManager.Pkg): File {
        return File(rootDir(context), "${pkg.name.lowercase()}_manifest.json")
    }

    private fun rootDir(context: Context): File {
        return File(context.filesDir, ROOT_DIR)
    }

    private fun dedupeKey(meta: PackageManager.ManualMeta): String {
        return meta.remotePath.trim().lowercase()
    }

    private fun sanitize(meta: PackageManager.ManualMeta): PackageManager.ManualMeta {
        val remote = meta.remotePath.replace('\\', '/').trim().trimStart('/')
        val local = meta.localRelativePath.replace('\\', '/').trim().trimStart('/').ifBlank { remote }
        val title = meta.title.trim().ifBlank { remote.substringAfterLast('/').substringBeforeLast('.') }
        return if (meta.id.isBlank()) {
            PackageManager.ManualMeta(title = title, remotePath = remote, localRelativePath = local)
        } else {
            PackageManager.ManualMeta(title = title, remotePath = remote, id = meta.id, localRelativePath = local)
        }
    }
}