@file:Suppress("unused")

package com.cabgon.blackhawk.data.inspection480h

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Inspection480hChecklist(
    val title: String,
    val meta: Inspection480hMeta? = null,
    val sections: List<Inspection480hChecklistSection>
)

@JsonClass(generateAdapter = true)
data class Inspection480hMeta(
    val defaults: Inspection480hDefaults? = null
)

@JsonClass(generateAdapter = true)
data class Inspection480hDefaults(
    val unused: String? = null
)

@JsonClass(generateAdapter = true)
data class Inspection480hChecklistSection(
    val title: String,
    val items: List<Inspection480hChecklistItem>
)

@JsonClass(generateAdapter = true)
data class Inspection480hChecklistItem(
    val title: String,
    val code: String? = null,
    val short: String? = null,
    val required: Boolean? = null,
    val photo: Boolean? = null,
    val notes: Boolean? = null,
    val warning: Boolean? = null,
    val info: Boolean? = null,
    val text: String? = null,
    val subitems: List<Inspection480hChecklistItem>? = null
)
