@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.thor1or.dayloop.ui

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

/** How the time was actually spent, and how well lengths are being estimated. */
@Composable
fun StatsScreen(vm: AppViewModel) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val finished by vm.finishedBlocks.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val nowMs by rememberNow(30_000)
    val zone = ZoneId.systemDefault()
    val today = remember(nowMs) { LocalDate.now() }
    var days by rememberSaveable { mutableStateOf(1) }
    var editing by remember { mutableStateOf<TimeEntry?>(null) }

    val fromMs = today.minusDays(days - 1L).atStartOfDay(zone).toInstant().toEpochMilli()
    val inRange = entries.filter { it.startMs >= fromMs }
    fun minutes(e: TimeEntry) = ((e.endMs ?: nowMs) - e.startMs) / 60_000.0
    val total = inRange.sumOf { minutes(it) }
    val byGroup = inRange.groupBy { it.groupId }
        .map { (id, list) -> id to list.sumOf { minutes(it) } }
        .sortedByDescending { it.second }

    val day = today.toEpochDay()
    val todayBlocks = finished.filter { it.day == day }
    // Activities that were finished at least twice: is the planned length realistic?
    val estimates = finished
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

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(1 to "Today", 7 to "7 days", 30 to "30 days").forEach { (n, label) ->
                    FilterChip(selected = days == n, onClick = { days = n }, label = { Text(label) })
                }
            }
            Text(
                NlParser.formatDuration(total.roundToInt()),
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                "tracked",
                Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (byGroup.isNotEmpty()) {
            item { SectionHeader("By group") }
            items(byGroup) { (id, mins) ->
                val g = groups.firstOrNull { it.id == id }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row {
                        Text(g?.name ?: "No group", Modifier.weight(1f))
                        Text(NlParser.formatDuration(mins.roundToInt()), color = MaterialTheme.colorScheme.onSurfaceVariant)
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

        if (todayBlocks.isNotEmpty()) {
            item {
                SectionHeader("Today against the plan")
                val planned = todayBlocks.sumOf { it.desiredMin }
                val actual = todayBlocks.sumOf { it.actualEnd!! - it.actualStart!! }
                Text(
                    "${todayBlocks.size} ${if (todayBlocks.size == 1) "activity" else "activities"} finished · planned ${NlParser.formatDuration(planned)}, " +
                        "took ${NlParser.formatDuration(actual)}",
                    Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        if (estimates.isNotEmpty()) {
            item { SectionHeader("Your estimates") }
            items(estimates) { (title, planned, actual) ->
                val diff = (actual - planned).roundToInt()
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "plan ${NlParser.formatDuration(planned.roundToInt())} → takes ${NlParser.formatDuration(actual.roundToInt())}",
                        color = if (diff > 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
        items(inRange, key = { it.id }) { e ->
            val start = Instant.ofEpochMilli(e.startMs).atZone(zone)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { editing = e }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(groupColor(groups, e.groupId))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
