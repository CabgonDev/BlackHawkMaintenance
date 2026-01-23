package com.cabgon.blackhawk.ui.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.data.PartInfo
import com.cabgon.blackhawk.data.PartRepo
import com.cabgon.blackhawk.data.SikorskyPartHit
import com.cabgon.blackhawk.data.SikorskyPartsIndex
import com.cabgon.blackhawk.databinding.FragmentPartNumbersBinding
import com.cabgon.blackhawk.ui.adapters.SikorskyPartHitAdapter
import com.cabgon.blackhawk.ui.pdf.PdfViewerActivity
import com.cabgon.blackhawk.ui.pdf.PdfViewerActivity.Companion.EXTRA_ASSET_PATH
import com.cabgon.blackhawk.ui.pdf.PdfViewerActivity.Companion.EXTRA_PAGE
import com.cabgon.blackhawk.util.NetworkGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val EXTRA_HIGHLIGHT_QUERY = "extra_highlight_query"

class PartNumbersFragment : Fragment() {

    private var _b: FragmentPartNumbersBinding? = null
    private val b get() = _b!!

    private lateinit var partsIndex: SikorskyPartsIndex
    private lateinit var localAdapter: SikorskyPartHitAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentPartNumbersBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Solo esta pantalla puede usar internet (WBParts)
        NetworkGuard.internetAllowedForPartsOnly = true

        // Índice local Sikorsky de números de parte (offline, DB en assets/index)
        partsIndex = SikorskyPartsIndex.openFromAssets(requireContext())

        // Lista de resultados locales (RecyclerView con scroll normal)
        localAdapter = SikorskyPartHitAdapter { hit ->
            openPdf(hit.assetPath, hit.page)
        }

        b.rvLocalOccurrences.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = localAdapter
            isNestedScrollingEnabled = true
        }

        b.btnSearchPart.setOnClickListener {
            val q = b.edtPart.text?.toString()?.trim().orEmpty()
            if (q.isBlank()) return@setOnClickListener

            // Limpia estado anterior
            b.layoutWBPartsCard.visibility = View.GONE
            b.layoutLocalCard.visibility = View.GONE
            b.txtLocalMeta.visibility = View.GONE
            b.txtLocalBody.text = ""
            b.rvLocalOccurrences.visibility = View.GONE
            localAdapter.submit(emptyList())

            viewLifecycleOwner.lifecycleScope.launch {
                // 1) WBParts (web)
                val info: PartInfo? = runCatching { PartRepo.searchPartInfo(q) }.getOrNull()
                renderWBParts(info, fallbackQuery = q)

                // 2) Local (índice Sikorsky offline) – SIN límite artificial
                val localHits: List<SikorskyPartHit> = withContext(Dispatchers.IO) {
                    try {
                        partsIndex.search(q, limit = 500)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                renderLocal(localHits, pnQuery = q)
            }
        }

        // La tarjeta Local ya no abre nada; el click es directo en cada fila del RecyclerView
        b.layoutLocalCard.setOnClickListener(null)
    }

    private fun renderWBParts(info: PartInfo?, fallbackQuery: String) {
        if (info == null && fallbackQuery.isBlank()) {
            b.layoutWBPartsCard.visibility = View.GONE
            return
        }

        b.layoutWBPartsCard.visibility = View.VISIBLE
        b.imgWBParts.setImageResource(R.drawable.logo_wb)
        b.txtWBPartsTitle.text = "WBParts"

        val pn = info?.partNumber ?: fallbackQuery
        val nsn = info?.nsn ?: "-"
        val desc = info?.description ?: "-"

        // Texto plano, SIN NSN clicable
        val text = "Part Number: $pn\nNSN: $nsn\nDescription: $desc"
        b.txtWBPartsBody.text = text

        // Botón / chip "WBParts Web" para abrir en navegador
        val goUrl = info?.pageUrl
            ?: "https://www.wbparts.com/search.cfm?q=${Uri.encode(fallbackQuery)}"

        b.txtWBPartsGo.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, goUrl.toUri())) }
        }
    }

    private fun renderLocal(hits: List<SikorskyPartHit>, pnQuery: String) {
        b.layoutLocalCard.visibility = View.VISIBLE
        b.imgLocal.setImageResource(R.drawable.ic_uh60_front)

        if (hits.isEmpty()) {
            b.txtLocalTitle.text = "Local"
            b.txtLocalMeta.visibility = View.GONE
            b.txtLocalBody.visibility = View.VISIBLE
            b.txtLocalBody.text = "Sin coincidencias locales."
            b.rvLocalOccurrences.visibility = View.GONE
            return
        }

        val effectiveHits = hits
        val total = effectiveHits.size

        b.txtLocalTitle.text = "Local ($total coincidencias)"

        val first = effectiveHits.first()
        val pn = (first.partNumber ?: pnQuery).ifBlank { pnQuery }
        val nsn = (first.nsn ?: "-").ifBlank { "-" }

        // Descripción PRINCIPAL: solo 2 palabras
        val rawDesc = (first.description ?: "")
            .replace('\n', ' ')
            .trim()

        val shortDesc = rawDesc
            .split(Regex("\\s+"))
            .take(2)
            .joinToString(" ")

        val desc = if (shortDesc.isNotBlank()) shortDesc else "-"

        b.txtLocalMeta.visibility = View.VISIBLE
        b.txtLocalMeta.text = "Part Number: $pn\nNSN: $nsn\nDesc: $desc"

        b.txtLocalBody.visibility = View.GONE
        b.txtLocalBody.text = "Toca una fila para abrir el manual."

        b.rvLocalOccurrences.visibility = View.VISIBLE
        localAdapter.submit(effectiveHits)
    }

    /** Abre el PDF y manda el query para highlight */
    private fun openPdf(assetPath: String, page1Based: Int) {
        val q = b.edtPart.text?.toString()?.trim().orEmpty()
        startActivity(
            Intent(requireContext(), PdfViewerActivity::class.java).apply {
                putExtra(EXTRA_ASSET_PATH, assetPath)
                putExtra(EXTRA_PAGE, page1Based)
                putExtra(EXTRA_HIGHLIGHT_QUERY, q)
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
