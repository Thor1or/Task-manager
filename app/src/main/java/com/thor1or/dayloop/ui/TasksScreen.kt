@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.thor1or.dayloop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thor1or.dayloop.data.GroupEntity
import com.thor1or.dayloop.data.Task
import com.thor1or.dayloop.logic.DateOrder
import com.thor1or.dayloop.logic.NlParser
import com.thor1or.dayloop.logic.Recurrence
import java.time.LocalDate
import java.time.LocalTime

/** The capture list: type anything, dated or not, and find it again when planning. */
@Composable
fun TasksScreen(vm: AppViewModel) {
    val all by vm.tasks.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nowMs by rememberNow(60_000)
    val today = remember(nowMs) { LocalDate.now() }
    val day = today.toEpochDay()

    var filter by rememberSaveable { mutableStateOf<Long?>(null) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Task?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    val parsed = remember(input, today, settings.dateOrder) {
        NlParser.parse(input, today, LocalTime.now(), settings.dateOrder)
    }

    // Subtasks ("steps") are shown under their task, not as tasks of their own.
    val steps = remember(all) { all.filter { it.parentId != null }.groupBy { it.parentId!! } }
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    val tasks = all.filter { it.parentId == null && (filter == null || it.groupId == filter) }
    val open = tasks.filter { !it.done }
    val sections = listOf(
        "Overdue" to open.filter { !it.someday && it.dueDay != null && it.dueDay < day },
        "Today" to open.filter { !it.someday && it.dueDay == day },
        "Tomorrow" to open.filter { !it.someday && it.dueDay == day + 1 },
        "Upcoming" to open.filter { !it.someday && it.dueDay != null && it.dueDay > day + 1 },
        "Inbox" to open.filter { !it.someday && it.dueDay == null },
        "Someday" to open.filter { it.someday },
    )
    val done = tasks.filter { it.done }.sortedByDescending { it.doneAt ?: 0 }

    Column(Modifier.fillMaxSize()) {
        GroupChips(groups, filter, "All", Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { filter = it }
        LazyColumn(Modifier.weight(1f)) {
            if (open.isEmpty() && done.isEmpty()) {
                item {
                    Text(
                        "Nothing here yet. Type below — for example \"buy bread at 9am on 10/12\", " +
                            "\"call the bank tomorrow 1430 p1 #home\", or just a thought to sort out later. " +
                            "#name or @name files it under a group; p1 to p4 sets the priority.",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            sections.forEach { (name, list) ->
                if (list.isNotEmpty()) {
                    item(key = "h-$name") {
                        SectionHeader("$name · ${list.size}") {
                            if (name == "Overdue") {
                                TextButton(onClick = { vm.act { moveOverdueToToday() } }) { Text("Move to today") }
                            }
                        }
                    }
                    items(list, key = { it.id }) { t ->
                        val mine = steps[t.id].orEmpty()
                        Column {
                            TaskRow(t, groups, day, showDate = name != "Today" && name != "Tomorrow",
                                steps = mine, open = t.id in expanded,
                                onExpand = { expanded = if (t.id in expanded) expanded - t.id else expanded + t.id },
                                onToggle = { vm.act { setTaskDone(t.id, !t.done) } },
                                onStart = { vm.act { startTimer(t.title, taskId = t.id, groupId = t.groupId) } },
                                onClick = { editing = t })
                            if (t.id in expanded) StepList(t, mine, vm)
                        }
                    }
                }
            }
            if (done.isNotEmpty()) {
                item(key = "h-done") {
                    SectionHeader("Done · ${done.size}") {
                        if (showDone) TextButton(onClick = { vm.act { dao.deleteDoneTasks() } }) { Text("Clear") }
                        TextButton(onClick = { showDone = !showDone }) { Text(if (showDone) "Hide" else "Show") }
                    }
                }
                if (showDone) {
                    items(done, key = { it.id }) { t ->
                        TaskRow(t, groups, day, showDate = true,
                            steps = steps[t.id].orEmpty(), open = false, onExpand = null,
                            onToggle = { vm.act { setTaskDone(t.id, !t.done) } },
                            onStart = null,
                            onClick = { editing = t })
                    }
                }
            }
        }
        QuickAddField(
            value = input,
            onValueChange = { input = it },
            parsed = parsed,
            placeholder = "Add a task: buy bread at 9am on 10/12",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            groups = groups,
        ) {
            if (parsed.title.isNotBlank()) {
                val p = parsed
                val group = filter
                vm.act { addTask(p, group) }
                input = ""
            }
        }
    }

    editing?.let { t ->
        TaskDialog(t, steps[t.id].orEmpty(), groups, settings.dateOrder, today, vm) { editing = null }
    }
}

@Composable
private fun TaskRow(
    t: Task,
    groups: List<GroupEntity>,
    today: Long,
    showDate: Boolean,
    steps: List<Task>,
    open: Boolean,
    onExpand: (() -> Unit)?,
    onToggle: () -> Unit,
    onStart: (() -> Unit)?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val flag = priorityColor(t.priority)
        Checkbox(
            checked = t.done,
            onCheckedChange = { onToggle() },
            colors = if (flag == null) CheckboxDefaults.colors()
            else CheckboxDefaults.colors(uncheckedColor = flag, checkedColor = flag),
        )
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                t.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (t.done) TextDecoration.LineThrough else null,
                color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val details = listOfNotNull(
                "P${t.priority}".takeIf { t.priority < 4 },
                t.dueDay?.takeIf { showDate }?.let { dayLabel(LocalDate.ofEpochDay(it), LocalDate.ofEpochDay(today)) },
                t.dueMinute?.let { NlParser.formatClock(it) },
                t.durationMin?.let { NlParser.formatDuration(it) },
                Recurrence.decode(t.recurrence)?.describe(),
                groups.firstOrNull { it.id == t.groupId }?.name,
            )
            if (details.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t.groupId != null) {
                        Dot(groupColor(groups, t.groupId), 8.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!t.done && t.dueDay != null && t.dueDay < today) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (steps.isNotEmpty() && onExpand != null) {
            // Steps done out of all, and a way to unfold them.
            TextButton(onClick = onExpand, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("${steps.count { it.done }}/${steps.size}")
                Icon(
                    if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (open) "Hide steps" else "Show steps",
                )
            }
        }
        if (onStart != null) {
            IconButton(onClick = onStart) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Start timer")
            }
        }
    }
}

/** The steps of one task: tick them off, remove them, add another. Ticking the last one finishes the task. */
@Composable
private fun StepList(task: Task, steps: List<Task>, vm: AppViewModel, modifier: Modifier = Modifier.padding(start = 40.dp)) {
    var text by remember(task.id) { mutableStateOf("") }
    fun add() {
        val title = text.trim()
        if (title.isNotEmpty()) vm.act { addSubtask(task.id, title) }
        text = ""
    }
    Column(modifier) {
        steps.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = s.done, onCheckedChange = { on -> vm.act { setTaskDone(s.id, on) } })
                Text(
                    s.title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (s.done) TextDecoration.LineThrough else null,
                    color = if (s.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = { vm.act { deleteTask(s.id) } }) {
                    Icon(
                        Icons.Default.Close, contentDescription = "Remove step ${s.title}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text("Add a step") },
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            trailingIcon = {
                IconButton(onClick = { add() }, enabled = text.isNotBlank()) {
                    Icon(Icons.Default.Add, contentDescription = "Add step")
                }
            },
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun TaskDialog(
    task: Task,
    steps: List<Task>,
    groups: List<GroupEntity>,
    order: DateOrder,
    today: LocalDate,
    vm: AppViewModel,
    onClose: () -> Unit,
) {
    var title by remember { mutableStateOf(task.title) }
    var notes by remember { mutableStateOf(task.notes) }
    var groupId by remember { mutableStateOf(task.groupId) }
    var someday by remember { mutableStateOf(task.someday) }
    var priority by remember { mutableStateOf(task.priority) }
    var whenText by remember {
        mutableStateOf(
            NlParser.format(
                task.dueDay?.let(LocalDate::ofEpochDay), task.dueMinute, task.durationMin,
                Recurrence.decode(task.recurrence), order,
            )
        )
    }
    val schedule = remember(whenText) { NlParser.parse(whenText, today, LocalTime.now(), order) }
    val scheduleOk = schedule.title.isEmpty()

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Task") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    whenText, { whenText = it },
                    label = { Text("When") },
                    placeholder = { Text("tomorrow 9am for 30m") },
                    singleLine = true,
                    isError = !scheduleOk,
                    supportingText = {
                        Text(
                            if (!scheduleOk) "Not understood: \"${schedule.title}\""
                            else parsedSummary(schedule).ifEmpty { "No date — stays in the inbox" }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
                GroupChips(groups, groupId, "No group") { groupId = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..4).forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text("P$p") },
                            leadingIcon = priorityColor(p)?.let { c -> { Dot(c) } },
                        )
                    }
                }
                Text("Steps", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                StepList(task, steps, vm, Modifier)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Someday", Modifier.weight(1f))
                    Switch(someday, { someday = it })
                }
                TextButton(onClick = { vm.act { deleteTask(task.id) }; onClose() }) { Text("Delete") }
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && scheduleOk,
                onClick = {
                    val edited = task.copy(
                        title = title.trim(), notes = notes.trim(), groupId = groupId, someday = someday,
                        priority = priority,
                        dueDay = schedule.date?.toEpochDay(),
                        dueMinute = schedule.time?.let { it.hour * 60 + it.minute },
                        durationMin = schedule.durationMin,
                        recurrence = schedule.recurrence?.encode(),
                    )
                    vm.act {
                        // Ticking the last step may have finished the task while this was open.
                        val current = dao.task(task.id)
                        saveTask(edited.copy(done = current?.done ?: edited.done, doneAt = current?.doneAt ?: edited.doneAt, recurrence = if (current != null && current.done && !task.done) current.recurrence else edited.recurrence))
                    }
                    onClose()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
