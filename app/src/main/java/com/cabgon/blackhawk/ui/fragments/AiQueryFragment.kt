package com.cabgon.blackhawk.ui.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.ai.LocalReasoner.Mode
import com.cabgon.blackhawk.ai.LocalRetriever
import com.cabgon.blackhawk.ai.Translator
import com.cabgon.blackhawk.data.PackageManager
import com.cabgon.blackhawk.data.RAGIndex
import com.cabgon.blackhawk.databinding.FragmentAiQueryBinding
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
    private val keyMode = "answer_mode"
    private val keyExperimentalWarningSeen = "ai_query_experimental_warning_seen"

    private var answerMode: Mode = Mode.PRO

    private var allHits: List<RAGIndex.Hit> = emptyList()
    private var shownCount: Int = 0
    private val pageSize: Int = 10

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAiQueryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        NetworkGuard.internetAllowedForPartsOnly = false

        // ✅ Popup SOLO 1 vez (el banner queda permanente en la UI)
        showExperimentalPopupOnce()

        val pkg = PackageManager.Pkg.valueOf(Prefs.getPackage(requireContext())!!)
        index = RAGIndex.openFromAssets(requireContext(), PackageManager.indexAssetPath(pkg))

        retriever = LocalRetriever(requireNotNull(index))
        translator = Translator(requireContext())

        loadMode()

        resultsAdapter = ResultsAdapter(
            titlePrefix = if (answerMode == Mode.PRO) "Causa" else "Resultado",
            proMode = (answerMode == Mode.PRO)
        )
        footerAdapter = FooterAdapter {
            if (answerMode == Mode.PRO && allHits.isNotEmpty() && shownCount < allHits.size) {
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

        b.switchMode.isChecked = (answerMode == Mode.PRO)
        b.switchMode.setOnCheckedChangeListener { _, isChecked ->
            answerMode = if (isChecked) Mode.PRO else Mode.CONCISE
            saveMode()

            resultsAdapter = ResultsAdapter(
                titlePrefix = if (answerMode == Mode.PRO) "Causa" else "Resultado",
                proMode = (answerMode == Mode.PRO)
            )
            concatAdapter = ConcatAdapter(resultsAdapter, footerAdapter)
            b.rvCitations.adapter = concatAdapter

            resultsAdapter.submitList(emptyList())
            footerAdapter.show(false, 0, 0)
            allHits = emptyList()
            shownCount = 0
        }

        b.btnAsk.setOnClickListener { doQuery() }
        b.edtQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doQuery(); true } else false
        }
    }

    private fun showExperimentalPopupOnce() {
        val sp = requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (sp.getBoolean(keyExperimentalWarningSeen, false)) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Función experimental")
            .setMessage("Esta función es experimental. Consulta la sección “Manuales” para mayores referencias antes de tomar decisiones de mantenimiento.")
            .setCancelable(true)
            .setPositiveButton("Aceptar") { d, _ ->
                sp.edit().putBoolean(keyExperimentalWarningSeen, true).apply()
                d.dismiss()
            }
            .show()
    }

    private fun doQuery() {
        val qEs = b.edtQuery.text?.toString()?.trim().orEmpty()
        if (qEs.isBlank()) return

        viewLifecycleOwner.lifecycleScope.launch {
            b.btnAsk.isEnabled = false
            resultsAdapter.submitList(emptyList())
            footerAdapter.show(false, 0, 0)
            allHits = emptyList()
            shownCount = 0

            try {
                val qEn = withContext(Dispatchers.IO) {
                    runCatching {
                        translator.ensureModels()
                        translator.esToEn(qEs)
                    }.getOrElse { qEs }
                }

                val hits = withContext(Dispatchers.IO) {
                    runCatching { retriever.retrieve(qEn, limit = 200) }
                        .getOrElse { emptyList() }
                }

                allHits = if (answerMode == Mode.CONCISE) hits.take(5) else hits
                shownCount = min(pageSize, allHits.size)
                renderPage()
            } finally {
                b.btnAsk.isEnabled = true
                b.edtQuery.clearFocus()
                b.rvCitations.clearFocus()
                b.rvCitations.stopScroll()
            }
        }
    }

    private fun renderPage() {
        val pageHits = allHits.take(shownCount)
        val startIdx = shownCount - pageHits.size + 1
        resultsAdapter.submitList(resultsAdapter.mapHits(pageHits, startIdx).toList())

        val hasMore = (answerMode == Mode.PRO) && shownCount < allHits.size
        if (hasMore) footerAdapter.show(true, shownCount, allHits.size)
        else footerAdapter.show(false, shownCount, allHits.size)

        b.rvCitations.stopScroll()
    }

    private fun loadMode() {
        val sp = requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        answerMode = when (sp.getString(keyMode, "PRO")) {
            "CONCISE" -> Mode.CONCISE
            else -> Mode.PRO
        }
    }

    private fun saveMode() {
        val sp = requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        sp.edit().putString(keyMode, answerMode.name).apply()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Cierre explícito del índice SQLite para evitar leaks
        runCatching { index?.close() }
        index = null

        _b = null
    }
}
