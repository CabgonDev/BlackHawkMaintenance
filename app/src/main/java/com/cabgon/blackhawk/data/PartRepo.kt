// app/src/main/java/com/cabgon/blackhawk/data/PartRepo.kt
package com.cabgon.blackhawk.data

import android.net.Uri
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
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

object PartRepo {
    private const val BASE_URL = "https://www.wbparts.com/"

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

        val doc = try {
            Jsoup.parse(html, BASE_URL)
        } catch (_: Exception) {
            return null
        }

        // ── 1) Ubicar tabla con encabezado "Part Number" (WBParts)
        val table = doc.selectFirst("table:has(th:matchesOwn((?i)^\\s*Part\\s*Number\\s*$))")
            ?: doc.select("table").firstOrNull { t ->
                t.select("th").any { it.text().trim().equals("Part Number", true) }
            }

        // Si no se encontró, fallback genérico
        if (table == null) {
            val row = doc.selectFirst("table:has(tr td), table.results")?.selectFirst("tr:has(td)")
            val firstCell = row?.selectFirst("td")
                ?: doc.selectFirst("ul li, ol li, div.result, article, div.search-results div")
                ?: doc.body()

            val pageUrl = firstCell.selectFirst("a[href]")?.absUrl("href")

            val pn = firstCell.selectFirst("a, strong, b")?.text()?.trim()
                ?: firstCell.ownText().trim().takeIf { it.isNotBlank() }
                ?: firstCell.text().trim()

            val nsnAnchor = firstCell.select("a[href]").firstOrNull { a ->
                val t = a.text().trim()
                t.matches(Regex("\\b\\d{4}-\\d{2}-\\d{3}-\\d{4}\\b")) ||
                        t.replace("-", "").matches(Regex("^\\d{13}$")) ||
                        t.contains("NSN", true)
            }
            val nsnRaw = nsnAnchor?.text()?.trim()
            val nsn = nsnRaw?.let { formatNsn(it) }
            val nsnUrl = nsnAnchor?.absUrl("href")?.takeIf { it.isNotBlank() }
            val description = firstCell.ownText().ifBlank { firstCell.text() }.take(300)

            return PartInfo(
                partNumber = pn,
                nsn = nsn,
                nsnUrl = nsnUrl,
                description = description.ifBlank { null },
                pageUrl = pageUrl ?: "${BASE_URL}search.cfm?q=${Uri.encode(query)}"
            )
        }

        // ── 2) Determinar índices de columnas por encabezado
        val headers = table.select("th")
        var iPN = 0
        var iNSN = 1
        var iDesc = 2

        headers.forEachIndexed { idx, th ->
            when (th.text().trim().lowercase()) {
                "part number" -> iPN = idx
                "nsn" -> iNSN = idx
                "description" -> iDesc = idx
            }
        }

        // ── 3) Tomar PRIMERA FILA de datos
        val firstRow = table.selectFirst("tbody tr:has(td)") ?: table.selectFirst("tr:has(td)")
        ?: return null

        val tds = firstRow.select("td")
        val pnCell = tds.getOrNull(iPN)
        val nsnCell = tds.getOrNull(iNSN)
        val descCell = tds.getOrNull(iDesc)

        val pn = pnCell?.selectFirst("a, strong, b")?.text()?.trim()
            ?: pnCell?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: pnCell?.text()?.trim()
            ?: query

        val nsnAnchor = nsnCell?.selectFirst("a[href]")
        val nsnRaw = nsnAnchor?.text()?.trim()
            ?: nsnCell?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: nsnCell?.text()?.trim()
        val nsn = nsnRaw?.let { formatNsn(it) }
        val nsnUrl = nsnAnchor?.absUrl("href")?.takeIf { it.isNotBlank() }

        val description = descCell?.ownText()?.ifBlank { descCell.text() }?.trim()

        val pageUrl = pnCell?.selectFirst("a[href]")?.absUrl("href")
            ?: "${BASE_URL}search.cfm?q=${Uri.encode(query)}"

        if (pn.isBlank() && nsn.isNullOrBlank() && description.isNullOrBlank()) return null

        return PartInfo(
            partNumber = pn,
            nsn = nsn,
            nsnUrl = nsnUrl,
            description = description?.ifBlank { null },
            pageUrl = pageUrl
        )
    }

    private fun formatNsn(raw: String): String {
        val t = raw.trim()
        val digits = t.replace(Regex("[^0-9]"), "")
        return when {
            t.matches(Regex("\\b\\d{4}-\\d{2}-\\d{3}-\\d{4}\\b")) -> t
            digits.length == 13 -> "${digits.take(4)}-${digits.substring(4, 6)}-${digits.substring(6, 9)}-${digits.substring(9)}"
            else -> t
        }
    }
}
