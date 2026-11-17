package com.folio.notes.ui.editor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.folio.notes.data.ExportFormat
import com.folio.notes.markdown.ImageBlock
import com.folio.notes.markdown.Inline
import com.folio.notes.markdown.LineKind
import com.folio.notes.markdown.Markdown
import com.folio.notes.markdown.MarkdownEditing
import com.folio.notes.markdown.TableBlock
import com.folio.notes.markdown.TextBlock
import com.folio.notes.ui.common.Dates
import com.folio.notes.ui.common.FolderPickerDialog
import com.folio.notes.ui.common.app
import com.folio.notes.ui.common.rememberVaultPrompt
import java.io.File
import kotlinx.coroutines.launch

private enum class EditorDialog { EXPORT, MOVE, INFO, LINK, SKETCH }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(onBack: () -> Unit, vm: EditorViewModel = viewModel()) {
    val app = app()
    val context = LocalContext.current
    val note by vm.note.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val vault = rememberVaultPrompt()
    val vaultKey by app.vault.key.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    var menu by remember { mutableStateOf(false) }
    var photoMenu by remember { mutableStateOf(false) }
    var formatting by rememberSaveable { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    val base = (17 * settings.textScale).sp
    val readOnly = vm.readOnly

    val leave = { vm.close(); onBack() }
    BackHandler(onBack = leave)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    LaunchedEffect(vm.missing) { if (vm.missing) onBack() }

    // ---- System pickers ---------------------------------------------------------------------------------
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::addImage) }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let(vm::addImage) }
    var saving by remember { mutableStateOf<ExportFormat?>(null) }
    val saveContract = remember {
        object : ActivityResultContracts.CreateDocument("*/*") {
            var mime = "*/*"
            override fun createIntent(context: Context, input: String) = super.createIntent(context, input).setType(mime)
        }
    }
    val saveFile = rememberLauncherForActivityResult(saveContract) { uri ->
        val format = saving
        if (uri != null && format != null) scope.launch {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)!!.use { vm.export(format, it) } }.isSuccess
            snackbar.showSnackbar(if (ok) "Saved" else "Couldn't save the file")
        }
        saving = null
    }
    fun launch(block: () -> Unit) {
        app.holdVaultOnce()
        try { block() } catch (e: ActivityNotFoundException) {
            app.onReturned()
            scope.launch { snackbar.showSnackbar("No app available for that") }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (vm.loaded && !vm.needsUnlock && !readOnly) {
                        IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                        IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    }
                    if (vm.loaded && !vm.needsUnlock) {
                        IconButton(onClick = { dialog = EditorDialog.EXPORT }) { Icon(Icons.Outlined.Share, "Share and export") }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            if (readOnly) DropdownMenuItem({ Text("Recover") }, { menu = false; vm.restore() })
                            else {
                                DropdownMenuItem({ Text(if (note?.pinned == true) "Unpin Note" else "Pin Note") }, { menu = false; vm.togglePin() })
                                DropdownMenuItem({ Text(if (note?.locked == true) "Remove Lock" else "Lock Note") }, {
                                    menu = false
                                    vault.withKey { key -> if (note?.locked == true) vm.removeLock(key) else vm.lock(key) }
                                })
                                if (note?.locked == true && vaultKey != null) DropdownMenuItem({ Text("Lock Now") }, { menu = false; vm.lockNow() })
                                DropdownMenuItem({ Text("Move") }, { menu = false; dialog = EditorDialog.MOVE })
                            }
                            DropdownMenuItem({ Text("Note Info") }, { menu = false; dialog = EditorDialog.INFO })
                            if (!readOnly) DropdownMenuItem({ Text("Delete", color = MaterialTheme.colorScheme.error) }, {
                                menu = false
                                focusManager.clearFocus()
                                vm.trash(onBack)
                            })
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (vm.loaded && !vm.needsUnlock && !readOnly) Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val current = vm.currentText?.value
                    AnimatedVisibility(formatting) {
                        Column {
                            val kind = current?.let(MarkdownEditing::currentKind)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Title" to LineKind.H1, "Heading" to LineKind.H2, "Subheading" to LineKind.H3, "Body" to LineKind.BODY).forEach { (label, k) ->
                                    FilterChip(kind == k, onClick = { vm.format { MarkdownEditing.setLineKind(it, k) } }, label = { Text(label) })
                                }
                                FilterChip(kind == LineKind.CODE || kind == LineKind.FENCE, onClick = { vm.format(MarkdownEditing::toggleCodeBlock) }, label = { Text("Monospaced") })
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                                fun active(type: Inline) = current != null && MarkdownEditing.isActive(current, type)
                                Tool(Icons.Filled.FormatBold, "Bold", active(Inline.BOLD)) { vm.format { MarkdownEditing.toggleInline(it, Inline.BOLD) } }
                                Tool(Icons.Filled.FormatItalic, "Italic", active(Inline.ITALIC)) { vm.format { MarkdownEditing.toggleInline(it, Inline.ITALIC) } }
                                Tool(Icons.Filled.FormatUnderlined, "Underline", active(Inline.UNDERLINE)) { vm.format { MarkdownEditing.toggleInline(it, Inline.UNDERLINE) } }
                                Tool(Icons.Filled.StrikethroughS, "Strikethrough", active(Inline.STRIKE)) { vm.format { MarkdownEditing.toggleInline(it, Inline.STRIKE) } }
                                Tool(Icons.Filled.Highlight, "Highlight", active(Inline.HIGHLIGHT)) { vm.format { MarkdownEditing.toggleInline(it, Inline.HIGHLIGHT) } }
                                Tool(Icons.Filled.Code, "Inline code", active(Inline.CODE)) { vm.format { MarkdownEditing.toggleInline(it, Inline.CODE) } }
                                Tool(Icons.Filled.Link, "Link") { dialog = EditorDialog.LINK }
                                Tool(Icons.AutoMirrored.Filled.FormatListBulleted, "Bulleted list", kind == LineKind.BULLET) { vm.format { MarkdownEditing.setLineKind(it, LineKind.BULLET) } }
                                Tool(Icons.Filled.FormatListNumbered, "Numbered list", kind == LineKind.NUMBERED) { vm.format { MarkdownEditing.setLineKind(it, LineKind.NUMBERED) } }
                                Tool(Icons.Filled.FormatQuote, "Quote", kind == LineKind.QUOTE) { vm.format { MarkdownEditing.setLineKind(it, LineKind.QUOTE) } }
                                Tool(Icons.AutoMirrored.Filled.FormatIndentDecrease, "Outdent") { vm.format { MarkdownEditing.indent(it, -1) } }
                                Tool(Icons.AutoMirrored.Filled.FormatIndentIncrease, "Indent") { vm.format { MarkdownEditing.indent(it, 1) } }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val kind = current?.let(MarkdownEditing::currentKind)
                        Tool(Icons.Filled.Checklist, "Checklist", kind == LineKind.TODO || kind == LineKind.DONE) { vm.format { MarkdownEditing.setLineKind(it, LineKind.TODO) } }
                        Tool(Icons.Filled.TextFormat, "Format", formatting) { formatting = !formatting }
                        Tool(Icons.Outlined.GridOn, "Table") { vm.insertTable() }
                        Box {
                            Tool(Icons.Outlined.Image, "Add picture") { photoMenu = true }
                            DropdownMenu(photoMenu, { photoMenu = false }) {
                                DropdownMenuItem({ Text("Choose Photo") }, {
                                    photoMenu = false
                                    launch { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                                }, leadingIcon = { Icon(Icons.Outlined.Image, null) })
                                DropdownMenuItem({ Text("Take Photo") }, {
                                    photoMenu = false
                                    val file = File(File(context.cacheDir, "shared").apply { mkdirs() }, "camera-${System.currentTimeMillis()}.jpg")
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                    cameraUri = uri
                                    launch { takePhoto.launch(uri) }
                                }, leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null) })
                            }
                        }
                        Tool(Icons.Filled.Draw, "Sketch") { dialog = EditorDialog.SKETCH }
                        Spacer(Modifier.weight(1f))
                        if (hasFocus) Tool(Icons.Filled.KeyboardHide, "Done editing") { focusManager.clearFocus(); keyboard?.hide() }
                    }
                }
            }
        },
    ) { inner ->
        when {
            !vm.loaded -> Box(Modifier.fillMaxSize())
            vm.needsUnlock -> Column(
                Modifier.fillMaxSize().padding(inner).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
                Text("This note is locked.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 16.dp))
                Button(onClick = { vault.withKey { } }) { Text("View Note") }
            }
            else -> Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (note?.pinned == true) Icon(Icons.Filled.PushPin, "Pinned", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(end = 4.dp).size(14.dp))
                    if (note?.locked == true) Icon(Icons.Outlined.Lock, "Locked", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp).size(14.dp))
                    Text(
                        Dates.long(note?.modifiedAt ?: System.currentTimeMillis()),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                }
                if (readOnly) Text(
                    "This note is in Recently Deleted. Recover it to edit.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                )
                val firstTextId = vm.blocks.firstOrNull { it is TextBlock }?.id
                vm.blocks.forEach { block ->
                    key(block.id) {
                        when (block) {
                            is TextBlock -> {
                                val requester = remember { FocusRequester() }
                                val request = vm.focusRequest
                                if (request?.blockId == block.id) LaunchedEffect(request) { runCatching { requester.requestFocus() }; keyboard?.show() }
                                MarkdownField(
                                    value = block.value,
                                    onValueChange = { vm.onTextChange(block.id, it) },
                                    titleFirstLine = block.id == firstTextId && vm.blocks.first().id == block.id,
                                    syntax = settings.syntax,
                                    base = base,
                                    readOnly = readOnly,
                                    onToggleCheckbox = { vm.toggleCheckbox(block.id, it) },
                                    onOpenLink = { url -> launch { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                                    modifier = Modifier.fillMaxWidth().focusRequester(requester).testTag("text")
                                        .onFocusChanged { if (it.isFocused) { vm.focusedId = block.id; hasFocus = true } else if (vm.focusedId == block.id) hasFocus = false },
                                )
                            }
                            is ImageBlock -> ImageBlockView(block, readOnly) { vm.removeBlock(block.id) }
                            is TableBlock -> TableBlockView(block, readOnly, base, vm)
                        }
                    }
                }
                // The empty space under the note puts the caret at its end.
                Box(
                    Modifier.fillMaxWidth().height(280.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !readOnly) { vm.focusEnd() },
                )
            }
        }
    }

    when (dialog) {
        EditorDialog.EXPORT -> ExportDialog(
            hasPictures = Markdown.attachments(vm.markdown).isNotEmpty(),
            onShare = { format ->
                dialog = null
                scope.launch {
                    val intent = if (format == ExportFormat.TEXT) {
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Markdown.toPlainText(vm.markdown)).putExtra(Intent.EXTRA_SUBJECT, vm.title)
                    } else {
                        val target = vm.exportTarget(format)
                        val file = File(File(context.cacheDir, "shared").apply { deleteRecursively(); mkdirs() }, target.fileName)
                        file.outputStream().use { vm.export(format, it) }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        Intent(Intent.ACTION_SEND).setType(target.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    launch { context.startActivity(Intent.createChooser(intent, "Share note")) }
                }
            },
            onSave = { format ->
                dialog = null
                val target = vm.exportTarget(format)
                saving = format
                saveContract.mime = target.mime
                launch { saveFile.launch(target.fileName) }
            },
            onPrint = { dialog = null; launch { print(context, vm.title, vm.html()) } },
            onCopy = { dialog = null; clipboard.setText(AnnotatedString(vm.markdown)); scope.launch { snackbar.showSnackbar("Markdown copied") } },
            onDismiss = { dialog = null },
        )
        EditorDialog.MOVE -> FolderPickerDialog("Move note to", folders, onPick = { it?.let(vm::moveTo); dialog = null }, onDismiss = { dialog = null })
        EditorDialog.INFO -> {
            val text = Markdown.toPlainText(vm.markdown, linkTargets = false)
            val folder = folders.firstOrNull { it.id == vm.currentFolderId }?.name ?: "Notes"
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Note Info") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoLine("Folder", folder)
                        note?.let { InfoLine("Created", Dates.long(it.createdAt)); InfoLine("Edited", Dates.long(it.modifiedAt)) }
                        InfoLine("Words", Markdown.wordCount(vm.markdown).toString())
                        InfoLine("Characters", text.count { !it.isWhitespace() }.toString())
                        InfoLine("Tags", Markdown.tags(vm.markdown).joinToString(" ") { "#$it" }.ifEmpty { "None" })
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } },
            )
        }
        EditorDialog.LINK -> LinkDialog(
            initialLabel = vm.currentText?.value?.let { it.text.substring(it.selection.min, it.selection.max) }.orEmpty().lineSequence().firstOrNull().orEmpty(),
            onConfirm = { label, url -> dialog = null; vm.format { MarkdownEditing.insertLink(it, label, url) } },
            onDismiss = { dialog = null },
        )
        EditorDialog.SKETCH -> SketchDialog(onDone = { dialog = null; vm.addImage(it) }, onDismiss = { dialog = null })
        null -> {}
    }
}

@Composable
private fun Tool(icon: ImageVector, description: String, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(if (active) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, description, tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value)
    }
}

@Composable
private fun LinkDialog(initialLabel: String, onConfirm: (label: String, url: String) -> Unit, onDismiss: () -> Unit) {
    var label by rememberSaveable { mutableStateOf(initialLabel) }
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("Address") }, modifier = Modifier.fillMaxWidth().testTag("linkUrl"))
                OutlinedTextField(label, { label = it }, singleLine = true, label = { Text("Text (optional)") }, modifier = Modifier.fillMaxWidth().testTag("linkLabel"))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(label, url) }, enabled = url.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExportDialog(
    hasPictures: Boolean,
    onShare: (ExportFormat) -> Unit,
    onSave: (ExportFormat) -> Unit,
    onPrint: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    var format by rememberSaveable { mutableStateOf(ExportFormat.MARKDOWN) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share & Export") },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExportFormat.entries.forEach { f -> FilterChip(format == f, onClick = { format = f }, label = { Text(f.label) }) }
                }
                Text(
                    when (format) {
                        ExportFormat.MARKDOWN -> if (hasPictures) "A .zip with the note as Markdown and its pictures." else "A .md file any Markdown app can open."
                        ExportFormat.TEXT -> "The words only, without formatting."
                        ExportFormat.HTML -> "A single web page with the pictures inside it."
                        ExportFormat.PDF -> "Pages laid out for reading or printing."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
                DialogAction("Share…") { onShare(format) }
                DialogAction("Save to Device…") { onSave(format) }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                DialogAction("Print…", onPrint)
                DialogAction("Copy as Markdown", onCopy)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DialogAction(label: String, onClick: () -> Unit) {
    Text(
        label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 11.dp, horizontal = 4.dp),
    )
}

/** Hands the note to the system print dialog, which can also save it as a PDF. */
private fun print(context: Context, title: String, html: String) {
    val web = WebView(context)
    web.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            (context.getSystemService(Context.PRINT_SERVICE) as PrintManager)
                .print(title, view.createPrintDocumentAdapter(title), PrintAttributes.Builder().build())
        }
    }
    web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
}
