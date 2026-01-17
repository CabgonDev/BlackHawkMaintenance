package com.cabgon.blackhawk.data

import android.content.Context
import androidx.room.Room
import com.cabgon.blackhawk.data.preflight.PreflightDb

object DbProvider {

    @Volatile
    private var preflightDb: PreflightDb? = null

    fun preflight(context: Context): PreflightDb {
        return preflightDb ?: synchronized(this) {
            preflightDb ?: Room.databaseBuilder(
                context.applicationContext,
                PreflightDb::class.java,
                "preflight.db"
            )
                // recrea automáticamente la DB si cambió el esquema
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { preflightDb = it } // <-- el .also va aquí correctamente
        }
    }
}
