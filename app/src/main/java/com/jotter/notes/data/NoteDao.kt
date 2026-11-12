package com.jotter.notes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Transaction
    @Query("SELECT * FROM notes")
    fun observeAll(): Flow<List<NoteWithDetails>>

    @Transaction
    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<NoteWithDetails>

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): NoteWithDetails?

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNote(id: Long): Note?

    @Insert
    suspend fun insert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Query("DELETE FROM notes WHERE id IN (:ids)")
    suspend fun delete(ids: Collection<Long>)

    @Query("UPDATE notes SET pinned = :pinned WHERE id IN (:ids)")
    suspend fun setPinned(ids: Collection<Long>, pinned: Boolean)

    @Query("UPDATE notes SET archived = :archived WHERE id IN (:ids)")
    suspend fun setArchived(ids: Collection<Long>, archived: Boolean)

    @Query("UPDATE notes SET trashedAt = :trashedAt WHERE id IN (:ids)")
    suspend fun setTrashedAt(ids: Collection<Long>, trashedAt: Long?)

    @Query("UPDATE notes SET color = :color WHERE id IN (:ids)")
    suspend fun setColor(ids: Collection<Long>, color: Int)

    @Query("UPDATE notes SET reminderAt = :at, reminderRepeat = :repeat WHERE id = :id")
    suspend fun setReminder(id: Long, at: Long?, repeat: String)

    @Query("SELECT * FROM notes WHERE trashedAt IS NOT NULL AND trashedAt < :before")
    suspend fun trashedBefore(before: Long): List<Note>

    @Query("SELECT * FROM notes WHERE trashedAt IS NOT NULL")
    suspend fun trashed(): List<Note>

    @Query("SELECT * FROM notes WHERE reminderAt IS NOT NULL AND trashedAt IS NULL")
    suspend fun withReminders(): List<Note>

    // Checklist items
    @Query("DELETE FROM checklist_items WHERE noteId = :noteId")
    suspend fun deleteItems(noteId: Long)

    @Insert
    suspend fun insertItems(items: List<ChecklistItem>)

    // Labels
    @Query("SELECT * FROM labels ORDER BY name COLLATE NOCASE")
    fun observeLabels(): Flow<List<Label>>

    @Query("SELECT * FROM labels WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun labelByName(name: String): Label?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLabel(label: Label): Long

    @Query("UPDATE OR IGNORE labels SET name = :name WHERE id = :id")
    suspend fun renameLabel(id: Long, name: String)

    @Query("DELETE FROM labels WHERE id = :id")
    suspend fun deleteLabel(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNoteLabels(refs: List<NoteLabel>)

    @Query("DELETE FROM note_labels WHERE labelId = :labelId AND noteId IN (:noteIds)")
    suspend fun deleteNoteLabels(noteIds: Collection<Long>, labelId: Long)

    // Images
    @Insert
    suspend fun insertImage(image: NoteImage): Long

    @Query("DELETE FROM note_images WHERE id = :id")
    suspend fun deleteImage(id: Long)

    @Query("SELECT * FROM note_images WHERE noteId IN (:noteIds)")
    suspend fun imagesFor(noteIds: Collection<Long>): List<NoteImage>
}
