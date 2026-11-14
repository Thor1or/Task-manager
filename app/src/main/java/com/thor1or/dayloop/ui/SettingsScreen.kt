@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.thor1or.dayloop.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thor1or.dayloop.data.Backup
import com.thor1or.dayloop.data.GroupEntity
import com.thor1or.dayloop.data.Repository
import com.thor1or.dayloop.data.ThemeMode
import com.thor1or.dayloop.logic.DateOrder
import com.thor1or.dayloop.logic.NlParser
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs = vm.repo.prefs
    var editingGroup by remember { mutableStateOf<GroupEntity?>(null) }
    var confirmRestore by remember { mutableStateOf<String?>(null) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.act { Backup.write(context, dao, uri); vm.say("Backup saved") }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.act { confirmRestore = Backup.read(context, uri) }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            prefs.update { it.copy(backupFolder = uri.toString(), lastBackupDay = 0) }
            vm.act { Backup.autoBackup(context, dao, prefs); vm.say("Daily backup is on") }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        Text("Settings", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)

        SectionHeader("Groups")
        groups.forEach { g ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { editingGroup = g }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(Color(g.color), 14.dp)
                Spacer(Modifier.width(12.dp))
                Text(g.name)
            }
        }
        TextButton(
            onClick = {
                editingGroup = GroupEntity(name = "", color = Repository.GROUP_COLORS[groups.size % Repository.GROUP_COLORS.size])
            },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text("Add group") }

        SectionHeader("Appearance")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ThemeMode.SYSTEM to "Follow system", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEach { (mode, label) ->
                FilterChip(
                    selected = settings.theme == mode,
                    onClick = { prefs.update { it.copy(theme = mode) } },
                    label = { Text(label) },
                )
            }
        }

        SectionHeader("Dates")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.dateOrder == DateOrder.DMY,
                onClick = { prefs.update { it.copy(dateOrder = DateOrder.DMY) } },
                label = { Text("10/12 = 10 December") },
            )
            FilterChip(
                selected = settings.dateOrder == DateOrder.MDY,
                onClick = { prefs.update { it.copy(dateOrder = DateOrder.MDY) } },
                label = { Text("10/12 = October 12") },
            )
        }

        SectionHeader("Day plan")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClockField("Day starts", settings.dayStart, allowNone = false, Modifier.weight(1f)) { v ->
                if (v != null && v < settings.dayEnd) prefs.update { it.copy(dayStart = v) }
            }
            ClockField("Day ends", settings.dayEnd, allowNone = false, Modifier.weight(1f)) { v ->
                if (v != null && v > settings.dayStart) prefs.update { it.copy(dayEnd = v) }
            }
        }

        SectionHeader("Daily prompts")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClockField("Plan the day", settings.morningPrompt, allowNone = true, Modifier.weight(1f)) { v ->
                prefs.update { it.copy(morningPrompt = v) }
                vm.repo.reminders.prompts(prefs.settings.value)
            }
            ClockField("Review the day", settings.eveningPrompt, allowNone = true, Modifier.weight(1f)) { v ->
                prefs.update { it.copy(eveningPrompt = v) }
                vm.repo.reminders.prompts(prefs.settings.value)
            }
        }
        Text(
            "Leave a prompt empty to turn it off.",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!vm.repo.reminders.canBeExact() && Build.VERSION.SDK_INT >= 31) {
            Text(
                "Reminders may arrive a few minutes late until exact alarms are allowed.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(
                onClick = { context.startActivity(Intent(SystemSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text("Allow exact alarms") }
        }

        SectionHeader("Backup")
        Text(
            if (settings.backupFolder == null) "Your data lives only on this phone. Pick a folder to get a dated copy every day."
            else "A dated copy is written to your chosen folder once a day.",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { folder.launch(null) }) {
                Text(if (settings.backupFolder == null) "Choose backup folder" else "Change backup folder")
            }
            FilledTonalButton(onClick = { export.launch("dayloop-${LocalDate.now()}.json") }) { Text("Export now") }
            FilledTonalButton(onClick = { import.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) {
                Text("Restore from file")
            }
        }
    }

    editingGroup?.let { GroupDialog(it, vm) { editingGroup = null } }
    confirmRestore?.let { json ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Replace everything?") },
            text = { Text("Restoring replaces all tasks, habits, plans and time entries on this phone with the contents of the file.") },
            confirmButton = {
                Button(onClick = {
                    confirmRestore = null
                    vm.act {
                        Backup.restore(dao, json)
                        rescheduleAll()
                        vm.say("Backup restored")
                    }
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }
}

/** A time typed as text ("7", "7:30", "9pm"); saved when it reads as a time. */
@Composable
private fun ClockField(label: String, minute: Int?, allowNone: Boolean, modifier: Modifier, onChange: (Int?) -> Unit) {
    var text by remember(minute) { mutableStateOf(minute?.let(NlParser::formatClock) ?: "") }
    val parsed = NlParser.parseClock(text)
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            val v = NlParser.parseClock(it)
            // Only complete times are saved, so typing "1" on the way to "14:00" changes nothing.
            if (v != null && it.contains(Regex("[:apAP]"))) onChange(v)
            else if (it.isBlank() && allowNone) onChange(null)
        },
        label = { Text(label) },
        singleLine = true,
        isError = if (text.isBlank()) !allowNone else parsed == null,
        placeholder = { Text(if (allowNone) "off" else "07:00") },
        modifier = modifier,
    )
}

@Composable
private fun GroupDialog(group: GroupEntity, vm: AppViewModel, onClose: () -> Unit) {
    var name by remember { mutableStateOf(group.name) }
    var color by remember { mutableStateOf(group.color) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (group.id == 0L) "New group" else "Group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Repository.GROUP_COLORS.forEach { c ->
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (c == color) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                                .padding(3.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .clickable { color = c }
                        )
                    }
                }
                if (group.id != 0L) {
                    TextButton(onClick = { vm.act { deleteGroup(group) }; onClose() }) { Text("Delete group") }
                }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = {
                vm.act { saveGroup(group.copy(name = name.trim(), color = color)) }
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
