package com.folio.notes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

private const val SUMMARY = "id, folderId, title, preview, attachments, locked, pinned, createdAt, modifiedAt, deletedAt"

@Dao
interface NoteDao {
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    fun observeFolders(): Flow<List<Folder>>

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    suspend fun folders(): List<Folder>

    @Insert suspend fun insert(folder: Folder): Long
    @Update suspend fun update(folder: Folder)
    @Query("DELETE FROM folders WHERE id IN (:ids)") suspend fun deleteFolders(ids: List<Long>)

    @Query("SELECT $SUMMARY FROM notes")
    fun observeSummaries(): Flow<List<NoteSummary>>

    /** Locked notes are found by title only: their content is ciphertext. */
    @Query(
        "SELECT $SUMMARY FROM notes WHERE deletedAt IS NULL AND " +
            "(title LIKE :pattern ESCAPE '\\' OR (locked = 0 AND content LIKE :pattern ESCAPE '\\'))"
    )
    fun search(pattern: String): Flow<List<NoteSummary>>

    @Query("SELECT * FROM note_tags")
    fun observeTags(): Flow<List<NoteTag>>

    @Query("SELECT * FROM notes WHERE id = :id") suspend fun note(id: Long): Note?
    @Query("SELECT * FROM notes WHERE id = :id") fun observeNote(id: Long): Flow<Note?>
    @Query("SELECT * FROM notes") suspend fun allNotes(): List<Note>
    @Query("SELECT * FROM notes WHERE id IN (:ids)") suspend fun notes(ids: List<Long>): List<Note>
    @Query("SELECT * FROM notes WHERE locked = 1") suspend fun lockedNotes(): List<Note>
    @Query("SELECT attachments FROM notes WHERE attachments != ''") suspend fun allAttachments(): List<String>

    @Insert suspend fun insert(note: Note): Long
    @Update suspend fun update(note: Note)

    @Query("DELETE FROM note_tags WHERE noteId = :noteId") suspend fun clearTags(noteId: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTags(tags: List<NoteTag>)

    @Transaction
    suspend fun replaceTags(noteId: Long, tags: Collection<String>) {
        clearTags(noteId)
        if (tags.isNotEmpty()) insertTags(tags.map { NoteTag(noteId, it) })
    }

    @Query("UPDATE notes SET pinned = :pinned WHERE id IN (:ids)") suspend fun setPinned(ids: List<Long>, pinned: Boolean)
    @Query("UPDATE notes SET folderId = :folderId WHERE id IN (:ids)") suspend fun move(ids: List<Long>, folderId: Long)
    @Query("UPDATE notes SET deletedAt = :at, pinned = 0 WHERE id IN (:ids)") suspend fun trash(ids: List<Long>, at: Long)
    @Query("UPDATE notes SET deletedAt = NULL WHERE id IN (:ids)") suspend fun restore(ids: List<Long>)
    @Query("UPDATE notes SET deletedAt = :at, pinned = 0, folderId = ${Folder.DEFAULT_ID} WHERE folderId IN (:folderIds) AND deletedAt IS NULL")
    suspend fun trashFolders(folderIds: List<Long>, at: Long)
    @Query("UPDATE notes SET folderId = ${Folder.DEFAULT_ID} WHERE folderId IN (:folderIds)")
    suspend fun orphanToDefault(folderIds: List<Long>)
    @Query("DELETE FROM notes WHERE id IN (:ids)") suspend fun delete(ids: List<Long>)
    @Query("SELECT id FROM notes WHERE deletedAt IS NOT NULL AND deletedAt < :before") suspend fun expired(before: Long): List<Long>
    @Query("SELECT id FROM notes WHERE deletedAt IS NOT NULL") suspend fun trashedIds(): List<Long>
}
