package com.folio.notes.data

import androidx.room.withTransaction
import com.folio.notes.markdown.Markdown
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class NotesRepository(
    private val db: AppDatabase,
    val attachments: Attachments,
) {
    private val dao = db.dao()

    val folders: Flow<List<Folder>> = dao.observeFolders()
    val summaries: Flow<List<NoteSummary>> = dao.observeSummaries()
    val tags: Flow<List<NoteTag>> = dao.observeTags()

    fun search(query: String): Flow<List<NoteSummary>> {
        val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return dao.search("%$escaped%")
    }

    // ---- Folders ----------------------------------------------------------------------------------------

    suspend fun folders() = dao.folders()

    suspend fun createFolder(name: String, parentId: Long? = null): Long =
        dao.insert(Folder(name = name.trim().ifEmpty { "New Folder" }, parentId = parentId))

    suspend fun renameFolder(id: Long, name: String) {
        if (id == Folder.DEFAULT_ID || name.isBlank()) return
        dao.folders().firstOrNull { it.id == id }?.let { dao.update(it.copy(name = name.trim())) }
    }

    /** Moves a folder under [parentId] (null = top level). Refuses to put a folder inside itself. */
    suspend fun moveFolder(id: Long, parentId: Long?): Boolean {
        val all = dao.folders()
        val folder = all.firstOrNull { it.id == id } ?: return false
        if (id == Folder.DEFAULT_ID || parentId == Folder.DEFAULT_ID || (parentId != null && parentId in subtree(all, id))) return false
        dao.update(folder.copy(parentId = parentId))
        return true
    }

    /** Deletes a folder and everything inside it; the notes go to Recently Deleted. */
    suspend fun deleteFolder(id: Long) {
        if (id == Folder.DEFAULT_ID) return
        val ids = subtree(dao.folders(), id).toList()
        db.withTransaction {
            dao.trashFolders(ids, System.currentTimeMillis())
            dao.orphanToDefault(ids)
            dao.deleteFolders(ids)
        }
    }

    // ---- Notes ------------------------------------------------------------------------------------------

    suspend fun note(id: Long) = dao.note(id)
    fun observeNote(id: Long) = dao.observeNote(id)
    suspend fun allNotes() = dao.allNotes()

    /** The note's Markdown; null when it is locked and [key] cannot open it. */
    fun content(note: Note, key: SecretKey?): String? = when {
        !note.locked -> note.content
        key == null -> null
        else -> try { Crypto.decryptText(key, note.content) } catch (e: Exception) { null }
    }

    /**
     * Creates the note ([id] 0) or updates its content. A locked note needs [key]; without it nothing is written.
     * Returns the note's id.
     */
    suspend fun save(
        id: Long,
        folderId: Long,
        content: String,
        key: SecretKey?,
        createdAt: Long? = null,
        modifiedAt: Long? = null,
        pinned: Boolean = false,
    ): Long {
        val existing = if (id != 0L) dao.note(id) else null
        if (existing != null && existing.locked && key == null) return id
        if (existing != null && content(existing, key) == content) return id
        val now = System.currentTimeMillis()
        val (title, preview) = Markdown.titleAndPreview(content)
        val locked = existing?.locked == true
        val derived = (existing ?: Note(folderId = folderId, createdAt = createdAt ?: now, pinned = pinned)).copy(
            title = title,
            preview = if (locked) "" else preview,
            content = if (locked) Crypto.encryptText(key!!, content) else content,
            attachments = Markdown.attachments(content).joinToString(","),
            modifiedAt = modifiedAt ?: now,
        )
        return db.withTransaction {
            val noteId = if (existing == null) dao.insert(derived) else { dao.update(derived); id }
            dao.replaceTags(noteId, if (locked) emptySet() else Markdown.tags(content))
            noteId
        }
    }

    suspend fun setPinned(ids: List<Long>, pinned: Boolean) = dao.setPinned(ids, pinned)
    suspend fun move(ids: List<Long>, folderId: Long) = dao.move(ids, folderId)
    suspend fun trash(ids: List<Long>) = dao.trash(ids, System.currentTimeMillis())
    suspend fun restore(ids: List<Long>) = dao.restore(ids)

    suspend fun deleteForever(ids: List<Long>) {
        if (ids.isEmpty()) return
        val files = dao.notes(ids).flatMap { it.attachmentList }
        dao.delete(ids)
        withContext(Dispatchers.IO) { attachments.delete(files) }
    }

    suspend fun emptyTrash() = deleteForever(dao.trashedIds())

    /** Housekeeping at launch: drops notes deleted more than 30 days ago and pictures nothing refers to. */
    suspend fun purge(now: Long = System.currentTimeMillis()) {
        deleteForever(dao.expired(now - TRASH_RETENTION_MS))
        val referenced = dao.allAttachments().flatMap { it.split(',') }.toSet()
        withContext(Dispatchers.IO) { attachments.sweep(referenced) }
    }

    // ---- Locking ----------------------------------------------------------------------------------------

    /** Encrypts a note and its pictures with [key]. */
    suspend fun lock(id: Long, key: SecretKey) {
        val note = dao.note(id) ?: return
        if (note.locked) return
        withContext(Dispatchers.IO) { note.attachmentList.forEach { attachments.recrypt(it, null, key) } }
        db.withTransaction {
            dao.update(note.copy(locked = true, preview = "", content = Crypto.encryptText(key, note.content)))
            dao.replaceTags(id, emptySet())
        }
    }

    /** Removes the lock from a note, storing it in the clear again. */
    suspend fun unlock(id: Long, key: SecretKey): Boolean {
        val note = dao.note(id) ?: return false
        if (!note.locked) return true
        val plain = content(note, key) ?: return false
        withContext(Dispatchers.IO) { note.attachmentList.forEach { attachments.recrypt(it, key, null) } }
        db.withTransaction {
            dao.update(note.copy(locked = false, preview = Markdown.titleAndPreview(plain).second, content = plain))
            dao.replaceTags(id, Markdown.tags(plain))
        }
        return true
    }

    suspend fun hasLockedNotes() = dao.lockedNotes().isNotEmpty()

    /** Re-encrypts every locked note for a new password ([to]), or unlocks them all when [to] is null. */
    suspend fun rekey(from: SecretKey, to: SecretKey?) {
        for (note in dao.lockedNotes()) {
            if (to == null) { unlock(note.id, from); continue }
            val plain = content(note, from) ?: continue
            withContext(Dispatchers.IO) { note.attachmentList.forEach { attachments.recrypt(it, from, to) } }
            dao.update(note.copy(content = Crypto.encryptText(to, plain)))
        }
    }

    companion object {
        const val TRASH_RETENTION_DAYS = 30
        const val TRASH_RETENTION_MS = TRASH_RETENTION_DAYS * 24L * 60 * 60 * 1000

        /** [id] and every folder nested beneath it. */
        fun subtree(all: List<Folder>, id: Long): Set<Long> {
            val out = LinkedHashSet<Long>()
            val queue = ArrayDeque(listOf(id))
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (out.add(next)) all.filter { it.parentId == next }.forEach { queue += it.id }
            }
            return out
        }
    }
}
