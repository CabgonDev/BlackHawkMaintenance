package com.cabgon.blackhawk.ui.admin

import android.content.Context
import com.cabgon.blackhawk.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reporte textual de diagnóstico para Estado IA.
 *
 * Nota:
 * - Este provider es deliberadamente "no frágil": si algo falla (por permisos/red),
 *   genera reporte parcial en lugar de crashear.
 * - Si tu AiStatusActivity ya tiene un builder de reporte, podemos redirigir
 *   AiStatusReportProvider.build() a ese builder en 1 línea.
 */
object AiStatusReportProvider {

    suspend fun build(ctx: Context): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()

        fun line(k: String, v: String) { sb.append(k).append(": ").append(v).append("\n") }
        fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        val user = FirebaseAuth.getInstance().currentUser
        val uid = user?.uid ?: "—"
        val email = user?.email ?: "—"

        line("Blackhawk Maintenance", "AI Status Report")
        line("GeneratedAt", now())
        line("AppVersion", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        line("BuildType", BuildConfig.BUILD_TYPE)
        line("UID", uid)
        line("Email", email)

        // Firestore snapshots: ai_ota/channels + content_ota/channels (si existen)
        val db = FirebaseFirestore.getInstance()

        sb.append("\n=== Firestore: ai_ota/channels ===\n")
        runCatching {
            val doc = db.collection("ai_ota").document("channels").get().await()
            if (!doc.exists()) {
                sb.append("No document ai_ota/channels\n")
            } else {
                // imprimimos claves top-level sin asumir schema exacto
                val data = doc.data ?: emptyMap<String, Any>()
                sb.append("Keys: ").append(data.keys.sorted().joinToString(", ")).append("\n")
            }
        }.onFailure {
            sb.append("Error: ").append(it.message ?: "unknown").append("\n")
        }

        sb.append("\n=== Firestore: content_ota/channels ===\n")
        runCatching {
            val doc = db.collection("content_ota").document("channels").get().await()
            if (!doc.exists()) {
                sb.append("No document content_ota/channels\n")
            } else {
                val data = doc.data ?: emptyMap<String, Any>()
                sb.append("Keys: ").append(data.keys.sorted().joinToString(", ")).append("\n")

                // Si existe stable.frequencies, lo mostramos de forma amigable
                val stable = data["stable"] as? Map<*, *>
                val freqs = stable?.get("frequencies") as? Map<*, *>
                if (freqs != null) {
                    sb.append("stable.frequencies.version=").append(freqs["version"] ?: "—").append("\n")
                    sb.append("stable.frequencies.storagePath=").append(freqs["storagePath"] ?: "—").append("\n")
                    sb.append("stable.frequencies.bytes=").append(freqs["bytes"] ?: "—").append("\n")
                    sb.append("stable.frequencies.sha256=").append(freqs["sha256"] ?: "—").append("\n")
                }
            }
        }.onFailure {
            sb.append("Error: ").append(it.message ?: "unknown").append("\n")
        }

        sb.toString().trimEnd()
    }
}
