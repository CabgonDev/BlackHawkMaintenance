package com.cabgon.blackhawk.content.frequencies

import android.content.Context
import android.util.Log
import com.cabgon.blackhawk.ai.await
import com.cabgon.blackhawk.content.ota.ContentOtaRepository
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object FrequenciesUpdateManager {

    private const val TAG = "FreqUpdate"

    // SharedPrefs
    private const val SP_FILE = "content_ota_prefs"
    private const val KEY_FREQ_VERSION = "freq_version"
    private const val KEY_FREQ_LAST_UPDATE_AT = "freq_last_update_at"

    // ✅ NUEVO (rollback-safe)
    private const val KEY_FREQ_SHA = "freq_sha"
    private const val KEY_FREQ_PATH = "freq_path"

    // Remote Config keys
    private const val RC_CONTENT_CHANNEL_DEFAULT = "content_channel_default"
    private const val RC_CONTENT_OTA_ENABLED = "content_ota_enabled"

    sealed class Event {
        data object Checking : Event()
        data class ChannelSelected(val channel: String) : Event()
        data class Downloading(val what: String) : Event()
        data class Verifying(val what: String) : Event()
        data class Applied(val what: String) : Event()
        data class UpToDate(val what: String) : Event()
        data class Skipped(val reason: String) : Event()
        data class Error(val what: String, val message: String) : Event()
    }

    data class Result(
        val updated: Boolean,
        val channelUsed: String,
        val events: List<Event>
    )

    suspend fun checkAndUpdate(
        context: Context,
        emit: ((Event) -> Unit)? = null
    ): Result = withContext(Dispatchers.IO) {

        val events = mutableListOf<Event>()
        fun push(e: Event) {
            events += e
            emit?.invoke(e)
        }

        push(Event.Checking)

        val rc = initRemoteConfig()
        runCatching { rc.fetchAndActivate().await() }
            .onFailure { Log.w(TAG, "RemoteConfig fetch failed: ${it.message}") }

        val enabled = rc.getBoolean(RC_CONTENT_OTA_ENABLED)
        val channel = rc.getString(RC_CONTENT_CHANNEL_DEFAULT).trim().ifBlank { "stable" }

        push(Event.ChannelSelected(channel))
        Log.d(TAG, "RC: content_ota_enabled=$enabled channel=$channel")

        if (!enabled) {
            push(Event.Skipped("Frecuencias OTA deshabilitado por Remote Config."))
            return@withContext Result(updated = false, channelUsed = channel, events = events)
        }

        val spec = ContentOtaRepository.loadFrequenciesSpec(channel)
        if (spec == null) {
            push(Event.Error("Frecuencias", "No se pudo leer spec en Firestore (content_ota/channels)."))
            return@withContext Result(updated = false, channelUsed = channel, events = events)
        }

        val sp = sp(context)
        val localVer = sp.getLong(KEY_FREQ_VERSION, 0L)
        val localSha = sp.getString(KEY_FREQ_SHA, "")?.trim().orEmpty()
        val localPath = sp.getString(KEY_FREQ_PATH, "")?.trim().orEmpty()

        val outFile = frequenciesFile(context)

        Log.d(
            TAG,
            "FREQ: localVer=$localVer remoteVer=${spec.version} path=${spec.storagePath} bytes=${spec.bytes} sha=${shaShort(spec.sha256)} " +
                    "localSha=${shaShort(localSha)} localPath=${if (localPath.isBlank()) "—" else localPath} outFile=${fileInfo(outFile)}"
        )

        // ✅ Rollback-safe:
        // UpToDate SOLO si version==, sha== (si existe), path== (si existe) y el archivo local se ve válido.
        val sameVer = (spec.version == localVer)
        val sameSha = spec.sha256.isNullOrBlank() || spec.sha256.equals(localSha, ignoreCase = true)
        val samePath = spec.storagePath.isBlank() || spec.storagePath == localPath
        val fileLooksOk = outFile.exists() && outFile.length() > 50

        if (sameVer && sameSha && samePath && fileLooksOk) {
            push(Event.UpToDate("Frecuencias"))
            return@withContext Result(updated = false, channelUsed = channel, events = events)
        }

        push(Event.Downloading("Frecuencias"))
        val ok = downloadStorageObject(
            context = context,
            storagePath = spec.storagePath,
            outFile = outFile,
            minBytes = spec.bytes ?: 50L,
            sha256 = spec.sha256,
            push = ::push,
            what = "Frecuencias"
        )

        if (ok) {
            sp.edit()
                .putLong(KEY_FREQ_VERSION, spec.version)
                .putString(KEY_FREQ_SHA, spec.sha256 ?: "")
                .putString(KEY_FREQ_PATH, spec.storagePath)
                .putLong(KEY_FREQ_LAST_UPDATE_AT, System.currentTimeMillis())
                .apply()

            push(Event.Applied("Frecuencias"))
            Log.d(TAG, "FREQ: applied remoteVer=${spec.version} outFile=${fileInfo(outFile)}")
            Result(updated = true, channelUsed = channel, events = events)
        } else {
            push(Event.Error("Frecuencias", "Descarga/verificación falló"))
            Log.w(TAG, "FREQ: failed remoteVer=${spec.version} outFile=${fileInfo(outFile)}")
            Result(updated = false, channelUsed = channel, events = events)
        }
    }

    fun localVersion(context: Context): Long =
        sp(context).getLong(KEY_FREQ_VERSION, 0L)

    fun lastUpdateAtMs(context: Context): Long =
        sp(context).getLong(KEY_FREQ_LAST_UPDATE_AT, 0L)

    fun frequenciesFile(context: Context): File {
        val dir = File(context.filesDir, "frequencies").apply { mkdirs() }
        return File(dir, "frequencies.json")
    }

    private fun initRemoteConfig() = Firebase.remoteConfig.apply {
        val settings = remoteConfigSettings { minimumFetchIntervalInSeconds = 60L }
        setConfigSettingsAsync(settings)

        val defaults: Map<String, Any> = mapOf(
            RC_CONTENT_OTA_ENABLED to true,
            RC_CONTENT_CHANNEL_DEFAULT to "stable"
        )
        setDefaultsAsync(defaults)
    }

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)

    private suspend fun downloadStorageObject(
        context: Context,
        storagePath: String,
        outFile: File,
        minBytes: Long,
        sha256: String?,
        push: (Event) -> Unit,
        what: String
    ): Boolean = withContext(Dispatchers.IO) {

        val ref = Firebase.storage.reference.child(storagePath)

        val tmp = File(outFile.parentFile ?: context.filesDir, outFile.name + ".tmp")
        tmp.parentFile?.mkdirs()

        runCatching {
            if (tmp.exists()) tmp.delete()

            ref.getFile(tmp).await()

            if (!tmp.exists() || tmp.length() < minBytes) {
                Log.w(TAG, "Downloaded file too small (${tmp.length()}) for $what; minBytes=$minBytes")
                tmp.delete()
                return@withContext false
            }

            push(Event.Verifying(what))

            if (!sha256.isNullOrBlank()) {
                val got = sha256(tmp)
                if (!got.equals(sha256, ignoreCase = true)) {
                    Log.w(TAG, "SHA256 mismatch for $what. expected=${shaShort(sha256)} got=${shaShort(got)}")
                    tmp.delete()
                    return@withContext false
                }
            }

            if (outFile.exists()) outFile.delete()
            val renamed = tmp.renameTo(outFile)
            if (!renamed) {
                tmp.copyTo(outFile, overwrite = true)
                tmp.delete()
            }

            outFile.exists() && outFile.length() >= minBytes
        }.getOrElse {
            Log.w(TAG, "downloadStorageObject failed for $what: ${it.message}")
            if (tmp.exists()) tmp.delete()
            false
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun shaShort(s: String?): String = s?.take(8)?.plus("…") ?: "null"

    private fun fileInfo(f: File): String =
        "exists=${f.exists()} size=${if (f.exists()) f.length() else 0} path=${f.absolutePath}"
}
