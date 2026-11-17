package com.folio.notes.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.data.Folder
import com.folio.notes.data.Settings
import com.folio.notes.data.ThemeMode
import com.folio.notes.markdown.SyntaxMode
import com.folio.notes.ui.common.PasswordDialog
import com.folio.notes.ui.common.app
import com.folio.notes.ui.common.rememberVaultPrompt
import java.time.LocalDate
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface PasswordStep {
    data object Create : PasswordStep
    data object ChangeOld : PasswordStep
    data class ChangeNew(val old: SecretKey) : PasswordStep
    data object TurnOff : PasswordStep
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = app()
    val context = LocalContext.current
    val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val vault = rememberVaultPrompt()
    var step by remember { mutableStateOf<PasswordStep?>(null) }
    val hasPassword = settings.vaultSalt != null

    val exportAll = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching { context.contentResolver.openOutputStream(uri)!!.use { app.exporter.exportAll(it, app.vault.key.value) } }.getOrNull()
            snackbar.showSnackbar(
                when {
                    result == null -> "Couldn't write the backup"
                    result.second > 0 -> "Exported ${result.first} notes. ${result.second} locked notes were left out."
                    else -> "Exported ${result.first} notes"
                }
            )
        }
    }
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            var notes = 0
            var failed = 0
            for (uri in uris) runCatching {
                val name = displayName(context, uri).lowercase()
                if (name.endsWith(".zip") || context.contentResolver.getType(uri) == "application/zip") {
                    notes += context.contentResolver.openInputStream(uri)!!.use { app.exporter.importZip(it, app.vault.key.value) }.notes
                } else {
                    val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) } }
                    if (app.exporter.importText(text, Folder.DEFAULT_ID) != null) notes++
                }
            }.onFailure { failed++ }
            snackbar.showSnackbar(
                (if (notes == 1) "Imported 1 note" else "Imported $notes notes") + if (failed > 0) ". $failed files couldn't be read." else ""
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Group("Appearance") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Theme")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEach { (mode, label) ->
                            FilterChip(settings.theme == mode, onClick = { scope.launch { app.settings.setTheme(mode) } }, label = { Text(label) })
                        }
                    }
                }
                Divider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Note text size")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("A", fontSize = 13.sp)
                        Slider(
                            settings.textScale, { scope.launch { app.settings.setTextScale(it) } },
                            valueRange = 0.85f..1.45f, steps = 5, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        Text("A", fontSize = 22.sp)
                    }
                }
            }
            Group("Markdown", "Notes are stored as Markdown. Choose how much of the syntax the editor shows.") {
                listOf(
                    SyntaxMode.HIDE to "Hide syntax",
                    SyntaxMode.CURRENT_LINE to "Show on the line being edited",
                    SyntaxMode.SHOW to "Always show",
                ).forEachIndexed { i, (mode, label) ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().clickable { scope.launch { app.settings.setSyntax(mode) } }.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(settings.syntax == mode, onClick = { scope.launch { app.settings.setSyntax(mode) } })
                        Text(label)
                    }
                }
            }
            Group(
                "Locked Notes",
                "Locked notes and their pictures are encrypted with your password. It is not stored and cannot be reset: without it, locked notes are unreadable.",
            ) {
                if (!hasPassword) Action("Set Password") { step = PasswordStep.Create }
                else {
                    Action("Change Password") { step = PasswordStep.ChangeOld }
                    Divider()
                    Action("Turn Off Password", MaterialTheme.colorScheme.error) { step = PasswordStep.TurnOff }
                }
            }
            Group("Backup", "The export is a .zip of Markdown files arranged in your folders, with pictures alongside. Importing adds to the notes you already have.") {
                Action("Export All Notes") {
                    val go = { app.holdVaultOnce(); exportAll.launch("Folio Notes ${LocalDate.now()}.zip") }
                    scope.launch { if (app.repository.hasLockedNotes() && !app.vault.isOpen) vault.withKey { go() } else go() }
                }
                Divider()
                Action("Import Notes") { app.holdVaultOnce(); importFiles.launch(arrayOf("application/zip", "text/*", "application/octet-stream")) }
            }
            Text(
                "Folio keeps everything on this device. It has no account and no internet permission.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
            )
        }
    }

    when (val s = step) {
        PasswordStep.Create -> PasswordDialog(
            "Create a password", "You will need this password to open locked notes. It cannot be recovered if you forget it.", confirmField = true,
            onSubmit = { app.vault.setPassword(it); step = null; null }, onDismiss = { step = null },
        )
        PasswordStep.ChangeOld -> PasswordDialog(
            "Change password", "Enter your current password.", confirmField = false,
            onSubmit = { pw -> app.vault.keyFor(pw)?.let { step = PasswordStep.ChangeNew(it); null } ?: "Wrong password" }, onDismiss = { step = null },
        )
        is PasswordStep.ChangeNew -> PasswordDialog(
            "New password", "Locked notes are re-encrypted with the new password.", confirmField = true,
            onSubmit = { pw ->
                val prepared = app.vault.prepare(pw)
                app.repository.rekey(s.old, prepared.key)
                app.vault.adopt(prepared)
                step = null
                snackbar.showSnackbar("Password changed")
                null
            },
            onDismiss = { step = null },
        )
        PasswordStep.TurnOff -> PasswordDialog(
            "Turn off password", "Every locked note is unlocked and stored without encryption. Enter your password to continue.", confirmField = false,
            onSubmit = { pw ->
                val key = app.vault.keyFor(pw)
                if (key == null) "Wrong password" else { app.repository.rekey(key, null); app.vault.clearPassword(); step = null; null }
            },
            onDismiss = { step = null },
        )
        null -> {}
    }
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

@Composable
private fun Group(title: String, footer: String? = null, content: @Composable () -> Unit) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) { Column { content() } }
    if (footer != null) Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun Action(label: String, color: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Text(label, color = color, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp))
}
