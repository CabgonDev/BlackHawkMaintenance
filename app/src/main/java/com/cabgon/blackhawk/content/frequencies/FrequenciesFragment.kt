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

    private companion object {
        private const val CITY_ALL = "TODAS"

        // Persistencia (Bundle)
        private const val STATE_QUERY = "freq_query"
        private const val STATE_CITY = "freq_city"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentFrequenciesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        // Recycler
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        // Restaurar estado UI (si existe) antes de cargar datos
        val restoredQuery = savedInstanceState?.getString(STATE_QUERY).orEmpty()
        val restoredCity = savedInstanceState?.getString(STATE_CITY)

        if (restoredQuery.isNotEmpty()) {
            b.etSearch.setText(restoredQuery)
            b.etSearch.setSelection(restoredQuery.length)
        }

        if (!restoredCity.isNullOrBlank()) {
            b.acCity.setText(restoredCity, false)
        }

        // Search IME
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

        // Filtrado mientras escribe
        b.etSearch.addTextChangedListener {
            applyFilters()
        }

        // Cambio de ciudad
        b.acCity.setOnItemClickListener { _, _, _, _ ->
            applyFilters()
        }

        // Clear
        b.btnClearFilters.setOnClickListener {
            b.etSearch.setText("")
            b.acCity.setText(CITY_ALL, false)
            hideKeyboard(b.etSearch)
            applyFilters()
        }

        // Carga/OTA
        b.txtStatus.text = "Cargando…"
        b.progress.visibility = View.VISIBLE

        lifecycleScope.launch {

            // 1) Si estamos sin red desde el inicio, esto DEBE marcar "Usando datos locales"
            val offlineAtStart = !isNetworkAvailable(requireContext().applicationContext)
            var usedLocalFallback = offlineAtStart

            FrequenciesUpdateManager.checkAndUpdate(requireContext().applicationContext) { ev ->

                // 2) Ajuste robusto:
                // - Applied => esta sesión sí aplicó OTA, por tanto NO es "datos locales"
                // - Error/Skipped => usamos local (o al menos no pudimos garantizar OTA)
                when (ev) {
                    is FrequenciesUpdateManager.Event.Applied -> usedLocalFallback = false
                    is FrequenciesUpdateManager.Event.Error -> usedLocalFallback = true
                    is FrequenciesUpdateManager.Event.Skipped -> usedLocalFallback = true
                    else -> { /* no-op */ }
                }

                requireActivity().runOnUiThread {
                    b.txtStatus.text = when (ev) {
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
                }
            }

            val f = FrequenciesUpdateManager.frequenciesFile(requireContext().applicationContext)
            val loaded = loadLocalManifest(f)

            b.progress.visibility = View.GONE

            if (loaded == null) {
                b.txtStatus.text = "No hay datos de Frecuencias disponibles."
                adapter.submitList(emptyList())
                return@launch
            }

            all = loaded.items

            val prefix = if (usedLocalFallback) "Usando datos locales · " else ""

            b.txtStatus.text = buildString {
                append(prefix)
                append("V")
                append(FrequenciesUpdateManager.localVersion(requireContext().applicationContext))
                append(" · ")
                append(all.size)
                append(" Frecuencias cargadas")
            }

            // Setup ciudad (ya con datos) y respetar ciudad restaurada si aplica
            setupCityDropdown(all)

            // Render inicial
            applyFilters()
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
    }

    private fun applyFilters() {
        if (all.isEmpty()) {
            adapter.setQuery("")
            adapter.submitList(emptyList())
            b.txtStatus.text = "No hay datos de Frecuencias disponibles."
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

        if (filtered.isEmpty()) {
            b.txtStatus.text = "Sin resultados con los filtros actuales."
        }
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
