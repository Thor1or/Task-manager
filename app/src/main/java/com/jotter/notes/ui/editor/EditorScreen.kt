package com.jotter.notes.ui.editor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.outlined.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddAlert
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jotter.notes.ui.components.ColorPickerRow
import com.jotter.notes.ui.components.ConfirmDialog
import com.jotter.notes.ui.components.LabelPicker
import com.jotter.notes.ui.components.NoteChips
import com.jotter.notes.ui.components.ReminderDialog
import com.jotter.notes.ui.theme.NoteColors
import com.jotter.notes.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(onBack: () -> Unit, vm: EditorViewModel = viewModel(factory = EditorViewModel.Factory)) {
    val state = vm.state
    val context = LocalContext.current
    val allLabels by vm.allLabels.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var showColors by rememberSaveable { mutableStateOf(false) }
    var showLabels by rememberSaveable { mutableStateOf(false) }
    var showReminder by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addImage(uri)
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val launchPicker = {
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    LaunchedEffect(state.loaded, state.missing) {
        if (state.missing) onBack()
        else if (state.loaded && vm.consumePickImage()) launchPicker()
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // Persist as soon as the app is backgrounded; the process may be killed afterwards.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    BackHandler(onBack = onBack)

    val bg = NoteColors.background(state.color)
    val fg = NoteColors.onBackground(state.color)
    val readOnly = state.trashed

    Scaffold(
        containerColor = bg,
        contentColor = fg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bg, navigationIconContentColor = fg, actionIconContentColor = fg,
                ),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Navigate up") } },
                actions = {
                    if (!readOnly && state.loaded) {
                        IconButton(onClick = vm::togglePinned) {
                            if (state.pinned) Icon(Icons.Filled.PushPin, "Unpin") else Icon(Icons.Outlined.PushPin, "Pin")
                        }
                        IconButton(onClick = { showReminder = true }) { Icon(Icons.Outlined.AddAlert, "Reminder") }
                        IconButton(onClick = { vm.toggleArchived(onBack) }) {
                            if (state.archived) Icon(Icons.Outlined.Unarchive, "Unarchive") else Icon(Icons.Outlined.Archive, "Archive")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = bg, contentColor = fg) {
                Row(
                    Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (readOnly) {
                        Text("Note in Trash", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.restore() }) {
                            Icon(Icons.Outlined.RestoreFromTrash, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Restore")
                        }
                        TextButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.DeleteForever, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Delete forever")
                        }
                    } else {
                        var addMenu by remember { mutableStateOf(false) }
                        var moreMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { addMenu = true }) { Icon(Icons.Outlined.AddBox, "Add") }
                            DropdownMenu(addMenu, onDismissRequest = { addMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Add image") },
                                    leadingIcon = { Icon(Icons.Outlined.Image, null) },
                                    onClick = { addMenu = false; launchPicker() },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (state.isChecklist) "Hide checkboxes" else "Checkboxes") },
                                    leadingIcon = { Icon(Icons.Outlined.CheckBox, null) },
                                    onClick = { addMenu = false; vm.toggleChecklist() },
                                )
                            }
                        }
                        IconButton(onClick = { showColors = true }) { Icon(Icons.Outlined.Palette, "Color") }
                        Text(
                            if (state.updatedAt > 0) "Edited ${Format.edited(state.updatedAt)}" else "",
                            Modifier.weight(1f).padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                        IconButton(onClick = vm::undo, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                        IconButton(onClick = vm::redo, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                        Box {
                            IconButton(onClick = { moreMenu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                            DropdownMenu(moreMenu, onDismissRequest = { moreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                    onClick = { moreMenu = false; vm.trash(onBack) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Make a copy") },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                    onClick = { moreMenu = false; vm.duplicate() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Send") },
                                    leadingIcon = { Icon(Icons.Outlined.Share, null) },
                                    onClick = {
                                        moreMenu = false
                                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                            .putExtra(Intent.EXTRA_SUBJECT, state.title)
                                            .putExtra(Intent.EXTRA_TEXT, state.shareText())
                                        context.startActivity(Intent.createChooser(send, null))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Labels") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, null) },
                                    onClick = { moreMenu = false; showLabels = true },
                                )
                                if (state.isChecklist && state.items.any { it.checked }) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Uncheck all items") },
                                        onClick = { moreMenu = false; vm.uncheckAll() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete checked items") },
                                        onClick = { moreMenu = false; vm.deleteChecked() },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            state.images.forEach { image ->
                key(image.id) {
                    Box {
                        AsyncImage(
                            model = vm.imageFile(image),
                            contentDescription = "Note image",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (!readOnly) {
                            IconButton(
                                onClick = { vm.removeImage(image) },
                                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp)
                                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                            ) { Icon(Icons.Outlined.Close, "Remove image", tint = Color.White) }
                        }
                    }
                }
            }
            val titleStyle = MaterialTheme.typography.titleLarge.copy(color = fg)
            val bodyStyle = MaterialTheme.typography.bodyLarge.copy(color = fg)

            PlainField(
                value = state.title,
                onValueChange = vm::setTitle,
                placeholder = "Title",
                style = titleStyle,
                readOnly = readOnly,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("title"),
            )
            if (state.isChecklist) {
                Checklist(state, vm, bodyStyle, fg, bg, readOnly)
            } else {
                val focus = remember { FocusRequester() }
                LaunchedEffect(state.focusTarget) {
                    if (state.focusTarget == EditorState.FOCUS_CONTENT) {
                        focus.requestFocus()
                        vm.focusHandled()
                    }
                }
                PlainField(
                    value = state.content,
                    onValueChange = vm::setContent,
                    placeholder = "Note",
                    style = bodyStyle,
                    readOnly = readOnly,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .focusRequester(focus).testTag("content"),
                )
            }
            NoteChips(
                reminderAt = state.reminderAt,
                repeat = state.reminderRepeat,
                labels = state.labels,
                contentColor = fg,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                onReminderClick = if (readOnly) null else ({ showReminder = true }),
                onLabelClick = if (readOnly) null else ({ showLabels = true }),
            )
            Spacer(Modifier.height(48.dp))
        }
    }

    if (showColors) {
        ModalBottomSheet(onDismissRequest = { showColors = false }) {
            Text("Color", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall)
            ColorPickerRow(selected = state.color, onPick = vm::setColor)
            Spacer(Modifier.height(16.dp))
        }
    }
    if (showLabels) {
        LabelPicker(
            labels = allLabels,
            states = state.labels.associate { it.id to ToggleableState.On },
            onToggle = { label, on -> vm.setLabel(label, on) },
            onCreate = { vm.createLabel(it) },
            onDismiss = { showLabels = false },
        )
    }
    if (showReminder) {
        ReminderDialog(
            initialAt = state.reminderAt,
            initialRepeat = state.reminderRepeat,
            onSave = { at, repeat ->
                vm.setReminder(at, repeat)
                showReminder = false
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onDelete = if (state.reminderAt != null) ({ vm.setReminder(null, state.reminderRepeat); showReminder = false }) else null,
            onDismiss = { showReminder = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete note forever?",
            text = "This can't be undone.",
            confirm = "Delete",
            onConfirm = { confirmDelete = false; vm.deleteForever(onBack) },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun Checklist(state: EditorState, vm: EditorViewModel, style: TextStyle, fg: Color, bg: Color, readOnly: Boolean) {
    var showChecked by rememberSaveable { mutableStateOf(true) }
    val open = state.items.filter { !it.checked }
    val done = state.items.filter { it.checked }

    @Composable
    fun Rows(items: List<EditorItem>) = items.forEach { item ->
        key(item.key) {
            ItemRow(
                item = item, style = style, fg = fg, bg = bg, readOnly = readOnly,
                requestFocus = state.focusTarget == item.key,
                onFocused = vm::focusHandled,
                onText = { vm.setItemText(item.key, it) },
                onToggle = { vm.toggleItem(item.key) },
                onRemove = { vm.removeItem(item.key) },
                onNext = { vm.addItemAfter(item.key) },
                canIndent = vm.canIndent(item.key),
                onIndent = { vm.setIndented(item.key, it) },
            )
        }
    }

    Rows(open)
    if (!readOnly) {
        Row(
            Modifier.fillMaxWidth().clickable { vm.addItem() }.padding(start = 28.dp, top = 10.dp, bottom = 10.dp).testTag("addItem"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Add, null, tint = fg.copy(alpha = 0.7f))
            Spacer(Modifier.width(14.dp))
            Text("List item", style = style, color = fg.copy(alpha = 0.6f))
        }
    }
    if (done.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = fg.copy(alpha = 0.15f))
        Row(
            Modifier.fillMaxWidth().clickable { showChecked = !showChecked }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (showChecked) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            Spacer(Modifier.width(12.dp))
            Text(
                "${done.size} checked " + if (done.size == 1) "item" else "items",
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (showChecked) Rows(done)
    }
}

@Composable
private fun ItemRow(
    item: EditorItem,
    style: TextStyle,
    fg: Color,
    bg: Color,
    readOnly: Boolean,
    requestFocus: Boolean,
    onFocused: () -> Unit,
    onText: (String) -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onNext: () -> Unit,
    canIndent: Boolean,
    onIndent: (Boolean) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val indent by rememberUpdatedState(onIndent)
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focus.requestFocus()
            onFocused()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            // Drag an item sideways to nest it under the one above, or to move it back out.
            .pointerInput(readOnly) {
                if (readOnly) return@pointerInput
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (dragged > INDENT_DRAG.toPx()) indent(true)
                        else if (dragged < -INDENT_DRAG.toPx()) indent(false)
                    },
                ) { _, amount -> dragged += amount }
            }
            .padding(start = 16.dp + INDENT_WIDTH * item.indent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = item.checked,
            onCheckedChange = { onToggle() },
            enabled = !readOnly,
            colors = CheckboxDefaults.colors(
                checkedColor = fg.copy(alpha = 0.6f), uncheckedColor = fg.copy(alpha = 0.7f), checkmarkColor = bg,
            ),
        )
        PlainField(
            value = item.text,
            onValueChange = onText,
            placeholder = "",
            style = if (item.checked) style.copy(color = fg.copy(alpha = 0.6f), textDecoration = TextDecoration.LineThrough) else style,
            readOnly = readOnly,
            imeAction = ImeAction.Next,
            onImeAction = onNext,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp)
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent {
                    // Backspace in an empty item removes it, like deleting an empty line.
                    when {
                        it.type != KeyEventType.KeyDown -> false
                        it.key == Key.Backspace && item.text.isEmpty() -> {
                            // Like a word processor: first un-nest the empty sub-item, then delete it.
                            if (item.isChild) onIndent(false) else onRemove()
                            true
                        }
                        it.key == Key.Tab -> { onIndent(!it.isShiftPressed); true }
                        else -> false
                    }
                }
                .testTag("item"),
        )
        if (!readOnly && focused) {
            if (item.isChild) {
                IconButton(onClick = { onIndent(false) }) {
                    Icon(Icons.AutoMirrored.Outlined.FormatIndentDecrease, "Remove indent", tint = fg.copy(alpha = 0.7f))
                }
            } else if (canIndent) {
                IconButton(onClick = { onIndent(true) }) {
                    Icon(Icons.AutoMirrored.Outlined.FormatIndentIncrease, "Indent", tint = fg.copy(alpha = 0.7f))
                }
            }
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, "Delete item", tint = fg.copy(alpha = 0.7f)) }
        } else {
            Spacer(Modifier.size(48.dp))
        }
    }
}

private val INDENT_WIDTH = 32.dp
private val INDENT_DRAG = 24.dp

/** Borderless text field with a placeholder, matching the look of a sheet of paper. */
@Composable
private fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    style: TextStyle,
    readOnly: Boolean,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: () -> Unit = {},
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        readOnly = readOnly,
        textStyle = style,
        cursorBrush = SolidColor(LocalContentColor.current),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onImeAction() }),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, style = style.copy(color = style.color.copy(alpha = 0.5f)))
                }
                inner()
            }
        },
    )
}
