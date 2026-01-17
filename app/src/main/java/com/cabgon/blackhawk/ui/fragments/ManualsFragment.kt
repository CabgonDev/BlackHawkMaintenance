package com.cabgon.blackhawk.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.data.PackageManager
import com.cabgon.blackhawk.databinding.FragmentManualsBinding
import com.cabgon.blackhawk.databinding.ItemManualBinding
import com.cabgon.blackhawk.ui.pdf.PdfViewerActivity
import com.cabgon.blackhawk.util.Prefs

class ManualsFragment : Fragment() {

    private var _b: FragmentManualsBinding? = null
    private val b get() = _b!!

    private lateinit var items: List<com.cabgon.blackhawk.data.ManualMeta>
    private lateinit var manualsAdapter: RecyclerView.Adapter<ManualVH>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentManualsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val pkg = PackageManager.Pkg.valueOf(Prefs.getPackage(requireContext())!!)
        items = PackageManager.manuals(pkg)

        b.rvManuals.layoutManager = LinearLayoutManager(requireContext())

        manualsAdapter = object : RecyclerView.Adapter<ManualVH>() {

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ManualVH {
                val binding = ItemManualBinding.inflate(layoutInflater, parent, false)
                return ManualVH(binding)
            }

            override fun getItemCount() = items.size

            override fun onBindViewHolder(h: ManualVH, i: Int) {
                val m = items[i]

                h.b.txtTitle.text = m.title

                // ✅ Siempre lee el valor actualizado de Prefs
                val lastPage = Prefs.getManualLastPage1(requireContext(), m.assetPath)
                h.b.txtMeta.text = "Última página: $lastPage"

                h.itemView.setOnClickListener {
                    startActivity(
                        Intent(requireContext(), PdfViewerActivity::class.java)
                            .putExtra(PdfViewerActivity.EXTRA_ASSET_PATH, m.assetPath)
                    )
                }
            }
        }

        b.rvManuals.adapter = manualsAdapter
    }

    override fun onResume() {
        super.onResume()
        // ✅ Al volver del PDF viewer, refresca para que "Última página" se actualice al instante
        if (::manualsAdapter.isInitialized) {
            manualsAdapter.notifyDataSetChanged()
        }
    }

    class ManualVH(val b: ItemManualBinding) : RecyclerView.ViewHolder(b.root)

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
