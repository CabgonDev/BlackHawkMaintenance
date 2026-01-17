package com.cabgon.blackhawk.data.db

import android.content.Context
import androidx.room.Room

/**
 * Proveedor central de la base de datos AppDatabase.
 *
 * De momento NO se usa en el código existente.
 * Más adelante migraremos los repositorios (prevuelo, 40h, 120h, etc.) a este provider.
 */
object AppDbProvider {

    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase {
        // Doble check para inicializar solo una vez
        return instance ?: synchronized(this) {
            instance ?: buildDatabase(context.applicationContext).also { instance = it }
        }
    }

    private fun buildDatabase(appContext: Context): AppDatabase {
        return Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "blackhawk.db"          // nombre único para la BD central
        )
            // Mientras estamos en desarrollo, si cambiamos el schema
            // y no tenemos migraciones definidas, que la borre y la recree.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
}
