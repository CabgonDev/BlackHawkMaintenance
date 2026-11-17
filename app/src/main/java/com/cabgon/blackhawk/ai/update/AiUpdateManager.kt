package com.cabgon.blackhawk.ai.update

import android.content.Context
import android.util.Log
import com.cabgon.blackhawk.util.DownloadedFileCommit
import com.cabgon.blackhawk.ai.await
import com.cabgon.blackhawk.data.PackageManager
import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import com.google.firebase.storage.storage
import com.google.firebase.storage.FileDownloadTask
import com.google.firebase.storage.OnProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object AiUpdateManager {

    private const val TAG = "AiUpdateManager"

    // SharedPreferences
    private const val SP_FILE = "ai_ota_prefs"
    private const val KEY_IADS_VERSION = "rag_iads_version"
    private const val KEY_SIKORSKY_VERSION = "rag_sikorsky_version"
    private const val KEY_IADS_SHA256 = "rag_iads_sha256"
    private const val KEY_SIKORSKY_SHA256 = "rag_sikorsky_sha256"

    // Telemetría (timestamps)
    private const val KEY_LAST_UPDATE_AT_IADS = "last_update_at_iads"
    private const val KEY_LAST_UPDATE_AT_SIKORSKY = "last_update_at_sikorsky"

    // Remote Config keys
    private const val RC_OTA_ENABLED = "ota_enabled"
    private const val RC_OTA_FORCE = "ota_force"
    private const val RC_CHANNEL_DEFAULT = "ai_channel_default"

    sealed class Event {
        data object Checking : Event()
        data class ChannelSelected(val channel: String) : Event()
        data class Downloading(
            val what: String,
            val progress01: Float = 0f,
            val bytesTransferred: Long = 0L,
            val totalBytes: Long = -1L
        ) : Event()
        data class Verifying(val what: String) : Event()
        data class Applied(val what: String) : Event()
        data class UpToDate(val what: String) : Event()
        data class Skipped(val reason: String) : Event()
        data class Error(val what: String, val message: String) : Event()
    }

    data class Result(
        val updatedIndex: Boolean,
        val forceBlocked: Boolean,
        val channelUsed: String,
        val events: List<Event>
    )

    /**
     * Estimates the bytes still required before starting OTA downloads.
     * This is used only to warn about cellular-data consumption.
     */
    suspend fun estimatePendingDownloadBytes(
        context: Context,
        pkg: PackageManager.Pkg
    ): Long? = withContext(Dispatchers.IO) {
        val rc = initRemoteConfig()
        try { rc.fetchAndActivate().await() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return@withContext null }

        if (!rc.getBoolean(RC_OTA_ENABLED)) return@withContext 0L

        val channel = rc.getString(RC_CHANNEL_DEFAULT).trim().ifBlank { "stable" }
        val spec = AiOtaChannelRepository.loadChannelSpec(channel) ?: return@withContext null
        val artifact = when (pkg) {
            PackageManager.Pkg.IADS -> spec.indexes?.iads
            PackageManager.Pkg.SIKORSKY -> spec.indexes?.sikorsky
        } ?: return@withContext 0L

        val outFile = ragIndexFile(context, pkg)
        val expectedBytes = artifact.bytes?.takeIf { it > 0L }
        val expectedSha = artifact.sha256?.trim()?.takeIf { it.isNotEmpty() }
        val prefs = sp(context)
        val localVersion = prefs.getLong(
            if (pkg == PackageManager.Pkg.IADS) KEY_IADS_VERSION else KEY_SIKORSKY_VERSION,
            0L
        )

        val current = outFile.isFile && outFile.length() > 0L &&
                (expectedBytes == null || outFile.length() == expectedBytes) &&
                if (expectedSha != null) {
                    runCatching { sha256(outFile) }
                        .getOrNull()?.equals(expectedSha, ignoreCase = true) == true
                } else {
                    artifact.version <= localVersion
                }

        if (current) 0L else expectedBytes
    }

    suspend fun checkAndUpdateForPackage(
        context: Context,
        pkg: PackageManager.Pkg?,
        emit: ((Event) -> Unit)? = null
    ): Result = withContext(Dispatchers.IO) {

        val events = mutableListOf<Event>()
        fun push(e: Event) {
            if (e !is Event.Downloading) events += e
            emit?.invoke(e)
        }

        push(Event.Checking)

        // 1) Remote Config (flags + canal)
        val rc = initRemoteConfig()
        try { rc.fetchAndActivate().await() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w(TAG, "RemoteConfig fetch failed: ${e.message}") }
        currentCoroutineContext().ensureActive()

        val otaEnabled = rc.getBoolean(RC_OTA_ENABLED)
        val force = rc.getBoolean(RC_OTA_FORCE)
        val channel = rc.getString(RC_CHANNEL_DEFAULT).trim().ifBlank { "stable" }
        push(Event.ChannelSelected(channel))
        Log.d(TAG, "RC: ota_enabled=$otaEnabled ota_force=$force channel=$channel")

        if (!otaEnabled) {
            push(Event.Skipped("OTA deshabilitado por Remote Config."))
            return@withContext Result(
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
                updatedIndex = false,
                forceBlocked = force,
                channelUsed = channel,
                events = events
            )
        }

        Log.d(TAG, "FS: channel=$channel minAppVersionCode=${spec.minAppVersionCode} hasIndexes=${spec.indexes != null}")

        // Informativo
        spec.minAppVersionCode?.let { minVc ->
            Log.d(TAG, "Channel '$channel' minAppVersionCode=$minVc")
        }

        var updatedIndex = false
        var forceBlocked = false

        val sp = sp(context)

        // 3) ÍNDICE RAG usado por Consultas IA.
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
                val localShaKey = if (pkg == PackageManager.Pkg.IADS) KEY_IADS_SHA256 else KEY_SIKORSKY_SHA256
                val lastKey = if (pkg == PackageManager.Pkg.IADS) KEY_LAST_UPDATE_AT_IADS else KEY_LAST_UPDATE_AT_SIKORSKY

                val localVer = sp.getLong(localKey, 0L)
                val localSha = sp.getString(localShaKey, null)
                val outFile = ragIndexFile(context, pkg)
                val expectedSha = artifact.sha256?.trim()?.takeIf { it.isNotEmpty() }
                val expectedBytes = artifact.bytes?.takeIf { it > 0L }
                val fileHasExpectedSize = outFile.isFile && outFile.length() > 0L &&
                        (expectedBytes == null || outFile.length() == expectedBytes)
                val localShaMatches = if (!fileHasExpectedSize || expectedSha == null) {
                    false
                } else if (localSha.equals(expectedSha, ignoreCase = true)) {
                    true
                } else {
                    runCatching { sha256(outFile).equals(expectedSha, ignoreCase = true) }
                        .getOrDefault(false)
                }
                val upToDateReason = when {
                    localShaMatches -> "verified local SHA-256"
                    expectedSha == null && fileHasExpectedSize && artifact.version <= localVer ->
                        "saved version and size"
                    else -> null
                }

                Log.d(
                    TAG,
                    "INDEX(${pkg.name}): localVer=$localVer remoteVer=${artifact.version} path=${artifact.storagePath} bytes=${artifact.bytes} sha=${shaShort(artifact.sha256)} localSha=${shaShort(localSha)} outFile=${fileInfo(outFile)}"
                )

                if (upToDateReason != null) {
                    sp.edit()
                        .putLong(localKey, maxOf(localVer, artifact.version))
                        .putString(localShaKey, expectedSha)
                        .commit()
                    Log.d(TAG, "INDEX(${pkg.name}): skip download reason=$upToDateReason")
                    push(Event.UpToDate("Índice RAG (${pkg.name})"))
                } else {
                    Log.d(TAG, "INDEX(${pkg.name}): download required reason=${when {
                        !outFile.isFile -> "file missing"
                        expectedBytes != null && outFile.length() != expectedBytes -> "size mismatch"
                        expectedSha != null -> "SHA-256 mismatch or newer content"
                        artifact.version > localVer -> "remote version is newer"
                        else -> "local artifact not verified"
                    }}")
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
                            .putString(localShaKey, expectedSha)
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
            RC_CHANNEL_DEFAULT to "stable"
        )
        rc.setDefaultsAsync(defaults)

        return rc
    }

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)

    private fun ragIndexFile(ctx: Context, pkg: PackageManager.Pkg): File {
        val fileName = PackageManager.indexAssetPath(pkg).substringAfterLast("/")
        return File(ctx.filesDir, fileName).also(DownloadedFileCommit::recover)
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

        val parent = outFile.parentFile ?: context.filesDir
        parent.mkdirs()
        val tmp = File.createTempFile(outFile.name + ".", ".part", parent)

        try {
            runCatching {
                if (tmp.exists()) tmp.delete()

                ref.getFile(tmp).awaitWithProgress(onCanceled = { tmp.delete() }) { transferred, total ->
                    val progress = if (total > 0L) {
                        (transferred.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    push(Event.Downloading(what, progress, transferred, total))
                }

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

                currentCoroutineContext().ensureActive()
                DownloadedFileCommit.commit(tmp, outFile)

                outFile.exists() && outFile.length() >= minBytes
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "downloadStorageObject failed for $what: ${error.message}")
                if (tmp.exists()) tmp.delete()
                false
            }
        } finally {
            if (tmp.exists()) tmp.delete()
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

    // Helpers de trazabilidad
    private fun shaShort(s: String?): String = s?.take(8)?.plus("…") ?: "null"
    private fun fileInfo(f: File): String =
        "exists=${f.exists()} size=${if (f.exists()) f.length() else 0} path=${f.absolutePath}"
}

private suspend fun FileDownloadTask.awaitWithProgress(
    onCanceled: () -> Unit = {},
    onProgress: (transferred: Long, total: Long) -> Unit
): FileDownloadTask.TaskSnapshot =
    suspendCancellableCoroutine { cont ->
        val progress = OnProgressListener<FileDownloadTask.TaskSnapshot> { snapshot ->
            if (cont.isActive) onProgress(snapshot.bytesTransferred, snapshot.totalByteCount)
        }
        addOnProgressListener(progress)
        addOnSuccessListener { snapshot ->
            removeOnProgressListener(progress)
            if (cont.isActive) cont.resume(snapshot)
        }
        addOnFailureListener { error ->
            removeOnProgressListener(progress)
            if (cont.isActive) cont.resumeWithException(error)
        }
        addOnCanceledListener {
            removeOnProgressListener(progress)
            onCanceled()
            if (cont.isActive) cont.cancel(CancellationException("Descarga cancelada"))
        }

        cont.invokeOnCancellation {
            runCatching { removeOnProgressListener(progress) }
            runCatching { cancel() }
        }
    }
