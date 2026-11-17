package com.folio.notes.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /** The built-in "Notes" folder. It always exists and cannot be renamed, moved or deleted. */
        const val DEFAULT_ID = 1L
        const val DEFAULT_NAME = "Notes"
    }
}

/**
 * [content] is the note's Markdown – or, for a locked note, its ciphertext. [title], [preview] and
 * [attachments] are derived from the content whenever it is saved, so lists never have to parse (or decrypt) it.
 */
@Entity(tableName = "notes", indices = [Index("folderId"), Index("deletedAt")])
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long = Folder.DEFAULT_ID,
    val title: String = "",
    val preview: String = "",
    val content: String = "",
    /** Comma-separated attachment file names referenced by the content. */
    val attachments: String = "",
    val locked: Boolean = false,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = createdAt,
    val deletedAt: Long? = null,
) {
    val attachmentList get() = attachments.split(',').filter { it.isNotEmpty() }
}

/** A note without its content, for lists. */
data class NoteSummary(
    val id: Long,
    val folderId: Long,
    val title: String,
    val preview: String,
    val attachments: String,
    val locked: Boolean,
    val pinned: Boolean,
    val createdAt: Long,
    val modifiedAt: Long,
    val deletedAt: Long?,
) {
    val firstAttachment get() = attachments.substringBefore(',').takeIf { it.isNotEmpty() }
}

@Entity(
    tableName = "note_tags",
    primaryKeys = ["noteId", "tag"],
    foreignKeys = [ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tag")],
)
data class NoteTag(val noteId: Long, @ColumnInfo(collate = ColumnInfo.NOCASE) val tag: String)
