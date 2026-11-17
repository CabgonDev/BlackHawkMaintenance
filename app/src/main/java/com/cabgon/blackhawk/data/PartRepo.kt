package com.cabgon.blackhawk.data

import android.net.Uri
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.Locale
import java.util.concurrent.TimeUnit

data class PartInfo(
    val partNumber: String?,
    val nsn: String?,
    val nsnUrl: String?,
    val description: String?,
    val pageUrl: String?      // para "Ir al sitio web"
)

interface PartApi {
    @GET("search.cfm")
    suspend fun search(@Query("q") q: String): Response<String>
}

private data class SearchCandidate(
    val partNumber: String?,
    val nsn: String?,
    val nsnUrl: String?,
    val description: String?,
    val pageUrl: String?
) {
    fun toPartInfo(fallbackQuery: String, fallbackUrl: String): PartInfo {
        return PartInfo(
            partNumber = partNumber?.ifBlank { null } ?: fallbackQuery,
            nsn = nsn?.ifBlank { null },
            nsnUrl = nsnUrl?.ifBlank { null },
            description = description?.ifBlank { null },
            pageUrl = pageUrl?.ifBlank { null } ?: fallbackUrl
        )
    }
}

private data class LoadedDoc(
    val url: String,
    val doc: Document
)

object PartRepo {
    private const val BASE_URL = "https://www.wbparts.com/"
    private val NSN_REGEX = Regex("\\b\\d{4}-\\d{2}-\\d{3}-\\d{4}\\b")
    private val NSN_DIGITS_REGEX = Regex("^\\d{13}$")

    private val ua = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .header("User-Agent", "Mozilla/5.0 (Android) BlackHawkMaintenance/1.0")
            .build()
        chain.proceed(req)
    }

    /**
     * OkHttp/Retrofit SINGLETON (P0):
     * - Reutiliza conexiones
     * - Evita reconstruir clientes por request
     * - Timeouts duros para campo
     */
    private val okHttp: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .addInterceptor(ua)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS) // límite total duro
            .build()
    }

    private val api: PartApi by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(ScalarsConverterFactory.create())
            .client(okHttp)
            .build()
            .create(PartApi::class.java)
    }

    /**
     * Regresa info parseada del HTML de WBParts.
     * - Nunca lanza excepción: en fallo retorna null.
     */
    suspend fun searchPartInfo(q: String): PartInfo? {
        val query = q.trim()
        if (query.isBlank()) return null

        val resp = try {
            api.search(query)
        } catch (_: Exception) {
            return null
        }

        if (!resp.isSuccessful) return null
        val html = resp.body().orEmpty()
        if (html.isBlank()) return null

        val finalUrl = resp.raw().request.url.toString()
        val fallbackSearchUrl = "$BASE_URL/search.cfm?q=${Uri.encode(query)}"

        val doc = try {
            Jsoup.parse(html, BASE_URL)
        } catch (_: Exception) {
            return null
        }

        // Si WBParts respondió directo con página detalle, parsearla como fuente canónica.
        parseDetailPage(
            doc = doc,
            query = query,
            detailUrl = finalUrl,
            seed = null
        )?.let { return it }

        val table = findResultsTable(doc)
        if (table == null) {
            val fallbackCandidate = parseGenericFallback(doc)
            return fallbackCandidate?.toPartInfo(query, fallbackSearchUrl)
        }

        val candidates = parseTableCandidates(table)
        val best = candidates.maxByOrNull { scoreCandidate(it, query) }
            ?: parseGenericFallback(doc)
            ?: return null

        val detailPartInfo = best.pageUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { detailUrl ->
                fetchDocument(detailUrl)?.let { loaded ->
                    parseDetailPage(
                        doc = loaded.doc,
                        query = query,
                        detailUrl = loaded.url,
                        seed = best
                    )
                }
            }

        return detailPartInfo ?: best.toPartInfo(query, fallbackSearchUrl)
    }

    private fun findResultsTable(doc: Document): Element? {
        return doc.selectFirst("table:has(th:matchesOwn((?i)^\\s*Part\\s*Number\\s*$))")
            ?: doc.select("table").firstOrNull { t ->
                t.select("th").any { it.text().trim().equals("Part Number", true) }
            }
    }

    private fun parseTableCandidates(table: Element): List<SearchCandidate> {
        val headers = table.select("th")
        var iPN = 0
        var iNSN = 1
        var iDesc = 2

        headers.forEachIndexed { idx, th ->
            when (th.text().trim().lowercase(Locale.US)) {
                "part number" -> iPN = idx
                "nsn" -> iNSN = idx
                "description" -> iDesc = idx
            }
        }

        return table.select("tbody tr:has(td), tr:has(td)")
            .mapNotNull { row ->
                val tds = row.select("td")
                if (tds.isEmpty()) return@mapNotNull null

                val pnCell = tds.getOrNull(iPN)
                val nsnCell = tds.getOrNull(iNSN)
                val descCell = tds.getOrNull(iDesc)

                val pn = pnCell.extractPrimaryText()
                val nsnAnchor = nsnCell?.selectFirst("a[href]")
                val nsnRaw = nsnAnchor?.text()?.trim()
                    ?: nsnCell.extractPrimaryText()
                val nsn = nsnRaw?.let { formatNsn(it) }
                val nsnUrl = nsnAnchor?.absUrl("href")?.takeIf { it.isNotBlank() }
                val description = descCell?.ownText()?.ifBlank { descCell.text() }?.cleanText()
                val pageUrl = pnCell?.selectFirst("a[href]")?.absUrl("href")?.takeIf { it.isNotBlank() }

                if (pn.isNullOrBlank() && nsn.isNullOrBlank() && description.isNullOrBlank()) {
                    null
                } else {
                    SearchCandidate(
                        partNumber = pn,
                        nsn = nsn,
                        nsnUrl = nsnUrl,
                        description = description,
                        pageUrl = pageUrl
                    )
                }
            }
    }

    private fun parseGenericFallback(doc: Document): SearchCandidate? {
        val row = doc.selectFirst("table:has(tr td), table.results")?.selectFirst("tr:has(td)")
        val firstCell = row?.selectFirst("td")
            ?: doc.selectFirst("ul li, ol li, div.result, article, div.search-results div")
            ?: return null

        val pageUrl = firstCell.selectFirst("a[href]")?.absUrl("href")?.takeIf { it.isNotBlank() }
        val pn = firstCell.selectFirst("a, strong, b")?.text()?.trim()
            ?: firstCell.ownText().trim().takeIf { it.isNotBlank() }
            ?: firstCell.text().trim()

        val nsnAnchor = firstCell.select("a[href]").firstOrNull { a ->
            val t = a.text().trim()
            t.matches(NSN_REGEX) ||
                    t.replace("-", "").matches(NSN_DIGITS_REGEX) ||
                    t.contains("NSN", true)
        }
        val nsnRaw = nsnAnchor?.text()?.trim()
        val nsn = nsnRaw?.let { formatNsn(it) }
        val nsnUrl = nsnAnchor?.absUrl("href")?.takeIf { it.isNotBlank() }
        val description = firstCell.ownText().ifBlank { firstCell.text() }.cleanText().takeIf { it.isNotBlank() }

        return SearchCandidate(
            partNumber = pn,
            nsn = nsn,
            nsnUrl = nsnUrl,
            description = description,
            pageUrl = pageUrl
        )
    }

    private fun scoreCandidate(candidate: SearchCandidate, query: String): Int {
        val normalizedQuery = normalizeKey(query)
        val queryLooksLikeNsn = looksLikeNsn(query)
        val candidatePn = normalizeKey(candidate.partNumber)
        val candidateNsn = normalizeKey(candidate.nsn)
        val pageUrl = candidate.pageUrl.orEmpty().lowercase(Locale.US)
        val pageUrlKey = normalizeKey(pageUrl)
        val descriptionKey = normalizeKey(candidate.description)

        var score = 0

        if (candidatePn.isNotEmpty()) {
            if (candidatePn == normalizedQuery) score += 1000
            else if (candidatePn.startsWith(normalizedQuery) || normalizedQuery.startsWith(candidatePn)) score += 350
            else if (candidatePn.contains(normalizedQuery) || normalizedQuery.contains(candidatePn)) score += 180
        }

        if (candidateNsn.isNotEmpty()) {
            if (candidateNsn == normalizedQuery) score += 980
            else if (queryLooksLikeNsn && candidateNsn.contains(normalizedQuery)) score += 260
        }

        if (pageUrlKey.contains(normalizedQuery) && normalizedQuery.isNotBlank()) {
            score += if (pageUrl.contains("/rfq/")) 500 else 220
        }

        if (descriptionKey.contains(normalizedQuery) && normalizedQuery.length >= 4) {
            score += 90
        }

        if (candidate.pageUrl?.contains("/rfq/", ignoreCase = true) == true) score += 75
        if (!candidate.description.isNullOrBlank()) score += 10

        return score
    }

    private fun fetchDocument(url: String): LoadedDoc? {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            okHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body.string()
                if (body.isBlank()) return null
                val finalUrl = response.request.url.toString()
                LoadedDoc(finalUrl, Jsoup.parse(body, finalUrl))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDetailPage(
        doc: Document,
        query: String,
        detailUrl: String,
        seed: SearchCandidate?
    ): PartInfo? {
        val pageText = doc.text().cleanText()
        val titleText = doc.selectFirst("h1")?.text().cleanText().ifBlank {
            doc.title().cleanText()
        }

        val productHeader = doc.select("h1, h2, h3, h4, h5, h6, strong, b")
            .firstOrNull { it.text().contains("Product Details", ignoreCase = true) }
            ?: doc.selectFirst("*:matchesOwn((?i)^\\s*Product\\s+Details)")

        val detailsText = productHeader?.text().cleanText()
        val detailName = detailsText
            .substringAfter("|")
            .trim()
            .takeIf { it.isNotBlank() && !it.equals("Product Details", true) }

        val detailParagraph = findProductDescription(productHeader)
        val normalizedQuery = normalizeKey(query)
        val pageNsns = extractAllNsns(listOf(titleText, detailsText, pageText))
        val nsn = pageNsns.firstOrNull()
            ?: seed?.nsn
        val nsnUrl = seed?.nsnUrl

        val alternates = extractAlternates(pageText)
        val matchedAlternate = alternates.firstOrNull { normalizeKey(it) == normalizedQuery }

        val partNumber = when {
            !looksLikeNsn(query) && matchedAlternate != null -> matchedAlternate
            !looksLikeNsn(query) && pageContainsNormalizedToken(titleText, normalizedQuery) -> query.trim()
            !looksLikeNsn(query) && pageContainsNormalizedToken(pageText, normalizedQuery) -> query.trim()
            !seed?.partNumber.isNullOrBlank() && normalizeKey(seed.partNumber) == normalizedQuery -> seed.partNumber
            looksLikeNsn(query) -> seed?.partNumber?.takeIf { it.isNotBlank() } ?: alternates.firstOrNull()
            else -> seed?.partNumber?.takeIf { it.isNotBlank() } ?: query
        }

        val description = when {
            !detailName.isNullOrBlank() -> detailName
            !detailParagraph.isNullOrBlank() -> detailParagraph
            !seed?.description.isNullOrBlank() -> seed.description
            else -> null
        }

        val pageLooksDetailed = detailName != null ||
                !detailParagraph.isNullOrBlank() ||
                detailUrl.contains("/rfq/", ignoreCase = true) ||
                titleText.contains("NSN", ignoreCase = true)

        if (!pageLooksDetailed) return null
        if (partNumber.isNullOrBlank() && nsn.isNullOrBlank() && description.isNullOrBlank()) return null

        return PartInfo(
            partNumber = partNumber?.cleanText(),
            nsn = nsn?.cleanText(),
            nsnUrl = nsnUrl?.cleanText(),
            description = description?.cleanText(),
            pageUrl = detailUrl
        )
    }

    private fun findProductDescription(productHeader: Element?): String? {
        if (productHeader == null) return null

        var sibling = productHeader.nextElementSibling()
        while (sibling != null) {
            val text = sibling.text().cleanText()
            if (text.isBlank()) {
                sibling = sibling.nextElementSibling()
                continue
            }
            if (text.contains("Part Alternates:", ignoreCase = true)) break
            if (text.contains("Drawings & Photos", ignoreCase = true)) break
            if (text.contains("Demand History", ignoreCase = true)) break
            if (text.contains("Supply Group", ignoreCase = true)) break
            if (text.length >= 24) return text
            sibling = sibling.nextElementSibling()
        }

        val parent = productHeader.parent()
        val parentText = parent?.text().cleanText()
        if (parentText.contains("Product Details", ignoreCase = true)) {
            val extracted = parentText
                .substringAfter("Product Details")
                .substringAfter("|")
                .substringBefore("Part Alternates:")
                .cleanText()
            if (extracted.length >= 24) return extracted
        }

        return null
    }

    private fun extractAlternates(pageText: String): List<String> {
        val marker = "Part Alternates:"
        val block = pageText.substringAfter(marker, "")
            .substringBefore("Drawings & Photos")
            .substringBefore("Demand History")
            .cleanText()

        if (block.isBlank()) return emptyList()

        return block.split(',')
            .map { it.cleanText() }
            .filter { it.isNotBlank() }
    }

    private fun extractAllNsns(texts: List<String?>): List<String> {
        val out = LinkedHashSet<String>()
        texts.filterNotNull().forEach { raw ->
            NSN_REGEX.findAll(raw).forEach { match ->
                out += match.value
            }
            if (out.isEmpty()) {
                Regex("\\b\\d{13}\\b").findAll(raw).forEach { match ->
                    out += formatNsn(match.value)
                }
            }
        }
        return out.toList()
    }

    private fun pageContainsNormalizedToken(text: String?, normalizedToken: String): Boolean {
        if (text.isNullOrBlank() || normalizedToken.isBlank()) return false
        return normalizeKey(text).contains(normalizedToken)
    }

    private fun normalizeKey(value: String?): String {
        return value.orEmpty()
            .uppercase(Locale.US)
            .replace(Regex("[^A-Z0-9]"), "")
    }

    private fun looksLikeNsn(value: String?): Boolean {
        val raw = value.orEmpty().trim()
        if (raw.isBlank()) return false
        if (raw.matches(NSN_REGEX)) return true
        val digits = raw.replace(Regex("[^0-9]"), "")
        return digits.length == 13
    }

    private fun Element?.extractPrimaryText(): String? {
        val element = this ?: return null
        return element.selectFirst("a, strong, b")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: element.ownText().trim().takeIf { it.isNotBlank() }
            ?: element.text().trim().takeIf { it.isNotBlank() }
    }

    private fun String?.cleanText(): String {
        return this.orEmpty()
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun formatNsn(raw: String): String {
        val t = raw.trim()
        val digits = t.replace(Regex("[^0-9]"), "")
        return when {
            t.matches(NSN_REGEX) -> t
            digits.length == 13 -> "${digits.take(4)}-${digits.substring(4, 6)}-${digits.substring(6, 9)}-${digits.substring(9)}"
            else -> t
        }
    }
}