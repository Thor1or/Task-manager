package com.jotter.notes.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jotter.notes.JotterApp
import com.jotter.notes.data.NoteWithDetails
import com.jotter.notes.data.NotesRepository
import com.jotter.notes.ui.components.ColorPickerRow
import com.jotter.notes.ui.components.ConfirmDialog
import com.jotter.notes.ui.components.LabelPicker
import com.jotter.notes.ui.theme.NoteColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenNote: (Long) -> Unit,
    onNewNote: (checklist: Boolean, pickImage: Boolean, labelId: Long?) -> Unit,
    onEditLabels: () -> Unit,
    onSettings: () -> Unit,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val repo = (LocalContext.current.applicationContext as JotterApp).repository
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var showColors by remember { mutableStateOf(false) }
    var showLabels by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.snacks.collect { snack ->
            // Launch separately so a new message replaces the one on screen instead of queueing behind it.
            launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(
                    snack.message,
                    actionLabel = if (snack.undo != null) "Undo" else null,
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) snack.undo?.invoke()
            }
        }
    }

    val selecting = state.selection.isNotEmpty()
    BackHandler(enabled = drawer.isOpen || selecting || state.search.active || state.destination != Destination.Notes) {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            selecting -> vm.clearSelection()
            state.search.active -> vm.closeSearch()
            else -> vm.navigate(Destination.Notes)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = !selecting && !state.search.active,
        drawerContent = {
            DrawerContent(
                state = state,
                onNavigate = { vm.navigate(it); scope.launch { drawer.close() } },
                onEditLabels = { scope.launch { drawer.close() }; onEditLabels() },
                onSettings = { scope.launch { drawer.close() }; onSettings() },
            )
        },
    ) {
        val canCreate = !selecting && !state.search.active &&
            (state.destination == Destination.Notes || state.destination is Destination.ByLabel)
        val labelId = (state.destination as? Destination.ByLabel)?.id
        Scaffold(
            topBar = {
                when {
                    selecting -> SelectionBar(
                        state = state,
                        onClose = vm::clearSelection,
                        onPin = vm::pinSelected,
                        onArchive = vm::archiveSelected,
                        onColor = { showColors = true },
                        onLabel = { showLabels = true },
                        onTrash = vm::trashSelected,
                        onCopy = vm::copySelected,
                        onRestore = vm::restoreSelected,
                        onDeleteForever = { confirmDelete = true },
                    )
                    state.search.active -> SearchBar(state, vm)
                    state.destination == Destination.Notes -> SearchPill(
                        gridView = state.settings.gridView,
                        onMenu = { scope.launch { drawer.open() } },
                        onSearch = vm::openSearch,
                        onToggleGrid = vm::toggleGrid,
                    )
                    else -> DestinationBar(
                        state = state,
                        onMenu = { scope.launch { drawer.open() } },
                        onSearch = vm::openSearch,
                        onToggleGrid = vm::toggleGrid,
                        onEmptyTrash = { confirmEmpty = true },
                    )
                }
            },
            bottomBar = {
                if (canCreate) {
                    BottomAppBar(
                        actions = {
                            IconButton(onClick = { onNewNote(true, false, labelId) }) {
                                Icon(Icons.Outlined.CheckBox, "New list")
                            }
                            IconButton(onClick = { onNewNote(false, true, labelId) }) {
                                Icon(Icons.Outlined.Image, "New note with image")
                            }
                        },
                        floatingActionButton = {
                            FloatingActionButton(onClick = { onNewNote(false, false, labelId) }) {
                                Icon(Icons.Outlined.Add, "New note")
                            }
                        },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (state.search.active) SearchFilters(state, vm)
                if (state.destination == Destination.Trash && !state.search.active && state.visible.isNotEmpty()) {
                    Text(
                        "Notes in Trash are deleted after ${NotesRepository.TRASH_RETENTION_DAYS} days.",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    state.loading -> Unit
                    state.visible.isEmpty() -> EmptyState(state)
                    else -> NotesGrid(
                        state = state,
                        repo = repo,
                        onClick = { n -> if (selecting) vm.toggleSelection(n.note.id) else onOpenNote(n.note.id) },
                        onLongClick = { n -> vm.toggleSelection(n.note.id) },
                        onSwipe = vm::archive,
                    )
                }
            }
        }
    }

    if (showColors) {
        val common = state.selected.map { it.note.color }.distinct().singleOrNull()
        ModalBottomSheet(onDismissRequest = { showColors = false }) {
            Text("Color", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall)
            ColorPickerRow(selected = common, onPick = { vm.colorSelected(it); showColors = false })
            Spacer(Modifier.height(16.dp))
        }
    }
    if (showLabels) {
        val selected = state.selected
        LabelPicker(
            labels = state.labels,
            states = state.labels.associate { label ->
                val n = selected.count { s -> s.labels.any { it.id == label.id } }
                label.id to when (n) {
                    0 -> ToggleableState.Off
                    selected.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                }
            },
            onToggle = vm::labelSelected,
            onCreate = vm::createLabelForSelected,
            onDismiss = { showLabels = false; vm.clearSelection() },
        )
    }
    if (confirmDelete) {
        val n = state.selection.size
        ConfirmDialog(
            title = if (n == 1) "Delete note forever?" else "Delete $n notes forever?",
            text = "This can't be undone.",
            confirm = "Delete",
            onConfirm = { vm.deleteSelectedForever(); confirmDelete = false },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = "Empty trash?",
            text = "All notes in Trash will be permanently deleted.",
            confirm = "Empty trash",
            onConfirm = { vm.emptyTrash(); confirmEmpty = false },
            onDismiss = { confirmEmpty = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesGrid(
    state: HomeState,
    repo: NotesRepository,
    onClick: (NoteWithDetails) -> Unit,
    onLongClick: (NoteWithDetails) -> Unit,
    onSwipe: (NoteWithDetails) -> Unit,
) {
    val swipeable = state.destination == Destination.Notes && !state.search.active && state.selection.isEmpty()
    val sections = state.pinned.isNotEmpty() && !state.search.active && state.destination != Destination.Trash
    LazyVerticalStaggeredGrid(
        columns = if (state.settings.gridView) StaggeredGridCells.Adaptive(160.dp) else StaggeredGridCells.Fixed(1),
        modifier = Modifier.fillMaxSize().testTag("notesGrid"),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 16.dp),
        verticalItemSpacing = 8.dp,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        fun section(title: String, notes: List<NoteWithDetails>) {
            if (sections && notes.isNotEmpty()) {
                item(key = "header-$title", span = StaggeredGridItemSpan.FullLine) {
                    Text(
                        title,
                        Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(notes, key = { it.note.id }) { n ->
                val card = @Composable {
                    NoteCard(
                        details = n,
                        selected = n.note.id in state.selection,
                        imageFile = repo::imageFile,
                        onClick = { onClick(n) },
                        onLongClick = { onLongClick(n) },
                    )
                }
                if (swipeable) {
                    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                        if (value != SwipeToDismissBoxValue.Settled) onSwipe(n)
                        value != SwipeToDismissBoxValue.Settled
                    })
                    SwipeToDismissBox(
                        state = dismiss,
                        modifier = Modifier.animateItem(),
                        backgroundContent = {
                            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Archive, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    ) { card() }
                } else {
                    Box(Modifier.animateItem()) { card() }
                }
            }
        }
        section("Pinned", state.pinned)
        section("Others", state.others)
    }
}

@Composable
private fun EmptyState(state: HomeState) {
    val (icon, text) = when {
        state.search.active && !state.search.hasCriteria -> Icons.Outlined.Search to "Search by text, or pick a filter above"
        state.search.active -> Icons.Outlined.Search to "No matching notes"
        else -> when (state.destination) {
            Destination.Notes -> Icons.Outlined.Lightbulb to "Notes you add appear here"
            Destination.Reminders -> Icons.Outlined.Notifications to "Notes with upcoming reminders appear here"
            Destination.Archive -> Icons.Outlined.Archive to "Your archived notes appear here"
            Destination.Trash -> Icons.Outlined.Delete to "No notes in Trash"
            is Destination.ByLabel -> Icons.AutoMirrored.Outlined.Label to "No notes with this label yet"
        }
    }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchPill(gridView: Boolean, onMenu: () -> Unit, onSearch: () -> Unit, onToggleGrid: () -> Unit) {
    Surface(
        modifier = Modifier
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(52.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Open navigation drawer") }
            Box(
                Modifier.weight(1f).height(52.dp).clickable(onClick = onSearch).testTag("searchPill"),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    "Search your notes",
                    Modifier.padding(start = 4.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GridToggle(gridView, onToggleGrid)
        }
    }
}

@Composable
private fun GridToggle(gridView: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        if (gridView) Icon(Icons.Outlined.ViewAgenda, "Single-column view")
        else Icon(Icons.Outlined.GridView, "Multi-column view")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationBar(
    state: HomeState,
    onMenu: () -> Unit,
    onSearch: () -> Unit,
    onToggleGrid: () -> Unit,
    onEmptyTrash: () -> Unit,
) {
    val title = when (state.destination) {
        Destination.Notes -> "Notes"
        Destination.Reminders -> "Reminders"
        Destination.Archive -> "Archive"
        Destination.Trash -> "Trash"
        is Destination.ByLabel -> state.currentLabel?.name.orEmpty()
    }
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Open navigation drawer") } },
        actions = {
            if (state.destination == Destination.Trash) {
                if (state.visible.isNotEmpty()) TextButton(onClick = onEmptyTrash) { Text("Empty trash") }
            } else {
                IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "Search") }
            }
            GridToggle(state.settings.gridView, onToggleGrid)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(state: HomeState, vm: HomeViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = vm::closeSearch) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
        },
        title = {
            TextField(
                value = state.search.query,
                onValueChange = { q -> vm.updateSearch { it.copy(query = q) } },
                placeholder = { Text("Search your notes") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("searchField"),
            )
        },
        actions = {
            if (state.search.query.isNotEmpty()) {
                IconButton(onClick = { vm.updateSearch { it.copy(query = "") } }) { Icon(Icons.Outlined.Close, "Clear search") }
            }
        },
    )
}

@Composable
private fun SearchFilters(state: HomeState, vm: HomeViewModel) {
    val s = state.search
    Column {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TypeFilter.entries.forEach { t ->
                FilterChip(
                    selected = s.type == t,
                    onClick = { vm.updateSearch { it.copy(type = if (it.type == t) null else t) } },
                    label = { Text(t.label) },
                )
            }
            state.labels.forEach { l ->
                FilterChip(
                    selected = s.labelId == l.id,
                    onClick = { vm.updateSearch { it.copy(labelId = if (it.labelId == l.id) null else l.id) } },
                    label = { Text(l.name) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, null, Modifier.size(16.dp)) },
                )
            }
        }
        ColorPickerRow(
            selected = s.color,
            onPick = { c -> vm.updateSearch { it.copy(color = if (it.color == c) null else c) } },
        )
        HorizontalDivider()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    state: HomeState,
    onClose: () -> Unit,
    onPin: (Boolean) -> Unit,
    onArchive: (Boolean) -> Unit,
    onColor: () -> Unit,
    onLabel: () -> Unit,
    onTrash: () -> Unit,
    onCopy: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val selected = state.selected
    val inTrash = state.destination == Destination.Trash && !state.search.active
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Clear selection") } },
        title = { Text("${selected.size}") },
        actions = {
            if (inTrash) {
                IconButton(onClick = onRestore) { Icon(Icons.Outlined.RestoreFromTrash, "Restore") }
                IconButton(onClick = onDeleteForever) { Icon(Icons.Outlined.DeleteForever, "Delete forever") }
            } else {
                val allPinned = selected.isNotEmpty() && selected.all { it.note.pinned }
                val allArchived = selected.isNotEmpty() && selected.all { it.note.archived }
                IconButton(onClick = { onPin(!allPinned) }) {
                    if (allPinned) Icon(Icons.Filled.PushPin, "Unpin") else Icon(Icons.Outlined.PushPin, "Pin")
                }
                IconButton(onClick = onColor) { Icon(Icons.Outlined.Palette, "Change color") }
                IconButton(onClick = onLabel) { Icon(Icons.AutoMirrored.Outlined.Label, "Change labels") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (allArchived) "Unarchive" else "Archive") },
                            leadingIcon = { Icon(if (allArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null) },
                            onClick = { menu = false; onArchive(!allArchived) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                            onClick = { menu = false; onTrash() },
                        )
                        DropdownMenuItem(
                            text = { Text("Make a copy") },
                            onClick = { menu = false; onCopy() },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun DrawerContent(
    state: HomeState,
    onNavigate: (Destination) -> Unit,
    onEditLabels: () -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                "Jotter",
                Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
            val current = if (state.search.active) null else state.destination

            @Composable
            fun Entry(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
                NavigationDrawerItem(
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { Icon(icon, null) },
                    selected = selected,
                    onClick = onClick,
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
            Entry("Notes", Icons.Outlined.Lightbulb, current == Destination.Notes) { onNavigate(Destination.Notes) }
            Entry("Reminders", Icons.Outlined.Notifications, current == Destination.Reminders) { onNavigate(Destination.Reminders) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (state.labels.isNotEmpty()) {
                Text(
                    "Labels",
                    Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.labels.forEach { l ->
                    val d = Destination.ByLabel(l.id)
                    Entry(l.name, Icons.AutoMirrored.Outlined.Label, current == d) { onNavigate(d) }
                }
            }
            Entry(if (state.labels.isEmpty()) "Create new label" else "Edit labels",
                if (state.labels.isEmpty()) Icons.Outlined.Add else Icons.Outlined.Edit, false, onEditLabels)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Entry("Archive", Icons.Outlined.Archive, current == Destination.Archive) { onNavigate(Destination.Archive) }
            Entry("Trash", Icons.Outlined.Delete, current == Destination.Trash) { onNavigate(Destination.Trash) }
            Entry("Settings", Icons.Outlined.Settings, false, onSettings)
            Spacer(Modifier.width(1.dp).height(12.dp))
        }
    }
}
