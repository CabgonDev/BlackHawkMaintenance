package com.cabgon.blackhawk.admin.generalities

import com.cabgon.blackhawk.ai.await
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AdminGeneralitiesStatusService {

    data class StableStatus(
        val version: Long,
        val storagePath: String
    )

    private val db = FirebaseFirestore.getInstance()

    suspend fun getStableStatus(): StableStatus? = withContext(Dispatchers.IO) {
        val doc = db.collection("content_ota").document("channels").get().await()
        val stable = doc.get("stable") as? Map<*, *> ?: return@withContext null
        val gen = stable["generalities"] as? Map<*, *> ?: return@withContext null

        val version = (gen["version"] as? Number)?.toLong() ?: return@withContext null
        val path = (gen["storagePath"] as? String)?.trim().orEmpty()
        if (path.isBlank()) return@withContext null

        StableStatus(version, path)
    }
}
