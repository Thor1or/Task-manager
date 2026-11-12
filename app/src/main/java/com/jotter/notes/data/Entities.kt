package com.jotter.notes.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val content: String = "",
    val isChecklist: Boolean = false,
    /** Index into [com.jotter.notes.ui.theme.NoteColors]; 0 is the default (no colour). */
    val color: Int = 0,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** Non-null while the note sits in the trash. */
    val trashedAt: Long? = null,
    val reminderAt: Long? = null,
    val reminderRepeat: String = Repeat.NONE.name,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

enum class Repeat(val label: String) {
    NONE("Does not repeat"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
    YEARLY("Yearly");

    companion object {
        fun of(name: String?): Repeat = entries.firstOrNull { it.name == name } ?: NONE
    }
}

@Entity(
    tableName = "checklist_items",
    foreignKeys = [ForeignKey(
        entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("noteId")],
)
data class ChecklistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val text: String,
    val checked: Boolean = false,
    val position: Int = 0,
    /** 0 for a top-level item, 1 for a sub-item nested under the item above it. */
    @ColumnInfo(defaultValue = "0") val indent: Int = 0,
)

@Entity(tableName = "labels", indices = [Index(value = ["name"], unique = true)])
data class Label(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "note_labels",
    primaryKeys = ["noteId", "labelId"],
    foreignKeys = [
        ForeignKey(
            entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Label::class, parentColumns = ["id"], childColumns = ["labelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("labelId")],
)
data class NoteLabel(val noteId: Long, val labelId: Long)

@Entity(
    tableName = "note_images",
    foreignKeys = [ForeignKey(
        entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("noteId")],
)
data class NoteImage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    /** File name inside the app-private images directory. */
    val fileName: String,
    val position: Int = 0,
)

data class NoteWithDetails(
    @Embedded val note: Note,
    @Relation(parentColumn = "id", entityColumn = "noteId")
    val items: List<ChecklistItem> = emptyList(),
    @Relation(
        parentColumn = "id", entityColumn = "id",
        associateBy = Junction(NoteLabel::class, parentColumn = "noteId", entityColumn = "labelId"),
    )
    val labels: List<Label> = emptyList(),
    @Relation(parentColumn = "id", entityColumn = "noteId")
    val images: List<NoteImage> = emptyList(),
) {
    val sortedItems: List<ChecklistItem> get() = items.sortedBy { it.position }
    val sortedImages: List<NoteImage> get() = images.sortedBy { it.position }
    val sortedLabels: List<Label> get() = labels.sortedBy { it.name.lowercase() }

    /** Plain-text rendering used for sharing and for converting a list back to text. */
    fun bodyAsText(): String =
        if (note.isChecklist) sortedItems.joinToString("\n") { "  ".repeat(it.indent) + (if (it.checked) "[x] " else "[ ] ") + it.text }
        else note.content

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return note.title.contains(q, true) || note.content.contains(q, true) ||
            items.any { it.text.contains(q, true) } || labels.any { it.name.contains(q, true) }
    }
}
