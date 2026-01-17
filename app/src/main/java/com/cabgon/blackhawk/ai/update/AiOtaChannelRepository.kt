package com.cabgon.blackhawk.ai.update

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Lee la estructura:
 * collection: ai_ota
 * document: channels
 * fields: stable, beta, etc (cada uno es un Map con: config, indexes, model, minAppVersionCode)
 */
object AiOtaChannelRepository {

    private const val TAG = "AiOtaRepo"

    data class Artifact(
        val version: Long,
        val storagePath: String,
        val sha256: String?,
        val bytes: Long?
    )

    data class Indexes(
        val iads: Artifact?,
        val sikorsky: Artifact?
    )

    data class ChannelSpec(
        val channelName: String,
        val minAppVersionCode: Long?,
        val model: Artifact?,
        val indexes: Indexes?
    )

    suspend fun loadChannelSpec(channelName: String): ChannelSpec? {
        return try {
            val db = FirebaseFirestore.getInstance()
            val snap = db.collection("ai_ota")
                .document("channels")
                .get()
                .await()

            if (!snap.exists()) {
                Log.w(TAG, "Document ai_ota/channels no existe")
                return null
            }

            val channelMap = snap.get(channelName) as? Map<*, *>
            if (channelMap == null) {
                Log.w(TAG, "Canal '$channelName' no existe como field en ai_ota/channels")
                return null
            }

            val minAppVersionCode = (channelMap["minAppVersionCode"] as? Number)?.toLong()

            val modelMap = channelMap["model"] as? Map<*, *>
            val model = modelMap?.toArtifact()

            val indexesMap = channelMap["indexes"] as? Map<*, *>
            val iadsMap = indexesMap?.get("IADS") as? Map<*, *>
            val sikorskyMap = indexesMap?.get("SIKORSKY") as? Map<*, *>

            val indexes = Indexes(
                iads = iadsMap?.toArtifact(),
                sikorsky = sikorskyMap?.toArtifact()
            )

            ChannelSpec(
                channelName = channelName,
                minAppVersionCode = minAppVersionCode,
                model = model,
                indexes = indexes
            )
        } catch (e: Exception) {
            Log.w(TAG, "loadChannelSpec($channelName) failed: ${e.message}", e)
            null
        }
    }

    private fun Map<*, *>.toArtifact(): Artifact? {
        val version = (this["version"] as? Number)?.toLong() ?: 0L
        val storagePath = (this["storagePath"] as? String)?.trim().orEmpty()
        val sha256 = (this["sha256"] as? String)?.trim()?.ifBlank { null }
        val bytes = (this["bytes"] as? Number)?.toLong()

        if (version <= 0L || storagePath.isBlank()) return null

        return Artifact(
            version = version,
            storagePath = storagePath,
            sha256 = sha256,
            bytes = bytes
        )
    }
}
