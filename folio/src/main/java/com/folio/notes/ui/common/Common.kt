package com.folio.notes.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.FolioApp
import com.folio.notes.data.Folder
import android.graphics.BitmapFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun app(): FolioApp = LocalContext.current.applicationContext as FolioApp

// ---- Dates ---------------------------------------------------------------------------------------------------

object Dates {
    private fun date(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    /** Compact date for a list row: the time today, the weekday this week, otherwise a short date. */
    fun short(millis: Long, now: Long = System.currentTimeMillis()): String {
        val d = date(millis)
        val today = date(now)
        val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return when {
            d == today -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(zoned)
            d == today.minusDays(1) -> "Yesterday"
            d.isAfter(today.minusDays(7)) && !d.isAfter(today) -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).format(zoned)
        }
    }

    fun long(millis: Long): String {
        val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).format(zoned) + " at " + DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(zoned)
    }

    /** The heading a note falls under when the list is grouped by date. */
    fun bucket(millis: Long, now: Long = System.currentTimeMillis()): String {
        val d = date(millis)
        val today = date(now)
        return when {
            !d.isBefore(today) -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            d.isAfter(today.minusDays(8)) -> "Previous 7 Days"
            d.isAfter(today.minusDays(31)) -> "Previous 30 Days"
            d.year == today.year -> d.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
            else -> d.year.toString()
        }
    }
}

// ---- Folders -------------------------------------------------------------------------------------------------

/** Folders in display order – the built-in one first, children under their parents – each with its depth. */
fun folderTree(folders: List<Folder>): List<Pair<Folder, Int>> {
    val out = ArrayList<Pair<Folder, Int>>()
    val ids = folders.map { it.id }.toSet()
    fun add(parent: Long?, depth: Int) {
        folders.filter { it.parentId == parent || (parent == null && it.parentId != null && it.parentId !in ids) }
            .sortedWith(compareBy<Folder> { it.id != Folder.DEFAULT_ID }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .forEach { if (out.none { o -> o.first.id == it.id } && depth < 12) { out += it to depth; add(it.id, depth + 1) } }
    }
    add(null, 0)
    return out
}

@Composable
fun FolderPickerDialog(
    title: String,
    folders: List<Folder>,
    exclude: Set<Long> = emptySet(),
    topLevelOption: Boolean = false,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (topLevelOption) item {
                    FolderPickRow("Top level", 0) { onPick(null) }
                }
                items(folderTree(folders).filter { it.first.id !in exclude }) { (folder, depth) ->
                    FolderPickRow(folder.name, depth) { onPick(folder.id) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FolderPickRow(name: String, depth: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("pickFolder").padding(start = (depth * 20).dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge)
    }
}

// ---- Dialogs -------------------------------------------------------------------------------------------------

@Composable
fun TextPromptDialog(
    title: String,
    initial: String = "",
    label: String = "Name",
    confirm: String = "Save",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val submit = { if (value.text.isNotBlank()) onConfirm(value.text.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value, { value = it }, singleLine = true, label = { Text(label) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("prompt"),
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        },
        confirmButton = { TextButton(onClick = submit, enabled = value.text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Asks for a password. [onSubmit] returns an error to show, or null when the password was accepted (the
 * caller then closes the dialog). With [confirmField] the password has to be typed twice.
 */
@Composable
fun PasswordDialog(
    title: String,
    message: String,
    confirmField: Boolean,
    onSubmit: suspend (String) -> String?,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val submit: () -> Unit = {
        when {
            busy -> {}
            password.isEmpty() -> error = "Enter a password"
            confirmField && password != repeat -> error = "The passwords don't match"
            else -> { busy = true; scope.launch { error = onSubmit(password); busy = false } }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    password, { password = it; error = null }, singleLine = true, label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (confirmField) ImeAction.Next else ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("password"),
                )
                if (confirmField) OutlinedTextField(
                    repeat, { repeat = it; error = null }, singleLine = true, label = { Text("Repeat password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("passwordRepeat"),
                )
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = submit, enabled = !busy) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Runs actions that need the locked-notes key, asking for the password (or for a new one) first if necessary. */
class VaultPrompt {
    internal var pending by mutableStateOf<((SecretKey) -> Unit)?>(null)
    internal var setup by mutableStateOf(false)
    internal var ask: ((SecretKey) -> Unit) -> Unit = {}
    fun withKey(action: (SecretKey) -> Unit) = ask(action)
}

@Composable
fun rememberVaultPrompt(): VaultPrompt {
    val vault = app().vault
    val scope = rememberCoroutineScope()
    val prompt = remember { VaultPrompt() }
    prompt.ask = { action ->
        val key = vault.key.value
        if (key != null) action(key)
        else scope.launch { prompt.setup = !vault.hasPassword(); prompt.pending = action }
    }
    val action = prompt.pending
    if (action != null) {
        if (prompt.setup) PasswordDialog(
            title = "Create a password",
            message = "Locked notes are encrypted with this password. It is not stored anywhere: if you forget it, locked notes cannot be recovered.",
            confirmField = true,
            onSubmit = { pw -> val key = vault.setPassword(pw); prompt.pending = null; action(key); null },
            onDismiss = { prompt.pending = null },
        ) else PasswordDialog(
            title = "Locked notes",
            message = "Enter your notes password.",
            confirmField = false,
            onSubmit = { pw ->
                if (vault.open(pw)) { prompt.pending = null; action(vault.key.value!!); null } else "Wrong password"
            },
            onDismiss = { prompt.pending = null },
        )
    }
    return prompt
}

// ---- Pictures ------------------------------------------------------------------------------------------------

/** Shows an attached picture, decrypting it in memory when it belongs to a locked note. */
@Composable
fun AttachmentImage(name: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit, maxSize: Int = 1600) {
    val app = app()
    val key by app.vault.key.collectAsStateWithLifecycle()
    val bitmap by produceState<android.graphics.Bitmap?>(null, name, key) {
        value = withContext(Dispatchers.IO) {
            app.repository.attachments.read(name, key)?.let { bytes ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSize) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }
    val b = bitmap
    if (b != null) Image(b.asImageBitmap(), contentDescription = "Picture", modifier = modifier, contentScale = contentScale)
    else Box(modifier)
}
