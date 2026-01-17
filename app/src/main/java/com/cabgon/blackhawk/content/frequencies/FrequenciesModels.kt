package com.cabgon.blackhawk.content.frequencies

import java.util.Locale

data class FrequenciesManifest(
    val schema: Int,
    val generatedAt: String,
    val items: List<FrequencyItem>
)

data class FrequencyItem(
    val state: String,
    val city: String,
    val airportName: String,
    val icao: String,
    val iata: String?,
    val type: String,
    val callsign: String?,
    val freqMHz: Double?,
    val freqKhz: Double?,
    val ident: String?,
    val remarks: String?,
    val isEmergency: Boolean = false
) {
    fun displayFrequency(): String {
        val mhz = freqMHz?.takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.2f MHz", it) }
        val khz = freqKhz?.takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.2f kHz", it) }
        return mhz ?: khz ?: "-"
    }

    fun hasExtra(): Boolean =
        !ident.isNullOrBlank() || !remarks.isNullOrBlank()

    fun safeType(): String = type.trim()
    fun safeState(): String = state.trim()
    fun safeCity(): String = city.trim()
    fun safeAirport(): String = airportName.trim()
    fun safeIcao(): String = icao.trim()
}
