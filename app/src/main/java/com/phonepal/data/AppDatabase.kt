package com.phonepal.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Item::class, RagDoc::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun ragDao(): RagDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v2: rag_docs, which items are in the Gemini File Search store. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `rag_docs` (`itemId` INTEGER NOT NULL, `docName` TEXT NOT NULL, " +
                        "`indexedVersion` INTEGER NOT NULL, PRIMARY KEY(`itemId`))"
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "phonepal.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
