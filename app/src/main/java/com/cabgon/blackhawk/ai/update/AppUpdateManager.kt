package com.cabgon.blackhawk.ai.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import com.cabgon.blackhawk.BuildConfig
import com.cabgon.blackhawk.ai.await
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"

    // Cambia esto a true cuando quieras que bytes/sha sean estrictamente obligatorios.
    private const val STRICT_VALIDATION = false

    // prefs para recordar el APK descargado
    private const val PREFS_NAME = "bhm_app_update"
    private const val KEY_LAST_APK = "last_downloaded_apk"

    data class ApkSpec(
        val versionCode: Long,
        val storagePath: String,
        val sha256: String?,
        val bytes: Long?,
        val releaseNotes: String?,
        val downloadUrl: String? // URL HTTPS externa opcional
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

        val channel = rc.getString("ai_channel_default").trim().ifBlank { "stable" }
        val spec = AiOtaChannelRepository.loadChannelSpec(channel)

        val currentVc = BuildConfig.VERSION_CODE.toLong()
        val minVc = spec?.minAppVersionCode

        val apkSpec = loadApkSpecFromChannelMap(channel)

        val required = (minVc != null && currentVc < minVc)

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
     * Si apk.downloadUrl != null -> descarga por HTTP(S).
     * Si apk.downloadUrl == null -> descarga desde Firebase Storage como antes.
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

        val job: Job = currentCoroutineContext()[Job] ?: error("No Job in context")

        runCatching {
            if (tmp.exists()) tmp.delete()

            if (!apk.downloadUrl.isNullOrBlank()) {
                // --------- DESCARGA VÍA HTTP(S) EXTERNO ---------
                Log.d(TAG, "Downloading APK via HTTP: ${apk.downloadUrl}")
                downloadViaHttp(
                    url = apk.downloadUrl,
                    outFile = tmp,
                    expectedBytes = apk.bytes,
                    onProgress = onProgress
                ) {
                    !job.isActive
                }
            } else {
                // --------- DESCARGA VÍA FIREBASE STORAGE ---------
                Log.d(TAG, "Downloading APK via Firebase Storage: ${apk.storagePath}")
                val ref = Firebase.storage.reference.child(apk.storagePath)
                val task = ref.getFile(tmp)

                if (onProgress != null) {
                    task.addOnProgressListener { snap ->
                        val totalFromTask = snap.totalByteCount
                        val total = if (totalFromTask > 0L) {
                            totalFromTask
                        } else {
                            apk.bytes ?: 0L
                        }

                        if (total > 0L) {
                            onProgress(snap.bytesTransferred, total)
                        } else {
                            onProgress(snap.bytesTransferred, -1L)
                        }
                    }
                }

                // Esperamos a que termine la descarga (cancelable)
                task.await()

                // Si todo salió bien, nos aseguramos de reportar 100 %.
                if (onProgress != null) {
                    val length = tmp.length()
                    onProgress(length, length)
                }
            }

            if (!tmp.exists()) {
                Log.w(TAG, "Temp APK file does not exist after download")
                return@withContext false
            }

            val fileSize = tmp.length()
            val minBytes = apk.bytes ?: 1_000_000L

            if (fileSize < minBytes) {
                Log.w(
                    TAG,
                    "APK too small: fileSize=$fileSize < minBytes=$minBytes (metaBytes=${apk.bytes})"
                )
                if (STRICT_VALIDATION) {
                    tmp.delete()
                    return@withContext false
                }
            } else {
                Log.d(TAG, "APK size ok: fileSize=$fileSize (metaBytes=${apk.bytes})")
            }

            if (!apk.sha256.isNullOrBlank()) {
                val got = sha256(tmp)
                if (!got.equals(apk.sha256, ignoreCase = true)) {
                    Log.w(
                        TAG,
                        "APK sha mismatch expected=${apk.sha256} got=$got (STRICT_VALIDATION=$STRICT_VALIDATION)"
                    )
                    if (STRICT_VALIDATION) {
                        tmp.delete()
                        return@withContext false
                    }
                } else {
                    Log.d(TAG, "APK sha256 OK: $got")
                }
            } else {
                Log.d(TAG, "No sha256 configured in metadata, skipping hash check")
            }

            val renamed = tmp.renameTo(out)
            if (!renamed) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }

            // Guardar ruta del APK descargado con KTX edit{}
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit {
                putString(KEY_LAST_APK, out.absolutePath)
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
     * Descarga un archivo vía HTTP(S) a [outFile], reportando progreso si se pasa [onProgress].
     * Usa expectedBytes (si viene) o content-length del servidor para el total.
     *
     * isCancelled(): lambda que indica si el Job ya fue cancelado.
     */
    private fun downloadViaHttp(
        url: String,
        outFile: File,
        expectedBytes: Long?,
        onProgress: ((Long, Long) -> Unit)?,
        isCancelled: (() -> Boolean)? = null
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }

        val code = connection.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code for $url")
        }

        val totalFromHeader = connection.contentLengthLong
        val total = when {
            expectedBytes != null && expectedBytes > 0L -> expectedBytes
            totalFromHeader > 0L -> totalFromHeader
            else -> -1L
        }

        Log.d(
            TAG,
            "downloadViaHttp: url=$url totalFromHeader=$totalFromHeader expectedBytes=$expectedBytes totalUsed=$total"
        )

        connection.inputStream.use { input ->
            FileOutputStream(outFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = 0L
                while (true) {
                    if (isCancelled?.invoke() == true) {
                        Log.d(TAG, "downloadViaHttp: cancelled, throwing CancellationException")
                        throw CancellationException("HTTP download cancelled")
                    }

                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read

                    if (onProgress != null) {
                        if (total > 0L) {
                            onProgress(downloaded, total)
                        } else {
                            onProgress(downloaded, -1L)
                        }
                    }
                }
                output.flush()
            }
        }
    }

    /**
     * Si el usuario no tiene permitido instalar de "orígenes desconocidos"
     * para esta app, abre Settings correspondiente.
     */
    fun ensureUnknownSourcesPermission(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val pm = context.packageManager
            val canInstall = pm.canRequestPackageInstalls()
            if (!canInstall) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    // Usando extensión KTX toUri()
                    data = "package:${context.packageName}".toUri()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    private fun promptInstall(context: Context, apkFile: File) {
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

            val path = (config["appApkPath"] as? String)?.trim().orEmpty()
            val url = (config["appApkUrl"] as? String)?.trim()?.ifBlank { null }

            // Permitimos que path esté vacío si hay URL, pero no que falten ambos.
            if (path.isBlank() && url == null) return null

            ApkSpec(
                versionCode = v,
                storagePath = path,
                sha256 = (config["appApkSha256"] as? String)?.trim()?.ifBlank { null },
                bytes = (config["appApkBytes"] as? Number)?.toLong(),
                releaseNotes = (config["releaseNotes"] as? String)?.trim()?.ifBlank { null },
                downloadUrl = url
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

        val file = File(path)
        if (!file.exists()) {
            Log.w(TAG, "promptInstallFromLastDownloaded: file not found at $path")
            return false
        }

        Log.d(TAG, "promptInstallFromLastDownloaded: launching installer for $path")
        promptInstall(context, file)
        return true
    }
}
