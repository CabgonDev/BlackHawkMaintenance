package com.cabgon.blackhawk.ui.admin.enruta

data class AdminEnRutaItem(
    val matAeronave: String,
    val categoria: String,
    val ubicacion: String,
    val lastEditTimestamp: Long,
    val lastEditorUserId: String?
)
