package com.cabgon.blackhawk.content.generalities

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.databinding.FragmentGeneralitiesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class GeneralitiesFragment : Fragment() {

    private var _b: FragmentGeneralitiesBinding? = null
    private val b get() = _b!!

    private val adapter = GeneralitiesSectionsAdapter { section ->
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, GeneralitiesSectionFragment.newInstance(section.id))
            .addToBackStack("generalities_section")
            .commit()
    }

    private enum class SourceUsed { OTA, LOCAL }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentGeneralitiesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.swipeRefresh.setOnRefreshListener {
            refresh(forceUser = true)
        }

        refresh(forceUser = false)
    }

    private fun refresh(forceUser: Boolean) {
        b.progress.visibility = if (forceUser) View.GONE else View.VISIBLE
        b.txtStatus.text = if (forceUser) "Actualizando…" else "Cargando Generalidades…"

        lifecycleScope.launch {
            val ctx = requireContext().applicationContext

            val otaResult = GeneralitiesUpdateManager.checkAndUpdate(ctx)

            val outFile = GeneralitiesUpdateManager.generalitiesFile(ctx)
            val loaded = loadFromOtaOrAsset(ctx, outFile)

            b.progress.visibility = View.GONE
            b.swipeRefresh.isRefreshing = false

            if (loaded == null || loaded.first.sections.isEmpty()) {
                b.txtStatus.text = "No hay datos de Generalidades disponibles."
                adapter.submitList(emptyList())
                return@launch
            }

            val (manifest, source) = loaded
            adapter.submitList(manifest.sections)

            val fuenteTxt = if (source == SourceUsed.OTA) "Fuente: OTA" else "Fuente: Local"
            val canal = otaResult.channelUsed

            val status = buildString {
                append(fuenteTxt)
                append(" · Canal: ")
                append(canal)
                if (source == SourceUsed.OTA) {
                    append(" · V")
                    append(GeneralitiesUpdateManager.localVersion(ctx))
                } else {
                    val hasProblem = otaResult.events.any {
                        it is GeneralitiesUpdateManager.Event.Error || it is GeneralitiesUpdateManager.Event.Skipped
                    }
                    if (hasProblem) append(" · (fallback)")
                }
                append(" · ")
                append(manifest.sections.size)
                append(" secciones")
            }

            b.txtStatus.text = status
        }
    }

    private suspend fun loadFromOtaOrAsset(ctx: Context, otaFile: File): Pair<GeneralitiesManifest, SourceUsed>? =
        withContext(Dispatchers.IO) {

            if (otaFile.exists() && otaFile.length() > 50) {
                val parsed = runCatching { GeneralitiesJson.parse(otaFile.readText()) }.getOrNull()
                if (parsed != null && parsed.sections.isNotEmpty()) {
                    return@withContext parsed to SourceUsed.OTA
                }
            }

            val assetParsed = runCatching {
                val json = ctx.assets.open("generalidades/generalidades.json")
                    .bufferedReader().use { it.readText() }
                GeneralitiesJson.parse(json)
            }.getOrNull()

            if (assetParsed != null && assetParsed.sections.isNotEmpty()) {
                return@withContext assetParsed to SourceUsed.LOCAL
            }

            null
        }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
