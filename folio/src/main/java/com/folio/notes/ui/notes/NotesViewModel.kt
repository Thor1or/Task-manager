package com.folio.notes.ui.notes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.folio.notes.FolioApp
import com.folio.notes.data.Folder
import com.folio.notes.data.NoteSummary
import com.folio.notes.data.Settings
import com.folio.notes.data.SortBy
import com.folio.notes.ui.common.Dates
import javax.crypto.SecretKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which notes a list shows. */
enum class ListKind { ALL, FOLDER, TRASH, TAG }

data class Section(val title: String?, val notes: List<NoteSummary>)

data class NotesState(
    val loaded: Boolean = false,
    val title: String = "",
    val sections: List<Section> = emptyList(),
    val count: Int = 0,
    val folders: List<Folder> = emptyList(),
    /** Show which folder each note lives in – when the list spans folders. */
    val showFolder: Boolean = false,
    val searching: Boolean = false,
    val settings: Settings = Settings(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(application: Application, handle: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as FolioApp
    private val repository = app.repository
    val kind = ListKind.valueOf(handle.get<String>(KEY_KIND) ?: ListKind.ALL.name)
    val arg: String = handle.get<String>(KEY_ARG).orEmpty()
    /** Where a note created from this list goes. */
    val folderId: Long = if (kind == ListKind.FOLDER) arg.toLongOrNull() ?: Folder.DEFAULT_ID else Folder.DEFAULT_ID

    val query = MutableStateFlow("")
    private val results = query.flatMapLatest { if (it.isBlank()) flowOf(null) else repository.search(it) }

    val state = combine(repository.summaries, repository.folders, repository.tags, app.settings.settings, results) { notes, folders, tags, settings, found ->
        val searching = found != null
        val shown = found ?: when (kind) {
            ListKind.ALL -> notes.filter { it.deletedAt == null }
            ListKind.FOLDER -> notes.filter { it.deletedAt == null && it.folderId == folderId }
            ListKind.TRASH -> notes.filter { it.deletedAt != null }
            ListKind.TAG -> {
                val ids = tags.filter { it.tag.equals(arg, ignoreCase = true) }.map { it.noteId }.toSet()
                notes.filter { it.deletedAt == null && it.id in ids }
            }
        }
        val sorted = when {
            kind == ListKind.TRASH && !searching -> shown.sortedByDescending { it.deletedAt }
            settings.sortBy == SortBy.TITLE -> shown.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            settings.sortBy == SortBy.CREATED -> shown.sortedByDescending { it.createdAt }
            else -> shown.sortedByDescending { it.modifiedAt }
        }
        NotesState(
            loaded = true,
            title = when (kind) {
                ListKind.ALL -> "All Notes"
                ListKind.FOLDER -> folders.firstOrNull { it.id == folderId }?.name ?: "Notes"
                ListKind.TRASH -> "Recently Deleted"
                ListKind.TAG -> "#$arg"
            },
            sections = sections(sorted, settings, searching),
            count = sorted.size,
            folders = folders,
            showFolder = searching || kind == ListKind.ALL || kind == ListKind.TAG,
            searching = searching,
            settings = settings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesState())

    private fun sections(sorted: List<NoteSummary>, settings: Settings, searching: Boolean): List<Section> {
        if (sorted.isEmpty()) return emptyList()
        if (searching || kind == ListKind.TRASH) return listOf(Section(null, sorted))
        val (pinned, rest) = sorted.partition { it.pinned }
        val out = ArrayList<Section>()
        if (pinned.isNotEmpty()) out += Section("Pinned", pinned)
        if (settings.groupByDate && settings.sortBy != SortBy.TITLE) {
            val now = System.currentTimeMillis()
            rest.groupBy { Dates.bucket(if (settings.sortBy == SortBy.CREATED) it.createdAt else it.modifiedAt, now) }
                .forEach { (title, notes) -> out += Section(title, notes) }
        } else if (rest.isNotEmpty()) out += Section(if (pinned.isEmpty()) null else "Notes", rest)
        return out
    }

    fun setPinned(ids: Collection<Long>, pinned: Boolean) = viewModelScope.launch { repository.setPinned(ids.toList(), pinned) }
    fun trash(ids: Collection<Long>) = viewModelScope.launch { repository.trash(ids.toList()) }
    fun restore(ids: Collection<Long>) = viewModelScope.launch { repository.restore(ids.toList()) }
    fun deleteForever(ids: Collection<Long>) = viewModelScope.launch { repository.deleteForever(ids.toList()) }
    fun emptyTrash() = viewModelScope.launch { repository.emptyTrash() }
    fun move(ids: Collection<Long>, folderId: Long) = viewModelScope.launch { repository.move(ids.toList(), folderId) }
    fun lock(id: Long, key: SecretKey) = viewModelScope.launch { repository.lock(id, key) }
    fun unlock(id: Long, key: SecretKey) = viewModelScope.launch { repository.unlock(id, key) }
    fun lockNow() = app.vault.close()
    fun setSort(sortBy: SortBy) = viewModelScope.launch { app.settings.setSortBy(sortBy) }
    fun setGallery(on: Boolean) = viewModelScope.launch { app.settings.setGallery(on) }
    fun setGroupByDate(on: Boolean) = viewModelScope.launch { app.settings.setGroupByDate(on) }

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_ARG = "arg"
    }
}
