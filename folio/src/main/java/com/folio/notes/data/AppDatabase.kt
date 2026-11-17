package com.folio.notes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Folder::class, Note::class, NoteTag::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): NoteDao

    companion object {
        private val seedDefaultFolder = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "INSERT INTO folders (id, name, parentId, createdAt) VALUES (?, ?, NULL, ?)",
                    arrayOf<Any>(Folder.DEFAULT_ID, Folder.DEFAULT_NAME, System.currentTimeMillis()),
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "folio.db").addCallback(seedDefaultFolder).build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(seedDefaultFolder).allowMainThreadQueries().build()
    }
}
