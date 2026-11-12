package com.jotter.notes.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jotter.notes.JotterApp
import com.jotter.notes.data.Settings
import com.jotter.notes.data.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as JotterApp
    val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var themeDialog by remember { mutableStateOf(false) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                val json = app.backup.export()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) }
                }
            }.isSuccess
            snackbar.showSnackbar(if (ok) "Backup saved" else "Couldn't save backup")
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val count = runCatching {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                }
                app.backup.import(json)
            }.getOrNull()
            snackbar.showSnackbar(
                when (count) {
                    null -> "That file isn't a Jotter backup"
                    1 -> "1 note imported"
                    else -> "$count notes imported"
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Navigate up") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Header("Display")
            ListItem(
                headlineContent = { Text("Theme") },
                supportingContent = { Text(settings.theme.label) },
                modifier = Modifier.clickable { themeDialog = true },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Toggle("Use wallpaper colors", "Match the app colors to your wallpaper", settings.dynamicColor) {
                    scope.launch { app.settings.setDynamicColor(it) }
                }
            }
            Toggle("Sort by last edited", "Otherwise newest notes come first", settings.sortByModified) {
                scope.launch { app.settings.setSortByModified(it) }
            }
            HorizontalDivider()
            Header("Lists")
            Toggle("Add new items to bottom", null, settings.newItemsAtBottom) {
                scope.launch { app.settings.setNewItemsAtBottom(it) }
            }
            HorizontalDivider()
            Header("Backup")
            ListItem(
                headlineContent = { Text("Export notes") },
                supportingContent = { Text("Save all notes, lists, labels and images to a file") },
                modifier = Modifier.clickable { export.launch("jotter-backup-${LocalDate.now()}.json") },
            )
            ListItem(
                headlineContent = { Text("Import notes") },
                supportingContent = { Text("Add the notes from a backup file to this device") },
                modifier = Modifier.clickable { import.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Your notes stay on this device") },
                supportingContent = { Text("Jotter works fully offline and has no internet permission.") },
            )
        }
    }

    if (themeDialog) {
        AlertDialog(
            onDismissRequest = { themeDialog = false },
            title = { Text("Theme") },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        ListItem(
                            headlineContent = { Text(mode.label) },
                            leadingContent = { RadioButton(selected = settings.theme == mode, onClick = null) },
                            modifier = Modifier.clickable {
                                scope.launch { app.settings.setTheme(mode) }
                                themeDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { themeDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}
