@file:Suppress("unused")

package com.cabgon.blackhawk.data.inspection120h

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Inspection120hChecklist(
    val title: String,
    val meta: Inspection120hMeta? = null,
    val sections: List<Inspection120hChecklistSection>
)

@JsonClass(generateAdapter = true)
data class Inspection120hMeta(
    val defaults: Inspection120hDefaults? = null
)

@JsonClass(generateAdapter = true)
data class Inspection120hDefaults(
    val unused: String? = null
)

@JsonClass(generateAdapter = true)
data class Inspection120hChecklistSection(
    val title: String,
    val items: List<Inspection120hChecklistItem>
)

@JsonClass(generateAdapter = true)
data class Inspection120hChecklistItem(
    val title: String,
    val code: String? = null,
    val short: String? = null,
    val required: Boolean? = null,
    val photo: Boolean? = null,
    val notes: Boolean? = null,
    val warning: Boolean? = null,
    val info: Boolean? = null,
    val text: String? = null,
    val subitems: List<Inspection120hChecklistItem>? = null
)
