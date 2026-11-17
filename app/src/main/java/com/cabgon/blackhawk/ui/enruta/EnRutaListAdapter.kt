package com.cabgon.blackhawk.ui.enruta

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.databinding.ItemEnRutaListBinding

class EnRutaListAdapter(
    private var items: List<EnRutaViewModel.EnRutaListItemUi>,
    private val onClick: (EnRutaViewModel.EnRutaListItemUi) -> Unit,
    private val onRemoveClick: (EnRutaViewModel.EnRutaListItemUi) -> Unit
) : RecyclerView.Adapter<EnRutaListAdapter.EnRutaViewHolder>() {

    fun submitList(newItems: List<EnRutaViewModel.EnRutaListItemUi>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class EnRutaViewHolder(private val binding: ItemEnRutaListBinding) :
        RecyclerView.ViewHolder(binding.root), View.OnClickListener {

        private var currentItem: EnRutaViewModel.EnRutaListItemUi? = null

        init {
            binding.root.setOnClickListener(this)
            binding.btnRemoveEnRuta.setOnClickListener { currentItem?.let(onRemoveClick) }
        }

        fun bind(item: EnRutaViewModel.EnRutaListItemUi) {
            currentItem = item

            // Siempre ocultar el botón "Quitar" en la UI
            binding.btnRemoveEnRuta.visibility = View.GONE

            binding.txtMatricula.text = "UH-60L Mat. ${item.matAeronave}"
            binding.txtCategoria.text = "Categoría: ${item.categoria}"
            binding.txtUbicacion.text = "Ubicación: ${item.ubicacion}"

            binding.txtProx.text = item.proxInspeccionLabel
            binding.txtHorasDisp.text = item.horasDisponiblesLabel
            binding.txtHorasTotales.text = item.horasTotalesLabel

            binding.txtUltimaEdicion.text = "Última edición: ${item.lastEditDate}"
            binding.txtTecnico.text = "Técnico: ${item.tecnicoLabel}"

            applyHorasDispStyle(binding.txtHorasDisp, item.horasDisponiblesLabel)
        }

        override fun onClick(v: View?) {
            currentItem?.let(onClick)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EnRutaViewHolder {
        val binding = ItemEnRutaListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return EnRutaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EnRutaViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    // ----------------- Helpers -----------------

    private fun applyHorasDispStyle(view: TextView, label: String) {
        // Limpia tint por si el tema/Material lo estaba “lavando”
        view.backgroundTintList = null
        ViewCompat.setBackgroundTintList(view, null)

        val horas = extractFirstNumber(label)
        val ctx = view.context

        when {
            horas == null -> {
                view.setBackgroundResource(R.drawable.bg_enruta_chip)
                view.setTextColor(ContextCompat.getColor(ctx, android.R.color.black))
            }

            horas >= 25.0 -> {
                stopPulse(view)
                view.setBackgroundResource(R.drawable.bg_enruta_chip_green)
                view.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
            }

            horas >= 5.0 -> {
                stopPulse(view)
                // 🔶 Amarillo con BORDE oscuro (más visible)
                val drawable = ContextCompat
                    .getDrawable(ctx, R.drawable.bg_enruta_chip_yellow)
                    ?.mutate()

                if (drawable is android.graphics.drawable.GradientDrawable) {
                    drawable.setStroke(2, ContextCompat.getColor(ctx, R.color.preflight_warning_stroke))
                    view.background = drawable
                } else {
                    view.setBackgroundResource(R.drawable.bg_enruta_chip_yellow)
                }

                view.setTextColor(ContextCompat.getColor(ctx, android.R.color.black))
            }

            else -> {
                stopPulse(view)

                // 🔴 Rojo con BORDE oscuro
                val drawable = ContextCompat
                    .getDrawable(ctx, R.drawable.bg_enruta_chip_red)
                    ?.mutate()

                if (drawable is android.graphics.drawable.GradientDrawable) {
                    drawable.setStroke(
                        2,
                        ContextCompat.getColor(ctx, R.color.preflight_warning_stroke)
                    )
                    view.background = drawable
                } else {
                    view.setBackgroundResource(R.drawable.bg_enruta_chip_red)
                }

                view.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))

                // 🔴 Pulso crítico
                startRedPulse(view)
            }
        }
    }

    private fun extractFirstNumber(text: String): Double? {
        // Busca el primer número con decimales opcionales: 28.3 / 28,3 / 28
        val match = Regex("""(\d+(?:[.,]\d+)?)""").find(text) ?: return null
        return match.value.replace(",", ".").toDoubleOrNull()
    }

    private fun startRedPulse(view: View) {
        view.animate().cancel()

        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f

        // Pulso lento y estable
        view.animate()
            .scaleX(1.04f)
            .scaleY(1.04f)
            .alpha(0.9f)
            .setDuration(800)          // ⬅ más lento
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(800)  // ⬅ más lento
                    .withEndAction {
                        startRedPulse(view)
                    }
                    .start()
            }
            .start()
    }

    private fun stopPulse(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f
    }
}
