package com.folio.notes.ui.folders

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.folio.notes.FolioApp
import com.folio.notes.data.Folder
import com.folio.notes.data.NotesRepository
import com.folio.notes.ui.common.ConfirmDialog
import com.folio.notes.ui.common.FolderPickerDialog
import com.folio.notes.ui.common.TextPromptDialog
import com.folio.notes.ui.common.app
import com.folio.notes.ui.common.folderTree
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FolderRow(val folder: Folder, val depth: Int, val count: Int)

data class FoldersState(
    val loaded: Boolean = false,
    val folders: List<Folder> = emptyList(),
    val rows: List<FolderRow> = emptyList(),
    val allCount: Int = 0,
    val trashCount: Int = 0,
    val tags: List<Pair<String, Int>> = emptyList(),
)

class FoldersViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as FolioApp).repository

    val state = combine(repository.folders, repository.summaries, repository.tags) { folders, notes, tags ->
        val live = notes.filter { it.deletedAt == null }
        val liveIds = live.map { it.id }.toSet()
        val counts = live.groupingBy { it.folderId }.eachCount()
        FoldersState(
            loaded = true,
            folders = folders,
            rows = folderTree(folders).map { (f, depth) -> FolderRow(f, depth, counts[f.id] ?: 0) },
            allCount = live.size,
            trashCount = notes.size - live.size,
            tags = tags.filter { it.noteId in liveIds }.groupingBy { it.tag.lowercase() }.eachCount().toList().sortedBy { it.first },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoldersState())

    fun create(name: String, parentId: Long?) = viewModelScope.launch { repository.createFolder(name, parentId) }
    fun rename(id: Long, name: String) = viewModelScope.launch { repository.renameFolder(id, name) }
    fun move(id: Long, parentId: Long?) = viewModelScope.launch { repository.moveFolder(id, parentId) }
    fun delete(id: Long) = viewModelScope.launch { repository.deleteFolder(id) }
}

private sealed interface FolderDialog {
    data class Create(val parentId: Long?) : FolderDialog
    data class Rename(val folder: Folder) : FolderDialog
    data class Move(val folder: Folder) : FolderDialog
    data class Delete(val folder: Folder) : FolderDialog
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FoldersScreen(
    onOpenAll: () -> Unit,
    onOpenFolder: (Long) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenTag: (String) -> Unit,
    onNewNote: () -> Unit,
    onSettings: () -> Unit,
    vm: FoldersViewModel = app().let { app -> viewModel { FoldersViewModel(app) } },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<FolderDialog?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { dialog = FolderDialog.Create(null) }) {
                    Icon(Icons.Outlined.CreateNewFolder, "New folder", tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onNewNote) { Icon(Icons.Outlined.EditNote, "New note", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) }
            }
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item { Text("Folders", fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp)) }
            item {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                    androidx.compose.foundation.layout.Column {
                        FolderLine(Icons.Outlined.Inventory2, "All Notes", state.allCount, 0, onClick = onOpenAll)
                        state.rows.forEach { row ->
                            HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            val custom = row.folder.id != Folder.DEFAULT_ID
                            FolderLine(
                                Icons.Outlined.Folder, row.folder.name, row.count, row.depth,
                                onClick = { onOpenFolder(row.folder.id) },
                                menu = if (!custom) null else { close ->
                                    DropdownMenuItem({ Text("New Subfolder") }, { close(); dialog = FolderDialog.Create(row.folder.id) })
                                    DropdownMenuItem({ Text("Rename") }, { close(); dialog = FolderDialog.Rename(row.folder) })
                                    DropdownMenuItem({ Text("Move") }, { close(); dialog = FolderDialog.Move(row.folder) })
                                    DropdownMenuItem({ Text("Delete", color = MaterialTheme.colorScheme.error) }, { close(); dialog = FolderDialog.Delete(row.folder) })
                                },
                            )
                        }
                        if (state.trashCount > 0) {
                            HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            FolderLine(Icons.Outlined.Delete, "Recently Deleted", state.trashCount, 0, onClick = onOpenTrash)
                        }
                    }
                }
            }
            if (state.tags.isNotEmpty()) {
                item { Text("Tags", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp)) }
                item {
                    FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        state.tags.forEach { (tag, _) ->
                            Text(
                                "#$tag",
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface)
                                    .clickable { onOpenTag(tag) }.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        is FolderDialog.Create -> TextPromptDialog("New Folder", onConfirm = { vm.create(it, d.parentId); dialog = null }, onDismiss = { dialog = null })
        is FolderDialog.Rename -> TextPromptDialog("Rename Folder", d.folder.name, onConfirm = { vm.rename(d.folder.id, it); dialog = null }, onDismiss = { dialog = null })
        is FolderDialog.Move -> FolderPickerDialog(
            title = "Move “${d.folder.name}” into",
            folders = state.folders,
            exclude = NotesRepository.subtree(state.folders, d.folder.id) + Folder.DEFAULT_ID,
            topLevelOption = true,
            onPick = { vm.move(d.folder.id, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is FolderDialog.Delete -> ConfirmDialog(
            "Delete “${d.folder.name}”?",
            "The folder and its subfolders are removed. Their notes move to Recently Deleted.",
            "Delete", onConfirm = { vm.delete(d.folder.id) }, onDismiss = { dialog = null },
        )
        null -> {}
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderLine(
    icon: ImageVector,
    name: String,
    count: Int,
    depth: Int,
    onClick: () -> Unit,
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = if (menu != null) ({ open = true }) else null, onLongClickLabel = "Folder options")
                .testTag("folderRow")
                .padding(start = (16 + depth * 20).dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
        if (menu != null) DropdownMenu(open, { open = false }) { menu { open = false } }
    }
}
