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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import com.thor1or.dayloop.data.Template
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
    val stored by vm.blocks.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    // While a row is being dragged the list follows the finger; the order is saved on release.
    var order by remember { mutableStateOf<List<Long>?>(null) }
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    // Once the saved order arrives, the temporary one is dropped.
    LaunchedEffect(stored) { if (dragId == null) order = null }
    val blocks = remember(stored, order) {
        val rank = order?.withIndex()?.associate { it.value to it.index }
        if (rank == null) stored else stored.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }
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
    var loadingTemplate by remember { mutableStateOf(false) }
    var savingTemplate by remember { mutableStateOf(false) }
    val weekdayBit = 1 shl (date.dayOfWeek.value - 1)
    // The template meant for this weekday, if one is.
    val suggested = templates.firstOrNull { it.daysMask and weekdayBit != 0 }
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
            TextButton(onClick = { vm.repo.prefs.update { it.copy(planTimeline = !it.planTimeline) } }) {
                Text(if (settings.planTimeline) "List" else "Blocks")
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
                    item("Load template…") { loadingTemplate = true }
                    item("Save as template…") { savingTemplate = true }
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

        // Sessions of one task or habit are numbered: "part 1 of 2".
        val parts = remember(blocks) {
            val groupsOf = blocks.groupBy { it.habitId?.let { h -> "h$h" } ?: it.taskId?.let { t -> "t$t" } ?: it.title.trim().lowercase() }
            blocks.associate { b ->
                val same = groupsOf.values.first { list -> list.any { it.id == b.id } }
                b.id to if (same.size > 1) "part ${same.indexOfFirst { it.id == b.id } + 1} of ${same.size}" else null
            }
        }

        if (settings.planTimeline) {
            PlanTimeline(
                blocks = blocks, slots = slots, groups = groups,
                dayStart = settings.dayStart, dayEnd = settings.dayEnd, nowMin = nowMin,
                modifier = Modifier.weight(1f),
                onTap = { editing = it },
                onMove = { b, minute -> vm.act { moveBlockToTime(b.id, minute) } },
                onResize = { b, minutes, lock ->
                    vm.act { dao.block(b.id)?.let { saveBlock(it.copy(desiredMin = minutes, rigid = it.rigid || lock)) } }
                },
                onCreate = { minute ->
                    editing = PlanBlock(day = day, position = 0, title = "", desiredMin = 30, fixedStart = minute, rigid = true)
                },
            )
        } else LazyColumn(Modifier.weight(1f), state = listState) {
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
                        if (suggested != null) {
                            FilledTonalButton(onClick = { vm.act { loadTemplate(day, suggested) } }) {
                                Text("Load \"${suggested.name}\" template")
                            }
                        }
                        if (templates.any { it.id != suggested?.id }) {
                            FilledTonalButton(onClick = { loadingTemplate = true }) { Text("Load another template…") }
                        }
                    }
                }
            }
            items(blocks, key = { it.id }) { b ->
                val slot = slots[b.id] ?: return@items
                val dragging = dragId == b.id
                Column(
                    (if (dragging) Modifier else Modifier.animateItem())
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) dragY else 0f
                            shadowElevation = if (dragging) 12f else 0f
                        }
                        .then(
                            // Hold a waiting activity, then drag it up or down.
                            if (b.actualStart != null) Modifier else Modifier.pointerInput(b.id, day) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        order = stored.map { it.id }
                                        dragId = b.id
                                        dragY = 0f
                                    },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        dragY += drag.y
                                        val shown = listState.layoutInfo.visibleItemsInfo
                                        val me = shown.firstOrNull { it.key == b.id }
                                        val current = order
                                        if (me != null && current != null) {
                                            val centre = me.offset + me.size / 2 + dragY.toInt()
                                            val over = shown.firstOrNull {
                                                it.key != b.id && it.key is Long && centre in it.offset..it.offset + it.size
                                            }
                                            val down = over != null && over.offset > me.offset
                                            val waiting = over != null && stored.any { it.id == over.key && it.actualStart == null }
                                            // Swap once the dragged row is past the middle of its neighbour.
                                            if (over != null && waiting &&
                                                (if (down) centre > over.offset + over.size / 2 else centre < over.offset + over.size / 2)
                                            ) {
                                                val next = current.toMutableList()
                                                val to = next.indexOf(over.key as Long)
                                                next.remove(b.id)
                                                next.add(to, b.id)
                                                order = next
                                                // The row now sits where its neighbour was; keep it under the finger.
                                                dragY += if (down) -over.size else over.size
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        val final = order
                                        dragId = null
                                        dragY = 0f
                                        if (final != null) vm.act { reorderBlocks(day, final) }
                                    },
                                    onDragCancel = {
                                        dragId = null
                                        dragY = 0f
                                        order = null
                                    },
                                )
                            }
                        )
                ) {
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
                        part = parts[b.id],
                        steps = b.taskId?.let { id ->
                            tasks.filter { it.parentId == id }.takeIf { it.isNotEmpty() }
                                ?.let { list -> "${list.count { it.done }}/${list.size} steps" }
                        },
                        onLock = { vm.act { dao.block(b.id)?.let { saveBlock(it.copy(rigid = !it.rigid)) } } },
                        groups = groups,
                        canBegin = isToday,
                        onClick = { editing = b },
                        onBegin = { vm.act { beginBlock(b) } },
                        onDone = { vm.act { stopTimer(complete = true) } },
                        onMove = { delta -> vm.act { moveBlock(b, delta) } },
                        onReopen = { resume -> vm.act { reopenBlock(b.id, resume) } },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }

        QuickAddField(
            value = input,
            onValueChange = { input = it },
            parsed = parsed,
            placeholder = "Add activity: Deep work 90m #work",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            groups = groups,
        ) {
            if (parsed.title.isNotBlank()) {
                val p = parsed
                vm.act {
                    addBlock(
                        PlanBlock(
                            day = day, position = 0, title = p.title, groupId = groupIdFor(p.group),
                            desiredMin = p.durationMin ?: 30,
                            fixedStart = p.time?.let { it.hour * 60 + it.minute },
                            // Something typed with a start time is an appointment: its length is locked.
                            rigid = p.time != null,
                        )
                    )
                }
                input = ""
            }
        }
    }

    editing?.let { b ->
        // Re-read from the list so the dialog shows the activity as it is now.
        BlockDialog(
            blocks.firstOrNull { it.id == b.id && b.id != 0L } ?: b, groups, isToday, nowMin,
            alertAll = settings.alertAtEnd, onClose = { editing = null }, vm = vm,
        )
    }
    if (loadingTemplate) LoadTemplateDialog(templates, onClose = { loadingTemplate = false }, vm = vm, day = day)
    if (savingTemplate) SaveTemplateDialog(templates, suggested, weekdayBit, onClose = { savingTemplate = false }) { name, mask ->
        vm.act { saveTemplate(day, name, mask); vm.say("Saved as \"$name\"") }
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
    part: String?,
    steps: String?,
    onLock: () -> Unit,
    groups: List<GroupEntity>,
    canBegin: Boolean,
    onClick: () -> Unit,
    onBegin: () -> Unit,
    onDone: () -> Unit,
    onMove: (Int) -> Unit,
    onReopen: (Boolean) -> Unit,
) {
    var undoMenu by remember { mutableStateOf(false) }
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
                if (part != null) add(part)
                if (steps != null) add(steps)
                if (block.adhoc) add("unplanned")
                if (block.rigid) add("Locked length")
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
            // Finished by mistake? The tick opens a way back.
            done -> Box {
                IconButton(onClick = { undoMenu = true }) {
                    Icon(Icons.Default.Check, contentDescription = "Finished — tap to undo", tint = MaterialTheme.colorScheme.primary)
                }
                DropdownMenu(expanded = undoMenu, onDismissRequest = { undoMenu = false }) {
                    if (canBegin) {
                        DropdownMenuItem(text = { Text("Not finished — still running") }, onClick = { undoMenu = false; onReopen(true) })
                    }
                    DropdownMenuItem(text = { Text("Not started") }, onClick = { undoMenu = false; onReopen(false) })
                }
            }
            running -> Button(onClick = onDone, modifier = Modifier.padding(end = 8.dp)) { Text("Done") }
            else -> {
                // Locked: this activity keeps its length and the others adjust around it.
                IconButton(onClick = onLock, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = if (block.rigid) "Unlock length" else "Lock length",
                        tint = if (block.rigid) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = { onMove(-1) }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = { onMove(1) }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                }
                if (canBegin) {
                    TextButton(onClick = onBegin) { Text("Begin") }
                }
            }
        }
    }
}

/**
 * Edit an activity (or create one, when its id is 0): what was planned, and — once it has
 * happened — when it really started and ended.
 */
@Composable
private fun BlockDialog(
    block: PlanBlock,
    groups: List<GroupEntity>,
    isToday: Boolean,
    nowMin: Int?,
    alertAll: Boolean,
    onClose: () -> Unit,
    vm: AppViewModel,
) {
    val isNew = block.id == 0L
    var alarm by remember { mutableStateOf(block.alarm) }
    val started = block.actualStart != null
    var title by remember { mutableStateOf(block.title) }
    var length by remember { mutableStateOf(NlParser.formatDuration(block.desiredMin)) }
    var fixed by remember { mutableStateOf(block.fixedStart?.let(NlParser::formatClock) ?: "") }
    var rigid by remember { mutableStateOf(block.rigid) }
    var groupId by remember { mutableStateOf(block.groupId) }
    val begunText = block.actualStart?.let(NlParser::formatClock) ?: ""
    val endedText = block.actualEnd?.let(NlParser::formatClock) ?: ""
    var begun by remember(block.actualStart) { mutableStateOf(begunText) }
    var ended by remember(block.actualEnd) { mutableStateOf(endedText) }

    val lengthMin = NlParser.parseDuration(length)
    val fixedMin = NlParser.parseClock(fixed)
    val fixedOk = fixed.isBlank() || fixedMin != null
    val begunMin = NlParser.parseClock(begun)
    val endedMin = NlParser.parseClock(ended)
    val begunOk = begun.isBlank() || (begunMin != null && (nowMin == null || begunMin <= nowMin))
    val endedOk = ended.isBlank() || (endedMin != null && begunMin != null && endedMin > begunMin)
    // Only today's plan can have something still running.
    val runningOk = begun.isBlank() || ended.isNotBlank() || isToday

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (isNew) "New activity" else "Activity") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                        Text("Lock length")
                        Text(
                            "Keeps this length; other activities adjust around it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(rigid, { rigid = it })
                }
                if (!alertAll) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Alert when time is up")
                            Text(
                                "A notification when this activity's planned time ends",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(alarm, { alarm = it })
                    }
                }
                GroupChips(groups, groupId, "No group") { groupId = it }

                if (!isNew) {
                    Text("What really happened", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            begun, { begun = it }, label = { Text("Started") }, singleLine = true,
                            placeholder = { Text("not yet") }, isError = !begunOk, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            ended, { ended = it }, label = { Text("Ended") }, singleLine = true,
                            placeholder = { Text(if (begun.isBlank()) "" else "running") },
                            isError = !endedOk || !runningOk, modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        "Correct these if you forgot to switch: e.g. 0930 or 14:15. A start with no end means it is running now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!started) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Split into", Modifier.padding(end = 4.dp))
                            listOf(2, 3, 4).forEach { n ->
                                TextButton(onClick = { vm.act { splitBlock(block.id, n) }; onClose() }) { Text("$n") }
                            }
                            Text("sessions", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row {
                        TextButton(onClick = { vm.act { deleteBlock(block) }; onClose() }) { Text("Delete") }
                        if (started) {
                            TextButton(onClick = { vm.act { reopenBlock(block.id, resume = false) }; onClose() }) {
                                Text("Mark not started")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && lengthMin != null && fixedOk && begunOk && endedOk && runningOk,
                onClick = {
                    val edited = block.copy(
                        title = title.trim(), desiredMin = lengthMin ?: block.desiredMin,
                        fixedStart = fixedMin, rigid = rigid, groupId = groupId, alarm = alarm,
                    )
                    val timesChanged = begun != begunText || ended != endedText
                    vm.act {
                        if (isNew) {
                            addBlock(edited)
                            return@act
                        }
                        // Position and progress may have changed while the dialog was open.
                        val current = dao.block(block.id) ?: return@act
                        saveBlock(
                            edited.copy(
                                position = current.position,
                                actualStart = current.actualStart,
                                actualEnd = current.actualEnd,
                            )
                        )
                        // A new fixed start moves the activity to that point of the day.
                        if (fixedMin != null && fixedMin != block.fixedStart) moveBlockToTime(block.id, fixedMin)
                        if (timesChanged) setBlockTimes(block.id, begunMin.takeIf { begun.isNotBlank() }, endedMin.takeIf { ended.isNotBlank() })
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
        val open = tasks.filter { !it.done && !it.someday && it.parentId == null }
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


/** Pick one of the saved day templates to add to the plan; the bin removes a template. */
@Composable
private fun LoadTemplateDialog(templates: List<Template>, onClose: () -> Unit, vm: AppViewModel, day: Long) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Load template") },
        text = {
            if (templates.isEmpty()) {
                Text("No templates yet. Build a day you like, then choose \"Save as template…\" — for example one for weekdays and one for weekends.")
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(templates, key = { it.id }) { t ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.act { vm.say("Added ${loadTemplate(day, t)} activities from \"${t.name}\"") }
                                    onClose()
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(t.name)
                                val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                                    .filterIndexed { i, _ -> t.daysMask and (1 shl i) != 0 }
                                if (days.isNotEmpty()) {
                                    Text(
                                        days.joinToString(" "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = { vm.act { deleteTemplate(t) } }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete template ${t.name}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/** Save the day's plan under a name, optionally tied to weekdays it should be offered on. */
@Composable
private fun SaveTemplateDialog(
    templates: List<Template>,
    suggested: Template?,
    weekdayBit: Int,
    onClose: () -> Unit,
    onSave: (String, Int) -> Unit,
) {
    var name by remember { mutableStateOf(suggested?.name ?: "") }
    var mask by remember { mutableStateOf(suggested?.daysMask ?: weekdayBit) }
    val replaces = templates.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Save as template") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { name = it }, label = { Text("Name") }, singleLine = true,
                    placeholder = { Text("Weekday") },
                    supportingText = { Text(if (replaces != null) "Replaces the existing \"${replaces.name}\"" else "A new template") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (templates.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        templates.forEach { t ->
                            AssistChip(onClick = { name = t.name; mask = t.daysMask }, label = { Text(t.name) })
                        }
                    }
                }
                Text("Offer it on an empty plan on:", style = MaterialTheme.typography.bodySmall)
                DayChips(mask) { mask = it }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), mask); onClose() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
