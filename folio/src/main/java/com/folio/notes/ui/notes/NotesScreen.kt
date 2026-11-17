package com.folio.notes.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.folio.notes.data.NoteSummary
import com.folio.notes.data.NotesRepository
import com.folio.notes.data.SortBy
import com.folio.notes.ui.common.AttachmentImage
import com.folio.notes.ui.common.ConfirmDialog
import com.folio.notes.ui.common.Dates
import com.folio.notes.ui.common.FolderPickerDialog
import com.folio.notes.ui.common.rememberVaultPrompt
import kotlinx.coroutines.launch

private sealed interface NotesDialog {
    data class Move(val ids: Set<Long>) : NotesDialog
    data class DeleteForever(val ids: Set<Long>) : NotesDialog
    data object EmptyTrash : NotesDialog
    data object Sort : NotesDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    onBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onNewNote: (folderId: Long) -> Unit,
    vm: NotesViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsState()
    val isTrash = vm.kind == ListKind.TRASH
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var dialog by remember { mutableStateOf<NotesDialog?>(null) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val vault = rememberVaultPrompt()
    val vaultKey by com.folio.notes.ui.common.app().vault.key.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    fun stopSelecting() { selecting = false; selected = emptySet() }
    BackHandler(selecting) { stopSelecting() }
    BackHandler(!selecting && query.isNotEmpty()) { vm.query.value = ""; focus.clearFocus() }

    fun trash(ids: Set<Long>) {
        vm.trash(ids)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(if (ids.size == 1) "Note deleted" else "${ids.size} notes deleted", "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) vm.restore(ids)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { if (selecting) Text(if (selected.isEmpty()) "Select Notes" else "${selected.size} Selected", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    if (selecting) IconButton(onClick = ::stopSelecting) { Icon(Icons.Filled.Close, "Stop selecting") }
                    else IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Folders") }
                },
                actions = {
                    if (!selecting) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(if (state.settings.gallery) "View as List" else "View as Gallery") }, { menu = false; vm.setGallery(!state.settings.gallery) })
                            if (state.count > 0) DropdownMenuItem({ Text("Select Notes") }, { menu = false; selecting = true })
                            if (!isTrash) {
                                DropdownMenuItem({ Text("Sort By") }, { menu = false; dialog = NotesDialog.Sort })
                                DropdownMenuItem({ Text(if (state.settings.groupByDate) "Don't Group by Date" else "Group by Date") }, { menu = false; vm.setGroupByDate(!state.settings.groupByDate) })
                            }
                            if (vaultKey != null) DropdownMenuItem({ Text("Lock Notes Now") }, { menu = false; vm.lockNow() })
                            if (isTrash && state.count > 0) DropdownMenuItem({ Text("Delete All", color = MaterialTheme.colorScheme.error) }, { menu = false; dialog = NotesDialog.EmptyTrash })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().height(52.dp).padding(horizontal = 8.dp)) {
                if (selecting) {
                    val ids = selected
                    TextButton(onClick = { if (isTrash) vm.restore(ids) else dialog = NotesDialog.Move(ids); if (isTrash) stopSelecting() }, enabled = ids.isNotEmpty(), modifier = Modifier.align(Alignment.CenterStart)) {
                        Text(if (isTrash) "Recover" else "Move")
                    }
                    TextButton(
                        onClick = { if (isTrash) dialog = NotesDialog.DeleteForever(ids) else { trash(ids); stopSelecting() } },
                        enabled = ids.isNotEmpty(), modifier = Modifier.align(Alignment.CenterEnd),
                    ) { Text("Delete") }
                } else {
                    Text(
                        if (state.count == 1) "1 Note" else "${state.count} Notes",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    if (!isTrash) IconButton(onClick = { onNewNote(vm.folderId) }, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Icon(Icons.Outlined.EditNote, "New note", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                }
            }
        },
    ) { inner ->
        val gallery = state.settings.gallery
        LazyVerticalGrid(
            columns = if (gallery) GridCells.Adaptive(150.dp) else GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(state.title, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 10.dp))
            }
            if (!isTrash) item(span = { GridItemSpan(maxLineSpan) }) { SearchField(query) { vm.query.value = it } }
            if (isTrash) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Notes stay here for ${NotesRepository.TRASH_RETENTION_DAYS} days, then they are permanently deleted.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            if (state.loaded && state.sections.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (state.searching) "No Results" else "No Notes",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
                )
            }
            state.sections.forEachIndexed { sectionIndex, section ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "h$sectionIndex${section.title}") {
                    if (section.title != null) Text(section.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                    else Spacer(Modifier.height(14.dp))
                }
                itemsIndexed(section.notes, key = { _, n -> "${section.title}-${n.id}" }) { index, note ->
                    val folderName = if (state.showFolder) state.folders.firstOrNull { it.id == note.folderId }?.name else null
                    val isSelected = note.id in selected
                    val onClick = { if (selecting) selected = if (isSelected) selected - note.id else selected + note.id else onOpenNote(note.id) }
                    NoteItem(
                        note = note, gallery = gallery, folderName = folderName, selecting = selecting, isSelected = isSelected,
                        first = index == 0, last = index == section.notes.lastIndex, isTrash = isTrash,
                        onClick = onClick,
                        onPin = { vm.setPinned(listOf(note.id), !note.pinned) },
                        onTrash = { trash(setOf(note.id)) },
                        onMove = { dialog = NotesDialog.Move(setOf(note.id)) },
                        onLock = { vault.withKey { key -> if (note.locked) vm.unlock(note.id, key) else vm.lock(note.id, key) } },
                        onRestore = { vm.restore(listOf(note.id)) },
                        onDeleteForever = { dialog = NotesDialog.DeleteForever(setOf(note.id)) },
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        is NotesDialog.Move -> FolderPickerDialog(
            title = if (d.ids.size == 1) "Move note to" else "Move ${d.ids.size} notes to",
            folders = state.folders,
            onPick = { it?.let { folder -> vm.move(d.ids, folder) }; dialog = null; stopSelecting() },
            onDismiss = { dialog = null },
        )
        is NotesDialog.DeleteForever -> ConfirmDialog(
            if (d.ids.size == 1) "Delete this note?" else "Delete ${d.ids.size} notes?",
            "This cannot be undone.", "Delete",
            onConfirm = { vm.deleteForever(d.ids); stopSelecting() }, onDismiss = { dialog = null },
        )
        NotesDialog.EmptyTrash -> ConfirmDialog(
            "Delete all notes here?", "Everything in Recently Deleted is erased. This cannot be undone.", "Delete All",
            onConfirm = { vm.emptyTrash() }, onDismiss = { dialog = null },
        )
        NotesDialog.Sort -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Sort By") },
            text = {
                Column {
                    listOf(SortBy.EDITED to "Date Edited", SortBy.CREATED to "Date Created", SortBy.TITLE to "Title").forEach { (sort, label) ->
                        Row(
                            Modifier.fillMaxWidth().combinedClickable(onClick = { vm.setSort(sort); dialog = null }).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(state.settings.sortBy == sort, onClick = { vm.setSort(sort); dialog = null })
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } },
        )
        null -> {}
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search", color = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                query, onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().testTag("search"),
            )
        }
        if (query.isNotEmpty()) Icon(
            Icons.Filled.Close, "Clear search", tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).clip(RoundedCornerShape(50)).combinedClickable(onClick = { onChange("") }),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun NoteItem(
    note: NoteSummary,
    gallery: Boolean,
    folderName: String?,
    selecting: Boolean,
    isSelected: Boolean,
    first: Boolean,
    last: Boolean,
    isTrash: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onTrash: () -> Unit,
    onMove: () -> Unit,
    onLock: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val title = note.title.ifBlank { "New Note" }
    val date = Dates.short(if (isTrash) note.deletedAt ?: note.modifiedAt else note.modifiedAt)
    val preview = if (note.locked) "Locked" else note.preview.lineSequence().firstOrNull().orEmpty().ifBlank { "No additional text" }
    val click = Modifier.combinedClickable(onClick = onClick, onLongClick = { if (!selecting) menu = true }, onLongClickLabel = "Note options")
        .semantics { contentDescription = "Note: $title" }

    @Composable
    fun Menu() = DropdownMenu(menu, { menu = false }) {
        if (isTrash) {
            DropdownMenuItem({ Text("Recover") }, { menu = false; onRestore() })
            DropdownMenuItem({ Text("Delete Permanently", color = MaterialTheme.colorScheme.error) }, { menu = false; onDeleteForever() })
        } else {
            DropdownMenuItem({ Text(if (note.pinned) "Unpin Note" else "Pin Note") }, { menu = false; onPin() })
            DropdownMenuItem({ Text(if (note.locked) "Remove Lock" else "Lock Note") }, { menu = false; onLock() })
            DropdownMenuItem({ Text("Move") }, { menu = false; onMove() })
            DropdownMenuItem({ Text("Delete", color = MaterialTheme.colorScheme.error) }, { menu = false; onTrash() })
        }
    }

    @Composable
    fun SelectionMark(modifier: Modifier = Modifier) {
        if (selecting) Icon(
            if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, if (isSelected) "Selected" else "Not selected",
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = modifier,
        )
    }

    if (gallery) {
        Column(Modifier.padding(bottom = 14.dp).testTag("noteItem"), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)).then(click)) {
                    when {
                        note.locked -> Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Lock, "Locked", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp)) }
                        note.firstAttachment != null && note.preview.isBlank() -> AttachmentImage(note.firstAttachment!!, Modifier.fillMaxSize(), ContentScale.Crop, 400)
                        else -> Column(Modifier.padding(12.dp)) {
                            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(note.preview, fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Clip)
                        }
                    }
                }
                SelectionMark(Modifier.align(Alignment.TopEnd).padding(8.dp))
                if (note.pinned && !selecting) Icon(Icons.Filled.PushPin, "Pinned", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp))
                Menu()
            }
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            Text(date, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val shape = RoundedCornerShape(
        topStart = if (first) 14.dp else 0.dp, topEnd = if (first) 14.dp else 0.dp,
        bottomStart = if (last) 14.dp else 0.dp, bottomEnd = if (last) 14.dp else 0.dp,
    )
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onPin(); false }
                SwipeToDismissBoxValue.EndToStart -> { onTrash(); true }
                else -> false
            }
        },
    )
    Box(Modifier.clip(shape).testTag("noteItem")) {
        SwipeToDismissBox(
            state = swipe,
            enableDismissFromStartToEnd = !isTrash && !selecting,
            enableDismissFromEndToStart = !isTrash && !selecting,
            backgroundContent = {
                val toEnd = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                Box(
                    Modifier.fillMaxSize().background(if (toEnd) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error).padding(horizontal = 22.dp),
                    contentAlignment = if (toEnd) Alignment.CenterStart else Alignment.CenterEnd,
                ) { Icon(if (toEnd) Icons.Filled.PushPin else Icons.Outlined.Delete, null, tint = Color.White) }
            },
        ) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(Modifier.fillMaxWidth().then(click).padding(start = 16.dp, end = 12.dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        SelectionMark(Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(date, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), maxLines = 1)
                                Spacer(Modifier.width(8.dp))
                                Text(preview, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (folderName != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(folderName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        when {
                            note.locked -> Icon(Icons.Outlined.Lock, "Locked", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).size(20.dp))
                            note.firstAttachment != null -> AttachmentImage(
                                note.firstAttachment!!, Modifier.padding(start = 8.dp).size(44.dp).clip(RoundedCornerShape(6.dp)), ContentScale.Crop, 200,
                            )
                        }
                    }
                    if (!last) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        Menu()
    }
}
