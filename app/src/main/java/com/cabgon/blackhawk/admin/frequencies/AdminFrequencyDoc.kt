package com.cabgon.blackhawk.admin.frequencies

import com.cabgon.blackhawk.content.frequencies.FrequencyItem

data class AdminFrequencyDoc(
    val id: String,
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
    val isEmergency: Boolean,
    val isDeleted: Boolean = false // ✅ tombstone
) {

    fun toMap(): Map<String, Any?> = mapOf(
        "state" to state,
        "city" to city,
        "airportName" to airportName,
        "icao" to icao,
        "iata" to iata,
        "type" to type,
        "callsign" to callsign,
        "freqMHz" to freqMHz,
        "freqKhz" to freqKhz,
        "ident" to ident,
        "remarks" to remarks,
        "isEmergency" to isEmergency,
        "isDeleted" to isDeleted
    )

    fun toFrequencyItem(): FrequencyItem = FrequencyItem(
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

    companion object {
        fun fromFirestore(id: String, m: Map<String, Any?>): AdminFrequencyDoc {
            return AdminFrequencyDoc(
                id = id,
                state = (m["state"] as? String) ?: "",
                city = (m["city"] as? String) ?: "",
                airportName = (m["airportName"] as? String) ?: "",
                icao = (m["icao"] as? String) ?: "",
                iata = (m["iata"] as? String),
                type = (m["type"] as? String) ?: "",
                callsign = (m["callsign"] as? String),
                freqMHz = (m["freqMHz"] as? Number)?.toDouble(),
                freqKhz = (m["freqKhz"] as? Number)?.toDouble(),
                ident = (m["ident"] as? String),
                remarks = (m["remarks"] as? String),
                isEmergency = (m["isEmergency"] as? Boolean) ?: false,
                isDeleted = (m["isDeleted"] as? Boolean) ?: false
            )
        }
    }
}
