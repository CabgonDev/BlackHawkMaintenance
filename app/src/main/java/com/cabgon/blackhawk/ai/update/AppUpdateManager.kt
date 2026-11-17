package com.cabgon.blackhawk.ai.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import com.cabgon.blackhawk.BuildConfig
import com.cabgon.blackhawk.ai.await
import com.google.firebase.Firebase
import com.google.firebase.storage.FileDownloadTask
import com.google.firebase.storage.OnProgressListener
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.File
import java.security.MessageDigest

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"

    private const val STABLE_CHANNEL = "stable"
    private const val STABLE_APK_PREFIX = "ai/stable/apk/"

    // prefs para recordar el APK descargado
    private const val PREFS_NAME = "bhm_app_update"
    private const val KEY_LAST_APK = "last_downloaded_apk"
    private const val KEY_LAST_APK_VERSION = "last_downloaded_apk_version"
    private const val KEY_LAST_APK_SHA256 = "last_downloaded_apk_sha256"

    data class ApkSpec(
        val versionCode: Long,
        val storagePath: String,
        val sha256: String?,
        val bytes: Long?,
        val releaseNotes: String?,
    )

    data class CheckResult(
        val updateRequired: Boolean,
        val minAppVersionCode: Long?,
        val currentVersionCode: Long,
        val apkSpec: ApkSpec?
    )

    /**
     * Lee RC para canal, luego Firestore ai_ota/channels.{channel}
     * y determina si BuildConfig.VERSION_CODE < minAppVersionCode.
     *
     * El parámetro context hoy no se usa, pero lo dejamos por si mañana
     * quieres meter lógica que dependa del contexto.
     */
    suspend fun check(
        @Suppress("UNUSED_PARAMETER") context: Context
    ): CheckResult = withContext(Dispatchers.IO) {
        val rc = Firebase.remoteConfig
        runCatching { rc.fetchAndActivate().await() }
            .onFailure { Log.w(TAG, "RC fetch failed: ${it.message}") }

        // Las actualizaciones de la aplicación solo se sirven desde el canal estable.
        // El canal de IA configurado en Remote Config no puede desviar este flujo.
        val configuredChannel = rc.getString("ai_channel_default").trim()
        val channel = STABLE_CHANNEL
        if (configuredChannel.isNotBlank() && configuredChannel != STABLE_CHANNEL) {
            Log.w(TAG, "Ignoring non-stable app update channel: $configuredChannel")
        }
        val spec = AiOtaChannelRepository.loadChannelSpec(channel)
            ?: throw IllegalStateException("No se pudo validar el canal estable de actualizaciones")

        val currentVc = BuildConfig.VERSION_CODE.toLong()
        val minVc = requireNotNull(spec.minAppVersionCode) {
            "La versión mínima estable no está configurada"
        }.also { require(it > 0L) { "La versión mínima estable no es válida" } }

        val apkSpec = loadApkSpecFromChannelMap(channel)

        val required = currentVc < minVc
        if (required && apkSpec == null) {
            throw IllegalStateException("La actualización obligatoria no tiene un APK válido configurado")
        }
        if (required && apkSpec!!.versionCode < minVc) {
            throw IllegalStateException("El APK publicado no cumple la versión mínima estable")
        }

        Log.d(
            TAG,
            "check: channel=$channel currentVc=$currentVc minVc=$minVc required=$required apkSpec=${apkSpec?.storagePath}"
        )

        CheckResult(
            updateRequired = required,
            minAppVersionCode = minVc,
            currentVersionCode = currentVc,
            apkSpec = apkSpec
        )
    }

    /**
     * Descarga APK a cache, valida bytes y sha256 (si viene),
     * y abre el instalador.
     *
     * onProgress (opcional) se llama con (bytesDescargados, bytesTotales).
     * Si no se conoce el total, bytesTotales será -1L.
     *
     * Respeta cancelación de corrutina:
     * - Propaga CancellationException hacia arriba para que el servicio pueda detenerse.
     *
     * Además: guarda la ruta del APK en SharedPreferences para poder
     * relanzar el instalador desde la Activity cuando el usuario toque la notificación.
     */
    suspend fun downloadAndPromptInstall(
        context: Context,
        apk: ApkSpec,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {

        val outDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val tmp = File(outDir, "update_${apk.versionCode}.apk.tmp")
        val out = File(outDir, "update_${apk.versionCode}.apk")

        runCatching {
            if (tmp.exists()) tmp.delete()

            require(apk.storagePath.startsWith(STABLE_APK_PREFIX)) {
                "Ruta de actualización fuera del prefijo estable"
            }
            require(apk.storagePath.indexOf("..") < 0 && !apk.storagePath.contains('\\')) {
                "Ruta de actualización inválida"
            }
            require(apk.bytes != null && apk.bytes > 0L) {
                "El tamaño del APK no está configurado"
            }
            require(apk.sha256?.matches(Regex("[0-9a-fA-F]{64}")) == true) {
                "El SHA-256 del APK no está configurado correctamente"
            }

            Log.d(TAG, "Downloading APK via Firebase Storage: ${apk.storagePath}")
            val ref = Firebase.storage.reference.child(apk.storagePath)
            val task = ref.getFile(tmp)
            task.awaitWithProgress(onProgress)

            if (onProgress != null) {
                val length = tmp.length()
                onProgress(length, length)
            }

            if (!tmp.exists()) {
                Log.w(TAG, "Temp APK file does not exist after download")
                return@withContext false
            }

            val fileSize = tmp.length()
            val expectedBytes = apk.bytes
            if (fileSize != expectedBytes) {
                throw IllegalStateException("Tamaño de APK inesperado: $fileSize != $expectedBytes")
            }
            Log.d(TAG, "APK size ok: fileSize=$fileSize")

            val expectedSha = apk.sha256
            val got = sha256(tmp)
            if (!got.equals(expectedSha, ignoreCase = true)) {
                throw IllegalStateException("La suma SHA-256 del APK no coincide")
            }
            Log.d(TAG, "APK sha256 OK")

            validateApkIdentity(context, tmp, apk.versionCode)

            if (out.exists()) out.delete()
            val renamed = tmp.renameTo(out)
            if (!renamed) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }

            // Guardar ruta del APK descargado con KTX edit{}
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit {
                putString(KEY_LAST_APK, out.absolutePath)
                putLong(KEY_LAST_APK_VERSION, apk.versionCode)
                putString(KEY_LAST_APK_SHA256, expectedSha.lowercase())
            }

            withContext(Dispatchers.Main) {
                promptInstall(context, out)
            }

            true
        }.getOrElse {
            if (it is CancellationException) {
                Log.d(TAG, "downloadAndPromptInstall cancelled: ${it.message}")
                throw it
            }

            Log.w(TAG, "downloadAndPromptInstall failed: ${it.message}", it)
            if (tmp.exists()) tmp.delete()
            false
        }
    }

    /**
     * Si el usuario no tiene permitido instalar de "orígenes desconocidos"
     * para esta app, abre Settings correspondiente.
     */
    fun ensureUnknownSourcesPermission(context: Context) {
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = "package:${context.packageName}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private fun promptInstall(context: Context, apkFile: File) {
        check(context.packageManager.canRequestPackageInstalls()) {
            "Permiso para instalar aplicaciones no concedido"
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
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

    /**
     * Lee desde Firestore el mapa stable.config.* usando AiOtaChannelRepository internamente.
     *
     * Ahora también lee "appApkUrl" (opcional) para descargas externas.
     */
    private suspend fun loadApkSpecFromChannelMap(channelName: String): ApkSpec? {
        return try {
            val snap = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("ai_ota")
                .document("channels")
                .get()
                .await()

            val channelMap = snap.get(channelName) as? Map<*, *> ?: return null
            val config = channelMap["config"] as? Map<*, *> ?: return null

            val v = (config["appApkVersionCode"] as? Number)?.toLong() ?: return null
            require(v > 0L) { "La versión del APK no es válida" }

            val path = (config["appApkPath"] as? String)?.trim().orEmpty()
            require(path.startsWith(STABLE_APK_PREFIX)) {
                "El APK debe provenir del Storage estable"
            }
            require(path.indexOf("..") < 0 && !path.contains('\\')) {
                "La ruta del APK contiene segmentos inválidos"
            }
            val sha = (config["appApkSha256"] as? String)?.trim()?.ifBlank { null }
            val bytes = (config["appApkBytes"] as? Number)?.toLong()
            require(sha?.matches(Regex("[0-9a-fA-F]{64}")) == true) { "SHA-256 ausente o inválido" }
            require(bytes != null && bytes > 0L) { "Tamaño del APK ausente o inválido" }

            ApkSpec(
                versionCode = v,
                storagePath = path,
                sha256 = sha,
                bytes = bytes,
                releaseNotes = (config["releaseNotes"] as? String)?.trim()?.take(4000)?.ifBlank { null }
            )
        } catch (e: Exception) {
            Log.w(TAG, "loadApkSpecFromChannelMap failed: ${e.message}")
            null
        }
    }

    /**
     * Helper público para lanzar el instalador usando el último APK descargado
     * y guardado en prefs.
     */
    fun promptInstallFromLastDownloaded(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_LAST_APK, null) ?: return false

        val version = prefs.getLong(KEY_LAST_APK_VERSION, -1L)
        val expectedSha = prefs.getString(KEY_LAST_APK_SHA256, null)
        val file = File(path)
        if (!file.exists()) {
            Log.w(TAG, "promptInstallFromLastDownloaded: file not found at $path")
            clearPendingInstall(context)
            return false
        }

        if (!isPendingApkValid(context, file, version, expectedSha)) {
            Log.w(TAG, "promptInstallFromLastDownloaded: pending APK failed local validation")
            file.delete()
            clearPendingInstall(context)
            return false
        }

        Log.d(TAG, "promptInstallFromLastDownloaded: launching installer for $path")
        return runCatching { promptInstall(context, file) }
            .onFailure { Log.w(TAG, "Could not open pending APK installer", it) }
            .isSuccess
    }

    fun hasPendingInstall(context: Context, expectedVersionCode: Long? = null): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_LAST_APK, null) ?: return false
        val version = prefs.getLong(KEY_LAST_APK_VERSION, -1L)
        if (expectedVersionCode != null && version != expectedVersionCode) return false
        return isPendingApkValid(
            context,
            File(path),
            version,
            prefs.getString(KEY_LAST_APK_SHA256, null)
        )
    }

    fun clearPendingInstall(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_LAST_APK).remove(KEY_LAST_APK_VERSION).remove(KEY_LAST_APK_SHA256) }
    }

    private fun validateApkIdentity(context: Context, apkFile: File, expectedVersionCode: Long) {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = context.packageManager.getPackageArchiveInfo(apkFile.path, flags)
            ?: error("No se pudo leer el manifiesto del APK")
        check(archive.packageName == context.packageName) {
            "El APK no pertenece a ${context.packageName}"
        }
        check(archive.longVersionCode == expectedVersionCode) {
            "La versión del APK no coincide con sus metadatos"
        }

        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val installedCerts = signingCertificates(installed)
        val archiveCerts = signingCertificates(archive)
        check(installedCerts.intersect(archiveCerts).isNotEmpty()) {
            "El certificado del APK no coincide con la aplicación instalada"
        }
    }

    private fun isPendingApkValid(
        context: Context,
        file: File,
        version: Long,
        expectedSha: String?
    ): Boolean = runCatching {
        file.isFile && version > 0L && expectedSha?.matches(Regex("[0-9a-fA-F]{64}")) == true &&
            sha256(file).equals(expectedSha, ignoreCase = true).also {
                if (it) validateApkIdentity(context, file, version)
            }
    }.getOrDefault(false)

    private fun signingCertificates(info: PackageInfo): Set<String> {
        val signingInfo = info.signingInfo ?: error("El APK no contiene firma verificable")
        val signatures = if (signingInfo.hasPastSigningCertificates()) {
            signingInfo.signingCertificateHistory
        } else {
            signingInfo.apkContentsSigners
        }
        return signatures.map { it.toCharsString() }.toSet()
    }

    private suspend fun FileDownloadTask.awaitWithProgress(
        onProgress: ((Long, Long) -> Unit)?
    ): FileDownloadTask.TaskSnapshot = suspendCancellableCoroutine { cont ->
        val progress = OnProgressListener<FileDownloadTask.TaskSnapshot> { snapshot ->
            if (cont.isActive) onProgress?.invoke(snapshot.bytesTransferred, snapshot.totalByteCount)
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
            if (cont.isActive) cont.cancel(CancellationException("Descarga cancelada"))
        }
        cont.invokeOnCancellation {
            runCatching { removeOnProgressListener(progress) }
            runCatching { cancel() }
        }
    }
}
