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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val EXTRA_HIGHLIGHT_QUERY = "extra_highlight_query"

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

        // Solo esta pantalla puede usar internet (WBParts)
        NetworkGuard.internetAllowedForPartsOnly = true

        localAdapter = SikorskyPartHitAdapter { hit ->
            openPdf(hit.assetPath, hit.page)
        }

        b.rvLocalOccurrences.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = localAdapter
            isNestedScrollingEnabled = true
        }

        // Estado inicial
        b.btnSearchPart.isEnabled = false
        b.layoutWBPartsCard.visibility = View.GONE
        b.layoutLocalCard.visibility = View.GONE
        b.txtLocalMeta.visibility = View.GONE
        b.txtLocalBody.visibility = View.VISIBLE
        b.txtLocalBody.text = "Cargando índice local…"
        b.rvLocalOccurrences.visibility = View.GONE

        // Abrir índice local en IO (copia DB desde assets si aplica)
        initJob?.cancel()
        initJob = viewLifecycleOwner.lifecycleScope.launch {
            val idx = withContext(Dispatchers.IO) {
                SikorskyPartsIndex.openFromAssets(requireContext())
            }
            partsIndex = idx
            b.btnSearchPart.isEnabled = true
            b.txtLocalBody.text = "Ingresa un N/P y presiona Buscar."
        }

        b.btnSearchPart.setOnClickListener {
            val raw = b.edtPart.text?.toString().orEmpty()
            val q = raw.trim()

            // ✅ Validación operativa: mínimo 3 alfanuméricos
            val validationMsg = validatePartQuery(q)
            if (validationMsg != null) {
                // Limpia UI y muestra mensaje
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
                b.txtLocalBody.visibility = View.VISIBLE
                b.txtLocalBody.text = "El índice local aún está cargando…"
                return@setOnClickListener
            }

            // Evita doble ejecución
            b.btnSearchPart.isEnabled = false

            // Limpia estado anterior
            b.layoutWBPartsCard.visibility = View.GONE
            b.layoutLocalCard.visibility = View.GONE
            b.txtLocalMeta.visibility = View.GONE
            b.txtLocalBody.visibility = View.GONE
            b.txtLocalBody.text = ""
            b.rvLocalOccurrences.visibility = View.GONE
            localAdapter.submit(emptyList())

            viewLifecycleOwner.lifecycleScope.launch {
                // 1) WBParts (web) -> IO
                val info: PartInfo? = withContext(Dispatchers.IO) {
                    runCatching { PartRepo.searchPartInfo(q) }.getOrNull()
                }
                renderWBParts(info, fallbackQuery = q)

                // 2) Local -> IO
                val localHits: List<SikorskyPartHit> = withContext(Dispatchers.IO) {
                    runCatching { idx.search(q, limit = 500) }.getOrElse { emptyList() }
                }
                renderLocal(localHits, pnQuery = q)

                b.btnSearchPart.isEnabled = true
            }
        }

        b.layoutLocalCard.setOnClickListener(null)
    }

    /**
     * Regla:
     * - Si no hay alfanuméricos: inválido
     * - Si hay 1 o 2 alfanuméricos: muy corto
     */
    private fun validatePartQuery(q: String): String? {
        if (q.isBlank()) return "Ingresa un N/P."

        val alphaNumCount = q.count { it.isLetterOrDigit() }
        return when {
            alphaNumCount == 0 -> "Formato de N/P inválido."
            alphaNumCount in 1..2 -> "N/P muy corto. Ingresa al menos 3 caracteres."
            else -> null
        }
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
        initJob?.cancel()
        initJob = null

        runCatching { partsIndex?.close() }
        partsIndex = null

        _b = null
    }
}
