package com.cabgon.blackhawk.ui.fragments
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.data.RAGIndex
import com.cabgon.blackhawk.databinding.ItemCitationBinding
class CitationsAdapter(
    private val onOpen: (manualAsset: String, page: Int) -> Unit
) : RecyclerView.Adapter<CitationsAdapter.VH>() {
    private val items = mutableListOf<RAGIndex.Hit>()
    fun submitHits(h: List<RAGIndex.Hit>) { items.clear(); items.addAll(h); notifyDataSetChanged() }
    class VH(val b: ItemCitationBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(p: ViewGroup, v: Int): VH =
        VH(ItemCitationBinding.inflate(LayoutInflater.from(p.context), p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, pos: Int) {
        val it = items[pos]
        h.b.txtSource.text = "${it.manual}  ·  pág. ${it.page}"
        h.b.btnOpen.setOnClickListener { _ -> onOpen(it.manual, it.page) }
    }
}