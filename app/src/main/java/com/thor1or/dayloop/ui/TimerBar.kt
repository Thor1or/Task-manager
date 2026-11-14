@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.thor1or.dayloop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

/** Always visible above the tabs: the running timer, or a way to start one. */
@Composable
fun TimerBar(vm: AppViewModel, onPick: () -> Unit) {
    val running by vm.running.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf(false) }
    running?.let { if (editing) EntryDialog(it, groups, vm) { editing = false } }

    Surface(tonalElevation = 6.dp) {
        val e = running
        if (e == null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onPick)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Start a timer", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val now by rememberNow(1000)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(groupColor(groups, e.groupId))
                Spacer(Modifier.width(10.dp))
                // Tap to correct when it started, rename it or change its group.
                Column(Modifier.weight(1f).clickable { editing = true }) {
                    Text(e.title.ifBlank { "Timer" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        elapsedLabel(now - e.startMs),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(onClick = { vm.act { stopTimer(complete = false) } }) { Text("Stop") }
                if (e.taskId != null || e.habitId != null) {
                    Button(onClick = { vm.act { stopTimer(complete = true) } }) { Text("Done") }
                }
            }
        }
    }
}

private data class Pick(
    val title: String,
    val hint: String,
    val taskId: Long? = null,
    val habitId: Long? = null,
    val groupId: Long? = null,
    val blockId: Long? = null,
)

/** Choose what to time: something from today's plan, a habit, a task, or free text. */
@Composable
fun TimerPicker(vm: AppViewModel, onClose: () -> Unit) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val habits by vm.habits.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    var groupId by remember { mutableStateOf<Long?>(null) }
    val today = LocalDate.now()
    val day = today.toEpochDay()
    var planned by remember { mutableStateOf<List<Pick>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        planned = vm.repo.dao.blocksNow(day).filter { it.actualStart == null }.map {
            Pick(it.title, "On today's plan", it.taskId, it.habitId, it.groupId, it.id)
        }
    }

    val picks = remember(planned, tasks, habits, entries) {
        val onPlanTasks = planned.mapNotNull { it.taskId }.toSet()
        val onPlanHabits = planned.mapNotNull { it.habitId }.toSet()
        val bit = 1 shl (today.dayOfWeek.value - 1)
        planned +
            habits.filter { it.daysMask and bit != 0 && it.id !in onPlanHabits }
                .map { Pick(it.name, "Habit", habitId = it.id, groupId = it.groupId) } +
            tasks.filter { !it.done && it.dueDay != null && it.dueDay <= day && it.id !in onPlanTasks }
                .map { Pick(it.title, "Task", taskId = it.id, groupId = it.groupId) } +
            entries.filter { it.taskId == null && it.habitId == null && it.title.isNotBlank() }
                .distinctBy { it.title.lowercase() }.take(8)
                .map { Pick(it.title, "Recent", groupId = it.groupId) }
    }

    fun start(p: Pick) {
        vm.act { startTimer(p.title, p.taskId, p.habitId, p.groupId, p.blockId) }
        onClose()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Start a timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("What are you doing?") },
                    placeholder = { Text("Reading #home") },
                    modifier = Modifier.fillMaxWidth(),
                )
                GroupChips(groups, groupId, "No group") { groupId = it }
                val shown = picks.filter { text.isBlank() || it.title.contains(text.trim(), ignoreCase = true) }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(shown) { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { start(p) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Dot(groupColor(groups, p.groupId))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    p.hint,
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
            Button(
                enabled = text.isNotBlank(),
                onClick = {
                    // "#name" or "@name" in the text files the entry under that group.
                    val tag = Regex("(?:^|\\s)[#@]([\\p{L}\\p{N}_-]+)").find(text)
                    val title = (if (tag != null) text.removeRange(tag.range) else text).trim().replace(Regex("\\s+"), " ")
                    val chosen = groupId
                    vm.act { startTimer(title.ifBlank { text.trim() }, groupId = groupIdFor(tag?.groupValues?.get(1)) ?: chosen) }
                    onClose()
                },
            ) { Text("Start") }
        },
        dismissButton = { OutlinedButton(onClick = onClose) { Text("Cancel") } },
    )
}
