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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thor1or.dayloop.data.GroupEntity
import com.thor1or.dayloop.data.Habit
import com.thor1or.dayloop.logic.NlParser
import java.time.LocalDate
import java.time.ZoneId

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

private fun Habit.dueOn(day: Long): Boolean =
    day >= createdDay && daysMask and (1 shl (LocalDate.ofEpochDay(day).dayOfWeek.value - 1)) != 0

/** Consecutive due days ticked, counting back from today (or from yesterday if today is open). */
private fun streak(h: Habit, checked: Set<Long>, today: Long): Int {
    var day = today
    var n = 0
    if (h.dueOn(day) && day !in checked) day--
    while (day >= h.createdDay) {
        if (h.dueOn(day)) {
            if (day in checked) n++ else break
        }
        day--
    }
    return n
}

@Composable
fun HabitsScreen(vm: AppViewModel) {
    val all by vm.habits.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val nowMs by rememberNow(30_000)
    val today = remember(nowMs) { LocalDate.now() }
    val day = today.toEpochDay()

    var filter by rememberSaveable { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<Habit?>(null) }
    var creating by remember { mutableStateOf(false) }

    val habits = all.filter { filter == null || it.groupId == filter }
    val byHabit = remember(checks) { checks.groupBy({ it.habitId }, { it.day }).mapValues { it.value.toSet() } }
    val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val trackedToday = remember(entries, nowMs, startOfDay) {
        entries.filter { it.habitId != null && it.startMs >= startOfDay }
            .groupBy { it.habitId!! }
            .mapValues { (_, list) -> (list.sumOf { (it.endMs ?: nowMs) - it.startMs } / 60_000).toInt() }
    }

    Column(Modifier.fillMaxSize()) {
        GroupChips(groups, filter, "All", Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { filter = it }
        LazyColumn(Modifier.weight(1f)) {
            item {
                Card(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        val dueToday = habits.filter { it.dueOn(day) }
                        val doneToday = dueToday.count { day in (byHabit[it.id] ?: emptySet()) }
                        Text("All habits", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (dueToday.isEmpty()) "Nothing due today" else "$doneToday of ${dueToday.size} done today",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.padding(4.dp))
                        // Each day is shaded by the share of that day's habits that were done.
                        Heatmap(today) { d ->
                            val due = habits.filter { it.dueOn(d) }
                            if (due.isEmpty()) null
                            else due.count { d in (byHabit[it.id] ?: emptySet()) }.toFloat() / due.size
                        }
                    }
                }
            }
            if (habits.isEmpty()) {
                item {
                    Text(
                        "No habits yet. Add the things you want to do every day; each one gets its own " +
                            "heatmap, an optional reminder, and can be timed.",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(habits.sortedBy { !it.dueOn(day) }, key = { it.id }) { h ->
                val checked = byHabit[h.id] ?: emptySet()
                val due = h.dueOn(day)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editing = h }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = day in checked,
                        onCheckedChange = { on -> vm.act { setHabitChecked(h.id, day, on) } },
                    )
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(
                            h.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (due) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val tracked = trackedToday[h.id] ?: 0
                        val details = listOfNotNull(
                            "Not today".takeIf { !due },
                            streak(h, checked, day).takeIf { it > 0 }?.let { "$it-day streak" },
                            h.reminderMinute?.let { NlParser.formatClock(it) },
                            when {
                                h.targetMin > 0 -> "$tracked / ${h.targetMin} min"
                                tracked > 0 -> "$tracked min today"
                                else -> null
                            },
                        )
                        if (details.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (h.groupId != null) {
                                    Dot(groupColor(groups, h.groupId), 8.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    details.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (running?.habitId != h.id) {
                        IconButton(onClick = { vm.act { startTimer(h.name, habitId = h.id, groupId = h.groupId) } }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Start timer")
                        }
                    }
                }
            }
            item {
                FilledTonalButton(onClick = { creating = true }, modifier = Modifier.padding(16.dp)) {
                    Text("New habit")
                }
            }
        }
    }

    val target = editing ?: if (creating) Habit(name = "", groupId = filter, createdDay = day) else null
    if (target != null) {
        HabitDialog(target, byHabit[target.id] ?: emptySet(), groups, today, vm) {
            editing = null
            creating = false
        }
    }
}

@Composable
private fun HabitDialog(
    habit: Habit,
    checked: Set<Long>,
    groups: List<GroupEntity>,
    today: LocalDate,
    vm: AppViewModel,
    onClose: () -> Unit,
) {
    val isNew = habit.id == 0L
    val day = today.toEpochDay()
    var name by remember { mutableStateOf(habit.name) }
    var mask by remember { mutableStateOf(habit.daysMask) }
    var reminder by remember { mutableStateOf(habit.reminderMinute?.let(NlParser::formatClock) ?: "") }
    var targetText by remember { mutableStateOf(if (habit.targetMin > 0) habit.targetMin.toString() else "") }
    var planText by remember { mutableStateOf(NlParser.formatDuration(habit.planMin)) }
    var groupId by remember { mutableStateOf(habit.groupId) }

    val reminderMin = NlParser.parseClock(reminder)
    val reminderOk = reminder.isBlank() || reminderMin != null
    val targetMin = if (targetText.isBlank()) 0 else NlParser.parseDuration(targetText)
    val planMin = NlParser.parseDuration(planText)

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (isNew) "New habit" else "Habit") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!isNew) {
                    Heatmap(today) { d -> if (!habit.dueOn(d)) null else if (d in checked) 1f else 0f }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = day - 1 in checked,
                            onCheckedChange = { on -> vm.act { setHabitChecked(habit.id, day - 1, on) } },
                        )
                        Text("Done yesterday")
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAY_LETTERS.forEachIndexed { i, letter ->
                        FilterChip(
                            selected = mask and (1 shl i) != 0,
                            onClick = { mask = mask xor (1 shl i) },
                            label = { Text(letter) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                OutlinedTextField(
                    reminder, { reminder = it }, label = { Text("Reminder time") }, singleLine = true,
                    placeholder = { Text("none — e.g. 7:30am") }, isError = !reminderOk, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        targetText, { targetText = it }, label = { Text("Timed goal") }, singleLine = true,
                        placeholder = { Text("none") }, isError = targetMin == null, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        planText, { planText = it }, label = { Text("Length on plan") }, singleLine = true,
                        isError = planMin == null, modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "With a timed goal (say 20m) the habit ticks itself once that much time is tracked in a day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GroupChips(groups, groupId, "No group") { groupId = it }
                if (!isNew) {
                    TextButton(onClick = { vm.act { deleteHabit(habit.id) }; onClose() }) { Text("Delete habit") }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && reminderOk && targetMin != null && planMin != null && mask != 0,
                onClick = {
                    val edited = habit.copy(
                        name = name.trim(), daysMask = mask, reminderMinute = reminderMin,
                        targetMin = targetMin ?: 0, planMin = planMin ?: 30, groupId = groupId,
                    )
                    vm.act { saveHabit(edited) }
                    onClose()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
