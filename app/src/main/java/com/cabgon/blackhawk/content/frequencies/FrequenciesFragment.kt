package com.cabgon.blackhawk.content.frequencies

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.databinding.FragmentFrequenciesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FrequenciesFragment : Fragment() {

    private var _b: FragmentFrequenciesBinding? = null
    private val b get() = _b!!

    private val adapter = FrequenciesAdapter { item ->
        FrequencyDetailBottomSheet
            .newInstance(item)
            .show(parentFragmentManager, "freq_detail")
    }

    /** Source of truth */
    private var all: List<FrequencyItem> = emptyList()
    private var baseStatusMessage: String = ""
    private var dataLoaded = false
    private var filtersExpanded = false

    private companion object {
        private const val CITY_ALL = "TODAS"

        // Persistencia (Bundle)
        private const val STATE_QUERY = "freq_query"
        private const val STATE_CITY = "freq_city"
        private const val STATE_FILTERS_EXPANDED = "freq_filters_expanded"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentFrequenciesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter
        b.recycler.setHasFixedSize(true)

        val restoredQuery = savedInstanceState?.getString(STATE_QUERY).orEmpty()
        val restoredCity = savedInstanceState?.getString(STATE_CITY)
        val restoredExpanded = savedInstanceState?.getBoolean(STATE_FILTERS_EXPANDED)

        if (restoredQuery.isNotEmpty()) {
            b.etSearch.setText(restoredQuery)
            b.etSearch.setSelection(restoredQuery.length)
        }

        if (!restoredCity.isNullOrBlank()) {
            b.acCity.setText(restoredCity, false)
        }

        filtersExpanded = restoredExpanded ?: hasActiveFilters()
        renderFiltersExpandedState(animated = false)

        b.layoutToggleFilters.setOnClickListener {
            filtersExpanded = !filtersExpanded
            renderFiltersExpandedState(animated = true)

            if (filtersExpanded) {
                b.etSearch.requestFocus()
            } else {
                hideKeyboard(b.etSearch)
            }
        }

        b.etSearch.setOnEditorActionListener { v, actionId, event ->
            val isSearch = actionId == EditorInfo.IME_ACTION_SEARCH
            val isDone = actionId == EditorInfo.IME_ACTION_DONE
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (isSearch || isDone || isEnter) {
                hideKeyboard(v)
                applyFilters()
                true
            } else false
        }

        b.etSearch.addTextChangedListener {
            applyFilters()
            syncCollapsedHint()
        }

        b.acCity.setOnItemClickListener { _, _, _, _ ->
            hideKeyboard(b.etSearch)
            applyFilters()
            syncCollapsedHint()
        }

        b.btnClearFilters.setOnClickListener {
            b.etSearch.setText("")
            b.acCity.setText(CITY_ALL, false)
            hideKeyboard(b.etSearch)
            applyFilters()
            syncCollapsedHint()
        }

        baseStatusMessage = getString(R.string.frequencies_status_loading)
        renderHeaderStatus(summary = getString(R.string.frequencies_results_summary_placeholder))
        showLoading(true)
        showEmptyState(
            visible = false,
            title = getString(R.string.frequencies_empty_title),
            body = getString(R.string.frequencies_empty_body)
        )

        lifecycleScope.launch {
            val offlineAtStart = !isNetworkAvailable(requireContext().applicationContext)
            var usedLocalFallback = offlineAtStart

            FrequenciesUpdateManager.checkAndUpdate(requireContext().applicationContext) { ev ->
                when (ev) {
                    is FrequenciesUpdateManager.Event.Applied -> usedLocalFallback = false
                    is FrequenciesUpdateManager.Event.Error -> usedLocalFallback = true
                    is FrequenciesUpdateManager.Event.Skipped -> usedLocalFallback = true
                    else -> Unit
                }

                val message = when (ev) {
                    is FrequenciesUpdateManager.Event.Checking -> "Comprobando actualización de Frecuencias…"
                    is FrequenciesUpdateManager.Event.ChannelSelected -> "Canal: ${ev.channel}"
                    is FrequenciesUpdateManager.Event.Downloading -> "Descargando ${ev.what}…"
                    is FrequenciesUpdateManager.Event.Verifying -> "Verificando ${ev.what}…"
                    is FrequenciesUpdateManager.Event.Applied -> "Actualizado: ${ev.what}"
                    is FrequenciesUpdateManager.Event.UpToDate ->
                        if (offlineAtStart) "Sin conexión; usando datos locales." else "Frecuencias al día"
                    is FrequenciesUpdateManager.Event.Skipped ->
                        if (offlineAtStart) "Sin conexión; usando datos locales." else ev.reason
                    is FrequenciesUpdateManager.Event.Error -> "No se pudo actualizar (${ev.what}); usando datos locales."
                }

                activity?.runOnUiThread {
                    _b ?: return@runOnUiThread
                    baseStatusMessage = message
                    renderHeaderStatus(summary = currentSummary())
                }
            }

            val f = FrequenciesUpdateManager.frequenciesFile(requireContext().applicationContext)
            val loaded = loadLocalManifest(f)

            if (!isAdded || _b == null) return@launch

            showLoading(false)

            if (loaded == null) {
                dataLoaded = false
                all = emptyList()
                adapter.setQuery("")
                adapter.submitList(emptyList())
                baseStatusMessage = "No hay datos de Frecuencias disponibles."
                renderHeaderStatus(summary = "Sincronización local/OTA sin datos disponibles")
                showEmptyState(
                    visible = true,
                    title = getString(R.string.frequencies_no_data_title),
                    body = getString(R.string.frequencies_no_data_body)
                )
                b.recycler.visibility = View.INVISIBLE
                return@launch
            }

            all = loaded.items
            dataLoaded = true

            baseStatusMessage = buildString {
                if (usedLocalFallback) append("Usando datos locales · ")
                append("V")
                append(FrequenciesUpdateManager.localVersion(requireContext().applicationContext))
            }

            setupCityDropdown(all)
            applyFilters()
            syncCollapsedHint()
        }
    }

    private fun isNetworkAvailable(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private suspend fun loadLocalManifest(file: File): FrequenciesManifest? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() < 10) return@withContext null
        runCatching {
            val json = file.readText()
            FrequenciesJson.parse(json)
        }.getOrNull()
    }

    private fun setupCityDropdown(items: List<FrequencyItem>) {
        val cities = items
            .asSequence()
            .map { it.safeCity() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()

        val cityList = listOf(CITY_ALL) + cities
        b.acCity.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, cityList))

        val current = b.acCity.text?.toString()?.trim().orEmpty()
        if (current.isBlank()) {
            b.acCity.setText(CITY_ALL, false)
        } else {
            val exists = cityList.any { it.equals(current, ignoreCase = true) }
            if (!exists) b.acCity.setText(CITY_ALL, false)
        }

        syncCollapsedHint()
    }

    private fun applyFilters() {
        if (!dataLoaded || all.isEmpty()) {
            adapter.setQuery("")
            adapter.submitList(emptyList())
            baseStatusMessage = "No hay datos de Frecuencias disponibles."
            renderHeaderStatus(summary = "Sincronización local/OTA sin datos disponibles")
            showEmptyState(
                visible = true,
                title = getString(R.string.frequencies_no_data_title),
                body = getString(R.string.frequencies_no_data_body)
            )
            b.recycler.visibility = View.INVISIBLE
            return
        }

        val q = b.etSearch.text?.toString()?.trim().orEmpty()
        val citySelRaw = b.acCity.text?.toString()?.trim().orEmpty().ifBlank { CITY_ALL }
        val cityIsAll = citySelRaw.equals(CITY_ALL, ignoreCase = true)

        val filtered = all.filter { item ->
            val matchCity = cityIsAll || item.safeCity().equals(citySelRaw, ignoreCase = true)

            val hay = buildString {
                append(item.safeState()); append(' ')
                append(item.safeCity()); append(' ')
                append(item.safeAirport()); append(' ')
                append(item.safeIcao()); append(' ')
                append(item.iata ?: ""); append(' ')
                append(item.safeType()); append(' ')
                append(item.callsign ?: ""); append(' ')
                append(item.ident ?: ""); append(' ')
                append(item.remarks ?: "")
            }

            val matchQ = q.isBlank() || hay.contains(q, ignoreCase = true)
            matchCity && matchQ
        }

        adapter.setQuery(q)
        adapter.submitList(filtered.toList())

        val summary = when {
            filtered.isEmpty() -> "0 de ${all.size} frecuencias visibles"
            filtered.size == all.size -> "${all.size} frecuencias disponibles"
            else -> "${filtered.size} de ${all.size} frecuencias visibles"
        }

        renderHeaderStatus(summary = summary)

        if (filtered.isEmpty()) {
            showEmptyState(
                visible = true,
                title = getString(R.string.frequencies_empty_title),
                body = getString(R.string.frequencies_empty_body)
            )
            b.recycler.visibility = View.INVISIBLE
        } else {
            showEmptyState(
                visible = false,
                title = getString(R.string.frequencies_empty_title),
                body = getString(R.string.frequencies_empty_body)
            )
            b.recycler.visibility = View.VISIBLE
        }
    }

    private fun renderHeaderStatus(summary: String) {
        b.txtStatus.text = baseStatusMessage.ifBlank { getString(R.string.frequencies_status_ready) }
        b.txtResultsSummary.text = summary
    }

    private fun currentSummary(): String {
        return b.txtResultsSummary.text?.toString().orEmpty().ifBlank {
            getString(R.string.frequencies_results_summary_placeholder)
        }
    }

    private fun showLoading(visible: Boolean) {
        b.progress.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showEmptyState(visible: Boolean, title: String, body: String) {
        b.cardEmptyState.visibility = if (visible) View.VISIBLE else View.GONE
        b.txtEmptyTitle.text = title
        b.txtEmptyBody.text = body
    }

    private fun renderFiltersExpandedState(animated: Boolean) {
        b.layoutFiltersContent.visibility = if (filtersExpanded) View.VISIBLE else View.GONE
        b.txtToggleFiltersState.text = if (filtersExpanded) "Ocultar" else "Mostrar"

        syncCollapsedHint()

        if (!animated && !filtersExpanded) {
            b.layoutFiltersContent.alpha = 1f
        }
    }

    private fun syncCollapsedHint() {
        val query = b.etSearch.text?.toString()?.trim().orEmpty()
        val city = b.acCity.text?.toString()?.trim().orEmpty().ifBlank { CITY_ALL }
        val hasQuery = query.isNotBlank()
        val hasCityFilter = !city.equals(CITY_ALL, ignoreCase = true)

        b.txtSearchCollapsedHint.text = when {
            filtersExpanded -> "Búsqueda y filtros visibles"
            hasQuery && hasCityFilter -> "$query · $city"
            hasQuery -> query
            hasCityFilter -> "Ciudad: $city"
            else -> "Toca para mostrar búsqueda y filtros"
        }
    }

    private fun hasActiveFilters(): Boolean {
        val query = b.etSearch.text?.toString()?.trim().orEmpty()
        val city = b.acCity.text?.toString()?.trim().orEmpty().ifBlank { CITY_ALL }
        return query.isNotBlank() || !city.equals(CITY_ALL, ignoreCase = true)
    }

    private fun hideKeyboard(view: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_QUERY, b.etSearch.text?.toString().orEmpty())
        outState.putString(STATE_CITY, b.acCity.text?.toString().orEmpty())
        outState.putBoolean(STATE_FILTERS_EXPANDED, filtersExpanded)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
