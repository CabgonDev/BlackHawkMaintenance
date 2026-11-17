package com.cabgon.blackhawk.content.frequencies

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.cabgon.blackhawk.R
import com.cabgon.blackhawk.databinding.BottomsheetFrequencyDetailBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import java.util.Locale

class FrequencyDetailBottomSheet : BottomSheetDialogFragment() {

    private var _b: BottomsheetFrequencyDetailBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = BottomsheetFrequencyDetailBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val args = requireArguments()

        val icao = args.getString(ARG_ICAO).orEmpty()
        val airport = args.getString(ARG_AIRPORT).orEmpty()
        val state = args.getString(ARG_STATE).orEmpty()
        val city = args.getString(ARG_CITY).orEmpty()
        val type = args.getString(ARG_TYPE).orEmpty()
        val callsign = args.getString(ARG_CALLSIGN)
        val ident = args.getString(ARG_IDENT)
        val iata = args.getString(ARG_IATA)
        val freq = args.getString(ARG_FREQUENCY).orEmpty()
        val remarks = args.getString(ARG_REMARKS)
        val isEmergency = args.getBoolean(ARG_IS_EMERGENCY, false)

        // -------- ICAO badge + Airport title --------

        val cleanIcao = icao.trim()
            .takeIf { it.isNotBlank() && !it.equals("null", true) }
            ?.uppercase(Locale.getDefault())

        val cleanAirport = airport.trim()
            .takeIf { it.isNotBlank() && !it.equals("null", true) }

        if (cleanIcao == null) {
            b.txtIcaoBadge.visibility = View.GONE
        } else {
            b.txtIcaoBadge.visibility = View.VISIBLE
            b.txtIcaoBadge.text = cleanIcao
            applyIcaoBadgeStyle()
        }

        b.txtTitle.text = cleanAirport ?: getString(R.string.frecuencias)

        // Subtitle (reservado, oculto)
        b.txtSubtitle.visibility = View.GONE

        // -------- Chips (SOLO contexto geográfico) --------
        b.chipGroupMeta.removeAllViews()
        addMetaChip("Estado: ${state.trim()}")
        addMetaChip("Ciudad: ${city.trim()}")


        val cleanType = type.trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
        val cleanCallsign = callsign?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", true) }
        val cleanIdent = ident?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", true) }

        // -------- BLOQUE PRO: Frecuencia | Servicio | Callsign/IDENT --------

        // Frecuencia
        b.txtFrequency.text = freq.ifBlank { "—" }
        b.txtFrequency.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (isEmergency) R.color.emergency_red else R.color.frequency_blue
            )
        )

        // Servicio
        b.txtFreqService.text = when {
            isEmergency -> "EMER"
            !cleanType.isNullOrBlank() -> cleanType.uppercase(Locale.getDefault())
            else -> "—"
        }

        // ¿Es Navaid?
        val isNavaid = isNavaidType(cleanType)

        // Header tercera columna
        b.txtHdrCallsign.text = if (isNavaid) "IDENT" else "CALLSIGN"

        // 3ra columna: si es navaid -> IDENT (1 línea), si no -> CALLSIGN (2 líneas)
        if (isEmergency) {
            b.txtFreqCallsign.visibility = View.GONE
        } else {
            val rawValue = if (isNavaid) cleanIdent else cleanCallsign

            if (rawValue.isNullOrBlank()) {
                b.txtFreqCallsign.visibility = View.GONE
            } else {
                b.txtFreqCallsign.visibility = View.VISIBLE
                val upper = rawValue.uppercase(Locale.getDefault())
                b.txtFreqCallsign.text = if (isNavaid) upper else formatTwoLines(upper)
            }
        }

        applyFrequencyCardStyle(isEmergency)

        // -------- Detalle --------
        b.txtDetail.text = buildString {
            iata?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", true) }?.let {
                append("IATA: ").append(it).append("\n")
            }
            // Si ya mostramos IDENT arriba (navaid), evitamos repetirlo aquí
            if (!isNavaid) {
                cleanIdent?.let { append("IDENT: ").append(it).append("\n") }
            }
            if (isNotEmpty()) while (endsWith("\n")) deleteAt(lastIndex) else append("Sin datos adicionales.")
        }

        val rem = remarks?.trim().takeIf { !it.isNullOrBlank() && !it.equals("null", true) }
        if (rem == null) {
            b.boxRemarks.visibility = View.GONE
        } else {
            b.boxRemarks.visibility = View.VISIBLE
            b.txtRemarks.text = rem
        }

        // -------- Copiar --------
        b.btnCopyFreq.setOnClickListener {
            copyToClipboard(getString(R.string.copiar_frecuencia), freq.ifBlank { "—" })
        }

        b.btnCopyAll.setOnClickListener {
            val meta = mutableListOf<String>()
            if (state.isNotBlank()) meta.add("Estado: $state")
            if (city.isNotBlank()) meta.add("Ciudad: $city")
            cleanType?.let { meta.add("Servicio: $it") }
            cleanCallsign?.let { meta.add("Callsign: $it") }
            cleanIdent?.let { meta.add("IDENT: $it") }
            iata?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", true) }?.let { meta.add("IATA: $it") }

            val titleForCopy = buildString {
                if (cleanIcao != null) append(cleanIcao).append(" · ")
                append(cleanAirport ?: getString(R.string.frecuencias))
            }.trim()

            val thirdLabel = if (isNavaid) "IDENT" else "CALLSIGN"
            val thirdValue = (if (isNavaid) cleanIdent else cleanCallsign).orEmpty()

            val full = buildString {
                append(titleForCopy).append("\n")
                if (meta.isNotEmpty()) append(meta.joinToString(" · ")).append("\n")
                append("FRECUENCIA: ").append(freq.ifBlank { "—" }).append("\n")
                append("SERVICIO: ").append(b.txtFreqService.text).append("\n")
                if (!isEmergency && thirdValue.isNotBlank()) {
                    append(thirdLabel).append(": ").append(thirdValue).append("\n")
                }
                append(b.txtDetail.text.toString()).append("\n")
                if (rem != null) append("OBS: ").append(rem)
                if (isEmergency) append("\nEMERGENCY: true")
            }.trim()

            copyToClipboard(getString(R.string.copiar_todo), full)
        }
    }

    // -------- Helpers --------

    private fun formatTwoLines(value: String): String {
        // Primera palabra en línea 1, el resto en línea 2
        val parts = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.size <= 1 -> value.trim()
            else -> {
                val first = parts.first()
                val rest = parts.drop(1).joinToString(" ")
                "$first\n$rest"
            }
        }
    }

    private fun isNavaidType(type: String?): Boolean {
        val t = type?.uppercase(Locale.getDefault()).orEmpty()
        return t.contains("VOR") ||
                t.contains("DME") ||
                t.contains("ILS") ||
                t.contains("LOC") ||
                t.contains("LOCALIZER") ||
                t.contains("GP") ||
                t.contains("GS") ||
                t.contains("GLIDE") ||
                t.contains("NDB") ||
                t.contains("TACAN") ||
                t.contains("VORTAC")
    }

    private fun applyIcaoBadgeStyle() {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFF263238.toInt())
            setStroke(dpInt(1f), 0xFF455A64.toInt())
            cornerRadius = dp(16f)
        }
        b.txtIcaoBadge.background = bg
        b.txtIcaoBadge.setTextColor(0xFFFFFFFF.toInt())
    }

    private fun applyFrequencyCardStyle(isEmergency: Boolean) {
        val surface = 0xFFF5F5F5.toInt()
        val stroke = if (isEmergency) 0xFF8E1B1B.toInt() else 0xFFDDDDDD.toInt()

        b.cardFrequency.setCardBackgroundColor(surface)
        b.cardFrequency.strokeWidth = dpInt(1f)
        b.cardFrequency.strokeColor = stroke
        b.cardFrequency.radius = dp(14f)
    }

    private fun addMetaChip(text: String) {
        val clean = text.trim()
        // Si acaba en ":" es que venía vacío el valor -> no armamos chip
        if (clean.endsWith(":")) return

        val chip = Chip(requireContext()).apply {
            this.text = clean
            isClickable = false
            isCheckable = false

            // Fondo transparente + borde gris
            chipBackgroundColor = ColorStateList.valueOf(0x00FFFFFF)
            chipStrokeWidth = dp(1f)
            chipStrokeColor = ColorStateList.valueOf(0xFFB0B0B0.toInt())

            // Radio de esquina sin usar la propiedad deprecated chipCornerRadius
            val radius = dp(18f)
            shapeAppearanceModel = shapeAppearanceModel
                .toBuilder()
                .setAllCornerSizes(radius)
                .build()

            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minHeight = dpInt(36f)
        }
        b.chipGroupMeta.addView(chip)
    }



    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun dpInt(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun copyToClipboard(label: String, value: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Snackbar.make(b.root, getString(R.string.copiado), Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        private const val ARG_STATE = "state"
        private const val ARG_CITY = "city"
        private const val ARG_AIRPORT = "airport"
        private const val ARG_ICAO = "icao"
        private const val ARG_IATA = "iata"
        private const val ARG_TYPE = "type"
        private const val ARG_CALLSIGN = "callsign"
        private const val ARG_FREQUENCY = "frequency"
        private const val ARG_IDENT = "ident"
        private const val ARG_REMARKS = "remarks"
        private const val ARG_IS_EMERGENCY = "isEmergency"

        fun newInstance(item: FrequencyItem): FrequencyDetailBottomSheet {
            val b = Bundle().apply {
                putString(ARG_STATE, item.state)
                putString(ARG_CITY, item.city)
                putString(ARG_AIRPORT, item.airportName)
                putString(ARG_ICAO, item.icao)
                putString(ARG_IATA, item.iata)
                putString(ARG_TYPE, item.type)
                putString(ARG_CALLSIGN, item.callsign)
                putString(ARG_FREQUENCY, item.displayFrequency())
                putString(ARG_IDENT, item.ident)
                putString(ARG_REMARKS, item.remarks)
                putBoolean(ARG_IS_EMERGENCY, item.isEmergency)
            }
            return FrequencyDetailBottomSheet().apply { arguments = b }
        }
    }
}
