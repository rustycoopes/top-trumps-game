package com.toptrumps.feature.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** `exportSchema = true` with the JSONs committed under `schemas/` — skipping it is the standard Room regret (the ADR). */
@Database(entities = [MatchEntity::class, MatchCardWinEntity::class], version = 1, exportSchema = true)
public abstract class HistoryDatabase : RoomDatabase() {
    internal abstract fun historyDao(): HistoryDao

    public companion object {
        public fun create(context: Context): HistoryDatabase =
            Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java, "match-history.db").build()
    }
}
