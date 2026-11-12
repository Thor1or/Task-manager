package com.jotter.notes.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jotter.notes.JotterApp
import com.jotter.notes.data.Label
import com.jotter.notes.data.NoteWithDetails
import com.jotter.notes.data.NotesRepository
import com.jotter.notes.data.Settings
import com.jotter.notes.data.SettingsStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Destination {
    data object Notes : Destination
    data object Reminders : Destination
    data object Archive : Destination
    data object Trash : Destination
    data class ByLabel(val id: Long) : Destination
}

enum class TypeFilter(val label: String) { LISTS("Lists"), IMAGES("Images"), REMINDERS("Reminders") }

data class Search(
    val active: Boolean = false,
    val query: String = "",
    val color: Int? = null,
    val labelId: Long? = null,
    val type: TypeFilter? = null,
) {
    val hasCriteria get() = query.isNotBlank() || color != null || labelId != null || type != null

    fun matches(n: NoteWithDetails): Boolean =
        n.matches(query) &&
            (color == null || n.note.color == color) &&
            (labelId == null || n.labels.any { it.id == labelId }) &&
            when (type) {
                null -> true
                TypeFilter.LISTS -> n.note.isChecklist
                TypeFilter.IMAGES -> n.images.isNotEmpty()
                TypeFilter.REMINDERS -> n.note.reminderAt != null
            }
}

data class HomeState(
    val loading: Boolean = true,
    val destination: Destination = Destination.Notes,
    val labels: List<Label> = emptyList(),
    val pinned: List<NoteWithDetails> = emptyList(),
    val others: List<NoteWithDetails> = emptyList(),
    val search: Search = Search(),
    val selection: Set<Long> = emptySet(),
    val settings: Settings = Settings(),
) {
    val visible get() = pinned + others
    val selected get() = visible.filter { it.note.id in selection }
    val currentLabel get() = (destination as? Destination.ByLabel)?.let { d -> labels.firstOrNull { it.id == d.id } }
}

data class Snack(val message: String, val undo: (suspend () -> Unit)? = null)

class HomeViewModel(
    private val repo: NotesRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private data class Params(val destination: Destination, val search: Search, val selection: Set<Long>)

    private val destination = MutableStateFlow<Destination>(Destination.Notes)
    private val search = MutableStateFlow(Search())
    private val selection = MutableStateFlow<Set<Long>>(emptySet())

    private val snackChannel = Channel<Snack>(Channel.BUFFERED)
    val snacks = snackChannel.receiveAsFlow()

    val state: StateFlow<HomeState> = combine(
        repo.notes, repo.labels, settingsStore.settings,
        combine(destination, search, selection, ::Params),
    ) { notes, labels, settings, p ->
        // A label can be deleted while it is being viewed; fall back to the main list.
        val dest = p.destination.takeUnless { it is Destination.ByLabel && labels.none { l -> l.id == it.id } }
            ?: Destination.Notes
        val byDate = compareByDescending<NoteWithDetails> {
            if (settings.sortByModified) it.note.updatedAt else it.note.createdAt
        }.thenByDescending { it.note.id }
        val live = notes.filter { it.note.trashedAt == null }
        val shown = when {
            p.search.active -> if (p.search.hasCriteria) live.filter(p.search::matches).sortedWith(byDate) else emptyList()
            dest is Destination.Notes -> live.filter { !it.note.archived }.sortedWith(byDate)
            dest is Destination.Reminders -> live.filter { it.note.reminderAt != null }.sortedBy { it.note.reminderAt }
            dest is Destination.Archive -> live.filter { it.note.archived }.sortedWith(byDate)
            dest is Destination.Trash -> notes.filter { it.note.trashedAt != null }.sortedByDescending { it.note.trashedAt }
            dest is Destination.ByLabel -> live.filter { n -> n.labels.any { it.id == dest.id } }.sortedWith(byDate)
            else -> emptyList()
        }
        val ids = shown.mapTo(HashSet()) { it.note.id }
        HomeState(
            loading = false,
            destination = dest,
            labels = labels,
            pinned = shown.filter { it.note.pinned },
            others = shown.filter { !it.note.pinned },
            search = p.search,
            selection = p.selection.filterTo(LinkedHashSet()) { it in ids },
            settings = settings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    fun navigate(d: Destination) {
        destination.value = d
        search.value = Search()
        clearSelection()
    }

    fun openSearch() { search.value = Search(active = true); clearSelection() }
    fun closeSearch() { search.value = Search(); clearSelection() }
    fun updateSearch(f: (Search) -> Search) = search.update(f)

    fun toggleSelection(id: Long) = selection.update { if (id in it) it - id else it + id }
    fun clearSelection() { selection.value = emptySet() }

    fun toggleGrid() = viewModelScope.launch { settingsStore.setGridView(!state.value.settings.gridView) }

    private fun selectedIds(): List<Long> = state.value.selection.toList()

    private fun act(message: String?, undo: (suspend () -> Unit)? = null, block: suspend (List<Long>) -> Unit) {
        val ids = selectedIds()
        if (ids.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            block(ids)
            if (message != null) snackChannel.send(Snack(message, undo))
        }
    }

    private fun count(n: Int, verb: String) = if (n == 1) "Note $verb" else "$n notes $verb"

    fun pinSelected(pinned: Boolean) = act(null) { repo.setPinned(it, pinned) }

    fun archiveSelected(archived: Boolean) {
        val before = state.value.selected.map { it.note }
        act(count(before.size, if (archived) "archived" else "unarchived"), undo = {
            repo.setArchived(before.map { it.id }, !archived)
            repo.setPinned(before.filter { it.pinned }.map { it.id }, true)
        }) { repo.setArchived(it, archived) }
    }

    /** Archives one note, e.g. from a swipe. */
    fun archive(note: NoteWithDetails) = viewModelScope.launch {
        val id = listOf(note.note.id)
        repo.setArchived(id, true)
        snackChannel.send(Snack("Note archived") {
            repo.setArchived(id, false)
            if (note.note.pinned) repo.setPinned(id, true)
        })
    }

    fun trashSelected() {
        val before = state.value.selected.map { it.note }
        act(count(before.size, "moved to trash"), undo = {
            repo.restore(before.map { it.id })
            repo.setPinned(before.filter { it.pinned }.map { it.id }, true)
        }) { repo.trash(it) }
    }

    fun restoreSelected() = act(count(selectedIds().size, "restored")) { repo.restore(it) }
    fun deleteSelectedForever() = act(null) { repo.deleteForever(it) }
    fun emptyTrash() = viewModelScope.launch { repo.emptyTrash() }

    fun colorSelected(color: Int) = act(null) { repo.setColor(it, color) }

    fun copySelected() = act(null) { ids ->
        ids.forEach { repo.duplicate(it) }
        snackChannel.send(Snack(if (ids.size == 1) "Copy created" else "${ids.size} copies created"))
    }

    /** Label changes keep the selection so several labels can be toggled in one go. */
    fun labelSelected(label: Label, on: Boolean) = viewModelScope.launch { repo.setLabel(selectedIds(), label.id, on) }

    fun createLabelForSelected(name: String) = viewModelScope.launch {
        repo.createLabel(name)?.let { repo.setLabel(selectedIds(), it, true) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JotterApp
                HomeViewModel(app.repository, app.settings)
            }
        }
    }
}
