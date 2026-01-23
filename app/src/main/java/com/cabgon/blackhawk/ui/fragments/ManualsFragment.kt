package com.cabgon.blackhawk.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.data.ManualMeta
import com.cabgon.blackhawk.data.PackageManager
import com.cabgon.blackhawk.databinding.FragmentManualsBinding
import com.cabgon.blackhawk.databinding.ItemManualBinding
import com.cabgon.blackhawk.ui.pdf.PdfViewerActivity
import com.cabgon.blackhawk.util.Prefs

class ManualsFragment : Fragment() {

    private var _b: FragmentManualsBinding? = null
    private val b get() = _b!!

    private lateinit var manualsAdapter: ManualsAdapter
    private lateinit var manuals: List<ManualMeta>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentManualsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Paquete actual (se define en StartupActivity y se puede cambiar vía nav_switch_pkg)
        val pkgEnum = currentPackage()

        // Título dinámico: "Manuales · <Paquete>"
        val pkgLabel = when (pkgEnum) {
            PackageManager.Pkg.IADS -> "IADS"
            PackageManager.Pkg.SIKORSKY -> "Sikorsky"
        }
        b.txtManualsTitle.text = "Manuales · $pkgLabel"

        // Lista de manuales para ese paquete
        manuals = PackageManager.manuals(pkgEnum)

        manualsAdapter = ManualsAdapter()

        b.rvManuals.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = manualsAdapter
        }
    }

    override fun onResume() {
        super.onResume()
        // Al volver del PDF viewer, refresca para que "Última página" se actualice al instante
        if (::manualsAdapter.isInitialized) {
            manualsAdapter.notifyDataSetChanged()
        }
    }

    /**
     * Obtiene el paquete actual desde Prefs.
     * StartupActivity guarda pkg.name (IADS / SIKORSKY) y nav_switch_pkg fuerza a re-seleccionar.
     */
    private fun currentPackage(): PackageManager.Pkg {
        val saved = Prefs.getPackage(requireContext())
        return try {
            if (saved.isNullOrBlank()) {
                PackageManager.Pkg.SIKORSKY
            } else {
                PackageManager.Pkg.valueOf(saved)
            }
        } catch (_: IllegalArgumentException) {
            PackageManager.Pkg.SIKORSKY
        }
    }

    private inner class ManualsAdapter : RecyclerView.Adapter<ManualVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ManualVH {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ItemManualBinding.inflate(inflater, parent, false)
            return ManualVH(binding)
        }

        override fun onBindViewHolder(holder: ManualVH, position: Int) {
            val meta = manuals[position]
            val ctx = holder.b.root.context

            // Título: custom si existe, si no el título del manual
            val customTitle = Prefs.getManualCustomTitle(ctx, meta.assetPath)
            val titleToShow = customTitle?.takeIf { it.isNotBlank() } ?: meta.title
            holder.b.txtTitle.text = titleToShow

            // Meta: "Última página: N" usando lo que guarda PdfViewerActivity
            val lastPage1 = Prefs.getManualLastPage1(ctx, meta.assetPath)
            holder.b.txtMeta.text = "Última página: $lastPage1"

            // Click: abrir visor PDF en la última página guardada
            holder.b.root.setOnClickListener {
                val intent = Intent(ctx, PdfViewerActivity::class.java).apply {
                    putExtra(PdfViewerActivity.EXTRA_ASSET_PATH, meta.assetPath)
                    putExtra(PdfViewerActivity.EXTRA_PAGE, lastPage1)
                }
                ctx.startActivity(intent)
            }
        }

        override fun getItemCount(): Int = manuals.size
    }

    class ManualVH(val b: ItemManualBinding) : RecyclerView.ViewHolder(b.root)

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
