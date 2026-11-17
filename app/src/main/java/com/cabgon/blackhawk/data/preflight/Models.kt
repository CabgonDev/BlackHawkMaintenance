package com.cabgon.blackhawk.model.preflight

data class Header(
    val id: Long,
    val fechaEpochMillis: Long,
    val hora24: String,
    val matAeronave: String,
    val tecnicoGrado: String,
    val tecnicoEspecialidad: String,
    val tecnicoNombre: String,
    val hsTotales: String?,
    val hsDisponibles: String?,
    val tecnicoMatricula: String?
)

data class Item(
    val id: Long,
    val title: String,
    val checked: Boolean = false
)

data class InspectionWithItems(
    val header: Header,
    val items: List<Item>
)
