package com.cabgon.blackhawk.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.R

class ChatAdapter(
    private val onSourceClick: (ChatSourceUi) -> Unit
) : RecyclerView.Adapter<ChatAdapter.VH>() {

    private var items: List<ChatMessageUi> = emptyList()

    fun submit(list: List<ChatMessageUi>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return VH(v, onSourceClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    class VH(
        itemView: View,
        private val onSourceClick: (ChatSourceUi) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val container: LinearLayout = itemView.findViewById(R.id.container)
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val sourcesContainer: LinearLayout = itemView.findViewById(R.id.sourcesContainer)

        fun bind(item: ChatMessageUi) {
            messageText.text = item.content

            // Estilo simple: usuario alineado a derecha, asistente a izquierda
            val params = messageText.layoutParams as ViewGroup.MarginLayoutParams
            if (item.role == "user") {
                messageText.textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                params.leftMargin = 64
                params.rightMargin = 0
            } else {
                messageText.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                params.leftMargin = 0
                params.rightMargin = 64
            }
            messageText.layoutParams = params

            sourcesContainer.removeAllViews()

            if (item.role == "assistant" && item.sources.isNotEmpty()) {
                sourcesContainer.visibility = View.VISIBLE

                val title = TextView(itemView.context).apply {
                    text = "Fuentes:"
                    textSize = 13f
                }
                sourcesContainer.addView(title)

                item.sources.forEach { src ->
                    val tv = TextView(itemView.context).apply {
                        text = "• ${src.manual} — pág. ${src.page}"
                        textSize = 13f
                        setPadding(0, 6, 0, 6)
                        setOnClickListener { onSourceClick(src) }
                    }
                    sourcesContainer.addView(tv)
                }
            } else {
                sourcesContainer.visibility = View.GONE
            }
        }
    }
}
