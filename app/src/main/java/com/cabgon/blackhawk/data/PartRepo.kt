// app/src/main/java/com/cabgon/blackhawk/data/PartRepo.kt
package com.cabgon.blackhawk.data

import android.net.Uri
import com.squareup.moshi.Moshi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

data class PartInfo(
    val partNumber: String?,
    val nsn: String?,
    val nsnUrl: String?,
    val description: String?,
    val pageUrl: String?      // ← para "Ir al sitio web"
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

    private fun api(): PartApi {
        val moshi = Moshi.Builder().build()
        val ok = OkHttpClient.Builder()
            .addInterceptor(ua)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(ok)
            .build()

        return retrofit.create(PartApi::class.java)
    }

    suspend fun searchPartInfo(q: String): PartInfo? {
        val resp = api().search(q)
        if (!resp.isSuccessful) return null
        val html = resp.body().orEmpty()
        val doc = Jsoup.parse(html, BASE_URL)

        // ── 1) Ubicar tabla con encabezado "Part Number" (WBParts)
        val table = doc.selectFirst("table:has(th:matchesOwn((?i)^\\s*Part\\s*Number\\s*$))")
            ?: doc.select("table").firstOrNull { t ->
                t.select("th").any { it.text().trim().equals("Part Number", true) }
            }

        // Si no se encontró, fallback genérico anterior
        if (table == null) {
            // Fallback: tomar primer "resultado" visible
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
            val nsn = nsnRaw?.let {
                val digits = it.replace(Regex("[^0-9]"), "")
                when {
                    it.matches(Regex("\\b\\d{4}-\\d{2}-\\d{3}-\\d{4}\\b")) -> it
                    digits.length == 13 -> "${digits.take(4)}-${digits.substring(4,6)}-${digits.substring(6,9)}-${digits.substring(9)}"
                    else -> it
                }
            }
            val nsnUrl = nsnAnchor?.absUrl("href")?.takeIf { it.isNotBlank() }
            val description = firstCell.ownText().ifBlank { firstCell.text() }.take(300)

            return PartInfo(
                partNumber = pn,
                nsn = nsn,
                nsnUrl = nsnUrl,
                description = description.ifBlank { null },
                pageUrl = pageUrl ?: "${BASE_URL}search.cfm?q=${Uri.encode(q)}"
            )
        }

        // ── 2) Determinar índices de columnas por encabezado
        val headers = table.select("th")
        var iPN = 0; var iNSN = 1; var iDesc = 2
        headers.forEachIndexed { idx, th ->
            when (th.text().trim().lowercase()) {
                "part number" -> iPN = idx
                "nsn" -> iNSN = idx
                "description" -> iDesc = idx
            }
        }

        // ── 3) Tomar PRIMERA FILA de datos y sus 3 celdas
        val firstRow = table.selectFirst("tbody tr:has(td)") ?: table.selectFirst("tr:has(td)")
        ?: return null
        val tds = firstRow.select("td")
        val pnCell = tds.getOrNull(iPN)
        val nsnCell = tds.getOrNull(iNSN)
        val descCell = tds.getOrNull(iDesc)

        // PN (preferir <a>, luego <strong>/<b>, luego texto)
        val pn = pnCell?.selectFirst("a, strong, b")?.text()?.trim()
            ?: pnCell?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: pnCell?.text()?.trim()
            ?: q

        // NSN (texto y URL del <a> si existe)
        val nsnAnchor = nsnCell?.selectFirst("a[href]")
        val nsnRaw = nsnAnchor?.text()?.trim()
            ?: nsnCell?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: nsnCell?.text()?.trim()
        val nsn = nsnRaw?.let {
            val digits = it.replace(Regex("[^0-9]"), "")
            when {
                it.matches(Regex("\\b\\d{4}-\\d{2}-\\d{3}-\\d{4}\\b")) -> it
                digits.length == 13 -> "${digits.take(4)}-${digits.substring(4,6)}-${digits.substring(6,9)}-${digits.substring(9)}"
                else -> it
            }
        }
        val nsnUrl = nsnAnchor?.absUrl("href")?.takeIf { it.isNotBlank() }

        // Descripción
        val description = descCell?.ownText()?.ifBlank { descCell.text() }?.trim()

        // URL de detalle (anchor en el PN)
        val pageUrl = pnCell?.selectFirst("a[href]")?.absUrl("href")
            ?: "${BASE_URL}search.cfm?q=${Uri.encode(q)}"

        // Si está vacío todo, devolver null
        if (pn.isBlank() && (nsn.isNullOrBlank()) && (description.isNullOrBlank())) return null

        return PartInfo(
            partNumber = pn,
            nsn = nsn,
            nsnUrl = nsnUrl,
            description = description?.ifBlank { null },
            pageUrl = pageUrl
        )
    }

}
