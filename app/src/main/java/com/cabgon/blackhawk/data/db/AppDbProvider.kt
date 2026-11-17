package com.cabgon.blackhawk.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Proveedor central de la base de datos AppDatabase.
 *
 * Actualmente lo usan las pantallas de En Ruta; los demás módulos se migrarán
 * a este proveedor cuando corresponda.
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
            .addMigrations(MIGRATION_9_10)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Chat IA ya no forma parte de la app. Solo se eliminan sus tablas.
            db.execSQL("DROP TABLE IF EXISTS `chat_sources`")
            db.execSQL("DROP TABLE IF EXISTS `chat_messages`")
            db.execSQL("DROP TABLE IF EXISTS `chat_sessions`")
        }
    }
}
