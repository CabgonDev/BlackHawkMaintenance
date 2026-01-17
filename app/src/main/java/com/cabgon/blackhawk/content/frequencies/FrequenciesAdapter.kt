package com.cabgon.blackhawk.content.frequencies

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.databinding.RowFrequencyBinding
import java.util.Locale

class FrequenciesAdapter(
    private val onItemClick: (FrequencyItem) -> Unit = {}
) : ListAdapter<FrequencyItem, FrequenciesAdapter.VH>(DIFF) {

    private var query: String = ""

    fun setQuery(q: String) {
        query = q.trim()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = RowFrequencyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b, onItemClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), query)
    }

    class VH(
        private val b: RowFrequencyBinding,
        private val onItemClick: (FrequencyItem) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {

        private fun clean(s: String?): String? {
            val v = s?.trim()
            if (v.isNullOrBlank()) return null
            if (v.equals("null", ignoreCase = true)) return null
            return v
        }

        fun bind(item: FrequencyItem, query: String) {
            b.card.setOnClickListener { onItemClick(item) }

            // --- TYPE PILL: EMER rojo si isEmergency ---
            val isEmer = item.isEmergency

            if (isEmer) {
                b.txtTypePill.text = "EMER"
                b.txtTypePill.setBackgroundResource(R.drawable.bg_pill_emergency)
                // Si quieres que el icono también cambie, descomenta:
                // b.ivType.setImageResource(R.drawable.ic_type_emergency)
                // b.ivType.imageTintList = ColorStateList.valueOf(0xFFC62828.toInt())
            } else {
                val type = item.safeType()
                b.txtTypePill.text = type.ifBlank { "—" }
                b.txtTypePill.setBackgroundResource(R.drawable.bg_pill)
            }

            // Icono por tipo (solo si no es emergencia, para no “confundir” semántica)
            val typeForIcon = item.safeType()
            b.ivType.setImageResource(typeToIcon(typeForIcon))

            // Top: ICAO · Airport
            val top = buildString {
                val icao = item.safeIcao()
                if (icao.isNotBlank()) append(icao)

                val ap = item.safeAirport()
                if (ap.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(ap)
                }
            }.trim()

            b.txtTop.text = highlight(top.ifBlank { "Frecuencia" }, query)

            // Mid: Estado/Ciudad · Servicio · Callsign
            val mid = buildString {
                val st = item.safeState()
                val ct = item.safeCity()
                if (st.isNotBlank() || ct.isNotBlank()) {
                    append(st.ifBlank { "-" })
                    append(" / ")
                    append(ct.ifBlank { "-" })
                }

                val type = item.safeType()
                if (type.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(type)
                }

                val cs = clean(item.callsign)
                if (cs != null) {
                    if (isNotEmpty()) append(" · ")
                    append(cs)
                }
            }.trim()

            if (mid.isNotBlank()) {
                b.txtMid.visibility = View.VISIBLE
                b.txtMid.text = highlight(mid, query)
            } else {
                b.txtMid.visibility = View.GONE
            }

            // Frequency
            val freq = item.displayFrequency().trim()
            if (freq.isNotBlank()) {
                b.txtFreq.visibility = View.VISIBLE
                b.txtFreq.text = highlight(freq, query)
            } else {
                b.txtFreq.visibility = View.GONE
            }

            // Bottom: IDENT + remarks
            val ident = clean(item.ident)
            val remarks = clean(item.remarks)

            val bottom = buildString {
                if (ident != null) append("IDENT: $ident")
                if (remarks != null) {
                    if (isNotEmpty()) append(" · ")
                    append(remarks)
                }
            }.trim()

            if (bottom.isNotBlank()) {
                b.txtBottom.visibility = View.VISIBLE
                b.txtBottom.text = highlight(bottom, query)
            } else {
                b.txtBottom.visibility = View.GONE
            }
        }

        private fun highlight(text: String, query: String): CharSequence {
            val q = query.trim()
            if (q.isBlank()) return text

            val lowerText = text.lowercase(Locale.getDefault())
            val lowerQ = q.lowercase(Locale.getDefault())

            var start = lowerText.indexOf(lowerQ)
            if (start < 0) return text

            val ss = SpannableString(text)
            while (start >= 0) {
                val end = start + lowerQ.length
                ss.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                start = lowerText.indexOf(lowerQ, end)
            }
            return ss
        }

        private fun typeToIcon(type: String): Int {
            val t = type.trim().uppercase(Locale.getDefault())
            return when {
                t.contains("TWR") || t.contains("TORRE") -> R.drawable.ic_type_twr
                t.contains("APP") || t.contains("APROX") || t.contains("APPROACH") -> R.drawable.ic_type_app
                t.contains("GND") || t.contains("GROUND") || t.contains("TIERRA") -> R.drawable.ic_type_gnd
                t.contains("ATIS") -> R.drawable.ic_type_atis
                else -> R.drawable.ic_radiofreq
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FrequencyItem>() {
            override fun areItemsTheSame(oldItem: FrequencyItem, newItem: FrequencyItem): Boolean {
                return oldItem.icao == newItem.icao &&
                        oldItem.type == newItem.type &&
                        oldItem.freqMHz == newItem.freqMHz &&
                        oldItem.freqKhz == newItem.freqKhz
            }

            override fun areContentsTheSame(oldItem: FrequencyItem, newItem: FrequencyItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
