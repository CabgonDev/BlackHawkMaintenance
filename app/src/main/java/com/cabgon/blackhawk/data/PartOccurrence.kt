package com.cabgon.blackhawk.data

data class PartOccurrence(
    val source: String,            // nombre del PDF
    val assetPath: String,         // ruta en assets
    val manualLabel: String,       // label corto para UI
    val page1: Int,                // página 1-based
    val occurrenceOnPage: Int,     // ordinal solo UI
    val snippet: String,           // preview texto
    val approxCharIndex: Int = 0   // opcional (highlight futuro)
)
