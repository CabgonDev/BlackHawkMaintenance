package com.cabgon.blackhawk.data.preflight

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PreflightInspection::class,
        PreflightItem::class
    ],
    version = 1,
    exportSchema = true
)
abstract class PreflightDb : RoomDatabase() {
    abstract fun dao(): PreflightDao
}
