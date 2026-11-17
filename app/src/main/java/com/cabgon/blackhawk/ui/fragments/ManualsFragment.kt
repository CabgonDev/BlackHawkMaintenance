package com.cabgon.blackhawk.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.data.PackageManager
import com.cabgon.blackhawk.databinding.FragmentManualsBinding
import com.cabgon.blackhawk.databinding.ItemManualBinding
import com.cabgon.blackhawk.manuals.update.ManualCatalogStore
import com.cabgon.blackhawk.ui.pdf.PdfViewerActivity
import com.cabgon.blackhawk.util.Prefs

class ManualsFragment : Fragment() {

    private var _b: FragmentManualsBinding? = null
    private val b get() = _b!!

    private lateinit var manualsAdapter: ManualsAdapter
    private var manuals: List<PackageManager.ManualMeta> = emptyList()

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

        manualsAdapter = ManualsAdapter()

        b.rvManuals.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = manualsAdapter
            setHasFixedSize(true)
        }

        renderHeader(currentPackage())
        reloadManuals()
    }

    override fun onResume() {
        super.onResume()
        if (::manualsAdapter.isInitialized) {
            renderHeader(currentPackage())
            reloadManuals()
        }
    }

    private fun reloadManuals() {
        if (_b == null) return
        val pkgEnum = currentPackage()
        manuals = ManualCatalogStore.resolveManuals(requireContext(), pkgEnum)
        manualsAdapter.notifyDataSetChanged()
        renderHeader(pkgEnum)
        renderEmptyState()
    }

    private fun renderHeader(pkgEnum: PackageManager.Pkg) {
        val (title, badge, subtitle) = when (pkgEnum) {
            PackageManager.Pkg.IADS -> Triple(
                "Manuales técnicos",
                "IADS",
                "Documentación tecnica, procedimientos y referencias del aeronave."
            )
            PackageManager.Pkg.SIKORSKY -> Triple(
                "Manuales",
                "Sikorsky",
                "Documentación tecnica, procedimientos y referencias del aeronave."
            )
        }

        b.txtManualsTitle.text = title
        b.txtManualsPackage.text = badge
        b.txtManualsSubtitle.text = subtitle
        b.txtManualsCount.text = resources.getQuantityString(
            com.cabgon.blackhawk.R.plurals.manuals_count,
            manuals.size,
            manuals.size
        )
    }

    private fun renderEmptyState() {
        val isEmpty = manuals.isEmpty()
        b.cardManualsEmpty.isVisible = isEmpty
        b.rvManuals.isVisible = !isEmpty
    }

    private fun currentPackage(): PackageManager.Pkg {
        val saved = Prefs.getPackage(requireContext())
        return try {
            if (saved.isNullOrBlank()) PackageManager.Pkg.SIKORSKY
            else PackageManager.Pkg.valueOf(saved)
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

            val manualKey = meta.id
            val customTitle = Prefs.getManualCustomTitle(ctx, manualKey)
            val titleToShow = customTitle?.takeIf { it.isNotBlank() } ?: meta.title
            val lastPage1 = Prefs.getManualLastPage1(ctx, meta.remotePath)

            holder.b.txtTitle.text = titleToShow
            holder.b.txtMeta.text = ctx.getString(com.cabgon.blackhawk.R.string.manuals_last_page_fmt, lastPage1)
            holder.b.txtAction.text = ctx.getString(com.cabgon.blackhawk.R.string.manuals_open_cta)
            holder.b.txtType.text = ctx.getString(com.cabgon.blackhawk.R.string.manuals_pdf_badge)

            holder.b.root.setOnClickListener {
                val intent = Intent(ctx, PdfViewerActivity::class.java).apply {
                    putExtra(PdfViewerActivity.EXTRA_ASSET_PATH, meta.remotePath)
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
