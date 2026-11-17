package com.cabgon.blackhawk.admin.generalities

import android.content.Context
import android.util.Log
import com.cabgon.blackhawk.ai.await
import com.cabgon.blackhawk.content.generalities.GeneralitiesJson
import com.cabgon.blackhawk.content.generalities.GeneralitiesTableBlock
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AdminGeneralitiesImportService {

    data class Result(val ok: Boolean, val message: String)

    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    private val draftCol = db.collection("content_admin")
        .document("generalities_draft")
        .collection("sections")

    suspend fun importFromStableToDraft(ctx: Context): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "importFromStableToDraft start")

            val channels = db.collection("content_ota").document("channels").get().await()
            val stable = channels.get("stable") as? Map<*, *> ?: return@withContext Result(false, "No existe canal stable en content_ota/channels.")
            val gen = stable["generalities"] as? Map<*, *> ?: return@withContext Result(false, "No existe stable.generalities en content_ota/channels.")
            val storagePath = (gen["storagePath"] as? String)?.trim().orEmpty()
            if (storagePath.isBlank()) return@withContext Result(false, "stable.generalities.storagePath está vacío.")

            Log.d(TAG, "importFromStableToDraft path=$storagePath")

            val bytes = storage.reference.child(storagePath).getBytes(10L * 1024L * 1024L).await()
            val json = bytes.toString(Charsets.UTF_8)

            val manifest = GeneralitiesJson.parse(json)
            val sections = manifest.sections
            if (sections.isEmpty()) return@withContext Result(false, "El JSON STABLE no trae secciones.")

            val batch = db.batch()
            var count = 0

            for (s in sections) {
                val table = s.blocks.firstOrNull { it is GeneralitiesTableBlock } as? GeneralitiesTableBlock
                if (table == null) continue

                val doc = AdminGeneralitySectionDoc(
                    id = s.id,
                    title = s.title,
                    order = s.order,
                    tableTitle = table.title ?: "",
                    columns = table.columns,
                    rows = table.rows,
                    isDeleted = false,
                    updatedAt = System.currentTimeMillis()
                )

                batch.set(draftCol.document(s.id), doc.toMap())
                count++
            }

            batch.commit().await()
            Result(true, "Importación STABLE completada: $count sección(es) cargadas a draft.")
        } catch (e: Exception) {
            Log.w(TAG, "importFromStableToDraft failed: ${e.message}", e)
            Result(false, "Error importando STABLE: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AdminGenImport"
    }
}
