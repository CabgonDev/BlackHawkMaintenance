package com.cabgon.blackhawk.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.cabgon.blackhawk.BuildConfig
import com.cabgon.blackhawk.ai.update.AiOtaChannelRepository
import com.google.android.gms.tasks.Tasks
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AiStatusReportBuilder {

    suspend fun build(ctx: Context): String = withContext(Dispatchers.IO) {
        val rc = Firebase.remoteConfig

        // RemoteConfig fetch (igual que tu activity)
        runCatching { Tasks.await(rc.fetchAndActivate()) }

        val channel = rc.getString("ai_channel_default").trim().ifBlank { "stable" }
        val otaEnabled = rc.getBoolean("ota_enabled")
        val otaForce = rc.getBoolean("ota_force")
        val allowModelOverCell = rc.getBoolean("ai_allow_model_download_over_cellular")

        val connectivity = connectivityLabel(ctx)

        // Firestore spec del canal (modelo/índices)
        val spec = runCatching { AiOtaChannelRepository.loadChannelSpec(channel) }.getOrNull()

        // Local prefs + archivos
        val sp = ctx.getSharedPreferences("ai_ota_prefs", Context.MODE_PRIVATE)

        val modelVer = sp.getLong("model_version", 0L)
        val iadsVer = sp.getLong("rag_iads_version", 0L)
        val sikVer = sp.getLong("rag_sikorsky_version", 0L)

        val lastModelAt = sp.getLong("last_update_at_model", 0L)
        val lastIadsAt = sp.getLong("last_update_at_iads", 0L)
        val lastSikAt = sp.getLong("last_update_at_sikorsky", 0L)

        val modelFile = File(File(ctx.filesDir, "models"), "model.gguf")
        val iadsFile = File(ctx.filesDir, "blackhawk_iads_fts.db")
        val sikFile = File(ctx.filesDir, "blackhawk_sikorsky_fts.db")

        fun fmt(ts: Long): String =
            if (ts <= 0L) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))

        fun fileLine(f: File): String =
            "exists=${f.exists()} size=${if (f.exists()) f.length() else 0} path=${f.absolutePath}"

        fun shaShort(s: String?): String = s?.take(8)?.plus("…") ?: "—"

        buildString {
            appendLine("ESTADO IA")
            appendLine("App: versionCode=${BuildConfig.VERSION_CODE}  versionName=${BuildConfig.VERSION_NAME}")
            appendLine("Conectividad: $connectivity")
            appendLine()
            appendLine("REMOTE CONFIG")
            appendLine("channel=$channel")
            appendLine("ota_enabled=$otaEnabled  ota_force=$otaForce  allowModelOverCell=$allowModelOverCell")
            appendLine()

            appendLine("CANAL (Firestore ai_ota/channels)")
            appendLine("minAppVersionCode=${spec?.minAppVersionCode ?: "—"}")
            appendLine("hasModel=${spec?.model != null}  hasIndexes=${spec?.indexes != null}")
            appendLine()

            appendLine("MODELO")
            appendLine("Local version: $modelVer")
            appendLine("Local file: ${fileLine(modelFile)}")
            appendLine("Última actualización: ${fmt(lastModelAt)}")
            appendLine("Remote version: ${spec?.model?.version ?: "—"}")
            appendLine("Remote bytes: ${spec?.model?.bytes ?: "—"}")
            appendLine("Remote sha: ${shaShort(spec?.model?.sha256)}")
            appendLine("Remote path: ${spec?.model?.storagePath ?: "—"}")
            appendLine()

            appendLine("ÍNDICE IADS")
            appendLine("Local version: $iadsVer")
            appendLine("Local file: ${fileLine(iadsFile)}")
            appendLine("Última actualización: ${fmt(lastIadsAt)}")
            appendLine("Remote version: ${spec?.indexes?.iads?.version ?: "—"}")
            appendLine("Remote bytes: ${spec?.indexes?.iads?.bytes ?: "—"}")
            appendLine("Remote sha: ${shaShort(spec?.indexes?.iads?.sha256)}")
            appendLine("Remote path: ${spec?.indexes?.iads?.storagePath ?: "—"}")
            appendLine()

            appendLine("ÍNDICE SIKORSKY")
            appendLine("Local version: $sikVer")
            appendLine("Local file: ${fileLine(sikFile)}")
            appendLine("Última actualización: ${fmt(lastSikAt)}")
            appendLine("Remote version: ${spec?.indexes?.sikorsky?.version ?: "—"}")
            appendLine("Remote bytes: ${spec?.indexes?.sikorsky?.bytes ?: "—"}")
            appendLine("Remote sha: ${shaShort(spec?.indexes?.sikorsky?.sha256)}")
            appendLine("Remote path: ${spec?.indexes?.sikorsky?.storagePath ?: "—"}")
        }
    }

    private fun connectivityLabel(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return "Sin red"
        val caps = cm.getNetworkCapabilities(net) ?: return "Sin red"

        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val eth = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val cell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        return when {
            wifi -> "Wi-Fi"
            eth -> "Ethernet"
            cell -> "Datos móviles"
            else -> "Red desconocida"
        }
    }
}
