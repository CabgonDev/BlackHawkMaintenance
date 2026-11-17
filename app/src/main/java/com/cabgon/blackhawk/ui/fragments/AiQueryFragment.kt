package com.cabgon.blackhawk.ui.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.ai.LocalRetriever
import com.cabgon.blackhawk.ai.TechnicalQueryPlanner
import com.cabgon.blackhawk.ai.Translator
import com.cabgon.blackhawk.data.PackageManager
import com.cabgon.blackhawk.data.RAGIndex
import com.cabgon.blackhawk.databinding.FragmentAiQueryBinding
import com.cabgon.blackhawk.ui.pdf.PdfViewerActivity
import com.cabgon.blackhawk.util.NetworkGuard
import com.cabgon.blackhawk.util.Prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

class AiQueryFragment : Fragment() {

    private var _b: FragmentAiQueryBinding? = null
    private val b get() = _b!!

    private lateinit var translator: Translator
    private lateinit var retriever: LocalRetriever
    private var index: RAGIndex? = null

    private lateinit var resultsAdapter: ResultsAdapter
    private lateinit var footerAdapter: FooterAdapter
    private lateinit var concatAdapter: ConcatAdapter

    private val prefsName = "ia_prefs"
    private val keyExperimentalWarningSeen = "ai_query_experimental_warning_seen"

    private var allHits: List<RAGIndex.Hit> = emptyList()
    private var shownCount: Int = 0
    private val pageSize: Int = 8

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAiQueryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        NetworkGuard.internetAllowedForPartsOnly = false
        showExperimentalPopupOnce()

        val pkg = currentPackage()
        b.tvPackageValue.text = pkg.name

        val indexPath = PackageManager.indexAssetPath(pkg)
        index = RAGIndex.openFromFilesOrNull(requireContext(), indexPath)

        setupAdapters()
        setupSearchListeners()

        if (index == null) {
            setSearchEnabled(false)
            setStatus(
                title = "Indice de busqueda no instalado",
                subtitle = "Conectate a internet y abre la app para descargar el indice local del paquete ${pkg.name}."
            )
            showEmpty(
                title = "Sin indice local",
                subtitle = "La busqueda asistida necesita el indice RAG del paquete activo."
            )
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Indice no instalado")
                .setMessage(
                    "El indice de busqueda IA no esta instalado en este dispositivo.\n\n" +
                            "Conectate a internet y abre la app para que se descargue automaticamente."
                )
                .setCancelable(true)
                .setPositiveButton("Aceptar") { d, _ -> d.dismiss() }
                .show()
            return
        }

        retriever = LocalRetriever(requireNotNull(index))
        translator = Translator(requireContext())
        setSearchEnabled(true)
        setStatus(
            title = "Busqueda asistida lista",
            subtitle = "Busca en los manuales locales. Los numeros de parte permanecen en su seccion independiente."
        )
        showEmpty(
            title = "Listo para consultar manuales",
            subtitle = "Escribe un sistema, sintoma, procedimiento, warning, caution, limite o referencia tecnica."
        )
    }

    private fun currentPackage(): PackageManager.Pkg {
        val saved = Prefs.getPackage(requireContext()) ?: PackageManager.Pkg.IADS.name
        return runCatching { PackageManager.Pkg.valueOf(saved) }.getOrDefault(PackageManager.Pkg.IADS)
    }

    private fun setupAdapters() {
        resultsAdapter = ResultsAdapter { hit -> openManual(hit) }
        footerAdapter = FooterAdapter {
            if (allHits.isNotEmpty() && shownCount < allHits.size) {
                shownCount = min(shownCount + pageSize, allHits.size)
                renderPage()
            }
        }
        concatAdapter = ConcatAdapter(resultsAdapter, footerAdapter)

        b.rvCitations.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = false
                reverseLayout = false
            }
            adapter = concatAdapter
            setHasFixedSize(true)
            isFocusable = false
            isFocusableInTouchMode = false
            itemAnimator = null
        }
    }

    private fun setupSearchListeners() {
        b.btnAsk.setOnClickListener { doQuery() }
        b.edtQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doQuery()
                true
            } else {
                false
            }
        }
    }

    private fun showExperimentalPopupOnce() {
        val sp = requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (sp.getBoolean(keyExperimentalWarningSeen, false)) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Funcion experimental")
            .setMessage("Esta busqueda es asistida por IA local y puede priorizar referencias, pero no sustituye la verificacion directa en manuales oficiales.")
            .setCancelable(true)
            .setPositiveButton("Aceptar") { d, _ ->
                sp.edit().putBoolean(keyExperimentalWarningSeen, true).apply()
                d.dismiss()
            }
            .show()
    }

    private fun doQuery() {
        val qEs = b.edtQuery.text?.toString()?.trim().orEmpty()
        if (qEs.isBlank() || index == null) return

        hideKeyboard()

        viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            resultsAdapter.submitList(emptyList())
            footerAdapter.show(false, 0, 0)
            allHits = emptyList()
            shownCount = 0
            b.emptyState.visibility = View.GONE
            b.tvResultCount.text = "Buscando referencias..."

            try {
                val plan = withContext(Dispatchers.IO) { buildSearchPlan(qEs) }
                val hits = withContext(Dispatchers.IO) { retrieveWithPlan(plan) }

                allHits = hits
                shownCount = allHits.size
                renderPage()

                if (allHits.isEmpty()) {
                    showEmpty(
                        title = "Sin resultados claros",
                        subtitle = "Intenta con el nombre del sistema en ingles/espanol, una condicion mas especifica o una palabra exacta del manual."
                    )
                    b.tvResultCount.text = "0 resultados"
                } else {
                    b.emptyState.visibility = View.GONE
                    b.tvResultCount.text = "${allHits.size} referencias encontradas"
                }
            } finally {
                setLoading(false)
                b.edtQuery.clearFocus()
                b.rvCitations.clearFocus()
                b.rvCitations.stopScroll()
            }
        }
    }

    private suspend fun buildSearchPlan(qEs: String): TechnicalQueryPlanner.Plan {
        val qEn = runCatching {
            translator.ensureModels()
            translator.esToEn(qEs)
        }.getOrElse { qEs }
        return TechnicalQueryPlanner.build(original = qEs, translated = qEn)
    }

    private fun retrieveWithPlan(plan: TechnicalQueryPlanner.Plan): List<RAGIndex.Hit> {
        data class Candidate(val hit: RAGIndex.Hit, val queryOrder: Int)

        val candidates = ArrayList<Candidate>()
        plan.queries.forEachIndexed { index, query ->
            val hits = runCatching { retriever.retrieve(query, limit = 60) }
                .getOrElse { emptyList() }
            hits.forEach { candidates += Candidate(it, index) }
        }

        val filteredCandidates = candidates.filterNot { isPartsManual(it.hit.manual) }
        if (filteredCandidates.isEmpty()) return emptyList()

        fun normalizedScore(hit: RAGIndex.Hit): Double = if (hit.score == 0.0) 1e9 else hit.score
        fun tokenBoost(hit: RAGIndex.Hit): Int {
            val text = (hit.snippet + " " + hit.manual).lowercase()
            return plan.keywords.count { token -> token.length >= 3 && text.contains(token.lowercase()) }
        }

        return filteredCandidates
            .groupBy { "${it.hit.manual}:${it.hit.page}" }
            .map { (_, group) ->
                group.minWith(
                    compareBy<Candidate> { normalizedScore(it.hit) }
                        .thenBy { it.queryOrder }
                )
            }
            .sortedWith(
                compareBy<Candidate> { normalizedScore(it.hit) }
                    .thenByDescending { tokenBoost(it.hit) }
                    .thenBy { it.queryOrder }
            )
            .map { it.hit }
            .take(100)
    }

    private fun isPartsManual(manual: String): Boolean {
        val normalized = manual.lowercase().replace('\\', '/')
        val fileName = normalized.substringAfterLast('/')

        if (Regex("""(^|[/_\s.-])23p($|[/_\s.-])""").containsMatchIn(normalized)) return true
        if (Regex("""(^|[/_\s.-])ipc($|[/_\s.-])""").containsMatchIn(normalized)) return true

        val partsTerms = listOf(
            "repair parts",
            "parts and special tools",
            "special tools list",
            "illustrated parts",
            "parts catalog",
            "parts catalogue",
            "catalogo de partes",
            "catalogo ilustrado",
            "catalogue of parts",
            "manual de partes",
            "parts manual"
        )

        if (partsTerms.any { normalized.contains(it) }) return true

        return fileName.contains("parts") &&
                (fileName.contains("catalog") || fileName.contains("ipc") || fileName.contains("repair") || fileName.contains("rpstl"))
    }

    private fun renderPage() {
        val pageHits = allHits.take(shownCount)
        resultsAdapter.submitList(resultsAdapter.mapHits(pageHits).toList())

        footerAdapter.show(false, shownCount, allHits.size)
        b.rvCitations.stopScroll()
    }

    private fun setSearchEnabled(enabled: Boolean) {
        b.btnAsk.isEnabled = enabled
        b.edtQuery.isEnabled = enabled
    }

    private fun setLoading(loading: Boolean) {
        b.btnAsk.isEnabled = !loading && index != null
        b.edtQuery.isEnabled = !loading && index != null
        b.progressSearch.visibility = if (loading) View.VISIBLE else View.GONE
        b.btnAsk.text = if (loading) "Consultando..." else "Buscar en manuales"
    }

    private fun setStatus(title: String, subtitle: String) {
        b.tvStatusTitle.text = title
        b.tvStatusSubtitle.text = subtitle
    }

    private fun showEmpty(title: String, subtitle: String) {
        b.emptyState.visibility = View.VISIBLE
        b.tvEmptyTitle.text = title
        //b.tvEmptySubtitle.text = subtitle
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.edtQuery.windowToken, 0)
    }

    private fun openManual(hit: RAGIndex.Hit) {
        val page = hit.page.coerceAtLeast(1)
        val matchedTerms = Regex("""\[([^\]]+)]""")
            .findAll(hit.snippet)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .flatMap { term -> sequenceOf(term) + term.split(Regex("\\s+")).asSequence() }
            .distinct()
            .take(12)
            .toList()
        val intent = android.content.Intent(requireContext(), PdfViewerActivity::class.java).apply {
            putExtra(PdfViewerActivity.EXTRA_ASSET_PATH, hit.manual)
            putExtra(PdfViewerActivity.EXTRA_PAGE, page)
            putStringArrayListExtra(PdfViewerActivity.EXTRA_HIGHLIGHT_QUERIES, ArrayList(matchedTerms))
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        runCatching { index?.close() }
        index = null
        _b = null
    }
}
