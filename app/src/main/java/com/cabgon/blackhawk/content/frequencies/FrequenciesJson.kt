package com.cabgon.blackhawk.content.frequencies

import org.json.JSONArray
import org.json.JSONObject

object FrequenciesJson {

    fun parse(json: String): FrequenciesManifest {
        val root = JSONObject(json)

        val schema = root.optInt("schema", 1)
        val generatedAt = root.optString("generatedAt", "")

        val itemsArr = root.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<FrequencyItem>(itemsArr.length())

        for (i in 0 until itemsArr.length()) {
            val o = itemsArr.getJSONObject(i)

            val state = o.optString("state", "")
            val city = o.optString("city", "")
            val airportName = o.optString("airportName", "")
            val icao = o.optString("icao", "")
            val iata = o.optString("iata", "").ifBlank { null }

            val type = o.optString("type", "")

            // ✅ null-safe: si viene null JSON, queda null en Kotlin (no "null")
            val callsign = o.optStringOrNull("callsign")
            val ident = o.optStringOrNull("ident")
            val remarks = o.optStringOrNull("remarks")

            // emergencia
            val isEmergency = o.optBoolean("isEmergency", false)

            // freqs
            val freqMHz = o.optDoubleOrNull("freqMHz")
            val freqKhz = o.optDoubleOrNull("freqKhz")

            items += FrequencyItem(
                state = state,
                city = city,
                airportName = airportName,
                icao = icao,
                iata = iata,
                type = type,
                callsign = callsign,
                freqMHz = freqMHz,
                freqKhz = freqKhz,
                ident = ident,
                remarks = remarks,
                isEmergency = isEmergency
            )
        }

        return FrequenciesManifest(
            schema = schema,
            generatedAt = generatedAt,
            items = items
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key, "").trim()
        if (v.isBlank()) return null
        if (v.equals("null", ignoreCase = true)) return null
        return v
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return runCatching {
            val v = getDouble(key)
            if (v.isNaN()) null else v
        }.getOrNull()
    }
}
