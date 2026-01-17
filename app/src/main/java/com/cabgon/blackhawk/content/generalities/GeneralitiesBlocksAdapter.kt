package com.cabgon.blackhawk.content.generalities

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.databinding.ItemGenTableHeader2Binding
import com.cabgon.blackhawk.databinding.ItemGenTableHeader3Binding
import com.cabgon.blackhawk.databinding.ItemGenTableRow2Binding
import com.cabgon.blackhawk.databinding.ItemGenTableRow3Binding
import com.cabgon.blackhawk.databinding.ItemGenTableTitleBinding

class GeneralitiesBlocksAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Item {
        data class TableTitle(val text: String) : Item() // solo si NO es redundante
        data class Header2(val c1: String, val c2: String) : Item()
        data class Row2(val c1: String, val c2: String) : Item()
        data class Header3(val c1: String, val c2: String, val c3: String) : Item()
        data class Row3(val c1: String, val c2: String, val c3: String) : Item()
    }

    private val items = mutableListOf<Item>()

    fun submit(newItems: List<Item>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Item.TableTitle -> 0
        is Item.Header2 -> 1
        is Item.Row2 -> 2
        is Item.Header3 -> 3
        is Item.Row3 -> 4
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> TitleVH(ItemGenTableTitleBinding.inflate(inf, parent, false))
            1 -> Header2VH(ItemGenTableHeader2Binding.inflate(inf, parent, false))
            2 -> Row2VH(ItemGenTableRow2Binding.inflate(inf, parent, false))
            3 -> Header3VH(ItemGenTableHeader3Binding.inflate(inf, parent, false))
            else -> Row3VH(ItemGenTableRow3Binding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val it = items[position]) {
            is Item.TableTitle -> (holder as TitleVH).bind(it)
            is Item.Header2 -> (holder as Header2VH).bind(it)
            is Item.Row2 -> (holder as Row2VH).bind(it)
            is Item.Header3 -> (holder as Header3VH).bind(it)
            is Item.Row3 -> (holder as Row3VH).bind(it)
        }
    }

    class TitleVH(private val b: ItemGenTableTitleBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: Item.TableTitle) { b.txtTitle.text = item.text }
    }

    class Header2VH(private val b: ItemGenTableHeader2Binding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: Item.Header2) { b.c1.text = item.c1; b.c2.text = item.c2 }
    }

    class Row2VH(private val b: ItemGenTableRow2Binding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: Item.Row2) { b.c1.text = item.c1; b.c2.text = item.c2 }
    }

    class Header3VH(private val b: ItemGenTableHeader3Binding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: Item.Header3) { b.c1.text = item.c1; b.c2.text = item.c2; b.c3.text = item.c3 }
    }

    class Row3VH(private val b: ItemGenTableRow3Binding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: Item.Row3) { b.c1.text = item.c1; b.c2.text = item.c2; b.c3.text = item.c3 }
    }

    companion object {
        /**
         * - Quita redundancia: si block.title == section.title, no muestra TableTitle.
         * - Render por bloques: solo table por ahora.
         */
        fun buildItems(section: GeneralitiesSection): List<Item> {
            val out = ArrayList<Item>()
            val sectionTitle = section.title.trim()

            section.blocks.forEach { block ->
                when (block) {
                    is GeneralitiesTableBlock -> {
                        val title = block.title?.trim().orEmpty()
                        val showTitle = title.isNotBlank() && title != sectionTitle
                        if (showTitle) out += Item.TableTitle(title)

                        val cols = block.columns
                        if (cols.size == 2) {
                            out += Item.Header2(cols[0], cols[1])
                            block.rows.forEach { row ->
                                out += Item.Row2(row.getOrNull(0).orEmpty(), row.getOrNull(1).orEmpty())
                            }
                        } else if (cols.size == 3) {
                            out += Item.Header3(cols[0], cols[1], cols[2])
                            block.rows.forEach { row ->
                                out += Item.Row3(
                                    row.getOrNull(0).orEmpty(),
                                    row.getOrNull(1).orEmpty(),
                                    row.getOrNull(2).orEmpty()
                                )
                            }
                        }
                    }
                    else -> Unit
                }
            }

            return out
        }
    }
}
