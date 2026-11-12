package com.jotter.notes.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jotter.notes.data.NoteWithDetails
import com.jotter.notes.data.Repeat
import com.jotter.notes.ui.components.NoteChips
import com.jotter.notes.ui.theme.NoteColors
import java.io.File

private const val MAX_PREVIEW_ITEMS = 8

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    details: NoteWithDetails,
    selected: Boolean,
    imageFile: (com.jotter.notes.data.NoteImage) -> File,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val note = details.note
    val shape = RoundedCornerShape(12.dp)
    val fg = NoteColors.onBackground(note.color)
    val border = when {
        selected -> BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
        note.color == 0 -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        else -> null
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { this.selected = selected }
            .testTag("noteCard"),
        shape = shape,
        color = NoteColors.background(note.color),
        contentColor = fg,
        border = border,
    ) {
        Column {
            details.sortedImages.firstOrNull()?.let { image ->
                AsyncImage(
                    model = imageFile(image),
                    contentDescription = "Note image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                )
            }
            val items = details.sortedItems
            val hasText = note.title.isNotBlank() || (if (note.isChecklist) items.isNotEmpty() else note.content.isNotBlank())
            val hasChips = note.reminderAt != null || details.labels.isNotEmpty()
            if (hasText || hasChips || details.images.isEmpty()) {
                Column(Modifier.padding(14.dp)) {
                    if (note.title.isNotBlank()) {
                        Text(
                            note.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (note.isChecklist) {
                        if (note.title.isNotBlank() && items.isNotEmpty()) Spacer(Modifier.height(6.dp))
                        val open = items.filter { !it.checked }
                        val done = items.size - open.size
                        // When everything is ticked, show the ticked items instead of an empty card.
                        val preview = if (open.isEmpty()) items else open
                        preview.take(MAX_PREVIEW_ITEMS).forEach { item ->
                            Row(Modifier.padding(start = 20.dp * item.indent, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.Top) {
                                Icon(
                                    if (item.checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                                    contentDescription = if (item.checked) "Checked" else "Not checked",
                                    modifier = Modifier.padding(top = 2.dp).size(16.dp),
                                    tint = fg.copy(alpha = 0.7f),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    item.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (item.checked) fg.copy(alpha = 0.6f) else fg,
                                    textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                                )
                            }
                        }
                        val hidden = preview.size - minOf(preview.size, MAX_PREVIEW_ITEMS)
                        val footer = when {
                            hidden > 0 && open.isNotEmpty() && done > 0 -> "+ $hidden more, $done checked"
                            hidden > 0 -> "+ $hidden more"
                            open.isNotEmpty() && done > 0 -> "+ $done checked " + if (done == 1) "item" else "items"
                            else -> null
                        }
                        if (footer != null) {
                            Text(
                                footer,
                                Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = fg.copy(alpha = 0.7f),
                            )
                        }
                    } else if (note.content.isNotBlank()) {
                        if (note.title.isNotBlank()) Spacer(Modifier.height(6.dp))
                        Text(
                            note.content,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!hasText && !hasChips) {
                        Text("Empty note", style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.6f))
                    }
                    NoteChips(
                        reminderAt = note.reminderAt,
                        repeat = Repeat.of(note.reminderRepeat),
                        labels = details.sortedLabels,
                        contentColor = fg,
                        maxLabels = 3,
                        modifier = Modifier.padding(top = if (hasText) 10.dp else 0.dp),
                    )
                }
            }
        }
    }
}
