package com.cabgon.blackhawk.ai.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.cabgon.blackhawk.ai.await
import com.cabgon.blackhawk.data.PackageManager
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object AiUpdateManager {

    private const val TAG = "AiUpdateManager"

    // SharedPreferences
    private const val SP_FILE = "ai_ota_prefs"
    private const val KEY_MODEL_VERSION = "model_version"
    private const val KEY_IADS_VERSION = "rag_iads_version"
    private const val KEY_SIKORSKY_VERSION = "rag_sikorsky_version"

    // Telemetría (timestamps)
    private const val KEY_LAST_UPDATE_AT_MODEL = "last_update_at_model"
    private const val KEY_LAST_UPDATE_AT_IADS = "last_update_at_iads"
    private const val KEY_LAST_UPDATE_AT_SIKORSKY = "last_update_at_sikorsky"

    // Remote Config keys
    private const val RC_OTA_ENABLED = "ota_enabled"
    private const val RC_OTA_FORCE = "ota_force"
    private const val RC_CHANNEL_DEFAULT = "ai_channel_default"
    private const val RC_ALLOW_MODEL_OVER_CELL = "ai_allow_model_download_over_cellular"

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
        val updatedModel: Boolean,
        val updatedIndex: Boolean,
        val forceBlocked: Boolean,
        val channelUsed: String,
        val events: List<Event>
    )

    suspend fun checkAndUpdateForPackage(
        context: Context,
        pkg: PackageManager.Pkg?,
        allowModelDownload: Boolean = true,
        emit: ((Event) -> Unit)? = null
    ): Result = withContext(Dispatchers.IO) {

        val events = mutableListOf<Event>()
        fun push(e: Event) {
            events += e
            emit?.invoke(e)
        }

        push(Event.Checking)

        // 1) Remote Config (flags + canal)
        val rc = initRemoteConfig()
        runCatching { rc.fetchAndActivate().await() }
            .onFailure { Log.w(TAG, "RemoteConfig fetch failed: ${it.message}") }

        val otaEnabled = rc.getBoolean(RC_OTA_ENABLED)
        val force = rc.getBoolean(RC_OTA_FORCE)
        val channel = rc.getString(RC_CHANNEL_DEFAULT).trim().ifBlank { "stable" }
        val allowModelOverCell = rc.getBoolean(RC_ALLOW_MODEL_OVER_CELL)

        push(Event.ChannelSelected(channel))
        Log.d(TAG, "RC: ota_enabled=$otaEnabled ota_force=$force channel=$channel allowModelOverCell=$allowModelOverCell")

        if (!otaEnabled) {
            push(Event.Skipped("OTA deshabilitado por Remote Config."))
            return@withContext Result(
                updatedModel = false,
                updatedIndex = false,
                forceBlocked = false,
                channelUsed = channel,
                events = events
            )
        }

        // 2) Firestore channels (source of truth)
        val spec = AiOtaChannelRepository.loadChannelSpec(channel)
        if (spec == null) {
            push(Event.Error("OTA", "No se pudo leer el canal '$channel' en Firestore (ai_ota/channels)."))
            return@withContext Result(
                updatedModel = false,
                updatedIndex = false,
                forceBlocked = force,
                channelUsed = channel,
                events = events
            )
        }

        Log.d(TAG, "FS: channel=$channel minAppVersionCode=${spec.minAppVersionCode} hasModel=${spec.model != null} hasIndexes=${spec.indexes != null}")

        // Informativo
        spec.minAppVersionCode?.let { minVc ->
            Log.d(TAG, "Channel '$channel' minAppVersionCode=$minVc")
        }

        var updatedModel = false
        var updatedIndex = false
        var forceBlocked = false

        val sp = sp(context)

        // 3) MODELO (solo si rol lo permite)
        if (!allowModelDownload) {
            push(Event.Skipped("Modelo: descarga bloqueada por rol (requiere Admin/Developer)."))
        } else {
            spec.model?.let { model ->
                val onMetered = isMetered(context)
                if (onMetered && !allowModelOverCell) {
                    push(Event.Skipped("Modelo: descargas por datos móviles deshabilitadas (ai_allow_model_download_over_cellular=false)."))
                } else {
                    val localVer = sp.getLong(KEY_MODEL_VERSION, 0L)
                    val localFile = modelFile(context)

                    Log.d(
                        TAG,
                        "MODEL: localVer=$localVer remoteVer=${model.version} path=${model.storagePath} bytes=${model.bytes} sha=${shaShort(model.sha256)} localFile=${fileInfo(localFile)}"
                    )

                    if (model.version <= localVer) {
                        push(Event.UpToDate("Modelo IA"))
                    } else {
                        push(Event.Downloading("Modelo IA"))
                        val ok = downloadStorageObject(
                            context = context,
                            storagePath = model.storagePath,
                            outFile = localFile,
                            minBytes = model.bytes ?: 10_000_000L,
                            sha256 = model.sha256,
                            push = ::push,
                            what = "Modelo IA"
                        )

                        if (ok) {
                            sp.edit()
                                .putLong(KEY_MODEL_VERSION, model.version)
                                .putLong(KEY_LAST_UPDATE_AT_MODEL, System.currentTimeMillis())
                                .apply()

                            updatedModel = true
                            push(Event.Applied("Modelo IA"))
                            Log.d(TAG, "MODEL: applied remoteVer=${model.version} localFile=${fileInfo(modelFile(context))}")
                        } else {
                            push(Event.Error("Modelo IA", "Descarga/verificación falló"))
                            Log.w(TAG, "MODEL: failed remoteVer=${model.version} localFile=${fileInfo(modelFile(context))}")
                            if (force) forceBlocked = true
                        }
                    }
                }
            } ?: run {
                push(Event.Skipped("Modelo: no configurado en Firestore para canal '$channel'."))
            }
        }

        // 4) ÍNDICE RAG (User/Moderator/Admin/Developer lo descargan si hay update)
        if (pkg == null) {
            push(Event.Skipped("Paquete no definido; se omite actualización del índice RAG."))
        } else {
            val artifact = when (pkg) {
                PackageManager.Pkg.IADS -> spec.indexes?.iads
                PackageManager.Pkg.SIKORSKY -> spec.indexes?.sikorsky
            }

            if (artifact == null) {
                push(Event.Skipped("Índice RAG (${pkg.name}) no configurado en Firestore para canal '$channel'."))
            } else {
                val localKey = if (pkg == PackageManager.Pkg.IADS) KEY_IADS_VERSION else KEY_SIKORSKY_VERSION
                val lastKey = if (pkg == PackageManager.Pkg.IADS) KEY_LAST_UPDATE_AT_IADS else KEY_LAST_UPDATE_AT_SIKORSKY

                val localVer = sp.getLong(localKey, 0L)
                val outFile = ragIndexFile(context, pkg)

                Log.d(
                    TAG,
                    "INDEX(${pkg.name}): localVer=$localVer remoteVer=${artifact.version} path=${artifact.storagePath} bytes=${artifact.bytes} sha=${shaShort(artifact.sha256)} outFile=${fileInfo(outFile)}"
                )

                if (artifact.version <= localVer) {
                    push(Event.UpToDate("Índice RAG (${pkg.name})"))
                } else {
                    push(Event.Downloading("Índice RAG (${pkg.name})"))
                    val ok = downloadStorageObject(
                        context = context,
                        storagePath = artifact.storagePath,
                        outFile = outFile,
                        minBytes = artifact.bytes ?: 500_000L,
                        sha256 = artifact.sha256,
                        push = ::push,
                        what = "Índice RAG (${pkg.name})"
                    )

                    if (ok) {
                        sp.edit()
                            .putLong(localKey, artifact.version)
                            .putLong(lastKey, System.currentTimeMillis())
                            .apply()

                        updatedIndex = true
                        push(Event.Applied("Índice RAG (${pkg.name})"))
                        Log.d(TAG, "INDEX(${pkg.name}): applied remoteVer=${artifact.version} outFile=${fileInfo(ragIndexFile(context, pkg))}")
                    } else {
                        push(Event.Error("Índice RAG (${pkg.name})", "Descarga/verificación falló"))
                        Log.w(TAG, "INDEX(${pkg.name}): failed remoteVer=${artifact.version} outFile=${fileInfo(outFile)}")
                        if (force) forceBlocked = true
                    }
                }
            }
        }

        Result(
            updatedModel = updatedModel,
            updatedIndex = updatedIndex,
            forceBlocked = forceBlocked,
            channelUsed = channel,
            events = events
        )
    }

    private fun initRemoteConfig(): FirebaseRemoteConfig {
        val rc = Firebase.remoteConfig
        val settings = remoteConfigSettings { minimumFetchIntervalInSeconds = 60L }
        rc.setConfigSettingsAsync(settings)

        val defaults: Map<String, Any> = mapOf(
            RC_OTA_ENABLED to true,
            RC_OTA_FORCE to false,
            RC_CHANNEL_DEFAULT to "stable",
            RC_ALLOW_MODEL_OVER_CELL to false
        )
        rc.setDefaultsAsync(defaults)

        return rc
    }

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)

    private fun modelFile(ctx: Context): File =
        File(File(ctx.filesDir, "models").apply { mkdirs() }, "model.gguf")

    private fun ragIndexFile(ctx: Context, pkg: PackageManager.Pkg): File {
        val fileName = PackageManager.indexAssetPath(pkg).substringAfterLast("/")
        return File(ctx.filesDir, fileName)
    }

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

    private fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val hasEth = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return !(hasWifi || hasEth)
    }

    // Helpers de trazabilidad
    private fun shaShort(s: String?): String = s?.take(8)?.plus("…") ?: "null"
    private fun fileInfo(f: File): String =
        "exists=${f.exists()} size=${if (f.exists()) f.length() else 0} path=${f.absolutePath}"
}
