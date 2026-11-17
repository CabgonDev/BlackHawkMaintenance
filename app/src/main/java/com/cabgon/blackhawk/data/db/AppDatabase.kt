package com.cabgon.blackhawk.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.cabgon.blackhawk.data.inspection120h.Inspection120hDao
import com.cabgon.blackhawk.data.inspection120h.Inspection120hHeader
import com.cabgon.blackhawk.data.inspection120h.Inspection120hItem
import com.cabgon.blackhawk.data.inspection40h.Inspection40hDao
import com.cabgon.blackhawk.data.inspection40h.Inspection40hHeader
import com.cabgon.blackhawk.data.inspection40h.Inspection40hItem
import com.cabgon.blackhawk.data.inspection480h.Inspection480hDao
import com.cabgon.blackhawk.data.inspection480h.Inspection480hHeader
import com.cabgon.blackhawk.data.inspection480h.Inspection480hItem
import com.cabgon.blackhawk.data.local.enruta.EnRutaDao
import com.cabgon.blackhawk.data.local.enruta.EnRutaRecargaEntity
import com.cabgon.blackhawk.data.local.enruta.EnRutaStatusEntity
import com.cabgon.blackhawk.data.preflight.PreflightDao
import com.cabgon.blackhawk.data.preflight.PreflightInspection
import com.cabgon.blackhawk.data.preflight.PreflightItem

/**
 * Base de datos central de la app.
 *
 * Nota: La app actual sigue usando PreflightDb en otras pantallas; AppDatabase
 * queda lista para ir migrando por módulos cuando tú lo decidas.
 */
@Database(
    entities = [
        // PREVUELO
        PreflightInspection::class,
        PreflightItem::class,

        // 40 HORAS
        Inspection40hHeader::class,
        Inspection40hItem::class,

        // 120 HORAS
        Inspection120hHeader::class,
        Inspection120hItem::class,

        // 480 HORAS
        Inspection480hHeader::class,
        Inspection480hItem::class,

        // EN RUTA
        EnRutaStatusEntity::class,
        EnRutaRecargaEntity::class,

    ],
    version = 10,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    // PREVUELO
    abstract fun preflightDao(): PreflightDao

    // 40 HORAS
    abstract fun inspection40hDao(): Inspection40hDao

    // 120 HORAS
    abstract fun inspection120hDao(): Inspection120hDao

    // 480 HORAS
    abstract fun inspection480hDao(): Inspection480hDao

    // EN RUTA
    abstract fun enRutaDao(): EnRutaDao

}
