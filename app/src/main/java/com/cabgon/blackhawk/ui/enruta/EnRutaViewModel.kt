package com.cabgon.blackhawk.ui.enruta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cabgon.blackhawk.data.enruta.EnRutaRepository
import com.cabgon.blackhawk.data.local.enruta.EnRutaRecargaEntity
import com.cabgon.blackhawk.data.local.enruta.EnRutaStatusEntity
import com.cabgon.blackhawk.data.local.enruta.EnRutaWithRecargas
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EnRutaViewModel(
    private val repository: EnRutaRepository,
    private val currentUserIdProvider: () -> String?
) : ViewModel() {

    // ---------- MODELOS DE UI ----------

    data class EnRutaListItemUi(
        val matAeronave: String,
        val categoria: String,
        val ubicacion: String,
        val proxInspeccionLabel: String,
        val horasDisponiblesLabel: String,
        val horasTotalesLabel: String,
        val lastEditDate: String,
        val tecnicoLabel: String
    )

    data class RecargaUi(
        val localId: Long?,
        val folio: String,
        val litros: String,
        val ubicacion: String
    )

    data class EnRutaDetailUiState(
        val isLoading: Boolean = true,
        val isSaving: Boolean = false,
        val errorMessage: String? = null,
        val saveSuccess: Boolean? = null,

        val matAeronave: String = "",
        val lastEditDate: String = "",
        val lastEditorUserId: String? = null,
        val tecnicoLabel: String = "—",

        val categoria: String = "A",
        val ubicacion: String = "",
        val tipoOps: String = "",

        val horasVuelo: String = "",
        val horasTotales: String = "",
        val horasDisponibles: String = "",
        val proxInspeccion: String = "40",

        val motor1Lcf1: String = "",
        val motor1Lcf2: String = "",
        val motor1Index: String = "",
        val motor1Horas: String = "",

        val motor2Lcf1: String = "",
        val motor2Lcf2: String = "",
        val motor2Index: String = "",
        val motor2Horas: String = "",

        val apuHoras: String = "",
        val apuEventos: String = "",

        val reportes: String = "",

        val recargas: List<RecargaUi> = emptyList()
    )

    // ---------- FECHA/HORA ----------
    private val dateFormatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    private fun nowString(): String = dateFormatter.format(Date())

    // ---------- FIRESTORE: users/{uid} ----------
    private val firestore = FirebaseFirestore.getInstance()
    private val usersCollection = "users"

    private data class TechInfo(val grado: String, val primerApellido: String)
    private val techCache = MutableStateFlow<Map<String, TechInfo>>(emptyMap())

    private fun primerApellido(nombreCompleto: String): String {
        val parts = nombreCompleto.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> ""
            parts.size == 1 -> parts[0]
            else -> parts[parts.size - 2] // penúltimo token
        }
    }

    private fun fetchMissingTechInfo(uids: Set<String>) {
        val current = techCache.value
        val missing = uids.filter { it.isNotBlank() && !current.containsKey(it) }.toSet()
        if (missing.isEmpty()) return

        missing.forEach { uid ->
            firestore.collection(usersCollection).document(uid).get()
                .addOnSuccessListener { doc ->
                    if (!doc.exists()) return@addOnSuccessListener
                    val grado = doc.getString("grado").orEmpty()
                    val nombre = doc.getString("nombre").orEmpty()
                    val ap1 = primerApellido(nombre)

                    techCache.update { old ->
                        old + (uid to TechInfo(grado = grado, primerApellido = ap1))
                    }
                }
        }
    }

    private fun buildTecnicoLabel(uid: String?, cache: Map<String, TechInfo>): String {
        val key = uid.orEmpty()
        val tech = cache[key] ?: return "—"
        return listOf(tech.grado, tech.primerApellido)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "—" }
    }

    // ---------- PULL TO REFRESH ----------
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun refreshEnRuta() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                repository.refreshFromFirestoreOnce()
            } catch (e: Exception) {
                _detailState.update { it.copy(errorMessage = "Error al refrescar En Ruta: ${e.message}") }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    // ---------- LISTA "EN RUTA" ----------
    val enRutaListUi: StateFlow<List<EnRutaListItemUi>> =
        combine(repository.observeEnRutaList(), techCache) { list, cache ->

            val uids = list.mapNotNull { it.lastEditorUserId }.toSet()
            fetchMissingTechInfo(uids)

            list.map { entity ->
                val tecnicoLabel = buildTecnicoLabel(entity.lastEditorUserId, cache)

                val lastEdit = if (entity.lastEditTimestamp > 0L)
                    dateFormatter.format(Date(entity.lastEditTimestamp))
                else entity.lastEditDate

                EnRutaListItemUi(
                    matAeronave = entity.matAeronave,
                    categoria = entity.categoria,
                    ubicacion = entity.ubicacion.ifBlank { "Sin ubicación" },
                    proxInspeccionLabel = "${entity.proxInspeccion} hrs",
                    horasDisponiblesLabel = "${entity.horasDisponibles} hrs disp.",
                    horasTotalesLabel = "${entity.horasTotales} hrs tot.",
                    lastEditDate = lastEdit,
                    tecnicoLabel = tecnicoLabel
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---------- DETALLE "EN RUTA" ----------
    private val _detailState = MutableStateFlow(EnRutaDetailUiState())

    val detailState: StateFlow<EnRutaDetailUiState> =
        combine(_detailState, techCache) { state, cache ->
            state.copy(tecnicoLabel = buildTecnicoLabel(state.lastEditorUserId, cache))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EnRutaDetailUiState())

    private var detailObserverJob: Job? = null

    init {
        repository.startRealtimeStatusListener(viewModelScope)
        // ✅ Limpieza / reconciliación inicial al entrar al módulo
        refreshEnRuta()
    }

    fun cargarDetalle(matAeronave: String) {
        detailObserverJob?.cancel()

        detailObserverJob = repository
            .observeEnRutaWithRecargasByMat(matAeronave)
            .onStart {
                _detailState.update { it.copy(isLoading = true, errorMessage = null, saveSuccess = null) }
            }
            .onEach { data ->
                if (data != null) {
                    val uid = data.status.lastEditorUserId
                    if (!uid.isNullOrBlank()) fetchMissingTechInfo(setOf(uid))
                    applyDetailFromEntity(data)
                }
            }
            .catch { e ->
                _detailState.update {
                    it.copy(isLoading = false, errorMessage = "Error observando detalle: ${e.message}")
                }
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            val existing = repository.getEnRutaWithRecargasByMat(matAeronave)
            if (existing != null) return@launch

            val uid = currentUserIdProvider() ?: "DESCONOCIDO"
            val now = System.currentTimeMillis()

            val newStatus = EnRutaStatusEntity(
                id = 0L,
                matAeronave = matAeronave,
                lastEditDate = nowString(),
                lastEditTimestamp = now,
                lastEditorUserId = uid,

                categoria = "A",
                ubicacion = "",
                tipoOps = "",

                horasVuelo = 0.0,
                horasTotales = 0.0,
                horasDisponibles = 0.0,
                proxInspeccion = 40,

                motor1Lcf1 = 0,
                motor1Lcf2 = 0,
                motor1Index = 0,
                motor1Horas = 0,

                motor2Lcf1 = 0,
                motor2Lcf2 = 0,
                motor2Index = 0,
                motor2Horas = 0,

                apuHoras = 0,
                apuEventos = 0,

                reportes = "",
                isDirty = true,
                lastSyncTimestamp = null
            )

            try {
                repository.saveLocalAndSync(newStatus, emptyList())
                fetchMissingTechInfo(setOf(uid))
            } catch (e: Exception) {
                _detailState.update {
                    it.copy(isLoading = false, errorMessage = "Error al crear registro En Ruta: ${e.message}")
                }
            }
        }
    }

    private fun applyDetailFromEntity(data: EnRutaWithRecargas) {
        val status = data.status

        val recargasUi = data.recargas.map { rec ->
            RecargaUi(
                localId = rec.id,
                folio = rec.folio.toString(),
                litros = rec.recargaLitros.toString(),
                ubicacion = rec.ubicacion
            )
        }

        val lastEdit = if (status.lastEditTimestamp > 0L)
            dateFormatter.format(Date(status.lastEditTimestamp))
        else status.lastEditDate

        _detailState.update {
            it.copy(
                isLoading = false,
                errorMessage = null,
                saveSuccess = null,

                matAeronave = status.matAeronave,
                lastEditDate = lastEdit,
                lastEditorUserId = status.lastEditorUserId,

                categoria = status.categoria,
                ubicacion = status.ubicacion,
                tipoOps = status.tipoOps,

                horasVuelo = status.horasVuelo.takeIf { v -> v != 0.0 }?.toString() ?: "",
                horasTotales = status.horasTotales.takeIf { v -> v != 0.0 }?.toString() ?: "",
                horasDisponibles = status.horasDisponibles.takeIf { v -> v != 0.0 }?.toString() ?: "",
                proxInspeccion = status.proxInspeccion.toString(),

                motor1Lcf1 = status.motor1Lcf1.takeIf { v -> v != 0 }?.toString() ?: "",
                motor1Lcf2 = status.motor1Lcf2.takeIf { v -> v != 0 }?.toString() ?: "",
                motor1Index = status.motor1Index.takeIf { v -> v != 0 }?.toString() ?: "",
                motor1Horas = status.motor1Horas.takeIf { v -> v != 0 }?.toString() ?: "",

                motor2Lcf1 = status.motor2Lcf1.takeIf { v -> v != 0 }?.toString() ?: "",
                motor2Lcf2 = status.motor2Lcf2.takeIf { v -> v != 0 }?.toString() ?: "",
                motor2Index = status.motor2Index.takeIf { v -> v != 0 }?.toString() ?: "",
                motor2Horas = status.motor2Horas.takeIf { v -> v != 0 }?.toString() ?: "",

                apuHoras = status.apuHoras.takeIf { v -> v != 0 }?.toString() ?: "",
                apuEventos = status.apuEventos.takeIf { v -> v != 0 }?.toString() ?: "",

                reportes = status.reportes,
                recargas = recargasUi
            )
        }
    }

    // ---------- EVENTOS DE UI ----------
    fun onCategoriaChange(value: String) = _detailState.update { it.copy(categoria = value) }
    fun onUbicacionChange(value: String) = _detailState.update { it.copy(ubicacion = value) }
    fun onTipoOpsChange(value: String) = _detailState.update { it.copy(tipoOps = value) }

    fun onHorasVueloChange(value: String) = _detailState.update { it.copy(horasVuelo = value) }
    fun onHorasTotalesChange(value: String) = _detailState.update { it.copy(horasTotales = value) }
    fun onHorasDisponiblesChange(value: String) = _detailState.update { it.copy(horasDisponibles = value) }
    fun onProxInspeccionChange(value: String) = _detailState.update { it.copy(proxInspeccion = value) }

    fun onMotor1Lcf1Change(v: String) = _detailState.update { it.copy(motor1Lcf1 = v) }
    fun onMotor1Lcf2Change(v: String) = _detailState.update { it.copy(motor1Lcf2 = v) }
    fun onMotor1IndexChange(v: String) = _detailState.update { it.copy(motor1Index = v) }
    fun onMotor1HorasChange(v: String) = _detailState.update { it.copy(motor1Horas = v) }

    fun onMotor2Lcf1Change(v: String) = _detailState.update { it.copy(motor2Lcf1 = v) }
    fun onMotor2Lcf2Change(v: String) = _detailState.update { it.copy(motor2Lcf2 = v) }
    fun onMotor2IndexChange(v: String) = _detailState.update { it.copy(motor2Index = v) }
    fun onMotor2HorasChange(v: String) = _detailState.update { it.copy(motor2Horas = v) }

    fun onApuHorasChange(v: String) = _detailState.update { it.copy(apuHoras = v) }
    fun onApuEventosChange(v: String) = _detailState.update { it.copy(apuEventos = v) }

    fun onReportesChange(v: String) = _detailState.update { it.copy(reportes = v) }

    // ---------- RECARGAS ----------
    fun agregarRecarga() {
        _detailState.update { state ->
            state.copy(recargas = state.recargas + RecargaUi(localId = null, folio = "", litros = "", ubicacion = ""))
        }
    }

    fun agregarRecarga(folio: Int, litros: Int, ubicacion: String) {
        _detailState.update { state ->
            state.copy(recargas = state.recargas + RecargaUi(localId = null, folio = folio.toString(), litros = litros.toString(), ubicacion = ubicacion))
        }
    }

    fun onRecargaFolioChange(index: Int, value: String) {
        _detailState.update { state ->
            val list = state.recargas.toMutableList()
            if (index in list.indices) list[index] = list[index].copy(folio = value)
            state.copy(recargas = list)
        }
    }

    fun onRecargaLitrosChange(index: Int, value: String) {
        _detailState.update { state ->
            val list = state.recargas.toMutableList()
            if (index in list.indices) list[index] = list[index].copy(litros = value)
            state.copy(recargas = list)
        }
    }

    fun onRecargaUbicacionChange(index: Int, value: String) {
        _detailState.update { state ->
            val list = state.recargas.toMutableList()
            if (index in list.indices) list[index] = list[index].copy(ubicacion = value)
            state.copy(recargas = list)
        }
    }

    fun eliminarRecarga(index: Int) {
        _detailState.update { state ->
            val list = state.recargas.toMutableList()
            if (index in list.indices) list.removeAt(index)
            state.copy(recargas = list)
        }
    }

    // ---------- GUARDAR ----------
    fun guardarCambios() {
        val state = _detailState.value
        if (state.matAeronave.isBlank()) return

        viewModelScope.launch {
            _detailState.update { it.copy(isSaving = true, errorMessage = null, saveSuccess = null) }

            try {
                val uid = currentUserIdProvider() ?: "DESCONOCIDO"
                val now = System.currentTimeMillis()

                fun parseDoubleOrZero(s: String): Double = s.replace(",", ".").toDoubleOrNull() ?: 0.0
                fun parseIntOrZero(s: String): Int = s.toIntOrNull() ?: 0

                val status = EnRutaStatusEntity(
                    id = 0L,
                    matAeronave = state.matAeronave,
                    lastEditDate = nowString(),
                    lastEditTimestamp = now,
                    lastEditorUserId = uid,

                    categoria = state.categoria,
                    ubicacion = state.ubicacion,
                    tipoOps = state.tipoOps,

                    horasVuelo = parseDoubleOrZero(state.horasVuelo),
                    horasTotales = parseDoubleOrZero(state.horasTotales),
                    horasDisponibles = parseDoubleOrZero(state.horasDisponibles),
                    proxInspeccion = parseIntOrZero(state.proxInspeccion),

                    motor1Lcf1 = parseIntOrZero(state.motor1Lcf1),
                    motor1Lcf2 = parseIntOrZero(state.motor1Lcf2),
                    motor1Index = parseIntOrZero(state.motor1Index),
                    motor1Horas = parseIntOrZero(state.motor1Horas),

                    motor2Lcf1 = parseIntOrZero(state.motor2Lcf1),
                    motor2Lcf2 = parseIntOrZero(state.motor2Lcf2),
                    motor2Index = parseIntOrZero(state.motor2Index),
                    motor2Horas = parseIntOrZero(state.motor2Horas),

                    apuHoras = parseIntOrZero(state.apuHoras),
                    apuEventos = parseIntOrZero(state.apuEventos),

                    reportes = state.reportes,
                    isDirty = true,
                    lastSyncTimestamp = null
                )

                val recargasEntities = state.recargas.map { r ->
                    EnRutaRecargaEntity(
                        id = 0L,
                        enRutaId = 0L,
                        folio = parseIntOrZero(r.folio),
                        recargaLitros = parseIntOrZero(r.litros),
                        ubicacion = r.ubicacion,
                        createdAt = now,
                        isDirty = true
                    )
                }

                repository.saveLocalAndSync(status, recargasEntities)

                fetchMissingTechInfo(setOf(uid))

                _detailState.update {
                    it.copy(
                        isSaving = false,
                        saveSuccess = true,
                        lastEditDate = nowString(),
                        lastEditorUserId = uid
                    )
                }
            } catch (e: Exception) {
                _detailState.update {
                    it.copy(
                        isSaving = false,
                        saveSuccess = false,
                        errorMessage = "Error al guardar cambios: ${e.message}"
                    )
                }
            }
        }
    }

    fun agregarARuta(matAeronave: String) {
        cargarDetalle(matAeronave)
    }

    fun quitarDeRuta(matAeronave: String) {
        viewModelScope.launch {
            try {
                repository.removeFromRuta(matAeronave)
                // ayuda a limpiar “fantasmas” si algo no llegó por listener
                refreshEnRuta()
            } catch (e: Exception) {
                _detailState.update { it.copy(errorMessage = "Error al quitar de En Ruta: ${e.message}") }
            }
        }
    }
}
