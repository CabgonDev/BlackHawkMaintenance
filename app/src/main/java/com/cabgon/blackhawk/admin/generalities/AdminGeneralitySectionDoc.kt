package com.cabgon.blackhawk.admin.generalities

import com.google.firebase.firestore.DocumentSnapshot

/**
 * Firestore Draft NO soporta nested arrays (rows: [[...],[...]])
 * Por eso guardamos rows como List<Map<String,String>>:
 *   rows: [ {c1:"",c2:"",c3:""}, ... ]
 *
 * En memoria seguimos trabajando como List<List<String>> para el editor.
 */
data class AdminGeneralitySectionDoc(
    val id: String = "",
    val title: String = "",
    val order: Int = 0,
    val tableTitle: String = "",
    val columns: List<String> = listOf("Col 1", "Col 2"),
    val rows: List<List<String>> = emptyList(),
    val isDeleted: Boolean = false,
    val updatedAt: Long = 0L
) {

    fun toMap(): Map<String, Any?> {
        val colCount = columns.size.coerceIn(2, 3)

        // ✅ rows como list of maps (evita nested arrays)
        val rowsAsMaps: List<Map<String, String>> = rows.map { r ->
            val c1 = r.getOrNull(0).orEmpty()
            val c2 = r.getOrNull(1).orEmpty()
            val c3 = if (colCount == 3) r.getOrNull(2).orEmpty() else ""
            buildMap {
                put("c1", c1)
                put("c2", c2)
                if (colCount == 3) put("c3", c3)
            }
        }

        val block = mapOf(
            "type" to "table",
            "title" to tableTitle,
            "columns" to columns,
            "rows" to rowsAsMaps
        )

        return mapOf(
            "title" to title,
            "order" to order,
            "blocks" to listOf(block),
            "isDeleted" to isDeleted,
            "updatedAt" to updatedAt
        )
    }

    companion object {

        fun fromFirestore(id: String, data: Map<String, Any?>): AdminGeneralitySectionDoc {
            val title = (data["title"] as? String).orEmpty()
            val order = (data["order"] as? Number)?.toInt() ?: 0
            val isDeleted = data["isDeleted"] as? Boolean ?: false
            val updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L

            val blocks = data["blocks"] as? List<*>
            val firstBlock = blocks?.firstOrNull() as? Map<*, *>

            val tableTitle = (firstBlock?.get("title") as? String).orEmpty()

            val columns = (firstBlock?.get("columns") as? List<*>)?.mapNotNull { it as? String }
                ?: listOf("Col 1", "Col 2")

            val colCount = columns.size.coerceIn(2, 3)

            // ✅ rows almacenadas en Firestore como List<Map<String,String>>
            val rowsAny = firstBlock?.get("rows") as? List<*>
            val rows = rowsAny?.map { rowObj ->
                val m = rowObj as? Map<*, *> ?: emptyMap<Any, Any>()
                val c1 = (m["c1"] as? String).orEmpty()
                val c2 = (m["c2"] as? String).orEmpty()
                val c3 = (m["c3"] as? String).orEmpty()

                if (colCount == 2) listOf(c1, c2) else listOf(c1, c2, c3)
            } ?: emptyList()

            return AdminGeneralitySectionDoc(
                id = id,
                title = title,
                order = order,
                tableTitle = tableTitle,
                columns = columns.take(colCount),
                rows = rows,
                isDeleted = isDeleted,
                updatedAt = updatedAt
            )
        }

        fun fromSnapshot(d: DocumentSnapshot): AdminGeneralitySectionDoc? {
            val data = d.data ?: return null
            @Suppress("UNCHECKED_CAST")
            return fromFirestore(d.id, data)
        }
    }
}
