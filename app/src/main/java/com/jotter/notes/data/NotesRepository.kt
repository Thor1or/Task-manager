package com.jotter.notes.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.jotter.notes.reminders.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Single entry point for everything stored on the device: notes, lists, labels, images, reminders. */
class NotesRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.noteDao()

    val notes: Flow<List<NoteWithDetails>> = dao.observeAll()
    val labels: Flow<List<Label>> = dao.observeLabels()

    val imagesDir: File get() = File(context.filesDir, "images").apply { mkdirs() }
    fun imageFile(image: NoteImage): File = File(imagesDir, image.fileName)

    suspend fun get(id: Long): NoteWithDetails? = dao.get(id)
    suspend fun getAll(): List<NoteWithDetails> = dao.getAll()

    /** Inserts or updates [note] and replaces its checklist items. Returns the note id. */
    suspend fun save(note: Note, items: List<ChecklistItem>): Long = db.withTransaction {
        val id = if (note.id == 0L) {
            val t = now()
            dao.insert(note.copy(
                createdAt = note.createdAt.takeIf { it > 0 } ?: t,
                updatedAt = note.updatedAt.takeIf { it > 0 } ?: t,
            ))
        } else {
            dao.update(note)
            note.id
        }
        dao.deleteItems(id)
        if (note.isChecklist && items.isNotEmpty()) {
            dao.insertItems(items.mapIndexed { i, it -> it.copy(id = 0, noteId = id, position = i) })
        }
        id
    }

    suspend fun setPinned(ids: Collection<Long>, pinned: Boolean) = db.withTransaction {
        dao.setPinned(ids, pinned)
        if (pinned) dao.setArchived(ids, false) // a pinned note always lives on the main screen
    }

    suspend fun setArchived(ids: Collection<Long>, archived: Boolean) = db.withTransaction {
        dao.setArchived(ids, archived)
        if (archived) dao.setPinned(ids, false)
    }

    suspend fun setColor(ids: Collection<Long>, color: Int) = dao.setColor(ids, color)

    suspend fun trash(ids: Collection<Long>) {
        db.withTransaction {
            dao.setTrashedAt(ids, now())
            dao.setPinned(ids, false)
        }
        ids.forEach { ReminderScheduler.cancel(context, it) }
    }

    suspend fun restore(ids: Collection<Long>) {
        dao.setTrashedAt(ids, null)
        ids.forEach { id -> dao.getNote(id)?.let { n -> n.reminderAt?.let { ReminderScheduler.schedule(context, id, it) } } }
    }

    suspend fun deleteForever(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val files = dao.imagesFor(ids).map(::imageFile)
        dao.delete(ids)
        ids.forEach { ReminderScheduler.cancel(context, it) }
        withContext(Dispatchers.IO) { files.forEach { it.delete() } }
    }

    suspend fun emptyTrash() = deleteForever(dao.trashed().map { it.id })

    /** Notes stay in the trash for [TRASH_RETENTION_DAYS] days, then are removed for good. */
    suspend fun purgeExpiredTrash() =
        deleteForever(dao.trashedBefore(now() - TRASH_RETENTION_DAYS * 86_400_000L).map { it.id })

    suspend fun duplicate(id: Long): Long? {
        val src = dao.get(id) ?: return null
        val t = now()
        val newId = save(
            src.note.copy(
                id = 0, pinned = false, archived = false, trashedAt = null,
                reminderAt = null, reminderRepeat = Repeat.NONE.name, createdAt = t, updatedAt = t,
            ),
            src.sortedItems,
        )
        dao.insertNoteLabels(src.labels.map { NoteLabel(newId, it.id) })
        withContext(Dispatchers.IO) {
            src.sortedImages.forEach { img ->
                val from = imageFile(img)
                if (from.exists()) {
                    val name = newImageName()
                    from.copyTo(File(imagesDir, name))
                    dao.insertImage(NoteImage(noteId = newId, fileName = name, position = img.position))
                }
            }
        }
        return newId
    }

    // Reminders

    suspend fun setReminder(id: Long, at: Long?, repeat: Repeat = Repeat.NONE) {
        dao.setReminder(id, at, if (at == null) Repeat.NONE.name else repeat.name)
        if (at == null) ReminderScheduler.cancel(context, id) else ReminderScheduler.schedule(context, id, at)
    }

    /** Re-registers every pending alarm, e.g. after a reboot (alarms do not survive one). */
    suspend fun rescheduleAllReminders() {
        val t = now()
        dao.withReminders().forEach { n ->
            var at = n.reminderAt ?: return@forEach
            if (at <= t) {
                at = ReminderScheduler.nextOccurrence(at, Repeat.of(n.reminderRepeat), t) ?: return@forEach
                dao.setReminder(n.id, at, n.reminderRepeat)
            }
            ReminderScheduler.schedule(context, n.id, at)
        }
    }

    // Labels

    /** Returns the id of the label called [name], creating it when needed; null for a blank name. */
    suspend fun createLabel(name: String): Long? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        dao.labelByName(clean)?.let { return it.id }
        return dao.insertLabel(Label(name = clean))
    }

    /** Returns false when the name is blank or already used by another label. */
    suspend fun renameLabel(id: Long, name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val existing = dao.labelByName(clean)
        if (existing != null && existing.id != id) return false
        dao.renameLabel(id, clean)
        return true
    }

    suspend fun deleteLabel(id: Long) = dao.deleteLabel(id)

    suspend fun setLabel(noteIds: Collection<Long>, labelId: Long, on: Boolean) {
        if (on) dao.insertNoteLabels(noteIds.map { NoteLabel(it, labelId) })
        else dao.deleteNoteLabels(noteIds, labelId)
    }

    // Images

    /** Copies the picked image into app-private storage so the note keeps working offline. */
    suspend fun addImage(noteId: Long, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val name = newImageName()
        val target = File(imagesDir, name)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext false
            input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
            addImageFile(noteId, name)
            true
        } catch (e: Exception) {
            target.delete()
            false
        }
    }

    suspend fun addImageBytes(noteId: Long, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val name = newImageName()
        File(imagesDir, name).writeBytes(bytes)
        addImageFile(noteId, name)
    }

    private suspend fun addImageFile(noteId: Long, name: String) {
        val position = (dao.get(noteId)?.images?.maxOfOrNull { it.position } ?: -1) + 1
        dao.insertImage(NoteImage(noteId = noteId, fileName = name, position = position))
    }

    suspend fun removeImage(image: NoteImage) {
        dao.deleteImage(image.id)
        withContext(Dispatchers.IO) { imageFile(image).delete() }
    }

    private fun newImageName() = "${UUID.randomUUID()}.img"

    companion object {
        const val TRASH_RETENTION_DAYS = 7
    }
}
