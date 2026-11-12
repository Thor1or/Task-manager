@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.thor1or.dayloop.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thor1or.dayloop.data.GroupEntity
import com.thor1or.dayloop.data.PlanBlock
import com.thor1or.dayloop.data.Task
import com.thor1or.dayloop.logic.NlParser
import com.thor1or.dayloop.logic.PlanEngine
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The day plan. Activities are listed in order with the start time and length the schedule
 * gives them; "Begin" pins an activity to the clock and the rest of the day is laid out again.
 */
@Composable
fun PlanScreen(vm: AppViewModel) {
    val day by vm.planDay.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val nowMs by rememberNow(15_000)

    val now = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
    val today = now.toLocalDate()
    val isToday = day == today.toEpochDay()
    val nowMin = if (isToday) now.hour * 60 + now.minute else null
    val date = LocalDate.ofEpochDay(day)
    // Past midnight, a plan that was showing "today" moves on to the new day.
    LaunchedEffect(today) {
        val t = today.toEpochDay()
        if (vm.lastToday != t) {
            if (vm.planDay.value == vm.lastToday) vm.planDay.value = t
            vm.lastToday = t
        }
    }

    val engineBlocks = remember(blocks) {
        blocks.map { PlanEngine.Block(it.id, it.desiredMin, it.fixedStart, it.rigid, it.actualStart, it.actualEnd) }
    }
    val result = remember(engineBlocks, settings, nowMin) {
        PlanEngine.compute(engineBlocks, settings.dayStart, settings.dayEnd, nowMin)
    }
    val ideal = remember(engineBlocks, settings) {
        PlanEngine.ideal(engineBlocks, settings.dayStart, settings.dayEnd).slots.associate { it.id to it.start }
    }
    val slots = remember(result) { result.slots.associateBy { it.id } }

    var editing by remember { mutableStateOf<PlanBlock?>(null) }
    var pickingTasks by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val parsed = remember(input, settings.dateOrder) {
        NlParser.parse(input, today, LocalTime.MIDNIGHT, settings.dateOrder)
    }
    val calendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.act { vm.say("Imported ${importCalendar(day)} calendar events") }
        else vm.say("Calendar access was not granted")
    }

    Column(Modifier.fillMaxSize()) {
        // Day switcher
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.planDay.value = day - 1 }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
            }
            Text(
                dayLabel(date, today),
                Modifier
                    .weight(1f)
                    .clickable { vm.planDay.value = today.toEpochDay() }
                    .padding(8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(onClick = { vm.planDay.value = day + 1 }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    @Composable
                    fun item(label: String, action: () -> Unit) = DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { menu = false; action() },
                    )
                    item("Add from tasks") { pickingTasks = true }
                    item("Add the day's habits") { vm.act { vm.say("Added ${addHabitsToPlan(day)} habits") } }
                    item("Import calendar events") { calendar.launch(Manifest.permission.READ_CALENDAR) }
                    item("Load template") {
                        vm.act { vm.say(if (loadTemplate(day) == 0) "No template saved yet" else "Template added") }
                    }
                    item("Save as template") { vm.act { saveTemplate(day); vm.say("Saved as the day template") } }
                    item("Clear this plan") { vm.act { clearPlan(day) } }
                }
            }
        }

        // Where the day stands
        val summary = when {
            blocks.isEmpty() -> "Nothing planned. Day runs ${NlParser.formatClock(settings.dayStart)}–${NlParser.formatClock(settings.dayEnd)}."
            result.overflow -> "Too much fixed time to fit — something has to move."
            result.ratio < 0.999 -> "Overbooked: flexible activities squeezed to ${(result.ratio * 100).toInt()}% of their length."
            else -> "Ends ${NlParser.formatClock(result.end)} · ${NlParser.formatDuration(result.free)} free"
        }
        Text(
            summary,
            Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (result.overflow || result.ratio < 0.999) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyColumn(Modifier.weight(1f)) {
            if (blocks.isEmpty()) {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Type activities below (\"Deep work 90m\", \"Lunch at 12:30 for 45m\"), or start from what you already have:",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(onClick = { pickingTasks = true }) { Text("Add from tasks") }
                        FilledTonalButton(onClick = { vm.act { vm.say("Added ${addHabitsToPlan(day)} habits") } }) {
                            Text("Add the day's habits")
                        }
                        FilledTonalButton(onClick = {
                            vm.act { if (loadTemplate(day) == 0) vm.say("No template saved yet") }
                        }) { Text("Load template") }
                    }
                }
            }
            items(blocks, key = { it.id }) { b ->
                val slot = slots[b.id] ?: return@items
                if (slot.gapBefore >= 5) {
                    Text(
                        "Free · ${NlParser.formatDuration(slot.gapBefore)}",
                        Modifier.padding(start = 84.dp, top = 4.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BlockRow(
                    block = b,
                    slot = slot,
                    idealStart = ideal[b.id],
                    groups = groups,
                    canBegin = isToday,
                    onClick = { editing = b },
                    onBegin = { vm.act { beginBlock(b) } },
                    onDone = { vm.act { stopTimer(complete = true) } },
                    onMove = { delta -> vm.act { moveBlock(b, delta) } },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }

        QuickAddField(
            value = input,
            onValueChange = { input = it },
            parsed = parsed,
            placeholder = "Add activity: Deep work 90m",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            if (parsed.title.isNotBlank()) {
                val p = parsed
                vm.act {
                    addBlock(
                        PlanBlock(
                            day = day, position = 0, title = p.title, groupId = groupIdFor(p.group),
                            desiredMin = p.durationMin ?: 30,
                            fixedStart = p.time?.let { it.hour * 60 + it.minute },
                        )
                    )
                }
                input = ""
            }
        }
    }

    editing?.let { b ->
        BlockDialog(b, groups, started = b.actualStart != null, onClose = { editing = null }, vm = vm)
    }
    if (pickingTasks) TaskPicker(tasks, day, onClose = { pickingTasks = false }) { chosen ->
        vm.act { addTasksToPlan(day, chosen) }
    }
}

@Composable
private fun BlockRow(
    block: PlanBlock,
    slot: PlanEngine.Slot,
    idealStart: Int?,
    groups: List<GroupEntity>,
    canBegin: Boolean,
    onClick: () -> Unit,
    onBegin: () -> Unit,
    onDone: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val done = slot.state == PlanEngine.State.DONE
    val running = slot.state == PlanEngine.State.RUNNING
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (running) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(56.dp)) {
            Text(NlParser.formatClock(slot.start), fontWeight = FontWeight.SemiBold)
            Text(
                NlParser.formatDuration(slot.length),
                style = MaterialTheme.typography.bodySmall,
                color = if (slot.compressed) MaterialTheme.colorScheme.error else muted,
            )
        }
        Box(
            Modifier
                .width(4.dp)
                .height(36.dp)
                .background(
                    groupColor(groups, block.groupId) ?: MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(2.dp),
                )
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                block.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) muted else MaterialTheme.colorScheme.onSurface,
            )
            val notes = buildList {
                if (block.fixedStart != null) add("Fixed ${NlParser.formatClock(block.fixedStart)}")
                if (block.rigid) add("Rigid")
                if (slot.compressed) add("wanted ${NlParser.formatDuration(block.desiredMin)}")
                if (done) add("planned ${NlParser.formatDuration(block.desiredMin)}")
                val late = if (idealStart != null && (done || running || slot.pushed)) slot.start - idealStart else 0
                if (late >= 5) add("${NlParser.formatDuration(late)} late")
                if (late <= -5) add("${NlParser.formatDuration(-late)} early")
            }
            if (notes.isNotEmpty()) {
                Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        when {
            done -> Icon(
                Icons.Default.Check, contentDescription = "Finished",
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp),
            )
            running -> Button(onClick = onDone, modifier = Modifier.padding(end = 8.dp)) { Text("Done") }
            else -> {
                IconButton(onClick = { onMove(-1) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = { onMove(1) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                }
                if (canBegin) {
                    TextButton(onClick = onBegin) { Text("Begin") }
                }
            }
        }
    }
}

@Composable
private fun BlockDialog(
    block: PlanBlock,
    groups: List<GroupEntity>,
    started: Boolean,
    onClose: () -> Unit,
    vm: AppViewModel,
) {
    var title by remember { mutableStateOf(block.title) }
    var length by remember { mutableStateOf(NlParser.formatDuration(block.desiredMin)) }
    var fixed by remember { mutableStateOf(block.fixedStart?.let(NlParser::formatClock) ?: "") }
    var rigid by remember { mutableStateOf(block.rigid) }
    var groupId by remember { mutableStateOf(block.groupId) }
    val lengthMin = NlParser.parseDuration(length)
    val fixedMin = NlParser.parseClock(fixed)
    val fixedOk = fixed.isBlank() || fixedMin != null

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Activity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        length, { length = it }, label = { Text("Length") }, singleLine = true,
                        isError = lengthMin == null, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        fixed, { fixed = it }, label = { Text("Fixed start") }, singleLine = true,
                        placeholder = { Text("none") }, isError = !fixedOk, modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Rigid length")
                        Text(
                            "Never shortened when the day is overbooked",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(rigid, { rigid = it })
                }
                GroupChips(groups, groupId, "No group") { groupId = it }
                Row {
                    TextButton(onClick = { vm.act { deleteBlock(block) }; onClose() }) { Text("Delete") }
                    if (started) {
                        TextButton(onClick = { vm.act { resetBlock(block) }; onClose() }) { Text("Mark not started") }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && lengthMin != null && fixedOk,
                onClick = {
                    val edited = block.copy(
                        title = title.trim(), desiredMin = lengthMin ?: block.desiredMin,
                        fixedStart = fixedMin, rigid = rigid, groupId = groupId,
                    )
                    // Position and progress may have changed while the dialog was open.
                    vm.act {
                        val current = dao.block(block.id) ?: return@act
                        saveBlock(
                            edited.copy(
                                position = current.position,
                                actualStart = current.actualStart,
                                actualEnd = current.actualEnd,
                            )
                        )
                    }
                    onClose()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** Pull tasks from the lists into the plan. */
@Composable
private fun TaskPicker(tasks: List<Task>, day: Long, onClose: () -> Unit, onAdd: (List<Task>) -> Unit) {
    val candidates = remember(tasks, day) {
        val open = tasks.filter { !it.done && !it.someday }
        open.filter { it.dueDay != null && it.dueDay <= day } +
            open.filter { it.dueDay == null } +
            open.filter { it.dueDay != null && it.dueDay > day && it.dueDay <= day + 7 }
    }
    var chosen by remember {
        mutableStateOf(candidates.filter { it.dueDay != null && it.dueDay <= day }.map { it.id }.toSet())
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add from tasks") },
        text = {
            if (candidates.isEmpty()) {
                Text("No open tasks. Capture some on the Tasks tab first.")
            } else {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(candidates, key = { it.id }) { t ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { chosen = if (t.id in chosen) chosen - t.id else chosen + t.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(t.id in chosen, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                            Column {
                                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when {
                                        t.dueDay == null -> "Inbox"
                                        else -> dayLabel(LocalDate.ofEpochDay(t.dueDay))
                                    } + (t.durationMin?.let { " · ${NlParser.formatDuration(it)}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = chosen.isNotEmpty(), onClick = {
                onAdd(candidates.filter { it.id in chosen })
                onClose()
            }) { Text("Add ${chosen.size}") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
