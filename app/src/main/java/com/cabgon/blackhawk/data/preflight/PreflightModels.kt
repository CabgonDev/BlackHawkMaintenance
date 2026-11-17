// com.cabgon.blackhawk.data.preflight.PreflightModels.kt
@file:Suppress("unused")

package com.cabgon.blackhawk.data.preflight

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PreflightChecklist(
    val title: String,
    val meta: PreflightMeta? = null,
    val sections: List<ChecklistSection>
)

@JsonClass(generateAdapter = true)
data class PreflightMeta(
    val matAeronaveOptions: List<String>? = null,
    val grados: List<String>? = null,
    val especialidades: List<String>? = null,
    val defaults: PreflightDefaults? = null
)

@JsonClass(generateAdapter = true)
data class PreflightDefaults(
    val grado: String? = null,
    val especialidad: String? = null
)

@JsonClass(generateAdapter = true)
data class ChecklistSection(
    val title: String,
    val items: List<ChecklistItem>
)

@JsonClass(generateAdapter = true)
data class ChecklistItem(
    val title: String,

    // Texto corto para UI (lo que estamos usando en el adapter)
    val short: String? = null,

    // Lógica
    val required: Boolean? = null,
    val photo: Boolean? = null,
    val notes: Boolean? = null,

    // Para advertencias / info tipo banner
    val warning: Boolean? = null,
    val info: Boolean? = null,
    val text: String? = null,

    // Subitems anidados (a, b, c...)
    val subitems: List<ChecklistItem>? = null
)
