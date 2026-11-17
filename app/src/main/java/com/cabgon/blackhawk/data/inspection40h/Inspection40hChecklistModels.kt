@file:Suppress("unused")

package com.cabgon.blackhawk.data.inspection40h

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Inspection40hChecklist(
    val title: String,
    val meta: Inspection40hMeta? = null,
    val sections: List<Inspection40hChecklistSection>
)

@JsonClass(generateAdapter = true)
data class Inspection40hMeta(
    val defaults: Inspection40hDefaults? = null
)

@JsonClass(generateAdapter = true)
data class Inspection40hDefaults(
    val unused: String? = null
)

@JsonClass(generateAdapter = true)
data class Inspection40hChecklistSection(
    val title: String,
    val items: List<Inspection40hChecklistItem>
)

@JsonClass(generateAdapter = true)
data class Inspection40hChecklistItem(
    val title: String,
    val code: String? = null,
    val short: String? = null,
    val required: Boolean? = null,
    val applicable: Boolean? = null,
    val photo: Boolean? = null,
    val notes: Boolean? = null,
    val warning: Boolean? = null,
    val info: Boolean? = null,
    val text: String? = null,
    val subitems: List<Inspection40hChecklistItem>? = null
)
