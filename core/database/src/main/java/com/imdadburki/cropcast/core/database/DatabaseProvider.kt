package com.imdadburki.cropcast.core.database

import android.content.Context
import androidx.room.Room

/** Single place that builds the Room database (spec §9). */
object DatabaseProvider {
    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "cropcast.db",
            ).build().also { instance = it }
        }
}
