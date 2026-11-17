package com.cabgon.blackhawk.content.ota

import android.util.Log
import com.cabgon.blackhawk.ai.await
import com.google.firebase.firestore.FirebaseFirestore

object ContentOtaRepository {

    private const val TAG = "ContentOtaRepo"

    data class ArtifactSpec(
        val version: Long,
        val storagePath: String,
        val bytes: Long?,
        val sha256: String?,
        val minAppVersionCode: Long?
    )

    suspend fun loadGeneralitiesSpec(channel: String): ArtifactSpec? {
        return try {
            val doc = FirebaseFirestore.getInstance()
                .collection("content_ota")
                .document("channels")
                .get()
                .await()

            val channelMap = doc.get(channel) as? Map<*, *> ?: run {
                Log.w(TAG, "Channel map not found for '$channel'")
                return null
            }

            val genMap = channelMap["generalities"] as? Map<*, *> ?: run {
                Log.w(TAG, "generalities map not found under channel '$channel'")
                return null
            }

            ArtifactSpec(
                version = (genMap["version"] as? Number)?.toLong() ?: 0L,
                storagePath = (genMap["storagePath"] as? String)?.trim().orEmpty(),
                bytes = (genMap["bytes"] as? Number)?.toLong(),
                sha256 = (genMap["sha256"] as? String)?.trim(),
                minAppVersionCode = (genMap["minAppVersionCode"] as? Number)?.toLong()
            ).takeIf { it.storagePath.isNotBlank() && it.version > 0L }
        } catch (e: Exception) {
            Log.w(TAG, "loadGeneralitiesSpec($channel) failed: ${e.message}")
            null
        }
    }


    suspend fun loadFrequenciesSpec(channel: String): ArtifactSpec? {
        return try {
            val doc = FirebaseFirestore.getInstance()
                .collection("content_ota")
                .document("channels")
                .get()
                .await()

            // Estructura: { stable: { frequencies: {...} }, beta: { frequencies: {...} } }
            val channelMap = doc.get(channel) as? Map<*, *> ?: run {
                Log.w(TAG, "Channel map not found for '$channel'")
                return null
            }

            val freqMap = channelMap["frequencies"] as? Map<*, *> ?: run {
                Log.w(TAG, "frequencies map not found under channel '$channel'")
                return null
            }

            ArtifactSpec(
                version = (freqMap["version"] as? Number)?.toLong() ?: 0L,
                storagePath = (freqMap["storagePath"] as? String)?.trim().orEmpty(),
                bytes = (freqMap["bytes"] as? Number)?.toLong(),
                sha256 = (freqMap["sha256"] as? String)?.trim(),
                minAppVersionCode = (freqMap["minAppVersionCode"] as? Number)?.toLong()
            ).takeIf { it.storagePath.isNotBlank() && it.version > 0L }
        } catch (e: Exception) {
            Log.w(TAG, "loadFrequenciesSpec($channel) failed: ${e.message}")
            null
        }
    }
}
