package com.cabgon.blackhawk.ai.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.cabgon.blackhawk.BuildConfig
import com.cabgon.blackhawk.ai.await
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"

    data class ApkSpec(
        val versionCode: Long,
        val storagePath: String,
        val sha256: String?,
        val bytes: Long?,
        val releaseNotes: String?
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
     */
    suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val rc = Firebase.remoteConfig
        runCatching { rc.fetchAndActivate().await() }
            .onFailure { Log.w(TAG, "RC fetch failed: ${it.message}") }

        val channel = rc.getString("ai_channel_default").trim().ifBlank { "stable" }
        val spec = AiOtaChannelRepository.loadChannelSpec(channel)

        val currentVc = BuildConfig.VERSION_CODE.toLong()
        val minVc = spec?.minAppVersionCode

        val apkSpec = loadApkSpecFromChannelMap(channel)

        val required = (minVc != null && currentVc < minVc)

        Log.d(TAG, "check: channel=$channel currentVc=$currentVc minVc=$minVc required=$required apkSpec=${apkSpec?.storagePath}")

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
     */
    suspend fun downloadAndPromptInstall(context: Context, apk: ApkSpec): Boolean = withContext(Dispatchers.IO) {
        val ref = Firebase.storage.reference.child(apk.storagePath)

        val outDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val tmp = File(outDir, "update_${apk.versionCode}.apk.tmp")
        val out = File(outDir, "update_${apk.versionCode}.apk")

        runCatching {
            if (tmp.exists()) tmp.delete()
            if (out.exists()) out.delete()

            ref.getFile(tmp).await()

            val minBytes = apk.bytes ?: 1_000_000L
            if (!tmp.exists() || tmp.length() < minBytes) {
                Log.w(TAG, "APK too small: ${tmp.length()} < $minBytes")
                tmp.delete()
                return@withContext false
            }

            if (!apk.sha256.isNullOrBlank()) {
                val got = sha256(tmp)
                if (!got.equals(apk.sha256, ignoreCase = true)) {
                    Log.w(TAG, "APK sha mismatch expected=${apk.sha256.take(8)}… got=${got.take(8)}…")
                    tmp.delete()
                    return@withContext false
                }
            }

            val renamed = tmp.renameTo(out)
            if (!renamed) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }

            withContext(Dispatchers.Main) {
                promptInstall(context, out)
            }

            true
        }.getOrElse {
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val pm = context.packageManager
            val canInstall = pm.canRequestPackageInstalls()
            if (!canInstall) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
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
     * Como tu repo actual no expone config, lo resolvemos re-leyendo el doc con un helper simple.
     *
     * Para mantener todo simple, se obtiene channelSpec y se re-carga el doc "ai_ota/channels" (una sola lectura).
     */
    private suspend fun loadApkSpecFromChannelMap(channelName: String): ApkSpec? {
        // Reutilizamos la lectura ya consolidada del repo (pero necesitamos 'config'),
        // así que hacemos una lectura directa aquí.
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
            if (path.isBlank()) return null

            ApkSpec(
                versionCode = v,
                storagePath = path,
                sha256 = (config["appApkSha256"] as? String)?.trim()?.ifBlank { null },
                bytes = (config["appApkBytes"] as? Number)?.toLong(),
                releaseNotes = (config["releaseNotes"] as? String)?.trim()?.ifBlank { null }
            )
        } catch (e: Exception) {
            Log.w(TAG, "loadApkSpecFromChannelMap failed: ${e.message}")
            null
        }
    }
}
