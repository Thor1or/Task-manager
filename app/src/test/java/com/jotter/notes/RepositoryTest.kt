package com.jotter.notes

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jotter.notes.data.ChecklistItem
import com.jotter.notes.data.Note
import com.jotter.notes.data.NotesRepository
import com.jotter.notes.data.Repeat
import com.jotter.notes.reminders.ReminderReceiver
import com.jotter.notes.reminders.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var app: JotterApp
    private lateinit var repo: NotesRepository
    private val day = 86_400_000L

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = app.repository
    }

    private fun items(vararg texts: String) = texts.map { ChecklistItem(noteId = 0, text = it) }

    @Test
    fun saveInsertsThenUpdatesAndReplacesItems() = runBlocking {
        val id = repo.save(Note(title = "Shop", isChecklist = true), items("Milk", "Eggs"))
        var n = repo.get(id)!!
        assertEquals(listOf("Milk", "Eggs"), n.sortedItems.map { it.text })
        assertTrue(n.note.createdAt > 0)

        repo.save(n.note.copy(title = "Shop!"), items("Bread"))
        n = repo.get(id)!!
        assertEquals("Shop!", n.note.title)
        assertEquals(listOf("Bread"), n.sortedItems.map { it.text })
        assertEquals(1, repo.getAll().size)
    }

    @Test
    fun pinningUnarchivesAndArchivingUnpins() = runBlocking {
        val id = repo.save(Note(title = "a"), emptyList())
        repo.setArchived(listOf(id), true)
        repo.setPinned(listOf(id), true)
        assertFalse(repo.get(id)!!.note.archived)
        repo.setArchived(listOf(id), true)
        assertFalse(repo.get(id)!!.note.pinned)
    }

    @Test
    fun trashRestoreAndPurge() = runBlocking {
        var now = 1_000_000L
        val r = NotesRepository(app, app.database) { now }
        val keep = r.save(Note(title = "keep"), emptyList())
        val old = r.save(Note(title = "old"), emptyList())
        val recent = r.save(Note(title = "recent"), emptyList())
        r.trash(listOf(old))
        now += 6 * day
        r.trash(listOf(recent, keep))
        r.restore(listOf(keep))
        now += 2 * day // "old" is now 8 days in the trash, "recent" 2 days
        r.purgeExpiredTrash()
        assertNull(r.get(old))
        assertNotNull(r.get(recent)!!.note.trashedAt)
        assertNull(r.get(keep)!!.note.trashedAt)
        r.emptyTrash()
        assertEquals(listOf("keep"), r.getAll().map { it.note.title })
    }

    @Test
    fun labelsAreUniqueCaseInsensitiveAndCascade() = runBlocking {
        val id = repo.save(Note(title = "a"), emptyList())
        val work = repo.createLabel(" Work ")!!
        assertEquals(work, repo.createLabel("work"))
        assertNull(repo.createLabel("  "))
        val home = repo.createLabel("Home")!!
        repo.setLabel(listOf(id), work, true)
        repo.setLabel(listOf(id), home, true)
        assertEquals(listOf("Home", "Work"), repo.get(id)!!.sortedLabels.map { it.name })

        assertFalse("renaming onto an existing name is refused", repo.renameLabel(home, "WORK"))
        assertTrue(repo.renameLabel(home, "House"))
        repo.deleteLabel(work)
        assertEquals(listOf("House"), repo.get(id)!!.sortedLabels.map { it.name })
        repo.setLabel(listOf(id), home, false)
        assertTrue(repo.get(id)!!.labels.isEmpty())
    }

    @Test
    fun duplicateCopiesContentButNotState() = runBlocking {
        val id = repo.save(Note(title = "List", isChecklist = true, color = 3, pinned = true), items("x", "y"))
        repo.setLabel(listOf(id), repo.createLabel("L")!!, true)
        repo.addImageBytes(id, byteArrayOf(1, 2, 3))
        repo.setReminder(id, System.currentTimeMillis() + day, Repeat.DAILY)

        val copy = repo.get(repo.duplicate(id)!!)!!
        assertEquals("List", copy.note.title)
        assertEquals(3, copy.note.color)
        assertEquals(listOf("x", "y"), copy.sortedItems.map { it.text })
        assertEquals(listOf("L"), copy.labels.map { it.name })
        assertFalse(copy.note.pinned)
        assertNull(copy.note.reminderAt)
        val original = repo.get(id)!!
        assertEquals(1, copy.images.size)
        assertTrue(repo.imageFile(copy.images[0]).exists())
        assertFalse("copy owns its own file", copy.images[0].fileName == original.images[0].fileName)

        // Deleting the copy removes its file and leaves the original's alone.
        repo.deleteForever(listOf(copy.note.id))
        assertFalse(repo.imageFile(copy.images[0]).exists())
        assertTrue(repo.imageFile(original.images[0]).exists())
    }

    @Test
    fun reminderSchedulesAndCancelsAlarm() = runBlocking {
        val alarms = shadowOf(app.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        val id = repo.save(Note(title = "Call"), emptyList())
        val at = System.currentTimeMillis() + day
        repo.setReminder(id, at, Repeat.NONE)
        assertEquals(at, alarms.scheduledAlarms.single().triggerAtMs)
        repo.trash(listOf(id))
        assertTrue(alarms.scheduledAlarms.isEmpty())
        repo.restore(listOf(id))
        assertEquals(1, alarms.scheduledAlarms.size)
        repo.setReminder(id, null)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertNull(repo.get(id)!!.note.reminderAt)
    }

    @Test
    fun firedRepeatingReminderNotifiesAndMovesToNextDay() = runBlocking {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val id = repo.save(Note(title = "Water plants", content = "Balcony too"), emptyList())
        val at = System.currentTimeMillis() - 1_000
        repo.setReminder(id, at, Repeat.DAILY)

        ReminderReceiver().onReceive(app, Intent().putExtra(ReminderScheduler.EXTRA_NOTE_ID, id))
        // The receiver works on the main looper + Room's executors; wait for it to settle.
        val nm = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        val deadline = System.currentTimeMillis() + 5_000
        while (repo.get(id)!!.note.reminderAt == at && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals(1, nm.allNotifications.size)
        assertEquals(at + day, repo.get(id)!!.note.reminderAt)
    }

    @Test
    fun backupRoundTrip() = runBlocking {
        val a = repo.save(Note(title = "Text", content = "hello", color = 2, pinned = true), emptyList())
        val b = repo.save(Note(title = "List", isChecklist = true, archived = true), listOf(
            ChecklistItem(noteId = 0, text = "one", checked = true), ChecklistItem(noteId = 0, text = "two", indent = 1),
        ))
        repo.setLabel(listOf(a, b), repo.createLabel("Shared")!!, true)
        repo.addImageBytes(a, byteArrayOf(9, 8, 7))
        val json = app.backup.export()

        repo.deleteForever(listOf(a, b))
        assertEquals(2, app.backup.import(json))
        val all = repo.getAll().associateBy { it.note.title }
        val text = all.getValue("Text")
        assertEquals("hello", text.note.content)
        assertEquals(2, text.note.color)
        assertTrue(text.note.pinned)
        assertEquals(listOf<Byte>(9, 8, 7), repo.imageFile(text.images.single()).readBytes().toList())
        val list = all.getValue("List")
        assertTrue(list.note.archived)
        assertEquals(listOf("one" to true, "two" to false), list.sortedItems.map { it.text to it.checked })
        assertEquals(listOf(0, 1), list.sortedItems.map { it.indent })
        assertEquals(listOf("Shared"), list.labels.map { it.name })

        assertTrue(runCatching { app.backup.import("{\"notes\":[]}") }.isFailure)
    }
}
