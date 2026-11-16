@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.thor1or.dayloop.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thor1or.dayloop.data.GroupEntity
import com.thor1or.dayloop.data.TimeEntry
import com.thor1or.dayloop.logic.NlParser
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private fun dur(minutes: Double) = NlParser.formatDuration(minutes.roundToInt())

/** One figure with its caption, for the row of headline numbers. */
@Composable
private fun Figure(value: String, caption: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Plain bars, one per value, scaled to the largest; [caption] sits underneath. */
@Composable
private fun Bars(values: List<Double>, color: Color, caption: String) {
    val top = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(72.dp)) {
            val step = size.width / values.size
            val w = (step * 0.7f).coerceAtMost(28.dp.toPx())
            values.forEachIndexed { i, v ->
                val h = (v / top).toFloat() * size.height
                val x = i * step + (step - w) / 2
                // A stub for empty days keeps the rhythm of the chart readable.
                drawRoundRect(track, Offset(x, size.height - 2.dp.toPx()), Size(w, 2.dp.toPx()))
                if (v > 0) {
                    drawRoundRect(color, Offset(x, size.height - h), Size(w, h), CornerRadius(3.dp.toPx(), 3.dp.toPx()))
                }
            }
        }
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A line of the statistics lists: a name, and what there is to say about it. */
@Composable
private fun StatRow(name: String, detail: String, color: Color? = null, warn: Boolean = false) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (color != null) {
            Dot(color, 8.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** How the time was actually spent: totals, averages per activity, habits, sleep and estimates. */
@Composable
fun StatsScreen(vm: AppViewModel) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val finished by vm.finishedBlocks.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val habits by vm.habits.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val nowMs by rememberNow(30_000)
    val zone = ZoneId.systemDefault()
    val today = remember(nowMs) { LocalDate.now() }
    val day = today.toEpochDay()
    var days by rememberSaveable { mutableStateOf(7) }
    var editing by remember { mutableStateOf<TimeEntry?>(null) }

    fun minutes(e: TimeEntry) = ((e.endMs ?: nowMs) - e.startMs) / 60_000.0
    fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()

    val firstDay = day - (days - 1)
    val fromMs = LocalDate.ofEpochDay(firstDay).atStartOfDay(zone).toInstant().toEpochMilli()
    val inRange = entries.filter { it.startMs >= fromMs }
    val work = inRange.filter { !it.sleep }
    val total = work.sumOf { minutes(it) }
    val byGroup = work.groupBy { it.groupId }
        .map { (id, list) -> id to list.sumOf { minutes(it) } }
        .sortedByDescending { it.second }

    // Tracked time per day (per week over three months), oldest first.
    val perDay = work.groupBy { dayOf(it.startMs) }.mapValues { (_, list) -> list.sumOf { minutes(it) } }
    val chart = if (days <= 30) (firstDay..day).map { perDay[it] ?: 0.0 }
    else (firstDay..day).chunked(7).map { week -> week.sumOf { perDay[it] ?: 0.0 } }

    // Every task, habit or free-text activity that was timed: how often, how long on average.
    val activities = work
        .groupBy { it.habitId?.let { h -> "h$h" } ?: it.taskId?.let { t -> "t$t" } ?: it.title.trim().lowercase() }
        .map { (_, list) -> list }
        .sortedByDescending { list -> list.sumOf { minutes(it) } }
        .take(15)

    val checked = remember(checks) { checks.groupBy({ it.habitId }, { it.day }).mapValues { it.value.toSet() } }
    val doneTasks = tasks.count { it.parentId == null && it.done && (it.doneAt ?: 0) >= fromMs }

    // Planned against actual, for activities that were planned (not ones a timer added).
    val planned = finished.filter { !it.adhoc && it.day >= firstDay }
    val plannedMin = planned.sumOf { it.desiredMin }
    val actualMin = planned.sumOf { it.actualEnd!! - it.actualStart!! }
    val estimates = finished.filter { !it.adhoc }
        .groupBy { it.habitId?.let { h -> "h$h" } ?: it.title.trim().lowercase() }
        .filter { it.value.size >= 2 }
        .map { (_, list) ->
            Triple(
                list.last().title,
                list.map { it.desiredMin }.average(),
                list.map { (it.actualEnd!! - it.actualStart!!).toDouble() }.average(),
            )
        }
        .sortedByDescending { kotlin.math.abs(it.third - it.second) }
        .take(8)

    // Sleep, by the morning it ended on. Always at least a week, so "Today" still says something.
    val sleepFrom = day - (maxOf(days, 7) - 1)
    val nights = entries.filter { it.sleep && it.endMs != null && dayOf(it.endMs) >= sleepFrom }
    val perNight = nights.groupBy { dayOf(it.endMs!!) }.mapValues { (_, list) -> list.sumOf { minutes(it) } }
    fun clockOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).let { it.hour * 60 + it.minute }
    // Bedtimes straddle midnight: count the small hours as "after 24:00" before averaging.
    val bedtime = nights.map { clockOf(it.startMs).let { m -> if (m < 12 * 60) m + 1440 else m } }.average()
    val wakeTime = nights.map { clockOf(it.endMs!!) }.average()

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(1 to "Today", 7 to "7 days", 30 to "30 days", 90 to "90 days").forEach { (n, label) ->
                    FilterChip(selected = days == n, onClick = { days = n }, label = { Text(label) })
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Figure(dur(total), "tracked", Modifier.weight(1f))
                if (days > 1) Figure(dur(total / days), "per day", Modifier.weight(1f))
                Figure("$doneTasks", if (doneTasks == 1) "task done" else "tasks done", Modifier.weight(1f))
            }
            if (days > 1) {
                Bars(
                    chart, MaterialTheme.colorScheme.primary,
                    if (days <= 30) "Tracked per day · busiest ${dur(chart.max())}"
                    else "Tracked per week · busiest ${dur(chart.max())}",
                )
            }
        }

        if (byGroup.isNotEmpty()) {
            item { SectionHeader("By group") }
            items(byGroup) { (id, mins) ->
                val g = groups.firstOrNull { it.id == id }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row {
                        Text(g?.name ?: "No group", Modifier.weight(1f))
                        Text(
                            "${dur(mins)} · ${(mins / total * 100).roundToInt()}%",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((mins / total).toFloat().coerceIn(0.01f, 1f))
                                .height(8.dp)
                                .background(groupColor(groups, id) ?: MaterialTheme.colorScheme.outline)
                        )
                    }
                }
            }
        }

        if (activities.isNotEmpty()) {
            item { SectionHeader("Tasks and habits: how long they take") }
            items(activities) { list ->
                val sum = list.sumOf { minutes(it) }
                val perDayAvg = sum / list.map { dayOf(it.startMs) }.distinct().size
                StatRow(
                    list.first().title.ifBlank { "Untitled" },
                    "${list.size}× · avg ${dur(sum / list.size)}" +
                        (if (list.size > 1 && days > 1) " · ${dur(perDayAvg)}/day" else "") + " · total ${dur(sum)}",
                    groupColor(groups, list.first().groupId),
                )
            }
        }

        if (habits.isNotEmpty() && days > 1) {
            item { SectionHeader("Habits") }
            items(habits, key = { "habit-${it.id}" }) { h ->
                val mine = checked[h.id] ?: emptySet()
                val due = (firstDay..day).filter { h.dueOn(it) }
                val done = due.count { it in mine }
                StatRow(
                    h.name,
                    (if (due.isEmpty()) "not due" else "${done * 100 / due.size}% ($done/${due.size})") +
                        " · streak ${currentStreak(h, mine, day)} · best ${bestStreak(h, mine, day)}",
                    groupColor(groups, h.groupId),
                )
            }
        }

        item { SectionHeader("Sleep") }
        if (nights.isEmpty()) {
            item {
                Text(
                    "No nights recorded yet. Press Sleep in the bar below when you end the day, and Wake up in the morning.",
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Figure(dur(perNight.values.average()), "per night", Modifier.weight(1f))
                    Figure(NlParser.formatClock(bedtime.roundToInt()), "to bed", Modifier.weight(1f))
                    Figure(NlParser.formatClock(wakeTime.roundToInt()), "awake", Modifier.weight(1f))
                }
                val span = (sleepFrom..day).toList().takeLast(30)
                Bars(
                    span.map { perNight[it] ?: 0.0 }, MaterialTheme.colorScheme.tertiary,
                    "${perNight.size} ${if (perNight.size == 1) "night" else "nights"} in the last ${span.size} days" +
                        (perNight[day]?.let { " · last night ${dur(it)}" } ?: "") +
                        " · shortest ${dur(perNight.values.min())}, longest ${dur(perNight.values.max())}",
                )
            }
        }

        if (planned.isNotEmpty()) {
            item {
                SectionHeader("Against the plan")
                Text(
                    "${planned.size} planned ${if (planned.size == 1) "activity" else "activities"} finished · " +
                        "planned ${NlParser.formatDuration(plannedMin)}, took ${NlParser.formatDuration(actualMin)}" +
                        if (plannedMin > 0) " (${"%.1f".format(actualMin.toDouble() / plannedMin)}× the plan)" else "",
                    Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        if (estimates.isNotEmpty()) {
            item { SectionHeader("Your estimates") }
            items(estimates) { (title, plan, actual) ->
                StatRow(title, "plan ${dur(plan)} → takes ${dur(actual)}", warn = actual - plan > 5)
            }
        }

        item { SectionHeader("Time entries") }
        if (inRange.isEmpty()) {
            item {
                Text(
                    "Nothing tracked in this period. Start a timer from the bar below, a task, a habit or the plan.",
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(inRange.take(200), key = { it.id }) { e ->
            val start = Instant.ofEpochMilli(e.startMs).atZone(zone)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { editing = e }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(if (e.sleep) MaterialTheme.colorScheme.tertiary else groupColor(groups, e.groupId))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (e.sleep) "Sleep" else e.title.ifBlank { "Untitled" },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (e.endMs == null) FontWeight.SemiBold else null,
                    )
                    Text(
                        (if (days > 1) dayLabel(start.toLocalDate(), today) + " " else "") +
                            NlParser.formatClock(start.hour * 60 + start.minute) +
                            if (e.endMs == null) " · running" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(NlParser.formatDuration(minutes(e).roundToInt()))
            }
        }
    }

    editing?.let { EntryDialog(it, groups, vm) { editing = null } }
}

/** Rename a time entry or file it under a group afterwards. */
@Composable
fun EntryDialog(entry: TimeEntry, groups: List<GroupEntity>, vm: AppViewModel, onClose: () -> Unit) {
    var title by remember { mutableStateOf(entry.title) }
    var groupId by remember { mutableStateOf(entry.groupId) }
    val zone = ZoneId.systemDefault()
    val dayStart = Instant.ofEpochMilli(entry.startMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    fun clock(ms: Long) = NlParser.formatClock(((ms - dayStart) / 60_000).toInt())
    var startText by remember { mutableStateOf(clock(entry.startMs)) }
    var endText by remember { mutableStateOf(entry.endMs?.let(::clock) ?: "") }
    val startMin = NlParser.parseClock(startText)
    val endMin = NlParser.parseClock(endText)
    // An end before the start means the entry ran past midnight.
    val endMs = endMin?.let { dayStart + (if (startMin != null && it <= startMin) it + 1440 else it) * 60_000L }
    val endOk = if (endText.isBlank()) entry.endMs == null else endMin != null
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Time entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        startText, { startText = it }, label = { Text("Started") }, singleLine = true,
                        isError = startMin == null, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        endText, { endText = it }, label = { Text("Ended") }, singleLine = true,
                        placeholder = { Text(if (entry.endMs == null) "running" else "") },
                        isError = !endOk, modifier = Modifier.weight(1f),
                    )
                }
                GroupChips(groups, groupId, "No group") { groupId = it }
                TextButton(onClick = { vm.act { deleteEntry(entry) }; onClose() }) { Text("Delete") }
            }
        },
        confirmButton = {
            Button(enabled = startMin != null && endOk, onClick = {
                val newStart = dayStart + (startMin ?: 0) * 60_000L
                val timesChanged = startText != clock(entry.startMs) || endText != (entry.endMs?.let(::clock) ?: "")
                vm.act {
                    // Keep whatever the timer wrote meanwhile (it may have been stopped).
                    val current = dao.entry(entry.id) ?: return@act
                    saveEntry(current.copy(title = title.trim(), groupId = groupId))
                    if (timesChanged) setEntryTimes(entry.id, newStart, endMs)
                }
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
