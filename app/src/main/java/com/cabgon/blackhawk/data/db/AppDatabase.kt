package com.cabgon.blackhawk.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.cabgon.blackhawk.data.chat.ChatDao
import com.cabgon.blackhawk.data.chat.ChatMessageEntity
import com.cabgon.blackhawk.data.chat.ChatSessionEntity
import com.cabgon.blackhawk.data.chat.ChatSourceEntity
import com.cabgon.blackhawk.data.inspection40h.Inspection40hDao
import com.cabgon.blackhawk.data.inspection40h.Inspection40hHeader
import com.cabgon.blackhawk.data.inspection40h.Inspection40hItem
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

        // EN RUTA
        EnRutaStatusEntity::class,
        EnRutaRecargaEntity::class,

        // CHAT IA (NUEVO)
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        ChatSourceEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    // PREVUELO
    abstract fun preflightDao(): PreflightDao

    // 40 HORAS
    abstract fun inspection40hDao(): Inspection40hDao

    // EN RUTA
    abstract fun enRutaDao(): EnRutaDao

    // CHAT IA (NUEVO)
    abstract fun chatDao(): ChatDao
}