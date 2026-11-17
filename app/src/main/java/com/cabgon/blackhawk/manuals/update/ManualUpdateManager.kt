package com.cabgon.blackhawk.manuals.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cabgon.blackhawk.util.DownloadedFileCommit
import com.cabgon.blackhawk.ai.await
import com.cabgon.blackhawk.data.PackageManager
import com.cabgon.blackhawk.util.ManualCache
import com.google.firebase.storage.FileDownloadTask
import com.google.firebase.storage.OnProgressListener
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ManualUpdateManager {

    private const val TAG = "MANUAL_UPDATE"

    private fun manifestPathFor(pkg: PackageManager.Pkg): String {
        return when (pkg) {
            PackageManager.Pkg.IADS -> "iads/manuals/manifest.json"
            PackageManager.Pkg.SIKORSKY -> "sikorsky/manuals/manifest.json"
        }
    }

    private const val MANIFEST_MAX_BYTES = 50_000L

    private const val PREFS_MANIFEST = "manual_manifest_prefs"
    private fun keyManifestVersion(pkg: PackageManager.Pkg) = "manifest_version_${pkg.name}"

    private fun getSavedManifestVersion(context: Context, pkg: PackageManager.Pkg): Long {
        return context.getSharedPreferences(PREFS_MANIFEST, Context.MODE_PRIVATE)
            .getLong(keyManifestVersion(pkg), -1L)
    }

    private fun setSavedManifestVersion(context: Context, pkg: PackageManager.Pkg, version: Long) {
        context.getSharedPreferences(PREFS_MANIFEST, Context.MODE_PRIVATE)
            .edit()
            .putLong(keyManifestVersion(pkg), version)
            .apply()
    }

    sealed class Event {
        data object Checking : Event()
        data class Downloading(
            val what: String,
            val progress01: Float,
            val itemIndex: Int = 1,
            val itemCount: Int = 1,
            val bytesTransferred: Long = 0L,
            val totalBytes: Long = -1L
        ) : Event()
        data class UpToDate(val what: String, val itemIndex: Int = 1, val itemCount: Int = 1) : Event()
        data class Applied(val what: String, val itemIndex: Int = 1, val itemCount: Int = 1) : Event()
        data class Error(val what: String, val message: String) : Event()
        data class Info(val message: String) : Event()
    }

    data class Result(
        val forceBlocked: Boolean,
        val downloadedCount: Int,
        val checkedCount: Int,
        val hasErrors: Boolean = false
    )

    /**
     * Estimates pending manual bytes before any file download starts.
     * Metadata is read from Storage so the cellular-data warning is meaningful.
     */
    suspend fun estimatePendingDownloadBytes(
        context: Context,
        pkg: PackageManager.Pkg
    ): Long? = withContext(Dispatchers.IO) {
        if (!hasInternet(context)) return@withContext null

        val storage = FirebaseStorage.getInstance().reference
        val remoteManifest = try {
            storage.child(manifestPathFor(pkg))
                .getBytes(MANIFEST_MAX_BYTES)
                .await()
                .let(ManualCatalogStore::parseManifest)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return@withContext null }

        val remoteVersion = remoteManifest.version.takeIf { it > 0L }
        val manuals = (remoteManifest.manuals + PackageManager.manuals(pkg))
            .distinctBy { it.remotePath.replace('\\', '/').lowercase() }

        if (remoteVersion != null && remoteVersion == getSavedManifestVersion(context, pkg)) {
            val allCached = manuals.isNotEmpty() && manuals.all { manual ->
                ManualCache.getLocalIfExists(
                    context,
                    manual.id,
                    manual.localRelativePath
                ) != null
            }
            if (allCached) return@withContext 0L
        }

        var pendingBytes = 0L
        for (manual in manuals) {
            currentCoroutineContext().ensureActive()
            try {
                val ref = storage.child(manual.remotePath)
                val meta = ref.metadata.await()
                val local = ManualCache.getLocalIfExists(
                    context,
                    manual.id,
                    manual.localRelativePath
                )
                val savedUpdated = ManualCache.getSavedUpdatedTime(context, manual.id)
                val savedSize = ManualCache.getSavedSize(context, manual.id)
                val needsDownload = local == null ||
                        savedUpdated <= 0L ||
                        meta.updatedTimeMillis > savedUpdated ||
                        (meta.sizeBytes > 0L && savedSize > 0L && meta.sizeBytes != savedSize)
                if (needsDownload) {
                    if (meta.sizeBytes <= 0L) return@withContext null
                    pendingBytes += meta.sizeBytes
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return@withContext null }
        }
        pendingBytes
    }

    /**
     * Flujo nuevo:
     * - Lee manifest por paquete.
     * - Si trae versión nueva, guarda el catálogo OTA local.
     * - Descarga usando la lista del manifest; si no trae lista, fallback al catálogo estático.
     * - Si el manifest no se puede leer, cae a legacy 1x1 con fallback del APK.
     */
    suspend fun checkAndUpdateForPackage(
        context: Context,
        pkg: PackageManager.Pkg,
        emit: (Event) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {

        if (!hasInternet(context)) {
            Log.w(TAG, "SKIP manuals update: no internet (pkg=$pkg)")
            return@withContext Result(forceBlocked = false, downloadedCount = 0, checkedCount = 0, hasErrors = true)
        }

        val fallbackManuals = ManualCatalogStore.resolveManuals(context, pkg)
        val storage = FirebaseStorage.getInstance().reference

        val manifestPath = manifestPathFor(pkg)
        val manifestRef = storage.child(manifestPath)

        val remoteManifest = runCatching {
            emit(Event.Checking)
            Log.d(TAG, "MANIFEST fetch start pkg=$pkg path='$manifestPath'")
            val bytes = manifestRef.getBytes(MANIFEST_MAX_BYTES).await()
            ManualCatalogStore.parseManifest(bytes)
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "MANIFEST read failed (fallback legacy). Reason=${e.localizedMessage}")
        }.getOrNull()

        var shouldSaveManifestVersion: Long? = null
        var manualsForUpdate = fallbackManuals

        if (remoteManifest != null) {
            val remoteVersion = remoteManifest.version.takeIf { it > 0L }
            val remoteManuals = remoteManifest.manuals

            if (remoteManuals.isNotEmpty()) {
                manualsForUpdate = remoteManuals
            }

            if (remoteVersion != null) {
                val savedVersion = getSavedManifestVersion(context, pkg)

                if (remoteVersion == savedVersion) {
                    val cachedManuals = ManualCatalogStore.resolveManuals(context, pkg)
                    val allCached = cachedManuals.isNotEmpty() && cachedManuals.all { manual ->
                        ManualCache.getLocalIfExists(
                            context,
                            manual.id,
                            manual.localRelativePath
                        ) != null
                    }
                    if (allCached) {
                        Log.d(TAG, "MANIFEST same version=$remoteVersion -> SKIP per-manual checks pkg=$pkg")
                        return@withContext Result(forceBlocked = false, downloadedCount = 0, checkedCount = 0)
                    }
                    Log.w(TAG, "MANIFEST same version=$remoteVersion but local manuals are incomplete; retrying pkg=$pkg")
                }

                Log.d(TAG, "MANIFEST changed local=$savedVersion remote=$remoteVersion -> update available pkg=$pkg")
                emit(Event.Info("Actualización disponible"))
                emit(Event.Info("Actualizando…"))
                shouldSaveManifestVersion = remoteVersion
            } else {
                emit(Event.Info("Comprobando manuales…"))
            }

            if (remoteManuals.isNotEmpty()) {
                ManualCatalogStore.saveCatalog(
                    context = context,
                    pkg = pkg,
                    version = remoteVersion ?: getSavedManifestVersion(context, pkg),
                    manuals = remoteManuals
                )
                manualsForUpdate = ManualCatalogStore.resolveManuals(context, pkg)
            }
        } else {
            emit(Event.Info("Comprobando manuales…"))
        }

        val manualsResolved = when {
            manualsForUpdate.isNotEmpty() -> manualsForUpdate
            else -> ManualCatalogStore.resolveManuals(context, pkg)
        }

        if (manualsResolved.isEmpty()) {
            return@withContext Result(forceBlocked = false, downloadedCount = 0, checkedCount = 0)
        }

        val legacyRes = legacyCheckAndUpdate(context, pkg, manualsResolved, emit)

        if (!legacyRes.hasErrors) shouldSaveManifestVersion?.let { v ->
            setSavedManifestVersion(context, pkg, v)
            Log.d(TAG, "MANIFEST saved new version=$v pkg=$pkg")
        }

        legacyRes
    }

    private suspend fun legacyCheckAndUpdate(
        context: Context,
        pkg: PackageManager.Pkg,
        manuals: List<PackageManager.ManualMeta>,
        emit: (Event) -> Unit
    ): Result {
        val storage = FirebaseStorage.getInstance().reference

        var downloaded = 0
        var checked = 0
        var hasErrors = false

        Log.d(TAG, "LEGACY checkAndUpdate pkg=$pkg manuals=${manuals.size}")

        for ((index, m) in manuals.withIndex()) {
            currentCoroutineContext().ensureActive()
            checked++
            val itemIndex = index + 1
            val itemCount = manuals.size

            val id = m.id
            val remotePath = m.remotePath
            val localRel = m.localRelativePath
            val title = m.title

            try {
                emit(Event.Checking)

                val ref = storage.child(remotePath)
                val meta = ref.metadata.await()

                val local = ManualCache.getLocalIfExists(context, id, localRel)
                val savedUpdated = ManualCache.getSavedUpdatedTime(context, id)
                val savedSize = ManualCache.getSavedSize(context, id)

                val remoteUpdated = meta.updatedTimeMillis
                val remoteSize = meta.sizeBytes

                val needsDownload =
                    local == null ||
                            savedUpdated <= 0L ||
                            remoteUpdated > savedUpdated ||
                            (remoteSize > 0 && savedSize > 0 && remoteSize != savedSize)

                if (!needsDownload) {
                    emit(Event.UpToDate(title, itemIndex, itemCount))
                    continue
                }

                val outFile = ManualCache.localFileFor(context, id, localRel)
                val tempFile = prepareTempFile(outFile)
                try {
                    ref.getFile(tempFile).awaitWithProgress(onCanceled = { tempFile.delete() }) { tr, tot ->
                        val p = if (tot > 0L) (tr.toFloat() / tot.toFloat()).coerceIn(0f, 1f) else 0f
                        emit(
                            Event.Downloading(
                                what = title,
                                progress01 = p,
                                itemIndex = itemIndex,
                                itemCount = itemCount,
                                bytesTransferred = tr,
                                totalBytes = tot
                            )
                        )
                    }

                    currentCoroutineContext().ensureActive()
                    commitDownloadedFile(tempFile, outFile)
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }

                ManualCache.setSavedUpdatedTime(context, id, remoteUpdated)
                ManualCache.setSavedSize(context, id, remoteSize.takeIf { it > 0 } ?: outFile.length())

                downloaded++
                emit(Event.Applied(title, itemIndex, itemCount))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                hasErrors = true
                Log.e(TAG, "LEGACY ERROR title='$title' id=$id remotePath='$remotePath' localRel='$localRel'", e)
                emit(Event.Error(title, e.localizedMessage ?: e.toString()))
            }
        }

        Log.d(TAG, "LEGACY DONE pkg=$pkg checked=$checked downloaded=$downloaded")
        return Result(
            forceBlocked = false,
            downloadedCount = downloaded,
            checkedCount = checked,
            hasErrors = hasErrors
        )
    }

    suspend fun ensureManualCached(
        context: Context,
        manualId: String,
        remotePath: String,
        localRelativePath: String,
        titleForUi: String? = null,
        emit: (Event) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val callerJob = currentCoroutineContext()[Job]
        val mainHandler = Handler(Looper.getMainLooper())
        fun emitOnMain(event: Event) {
            mainHandler.post { if (callerJob?.isActive != false) emit(event) }
        }
        ManualCache.getLocalIfExists(context, manualId, localRelativePath)?.let {
            Log.d(TAG, "ensureManualCached HIT title='${titleForUi ?: manualId}' id=$manualId file='${it.absolutePath}' size=${it.length()}")
            return@withContext it
        }

        if (!hasInternet(context)) {
            val msg = "Sin internet y el manual no está en cache."
            Log.w(TAG, "ensureManualCached SKIP id=$manualId remotePath='$remotePath' -> $msg")
            throw IllegalStateException(msg)
        }

        val ref = FirebaseStorage.getInstance().reference.child(remotePath)

        emitOnMain(Event.Checking)

        val meta = ref.metadata.await()

        val outFile = ManualCache.localFileFor(context, manualId, localRelativePath)
        val tempFile = prepareTempFile(outFile)
        try {
            ref.getFile(tempFile).awaitWithProgress(onCanceled = { tempFile.delete() }) { tr, tot ->
                val p = if (tot > 0L) (tr.toFloat() / tot.toFloat()).coerceIn(0f, 1f) else 0f
                emitOnMain(
                    Event.Downloading(
                        what = titleForUi ?: manualId,
                        progress01 = p,
                        bytesTransferred = tr,
                        totalBytes = tot
                    )
                )
            }

            currentCoroutineContext().ensureActive()
            commitDownloadedFile(tempFile, outFile)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }

        ManualCache.setSavedUpdatedTime(context, manualId, meta.updatedTimeMillis)
        ManualCache.setSavedSize(context, manualId, meta.sizeBytes.takeIf { it > 0 } ?: outFile.length())

        emitOnMain(Event.Applied(titleForUi ?: manualId))
        outFile
    }

    private fun prepareTempFile(finalFile: File): File {
        finalFile.parentFile?.mkdirs()
        return File.createTempFile(finalFile.name + ".", ".part", finalFile.parentFile)
    }

    private fun commitDownloadedFile(tempFile: File, finalFile: File) {
        if (!tempFile.exists() || tempFile.length() <= 0L) {
            throw IllegalStateException("La descarga terminó sin contenido")
        }

        DownloadedFileCommit.commit(tempFile, finalFile)
    }

    private fun hasInternet(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            true
        }
    }
}

private suspend fun FileDownloadTask.awaitWithProgress(
    onCanceled: () -> Unit = {},
    onProgress: (transferred: Long, total: Long) -> Unit
): FileDownloadTask.TaskSnapshot = suspendCancellableCoroutine { cont ->
    val prog = OnProgressListener<FileDownloadTask.TaskSnapshot> { snap ->
        if (cont.isActive) onProgress(snap.bytesTransferred, snap.totalByteCount)
    }
    addOnProgressListener(prog)
    addOnSuccessListener { snap ->
        removeOnProgressListener(prog)
        if (cont.isActive) cont.resume(snap)
    }

    addOnFailureListener { e ->
        removeOnProgressListener(prog)
        Log.e("MANUAL_UPDATE", "DOWNLOAD FAIL (FileDownloadTask)", e)
        if (cont.isActive) cont.resumeWithException(e)
    }
    addOnCanceledListener {
        removeOnProgressListener(prog)
        onCanceled()
        if (cont.isActive) cont.cancel(CancellationException("Descarga cancelada"))
    }

    cont.invokeOnCancellation {
        runCatching { removeOnProgressListener(prog) }
        runCatching { cancel() }
    }
}
