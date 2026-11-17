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
import java.util.Locale

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
        b.progress.visibility = View.VISIBLE
        b.txtStatus.text = if (forceUser) "Actualizando contenido…" else "Preparando generalidades…"
        //b.txtStatusDetail.text = "Verificando paquete OTA y respaldo local."
        b.txtSourceChip.text = "SYNC"
        b.txtChannelChip.text = "OTA"
        //b.txtSectionsCount.text = "—"
        //b.txtTablesCount.text = "—"

        lifecycleScope.launch {
            val ctx = requireContext().applicationContext

            val otaResult = GeneralitiesUpdateManager.checkAndUpdate(ctx)
            val outFile = GeneralitiesUpdateManager.generalitiesFile(ctx)
            val loaded = loadFromOtaOrAsset(ctx, outFile)

            b.progress.visibility = View.GONE
            b.swipeRefresh.isRefreshing = false

            if (loaded == null || loaded.first.sections.isEmpty()) {
                b.txtStatus.text = "Sin contenido disponible"
                //b.txtStatusDetail.text = "No se encontró información de generalidades ni en OTA ni en local."
                b.txtSourceChip.text = "SIN DATOS"
                b.txtChannelChip.text = otaResult.channelUsed.uppercase(Locale.ROOT)
                //b.txtSectionsCount.text = "0"
                //b.txtTablesCount.text = "0"
                adapter.submitList(emptyList())
                return@launch
            }

            val (manifest, source) = loaded
            val sections = manifest.sections
            val totalTables = sections.sumOf { section -> section.blocks.count { it is GeneralitiesTableBlock } }
            val totalRows = sections.sumOf { section ->
                section.blocks.filterIsInstance<GeneralitiesTableBlock>().sumOf { it.rows.size }
            }

            adapter.submitList(sections)

            val sourceLabel = if (source == SourceUsed.OTA) "OTA" else "LOCAL"
            val channelLabel = otaResult.channelUsed.uppercase(Locale.ROOT)
            val hasProblem = otaResult.events.any {
                it is GeneralitiesUpdateManager.Event.Error || it is GeneralitiesUpdateManager.Event.Skipped
            }

            b.txtSourceChip.text = sourceLabel
            b.txtChannelChip.text = channelLabel
            //b.txtSectionsCount.text = sections.size.toString()
            //b.txtTablesCount.text = totalTables.toString()
            b.txtStatus.text = if (source == SourceUsed.OTA) {
                "Contenido sincronizado"
            } else {
                "Usando respaldo local"
            }

            val statusDetailParts = mutableListOf<String>()
            if (source == SourceUsed.OTA) {
                statusDetailParts += "Canal $channelLabel"
                statusDetailParts += "V${GeneralitiesUpdateManager.localVersion(ctx)}"
            } else {
                statusDetailParts += "Canal $channelLabel"
                if (hasProblem) statusDetailParts += "fallback activo"
            }
            statusDetailParts += "${sections.size} secciones"
            statusDetailParts += "$totalTables tablas"
            statusDetailParts += "$totalRows filas"

            //b.txtStatusDetail.text = statusDetailParts.joinToString(" · ")
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
