package com.cabgon.blackhawk.ai.chat

import android.content.Context
import android.util.Log
import com.cabgon.blackhawk.ai.LocalRetriever
import com.cabgon.blackhawk.data.PackageManager
import com.cabgon.blackhawk.data.RAGIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

class LocalRetrieverAdapter(
    private val appContext: Context
) {
    private val indexCache = ConcurrentHashMap<PackageManager.Pkg, RAGIndex>()
    private val retrieverCache = ConcurrentHashMap<PackageManager.Pkg, LocalRetriever>()

    suspend fun retrieve(question: String, packageId: String, topK: Int): List<RagPassage> {
        val pkg = parsePkg(packageId)
        val retriever = getOrCreateRetriever(pkg)

        val qRaw = question.trim()
        val qN = normalizeQuery(qRaw)

        val k = keywords(qN)

        val candidates = linkedSetOf<String>().apply {
            if (k.isNotBlank()) add(k)
            if (qN.isNotBlank()) add(qN)
        }.toList()

        Log.d("RAG", "kEn='$k' qEnN='$qN'")
        Log.d("RAG", "candidates=$candidates")

        val allHits = mutableListOf<RAGIndex.Hit>()

        withContext(Dispatchers.IO) {
            for (q in candidates) {
                if (q.length < 3) continue
                val hits = runCatching { retriever.retrieve(q, limit = maxOf(topK, 12)) }
                    .getOrElse { emptyList() }

                Log.d("RAG", "pkg=${pkg.name} q='$q' hits=${hits.size}")
                allHits += hits
            }
        }

        /**
         * CLAVE:
         * - RAGIndex usa bm25(pages_fts) AS sc y ORDER BY sc (ASC)
         * - En bm25: MENOR = MEJOR.
         *
         * Además, el fallback LIKE devuelve score=0.0 (no es bm25 real),
         * así que lo mandamos al final tratándolo como "muy malo".
         */
        fun normScore(hit: RAGIndex.Hit): Double {
            return if (hit.score == 0.0) 1e9 else hit.score
        }

        val mapped = allHits
            .sortedBy { normScore(it) } // ✅ ASC: menor bm25 = más relevante
            .distinctBy { h -> "${h.manual}:${h.page}" }
            .take(topK)
            .map { hit ->
                RagPassage(
                    manual = hit.manual,
                    page = hit.page,          // 1-based (como el PDF viewer espera)
                    text = hit.snippet,
                    score = normScore(hit)    // normalizado para debug/orden
                )
            }

        Log.d("RAG", "FINAL pkg=${pkg.name} passages=${mapped.size}")
        return mapped
    }

    private fun parsePkg(packageId: String): PackageManager.Pkg {
        val normalized = packageId.trim().uppercase()
        return runCatching { PackageManager.Pkg.valueOf(normalized) }
            .getOrElse { PackageManager.Pkg.IADS }
    }

    private fun getOrCreateRetriever(pkg: PackageManager.Pkg): LocalRetriever {
        return retrieverCache[pkg] ?: synchronized(this) {
            retrieverCache[pkg] ?: run {
                val assetPath = PackageManager.indexAssetPath(pkg)
                Log.d("RAG", "open index from assets: $assetPath")

                val index = indexCache[pkg] ?: RAGIndex.openFromAssets(appContext, assetPath).also {
                    indexCache[pkg] = it
                }

                LocalRetriever(index).also { retrieverCache[pkg] = it }
            }
        }
    }

    private fun normalizeQuery(s: String): String {
        val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        return noAccents
            .lowercase()
            .replace(Regex("[^a-z0-9\\s\\-_/]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun keywords(s: String): String {
        if (s.isBlank()) return ""

        val stop = setOf(
            "what","which","who","when","where","why","how",
            "is","are","was","were","be","been","being",
            "do","does","did",
            "the","a","an","and","or","to","of","in","on","for","with","without","about",
            "purpose","define","explain","tell","me",
            // ruido
            "uh","black","hawk","uh60","uh-60","uh-60l"
        )

        return s.split(" ")
            .map { it.trim() }
            .filter { it.length >= 3 && it !in stop }
            .take(10)
            .joinToString(" ")
            .trim()
    }
}
