package com.jotter.notes

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jotter.notes.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val app: JotterApp get() = ApplicationProvider.getApplicationContext()

    /** Builds a database exactly as [version] of the app created it, from the exported Room schema. */
    private fun createDatabase(name: String, version: Int): SQLiteDatabase {
        val schema = File(System.getProperty("room.schemas"), "${AppDatabase::class.java.name}/$version.json")
        val database = JSONObject(schema.readText()).getJSONObject("database")
        val file = app.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        return SQLiteDatabase.openOrCreateDatabase(file, null).apply {
            val entities = database.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) {
                    execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = database.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) execSQL(setup.getString(i))
            this.version = version
        }
    }

    /** Notes written by version 1 of the app must survive the upgrade that adds indentation. */
    @Test
    fun v1ChecklistsSurviveUpgradeAsTopLevelItems() {
        val name = "migration-test.db"
        createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO notes (id, title, content, isChecklist, color, pinned, archived, reminderRepeat, createdAt, updatedAt) " +
                    "VALUES (1, 'Groceries', '', 1, 4, 1, 0, 'NONE', 10, 20)"
            )
            db.execSQL("INSERT INTO checklist_items (noteId, text, checked, position) VALUES (1, 'Milk', 1, 0)")
            db.execSQL("INSERT INTO checklist_items (noteId, text, checked, position) VALUES (1, 'Eggs', 0, 1)")
        }

        // Opening through Room runs the migration and then validates every table against the
        // current entities; a mismatch throws here.
        val db = Room.databaseBuilder(app, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2).build()
        val note = runBlocking { db.noteDao().get(1)!! }
        db.close()
        assertEquals("Groceries", note.note.title)
        assertEquals(
            listOf(Triple("Milk", true, 0), Triple("Eggs", false, 0)),
            note.sortedItems.map { Triple(it.text, it.checked, it.indent) },
        )
    }
}
