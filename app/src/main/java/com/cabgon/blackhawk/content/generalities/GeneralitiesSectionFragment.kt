package com.cabgon.blackhawk.content.generalities

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.databinding.FragmentGeneralitySectionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class GeneralitiesSectionFragment : Fragment() {

    private var _b: FragmentGeneralitySectionBinding? = null
    private val b get() = _b!!

    private val adapter = GeneralitiesBlocksAdapter()

    private enum class SourceUsed { OTA, LOCAL }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentGeneralitySectionBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.progress.visibility = View.VISIBLE
        val sectionId = requireArguments().getString(ARG_SECTION_ID).orEmpty()

        lifecycleScope.launch {
            val ctx = requireContext().applicationContext
            val outFile = GeneralitiesUpdateManager.generalitiesFile(ctx)
            val loaded = loadSectionFromOtaOrAsset(ctx, outFile, sectionId)

            b.progress.visibility = View.GONE

            if (loaded == null) {
                b.txtTitle.text = "Sección no disponible"
                b.txtSource.text = "Fuente: —"
                adapter.submit(emptyList())
                return@launch
            }

            val (section, source) = loaded
            b.txtTitle.text = section.title
            b.txtSource.text = if (source == SourceUsed.OTA) "Fuente: OTA" else "Fuente: Local"

            // ✅ Sin sticky header, y sin repetir título redundante (lo maneja buildItems)
            adapter.submit(GeneralitiesBlocksAdapter.buildItems(section))
        }
    }

    private suspend fun loadSectionFromOtaOrAsset(
        ctx: Context,
        otaFile: File,
        sectionId: String
    ): Pair<GeneralitiesSection, SourceUsed>? = withContext(Dispatchers.IO) {

        // 1) OTA/cache
        if (otaFile.exists() && otaFile.length() > 50) {
            val parsed = runCatching { GeneralitiesJson.parse(otaFile.readText()) }.getOrNull()
            val sec = parsed?.sections?.firstOrNull { it.id == sectionId }
            if (sec != null) return@withContext sec to SourceUsed.OTA
        }

        // 2) Asset fallback
        val assetParsed = runCatching {
            val json = ctx.assets.open("generalidades/generalidades.json")
                .bufferedReader().use { it.readText() }
            GeneralitiesJson.parse(json)
        }.getOrNull()

        val sec2 = assetParsed?.sections?.firstOrNull { it.id == sectionId }
        if (sec2 != null) return@withContext sec2 to SourceUsed.LOCAL

        null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        private const val ARG_SECTION_ID = "section_id"

        fun newInstance(sectionId: String) = GeneralitiesSectionFragment().apply {
            arguments = Bundle().apply { putString(ARG_SECTION_ID, sectionId) }
        }
    }
}
