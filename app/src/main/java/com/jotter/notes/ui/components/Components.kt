package com.jotter.notes.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FormatColorReset
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jotter.notes.data.Label
import com.jotter.notes.data.Repeat
import com.jotter.notes.ui.theme.NoteColors
import com.jotter.notes.util.Format
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Horizontal row of colour swatches, used in the editor and for bulk recolouring. */
@Composable
fun ColorPickerRow(selected: Int?, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(NoteColors.all) { index, color ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(NoteColors.background(index))
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    )
                    .clickable { onPick(index) }
                    .semantics { contentDescription = "Color ${color.name}" },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isSelected -> Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                    index == 0 -> Icon(Icons.Outlined.FormatColorReset, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun NoteChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
    struck: Boolean = false,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(8.dp),
        color = contentColor.copy(alpha = 0.08f),
        contentColor = contentColor,
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.12f)),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(Modifier.size(14.dp)) { icon() }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (struck) TextDecoration.LineThrough else null,
            )
        }
    }
}

/** Reminder + label chips shown under a note, both on cards and in the editor. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteChips(
    reminderAt: Long?,
    repeat: Repeat,
    labels: List<Label>,
    contentColor: Color,
    modifier: Modifier = Modifier,
    maxLabels: Int = Int.MAX_VALUE,
    onReminderClick: (() -> Unit)? = null,
    onLabelClick: (() -> Unit)? = null,
) {
    if (reminderAt == null && labels.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (reminderAt != null) {
            NoteChip(
                text = Format.reminder(reminderAt),
                icon = { Icon(if (repeat == Repeat.NONE) Icons.Outlined.Alarm else Icons.Outlined.Repeat, null) },
                struck = repeat == Repeat.NONE && reminderAt < System.currentTimeMillis(),
                contentColor = contentColor,
                onClick = onReminderClick,
            )
        }
        labels.take(maxLabels).forEach { NoteChip(it.name, contentColor = contentColor, onClick = onLabelClick) }
        if (labels.size > maxLabels) NoteChip("+${labels.size - maxLabels}", contentColor = contentColor, onClick = onLabelClick)
    }
}

/**
 * Full-screen label chooser for one or many notes. [states] maps a label id to whether all, some or
 * none of the notes carry it. Typing filters the list and offers to create a missing label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelPicker(
    labels: List<Label>,
    states: Map<Long, ToggleableState>,
    onToggle: (Label, Boolean) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = labels.filter { it.name.contains(query.trim(), ignoreCase = true) }
    val canCreate = query.isNotBlank() && labels.none { it.name.equals(query.trim(), ignoreCase = true) }
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close labels") }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Enter label name") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("labelQuery"),
                    )
                },
            )
            LazyColumn(Modifier.fillMaxSize().imePadding()) {
                if (canCreate) item(key = "create") {
                    Row(
                        Modifier.fillMaxWidth().clickable { onCreate(query.trim()); query = "" }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Text("Create \"${query.trim()}\"")
                    }
                }
                items(shown, key = { it.id }) { label ->
                    val state = states[label.id] ?: ToggleableState.Off
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onToggle(label, state != ToggleableState.On) }
                            .padding(start = 16.dp, end = 4.dp)
                            .testTag("labelRow"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Label, null)
                        Spacer(Modifier.width(16.dp))
                        Text(label.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TriStateCheckbox(state = state, onClick = { onToggle(label, state != ToggleableState.On) })
                    }
                }
            }
        }
    }
}

/** Date, time and repeat chooser for a note reminder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderDialog(
    initialAt: Long?,
    initialRepeat: Repeat,
    onSave: (Long, Repeat) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val initial = remember {
        initialAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
            ?: LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
    }
    var date by remember { mutableStateOf(initial.toLocalDate()) }
    var time by remember { mutableStateOf(initial.toLocalTime()) }
    var repeat by remember { mutableStateOf(initialRepeat) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var repeatMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialAt == null) "Add reminder" else "Edit reminder") },
        text = {
            Column {
                PickerRow(Format.day(date, LocalDate.now()), "reminderDate") { pickDate = true }
                PickerRow(time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)), "reminderTime") { pickTime = true }
                Box {
                    PickerRow(repeat.label, "reminderRepeat") { repeatMenu = true }
                    DropdownMenu(repeatMenu, onDismissRequest = { repeatMenu = false }) {
                        Repeat.entries.forEach {
                            DropdownMenuItem(text = { Text(it.label) }, onClick = { repeat = it; repeatMenu = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli(), repeat)
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )

    if (pickDate) {
        // The date picker works in UTC midnights, so convert the local date both ways.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    if (pickTime) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = { time = LocalTime.of(state.hour, state.minute); pickTime = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

@Composable
private fun PickerRow(text: String, tag: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag)) {
        Text(text, Modifier.padding(vertical = 14.dp), style = MaterialTheme.typography.bodyLarge)
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant).padding(top = 1.dp))
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
