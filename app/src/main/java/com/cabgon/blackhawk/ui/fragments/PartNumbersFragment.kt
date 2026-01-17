// app/src/main/java/com/cabgon/blackhawk/ui/fragments/PartNumbersFragment.kt
package com.cabgon.blackhawk.ui.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.PackageManager
import com.cabgon.blackhawk.data.PartInfo
import com.cabgon.blackhawk.data.PartRepo
import com.cabgon.blackhawk.data.RAGIndex
import com.cabgon.blackhawk.databinding.FragmentPartNumbersBinding
import com.cabgon.blackhawk.ui.pdf.PdfViewerActivity
import com.cabgon.blackhawk.ui.pdf.PdfViewerActivity.Companion.EXTRA_ASSET_PATH
import com.cabgon.blackhawk.ui.pdf.PdfViewerActivity.Companion.EXTRA_PAGE
import com.cabgon.blackhawk.util.NetworkGuard
import com.cabgon.blackhawk.util.Prefs
import kotlinx.coroutines.launch

private const val EXTRA_HIGHLIGHT_QUERY = "extra_highlight_query"

class PartNumbersFragment : Fragment() {

    private var _b: FragmentPartNumbersBinding? = null
    private val b get() = _b!!
    private lateinit var index: RAGIndex

    // Guardamos el último hit local para abrirlo al tocar la card
    private var lastLocalAsset: String? = null
    private var lastLocalPage: Int? = null

    private data class LocalMeta(
        val pn: String? = null,
        val nsn: String? = null,
        val description: String? = null,
    )

    /** Hook opcional para futuro meta local */
    private fun fetchLocalMeta(manual: String, page: Int): LocalMeta? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentPartNumbersBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        NetworkGuard.internetAllowedForPartsOnly = true

        val pkg = PackageManager.Pkg.valueOf(Prefs.getPackage(requireContext())!!)
        index = RAGIndex.openFromAssets(requireContext(), PackageManager.indexAssetPath(pkg))

        b.btnSearchPart.setOnClickListener {
            val q = b.edtPart.text?.toString()?.trim().orEmpty()
            if (q.isBlank()) return@setOnClickListener

            viewLifecycleOwner.lifecycleScope.launch {
                // 1) WBParts (web) — estable, sin cambios
                val info: PartInfo? = runCatching { PartRepo.searchPartInfo(q) }.getOrNull()
                renderWBParts(info, fallbackQuery = q)

                // 2) Local (FTS)
                val local = index.searchEnglish(q, limit = 5).firstOrNull()
                renderLocal(local?.manual, local?.page, pnFromQuery = q)
            }
        }

        // Tap en la tarjeta Local para abrir PDF si hay hit (con highlight)
        b.layoutLocalCard.setOnClickListener {
            val asset = lastLocalAsset
            val page = lastLocalPage
            if (!asset.isNullOrBlank() && page != null) {
                openPdf(asset, page) // manda EXTRA_HIGHLIGHT_QUERY
            }
        }
    }

    private fun renderWBParts(info: PartInfo?, fallbackQuery: String) {
        b.layoutWBPartsCard.visibility = View.VISIBLE
        b.imgWBParts.setImageResource(R.drawable.logo_wb)
        b.txtWBPartsTitle.text = "WBParts"

        val pn = info?.partNumber ?: fallbackQuery
        val nsn = info?.nsn ?: "-"
        val desc = info?.description ?: "-"

        val text = "Part Number: $pn\nNSN: $nsn\nDescription: $desc"
        val span = SpannableString(text)

        val nsnUrl = info?.nsnUrl
        if (!nsnUrl.isNullOrBlank() && nsn != "-") {
            val start = text.indexOf("NSN: ") + 5
            val end = start + nsn.length
            if (start in 0 until end && end <= text.length) {
                span.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, nsnUrl.toUri())) }
                    }
                }, start, end, 0)
                b.txtWBPartsBody.movementMethod = LinkMovementMethod.getInstance()
            }
        }

        b.txtWBPartsBody.text = span

        val goUrl = info?.pageUrl ?: "https://www.wbparts.com/search.cfm?q=${Uri.encode(fallbackQuery)}"
        b.txtWBPartsGo.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, goUrl.toUri())) }
        }
    }

    private fun renderLocal(manual: String?, page: Int?, pnFromQuery: String = "-") {
        b.layoutLocalCard.visibility = View.VISIBLE
        b.imgLocal.setImageResource(R.mipmap.ic_launcher)
        b.txtLocalTitle.text = "Local"

        val body = if (manual != null && page != null) {
            lastLocalAsset = manual
            lastLocalPage = page

            val manualName = manual.substringAfterLast('/').substringBeforeLast('.') // solo nombre
            val pnUpper = pnFromQuery.uppercase().ifBlank { "-" }

            "Manual: $manualName\n" +
                    "Página: $page\n" +
                    "Part Number: $pnUpper\n" +
                    "(Toca para abrir)"
        } else {
            lastLocalAsset = null
            lastLocalPage = null
            "Sin coincidencias locales."
        }
        b.txtLocalBody.text = body
    }

    /** Única versión de openPdf: abre y manda el query para highlight */
    private fun openPdf(assetPath: String, page1Based: Int) {
        val q = b.edtPart.text?.toString()?.trim().orEmpty()
        startActivity(
            Intent(requireContext(), PdfViewerActivity::class.java).apply {
                putExtra(EXTRA_ASSET_PATH, assetPath)
                putExtra(EXTRA_PAGE, page1Based)
                putExtra(EXTRA_HIGHLIGHT_QUERY, q) // ← se usa en el viewer para resaltar
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
