package com.cabgon.blackhawk.data.inspection40h

import android.content.Context
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Repository Firestore para inspecciones 40H.
 *
 * Firestore es la fuente de verdad para permitir trabajo colaborativo en tiempo real.
 * - El header vive en la colección principal.
 * - Los ítems viven en la subcolección "items" del documento.
 * - Cada check/uncheck actualiza quién lo hizo, cuándo lo hizo y el avance agregado.
 */
class Inspection40hRepository(private val context: Context) {

    companion object {
        private const val COLLECTION = "inspection_40h"
    }

    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val collection = firestore.collection(COLLECTION)

    data class Header(
        val id: String,
        val fechaEpochMillis: Long,
        val matAeronave: String,
        val hsTotales: Float,
        val supervisorGrade: String,
        val supervisorSpecialty: String,
        val supervisorFullName: String,
        val supervisorMatricula: String,
        val completed: Boolean,
        val totalItems: Int = 0,
        val doneItems: Int = 0,
        val lastModified: Long = 0L,
        val state: String = "draft",
        val lastSavedAt: Long? = null,
        val lastSavedByUid: String? = null,
        val lastSavedByName: String? = null,
        val closedAt: Long? = null,
        val closedByUid: String? = null,
        val closedByFirstLastName: String? = null
    )

    data class Item(
        val id: String,
        val code: String,
        val shortText: String,
        val longText: String,
        val orderIndex: Int,
        val checked: Boolean,
        val checkedByUid: String? = null,
        val checkedByFirstLastName: String? = null,
        val checkedAt: Long? = null,
        val progressIncluded: Boolean = true,
        val applicable: Boolean = true
    )

    data class InspectionWithItems(
        val header: Header,
        val items: List<Item>
    )

    data class ChecklistDefItem(
        val code: String,
        val shortText: String,
        val longText: String,
        val orderIndex: Int,
        val progressIncluded: Boolean,
        val applicable: Boolean
    )

    private fun loadChecklistDefinition(): List<ChecklistDefItem> {
        val checklist = Inspection40hChecklistStore.loadChecklist(context)
        val flatEntries = Inspection40hChecklistStore.flattenItems(checklist)

        return flatEntries.mapIndexed { index, entry ->
            val spec = entry.item
            val code = spec.code
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                .orEmpty()
            val shortText = spec.short?.takeIf { it.isNotBlank() }
                ?: spec.title
            val longText = when {
                spec.warning == true || spec.info == true ->
                    spec.text?.takeIf { it.isNotBlank() } ?: spec.title
                else -> spec.title
            }
            val applicable = spec.applicable != false
            val progressIncluded = applicable && spec.warning != true && spec.info != true && !entry.hasChildren

            return@mapIndexed ChecklistDefItem(
                code = code,
                shortText = shortText,
                longText = longText,
                orderIndex = entry.orderIndex,
                progressIncluded = progressIncluded,
                applicable = applicable
            )
        }
    }

    fun observeInspections(): Flow<List<Header>> = callbackFlow {
        val registration = collection
            .orderBy("fechaEpochMillis", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                val headers = snapshot?.documents.orEmpty().mapNotNull { doc ->
                    doc.takeIf { it.exists() }?.let(::mapHeaderDoc)
                }
                trySend(headers)
            }

        awaitClose { registration.remove() }
    }

    fun observeWithItems(): Flow<List<InspectionWithItems>> {
        return observeInspections().map { headers ->
            headers.map { header -> InspectionWithItems(header = header, items = emptyList()) }
        }
    }

    fun observeInspection(id: String): Flow<InspectionWithItems?> = callbackFlow {
        var latestHeader: Header? = null
        var latestItems: List<Item> = emptyList()
        var headerResolved = false
        var itemsResolved = false

        fun push() {
            if (!headerResolved) return
            if (latestHeader == null) {
                trySend(null)
                return
            }
            if (!itemsResolved) return
            trySend(InspectionWithItems(latestHeader!!, latestItems))
        }

        val headerRegistration = collection.document(id)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                headerResolved = true
                latestHeader = if (snapshot == null || !snapshot.exists()) null else mapHeaderDoc(snapshot)
                push()
            }

        val itemsRegistration = collection.document(id)
            .collection("items")
            .orderBy("orderIndex", com.google.firebase.firestore.Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                itemsResolved = true
                latestItems = snapshot?.documents.orEmpty().map(::mapItemDoc)
                push()
            }

        awaitClose {
            headerRegistration.remove()
            itemsRegistration.remove()
        }
    }

    suspend fun getInspection(id: String): InspectionWithItems? {
        val headerSnapshot = collection.document(id).get().await()
        if (!headerSnapshot.exists()) return null

        val itemsSnapshot = collection.document(id)
            .collection("items")
            .orderBy("orderIndex", com.google.firebase.firestore.Query.Direction.ASCENDING)
            .get()
            .await()

        return InspectionWithItems(
            header = mapHeaderDoc(headerSnapshot),
            items = itemsSnapshot.documents.map(::mapItemDoc)
        )
    }

    suspend fun createInspection40h(
        fechaEpochMillis: Long,
        matAeronave: String,
        hsTotales: Float,
        supervisorGrade: String,
        supervisorSpecialty: String,
        supervisorFullName: String,
        supervisorMatricula: String
    ): String {
        val now = System.currentTimeMillis()
        val template = loadChecklistDefinition()
        val totalProgressItems = template.count { it.progressIncluded }
        val docRef = collection.document()
        val batch = firestore.batch()

        batch.set(
            docRef,
            hashMapOf(
                "inspectionType" to "40h",
                "fechaEpochMillis" to fechaEpochMillis,
                "matAeronave" to matAeronave.trim(),
                "hsTotales" to hsTotales.toDouble(),
                "supervisorGrade" to supervisorGrade.trim(),
                "supervisorSpecialty" to supervisorSpecialty.trim(),
                "supervisorFullName" to supervisorFullName.trim(),
                "supervisorMatricula" to supervisorMatricula.trim(),
                "completed" to false,
                "doneItems" to 0,
                "totalItems" to totalProgressItems,
                "lastModified" to now,
                "lastSavedAt" to now,
                "state" to "draft"
            )
        )

        template.forEach { def ->
            val itemRef = docRef.collection("items").document()
            batch.set(
                itemRef,
                hashMapOf(
                    "code" to def.code,
                    "shortText" to def.shortText,
                    "longText" to def.longText,
                    "orderIndex" to def.orderIndex,
                    "checked" to !def.applicable,
                    "checkedByUid" to null,
                    "checkedByFirstLastName" to null,
                    "checkedAt" to null,
                    "progressIncluded" to def.progressIncluded,
                    "applicable" to def.applicable,
                    "lastModified" to now
                )
            )
        }

        batch.commit().await()
        return docRef.id
    }

    suspend fun setItemNotApplicable(
        inspectionId: String,
        itemId: String
    ) {
        val now = System.currentTimeMillis()
        val headerRef = collection.document(inspectionId)
        val itemRef = headerRef.collection("items").document(itemId)

        firestore.runTransaction { txn ->
            val headerSnap = txn.get(headerRef)
            if (!headerSnap.exists()) {
                throw IllegalStateException("La inspección ya no existe en Firestore.")
            }
            if ((headerSnap.getString("state") ?: "draft") == "closed") {
                return@runTransaction
            }

            val itemSnap = txn.get(itemRef)
            if (!itemSnap.exists()) {
                throw IllegalStateException("El ítem ya no existe en Firestore.")
            }

            val wasChecked = itemSnap.getBoolean("checked") ?: false
            val progressIncluded = itemSnap.getBoolean("progressIncluded") ?: true
            val currentDone = (headerSnap.getLong("doneItems") ?: 0L).toInt()
            val totalItems = (headerSnap.getLong("totalItems") ?: 0L).toInt()

            val nextTotal = if (progressIncluded) totalItems - 1 else totalItems
            val nextDone = if (progressIncluded && wasChecked) currentDone - 1 else currentDone
            val safeTotal = nextTotal.coerceAtLeast(0)
            val safeDone = nextDone.coerceIn(0, safeTotal)
            val nextState = deriveState(doneItems = safeDone, totalItems = safeTotal)

            txn.update(
                itemRef,
                mapOf(
                    "checked" to true,
                    "checkedByUid" to null,
                    "checkedByFirstLastName" to null,
                    "checkedAt" to null,
                    "progressIncluded" to false,
                    "applicable" to false,
                    "lastModified" to now
                )
            )

            txn.update(
                headerRef,
                mapOf(
                    "doneItems" to safeDone,
                    "totalItems" to safeTotal,
                    "completed" to (safeTotal > 0 && safeDone >= safeTotal),
                    "lastModified" to now,
                    "lastSavedAt" to now,
                    "state" to nextState
                )
            )
        }.await()
    }

    suspend fun setItemChecked(
        inspectionId: String,
        itemId: String,
        checked: Boolean,
        uid: String?,
        firstLastName: String?
    ) {
        val now = System.currentTimeMillis()
        val headerRef = collection.document(inspectionId)
        val itemRef = headerRef.collection("items").document(itemId)

        firestore.runTransaction { txn ->
            val headerSnap = txn.get(headerRef)
            if (!headerSnap.exists()) {
                throw IllegalStateException("La inspección ya no existe en Firestore.")
            }
            if ((headerSnap.getString("state") ?: "draft") == "closed") {
                throw IllegalStateException("La inspección ya fue finalizada y no se puede editar.")
            }

            val itemSnap = txn.get(itemRef)
            if (!itemSnap.exists()) {
                throw IllegalStateException("El ítem ya no existe en Firestore.")
            }

            val wasChecked = itemSnap.getBoolean("checked") ?: false
            val progressIncluded = itemSnap.getBoolean("progressIncluded") ?: true
            val currentDone = (headerSnap.getLong("doneItems") ?: 0L).toInt()
            val totalItems = (headerSnap.getLong("totalItems") ?: 0L).toInt()

            val nextDone = when {
                !progressIncluded -> currentDone
                wasChecked == checked -> currentDone
                checked -> currentDone + 1
                else -> currentDone - 1
            }.coerceIn(0, totalItems.coerceAtLeast(0))

            val nextState = deriveState(doneItems = nextDone, totalItems = totalItems)

            txn.update(
                itemRef,
                mapOf(
                    "checked" to checked,
                    "checkedByUid" to if (checked) uid else null,
                    "checkedByFirstLastName" to if (checked) firstLastName else null,
                    "checkedAt" to if (checked) now else null,
                    "lastModified" to now
                )
            )

            txn.update(
                headerRef,
                mapOf(
                    "doneItems" to nextDone,
                    "completed" to (totalItems > 0 && nextDone >= totalItems),
                    "lastModified" to now,
                    "lastSavedAt" to now,
                    "lastSavedByUid" to uid,
                    "lastSavedByName" to firstLastName,
                    "state" to nextState
                )
            )
        }.await()
    }

    suspend fun saveProgress(
        inspectionId: String,
        uid: String?,
        firstLastName: String?
    ) {
        val now = System.currentTimeMillis()
        val headerRef = collection.document(inspectionId)

        firestore.runTransaction { txn ->
            val headerSnap = txn.get(headerRef)
            if (!headerSnap.exists()) {
                throw IllegalStateException("La inspección ya no existe en Firestore.")
            }
            if ((headerSnap.getString("state") ?: "draft") == "closed") {
                throw IllegalStateException("La inspección ya fue finalizada y no se puede editar.")
            }

            val totalItems = (headerSnap.getLong("totalItems") ?: 0L).toInt()
            val doneItems = (headerSnap.getLong("doneItems") ?: 0L).toInt()
            val nextState = deriveState(doneItems = doneItems, totalItems = totalItems)

            txn.update(
                headerRef,
                mapOf(
                    "completed" to (totalItems > 0 && doneItems >= totalItems),
                    "lastModified" to now,
                    "lastSavedAt" to now,
                    "lastSavedByUid" to uid,
                    "lastSavedByName" to firstLastName,
                    "state" to nextState
                )
            )
        }.await()
    }

    suspend fun saveDraft(
        inspectionId: String,
        uid: String?,
        firstLastName: String?
    ) = saveProgress(inspectionId, uid, firstLastName)

    suspend fun finalizeInspection(
        inspectionId: String,
        uid: String?,
        firstLastName: String?
    ) {
        val now = System.currentTimeMillis()
        val headerRef = collection.document(inspectionId)

        firestore.runTransaction { txn ->
            val headerSnap = txn.get(headerRef)
            if (!headerSnap.exists()) {
                throw IllegalStateException("La inspección ya no existe en Firestore.")
            }

            val state = headerSnap.getString("state") ?: "draft"
            if (state == "closed") {
                return@runTransaction
            }

            val totalItems = (headerSnap.getLong("totalItems") ?: 0L).toInt()
            val doneItems = (headerSnap.getLong("doneItems") ?: 0L).toInt()
            if (totalItems <= 0 || doneItems < totalItems) {
                throw IllegalStateException("Completa todas las tareas antes de finalizar la inspección.")
            }

            txn.update(
                headerRef,
                mapOf(
                    "completed" to true,
                    "lastModified" to now,
                    "lastSavedAt" to now,
                    "lastSavedByUid" to uid,
                    "lastSavedByName" to firstLastName,
                    "state" to "closed",
                    "closedAt" to now,
                    "closedByUid" to uid,
                    "closedByFirstLastName" to firstLastName
                )
            )
        }.await()
    }

    suspend fun deleteInspection(id: String) {
        val headerRef = collection.document(id)
        val itemsSnapshot = headerRef.collection("items").get().await()
        val batch = firestore.batch()

        itemsSnapshot.documents.forEach { batch.delete(it.reference) }
        batch.delete(headerRef)
        batch.commit().await()
    }

    private fun deriveState(doneItems: Int, totalItems: Int): String {
        return when {
            totalItems > 0 && doneItems >= totalItems -> "ready_to_close"
            doneItems > 0 -> "in_progress"
            else -> "draft"
        }
    }

    private fun mapHeaderDoc(doc: DocumentSnapshot): Header {
        return Header(
            id = doc.id,
            fechaEpochMillis = doc.getLong("fechaEpochMillis") ?: 0L,
            matAeronave = doc.getString("matAeronave").orEmpty(),
            hsTotales = (doc.getDouble("hsTotales")
                ?: doc.getLong("hsTotales")?.toDouble()
                ?: 0.0).toFloat(),
            supervisorGrade = doc.getString("supervisorGrade").orEmpty(),
            supervisorSpecialty = doc.getString("supervisorSpecialty").orEmpty(),
            supervisorFullName = doc.getString("supervisorFullName").orEmpty(),
            supervisorMatricula = doc.getString("supervisorMatricula").orEmpty(),
            completed = doc.getBoolean("completed") ?: false,
            totalItems = (doc.getLong("totalItems") ?: 0L).toInt(),
            doneItems = (doc.getLong("doneItems") ?: 0L).toInt(),
            lastModified = doc.getLong("lastModified") ?: 0L,
            state = doc.getString("state") ?: "draft",
            lastSavedAt = doc.getLong("lastSavedAt"),
            lastSavedByUid = doc.getString("lastSavedByUid"),
            lastSavedByName = doc.getString("lastSavedByName"),
            closedAt = doc.getLong("closedAt"),
            closedByUid = doc.getString("closedByUid"),
            closedByFirstLastName = doc.getString("closedByFirstLastName")
        )
    }

    private fun mapItemDoc(doc: DocumentSnapshot): Item {
        return Item(
            id = doc.id,
            code = doc.getString("code").orEmpty(),
            shortText = doc.getString("shortText").orEmpty(),
            longText = doc.getString("longText").orEmpty(),
            orderIndex = (doc.getLong("orderIndex") ?: 0L).toInt(),
            checked = doc.getBoolean("checked") ?: false,
            checkedByUid = doc.getString("checkedByUid"),
            checkedByFirstLastName = doc.getString("checkedByFirstLastName"),
            checkedAt = doc.getLong("checkedAt"),
            progressIncluded = doc.getBoolean("progressIncluded") ?: true,
            applicable = doc.getBoolean("applicable") ?: true
        )
    }
}
