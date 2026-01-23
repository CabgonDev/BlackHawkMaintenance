package com.cabgon.blackhawk.data

/**
 * Resultado genérico para búsqueda dentro de un PDF.
 * Usado por PdfViewerActivity y OccurrenceResultAdapter.
 */
data class OccurrenceHit(
    val source: String,
    val page1: Int,
    val snippet: String,
    val chunkId: String = "",
    val chunkText: String = "",
    val approxCharIndexInChunk: Int = 0,
    val occurrenceIndex: Int = 0
)
