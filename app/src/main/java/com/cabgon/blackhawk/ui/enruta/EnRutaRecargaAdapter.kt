package com.cabgon.blackhawk.ui.enruta

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cabgon.blackhawk.databinding.ItemEnRutaRecargaBinding
import com.cabgon.blackhawk.ui.enruta.EnRutaViewModel.RecargaUi

class EnRutaRecargaAdapter(
    private val onFolioChange: (index: Int, value: String) -> Unit,
    private val onLitrosChange: (index: Int, value: String) -> Unit,
    private val onUbicacionChange: (index: Int, value: String) -> Unit,
    private val onEliminar: (index: Int) -> Unit
) : RecyclerView.Adapter<EnRutaRecargaAdapter.RecargaViewHolder>() {

    private var items: List<RecargaUi> = emptyList()

    fun submitList(newItems: List<RecargaUi>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecargaViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemEnRutaRecargaBinding.inflate(inflater, parent, false)
        return RecargaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecargaViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class RecargaViewHolder(
        private val b: ItemEnRutaRecargaBinding
    ) : RecyclerView.ViewHolder(b.root) {

        private fun makeWatcher(onAfter: (String) -> Unit) = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                onAfter(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }

        fun bind(item: RecargaUi, index: Int) {
            // Título "Recarga #n"
            b.txtTituloRecarga.text = "Recarga ${index + 1}"

            // Quitamos listeners antiguos (si quisieras afinarlos más, podrías guardarlos como tags).

            // FOLIO
            b.edtFolioItem.setText(item.folio)
            b.edtFolioItem.setSelection(b.edtFolioItem.text?.length ?: 0)
            b.edtFolioItem.addTextChangedListener(makeWatcher { value ->
                onFolioChange(index, value)
            })

            // LITROS
            b.edtLitrosItem.setText(item.litros)
            b.edtLitrosItem.setSelection(b.edtLitrosItem.text?.length ?: 0)
            b.edtLitrosItem.addTextChangedListener(makeWatcher { value ->
                onLitrosChange(index, value)
            })

            // UBICACIÓN
            b.edtUbicacionItem.setText(item.ubicacion)
            b.edtUbicacionItem.setSelection(b.edtUbicacionItem.text?.length ?: 0)
            b.edtUbicacionItem.addTextChangedListener(makeWatcher { value ->
                onUbicacionChange(index, value)
            })

            // ELIMINAR
            b.btnEliminarRecarga.setOnClickListener {
                // usamos el index que recibimos en bind para no depender de adapterPosition
                onEliminar(index)
            }
        }
    }
}
