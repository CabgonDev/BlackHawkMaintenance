package com.cabgon.blackhawk.ui.fragments

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.net.toUri
import androidx.core.view.isVisible
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
import com.cabgon.blackhawk.ui.pdf.PdfViewerActivity.Companion.EXTRA_HIGHLIGHT_QUERIES
import com.cabgon.blackhawk.ui.pdf.PdfViewerActivity.Companion.EXTRA_PAGE
import com.cabgon.blackhawk.ui.pdf.PdfViewerActivity.Companion.EXTRA_PART_DESCRIPTION
import com.cabgon.blackhawk.ui.pdf.PdfViewerActivity.Companion.EXTRA_PART_DESCRIPTION_NSN
import com.cabgon.blackhawk.ui.pdf.PdfViewerActivity.Companion.EXTRA_PART_DESCRIPTION_NUMBER
import com.cabgon.blackhawk.util.NetworkGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PartNumbersFragment : Fragment() {

    private var _b: FragmentPartNumbersBinding? = null
    private val b get() = _b!!

    private var partsIndex: SikorskyPartsIndex? = null
    private lateinit var localAdapter: SikorskyPartHitAdapter

    private var initJob: Job? = null

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

        NetworkGuard.internetAllowedForPartsOnly = true

        localAdapter = SikorskyPartHitAdapter { hit ->
            openPdf(
                hit = hit,
                queries = listOfNotNull(
                    b.edtPart.text?.toString()?.trim(),
                    hit.partNumber,
                    hit.nsn
                )
            )
        }

        b.rvLocalOccurrences.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = localAdapter
            isNestedScrollingEnabled = false
            setHasFixedSize(false)
        }

        b.btnSearchPart.isEnabled = false
        b.layoutWBPartsCard.visibility = View.GONE
        b.layoutLocalCard.visibility = View.GONE
        b.txtLocalMeta.visibility = View.GONE
        b.txtLocalBody.visibility = View.VISIBLE
        b.txtLocalBody.text = "Cargando índice local…"
        b.rvLocalOccurrences.visibility = View.GONE
        b.layoutSearching.isVisible = false

        initJob?.cancel()
        initJob = viewLifecycleOwner.lifecycleScope.launch {
            val idx = withContext(Dispatchers.IO) {
                SikorskyPartsIndex.openFromAssets(requireContext())
            }
            if (_b == null) return@launch
            partsIndex = idx
            b.btnSearchPart.isEnabled = true
            b.txtLocalBody.text = "Ingresa un N/P y presiona Buscar."
        }

        b.btnSearchPart.setOnClickListener {
            hideKeyboard()

            val raw = b.edtPart.text?.toString().orEmpty()
            val q = raw.trim()

            val validationMsg = validatePartQuery(q)
            if (validationMsg != null) {
                showSearching(false)
                b.layoutWBPartsCard.visibility = View.GONE
                b.layoutLocalCard.visibility = View.VISIBLE
                b.imgLocal.setImageResource(R.drawable.ic_uh60_front)
                b.txtLocalTitle.text = "Local"
                b.txtLocalMeta.visibility = View.GONE
                b.txtLocalBody.visibility = View.VISIBLE
                b.txtLocalBody.text = validationMsg
                b.rvLocalOccurrences.visibility = View.GONE
                localAdapter.submit(emptyList())
                return@setOnClickListener
            }

            val idx = partsIndex
            if (idx == null) {
                showSearching(false)
                b.txtLocalBody.visibility = View.VISIBLE
                b.txtLocalBody.text = "El índice local aún está cargando…"
                return@setOnClickListener
            }

            b.btnSearchPart.isEnabled = false
            b.btnSearchPart.text = "Buscando…"
            showSearching(true)

            b.layoutWBPartsCard.visibility = View.GONE
            b.layoutLocalCard.visibility = View.GONE
            b.txtLocalMeta.visibility = View.GONE
            b.txtLocalBody.visibility = View.GONE
            b.txtLocalBody.text = ""
            b.rvLocalOccurrences.visibility = View.GONE
            localAdapter.submit(emptyList())

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val info: PartInfo? = withContext(Dispatchers.IO) {
                        runCatching { PartRepo.searchPartInfo(q) }.getOrNull()
                    }
                    if (_b == null) return@launch
                    renderWBParts(info, fallbackQuery = q)

                    val localHits: List<SikorskyPartHit> = withContext(Dispatchers.IO) {
                        runCatching { idx.search(q, limit = 500) }.getOrElse { emptyList() }
                    }
                    if (_b == null) return@launch
                    renderLocal(localHits, pnQuery = q)
                } finally {
                    if (_b != null) {
                        b.btnSearchPart.isEnabled = true
                        b.btnSearchPart.text = "Buscar"
                        showSearching(false)
                    }
                }
            }
        }

        b.layoutLocalCard.setOnClickListener(null)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(b.edtPart.windowToken, 0)
        b.edtPart.clearFocus()
    }

    private fun validatePartQuery(q: String): String? {
        if (q.isBlank()) return "Ingresa un N/P."

        val alphaNumCount = q.count { it.isLetterOrDigit() }
        return when {
            alphaNumCount == 0 -> "Formato de N/P inválido."
            alphaNumCount in 1..2 -> "N/P muy corto. Ingresa al menos 3 caracteres."
            else -> null
        }
    }

    private fun showSearching(show: Boolean) {
        if (_b == null) return
        b.layoutSearching.isVisible = show
        b.txtSearchStatus.text = if (show) "Buscando en WBParts e índice local…" else ""
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

        b.txtWBPartsBody.text = "Part Number: $pn\nNSN: $nsn\nDescription: $desc"

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

        val total = hits.size
        b.txtLocalTitle.text = "Local ($total coincidencias)"

        val first = hits.first()
        val pn = (first.partNumber ?: pnQuery).ifBlank { pnQuery }
        val nsn = (first.nsn ?: "-").ifBlank { "-" }

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

        b.txtLocalBody.visibility = View.VISIBLE
        b.txtLocalBody.text = "Toca una fila para abrir el manual. Mostrando $total filas."

        b.rvLocalOccurrences.visibility = View.VISIBLE
        localAdapter.submit(hits)
    }

    private fun openPdf(hit: SikorskyPartHit, queries: List<String>) {
        val validQueries = queries.map(String::trim).filter(String::isNotBlank).distinct()
        startActivity(
            Intent(requireContext(), PdfViewerActivity::class.java).apply {
                putExtra(EXTRA_ASSET_PATH, hit.assetPath)
                putExtra(EXTRA_PAGE, hit.page)
                putExtra(PdfViewerActivity.EXTRA_SHOW_PART_SEARCH_LOADING, true)
                putStringArrayListExtra(EXTRA_HIGHLIGHT_QUERIES, ArrayList(validQueries))
                hit.description?.trim()?.takeIf(String::isNotBlank)?.let {
                    putExtra(EXTRA_PART_DESCRIPTION, it)
                }
                hit.partNumber?.trim()?.takeIf(String::isNotBlank)?.let {
                    putExtra(EXTRA_PART_DESCRIPTION_NUMBER, it)
                }
                hit.nsn?.trim()?.takeIf(String::isNotBlank)?.let {
                    putExtra(EXTRA_PART_DESCRIPTION_NSN, it)
                }
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        initJob?.cancel()
        initJob = null

        runCatching { partsIndex?.close() }
        partsIndex = null

        _b = null
    }
}
