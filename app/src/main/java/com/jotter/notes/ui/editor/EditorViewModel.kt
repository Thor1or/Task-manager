package com.jotter.notes.ui.editor

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jotter.notes.JotterApp
import com.jotter.notes.data.ChecklistItem
import com.jotter.notes.data.Label
import com.jotter.notes.data.Note
import com.jotter.notes.data.NoteImage
import com.jotter.notes.data.NotesRepository
import com.jotter.notes.data.Repeat
import com.jotter.notes.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EditorItem(val key: Long, val text: String, val checked: Boolean = false)

data class EditorState(
    val loaded: Boolean = false,
    /** The note was deleted elsewhere; the screen should close. */
    val missing: Boolean = false,
    val id: Long = 0,
    val title: String = "",
    val content: String = "",
    val isChecklist: Boolean = false,
    val items: List<EditorItem> = emptyList(),
    val color: Int = 0,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val trashed: Boolean = false,
    val reminderAt: Long? = null,
    val reminderRepeat: Repeat = Repeat.NONE,
    val labels: List<Label> = emptyList(),
    val images: List<NoteImage> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Checklist item key to focus next, or [FOCUS_CONTENT] for the text body. */
    val focusTarget: Long? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val isEmpty: Boolean
        get() = title.isBlank() && images.isEmpty() &&
            if (isChecklist) items.all { it.text.isBlank() } else content.isBlank()

    fun shareText(): String = buildString {
        if (title.isNotBlank()) appendLine(title)
        if (isChecklist) items.forEach { appendLine((if (it.checked) "[x] " else "[ ] ") + it.text) }
        else append(content)
    }.trim()

    companion object {
        const val FOCUS_CONTENT = -1L
    }
}

class EditorViewModel(
    private val repo: NotesRepository,
    private val settingsStore: SettingsStore,
    /** Application scope: the final save must finish even after this view model is cleared. */
    private val appScope: CoroutineScope,
    private val handle: SavedStateHandle,
) : ViewModel() {

    private data class Snapshot(val title: String, val content: String, val isChecklist: Boolean, val items: List<EditorItem>)

    var state by mutableStateOf(EditorState())
        private set

    val allLabels = repo.labels.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var lastEdit = 0L

    private val mutex = Mutex()
    private var saveJob: Job? = null
    private var contentDirty = false
    private var discarded = false
    private var nextKey = 1L
    private var newItemsAtBottom = true
    private var pendingLabelId: Long? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        newItemsAtBottom = settingsStore.settings.first().newItemsAtBottom
        // Once a new note has been saved its id is kept here, so a recreated screen reopens it.
        val id = handle.get<Long>(KEY_ID) ?: 0L
        if (id != 0L) {
            val d = repo.get(id)
            if (d == null) {
                state = EditorState(loaded = true, missing = true)
                return
            }
            val n = d.note
            state = EditorState(
                loaded = true, id = n.id, title = n.title, content = n.content, isChecklist = n.isChecklist,
                items = d.sortedItems.map { EditorItem(nextKey++, it.text, it.checked) },
                color = n.color, pinned = n.pinned, archived = n.archived, trashed = n.trashedAt != null,
                reminderAt = n.reminderAt, reminderRepeat = Repeat.of(n.reminderRepeat),
                labels = d.sortedLabels, images = d.sortedImages,
                createdAt = n.createdAt, updatedAt = n.updatedAt,
            )
        } else {
            val checklist = handle.get<Boolean>(KEY_CHECKLIST) ?: false
            pendingLabelId = handle.get<Long>(KEY_LABEL)?.takeIf { it > 0 }
            val first = EditorItem(nextKey++, "")
            state = EditorState(
                loaded = true,
                isChecklist = checklist,
                items = if (checklist) listOf(first) else emptyList(),
                focusTarget = when {
                    handle.get<Boolean>(KEY_PICK_IMAGE) == true -> null
                    checklist -> first.key
                    else -> EditorState.FOCUS_CONTENT
                },
            )
        }
    }

    /** True exactly once for a note opened through "new note with image". */
    fun consumePickImage(): Boolean {
        val pick = handle.get<Boolean>(KEY_PICK_IMAGE) == true
        handle[KEY_PICK_IMAGE] = false
        return pick
    }

    fun focusHandled() { state = state.copy(focusTarget = null) }

    // ---- Saving ----

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            persist()
        }
    }

    private fun edited() {
        contentDirty = true
        scheduleSave()
    }

    /** Writes the note. A brand-new empty note is only inserted when [force] is set. */
    private suspend fun persist(force: Boolean = false) = mutex.withLock {
        val s = state
        if (!s.loaded || s.missing || discarded || s.trashed) return@withLock
        if (s.id == 0L && s.isEmpty && !force) return@withLock
        val now = System.currentTimeMillis()
        val updatedAt = if (contentDirty || s.updatedAt == 0L) now else s.updatedAt
        val createdAt = s.createdAt.takeIf { it > 0 } ?: now
        contentDirty = false
        val id = repo.save(
            Note(
                id = s.id, title = s.title, content = if (s.isChecklist) "" else s.content,
                isChecklist = s.isChecklist, color = s.color, pinned = s.pinned, archived = s.archived,
                reminderAt = s.reminderAt, reminderRepeat = s.reminderRepeat.name,
                createdAt = createdAt, updatedAt = updatedAt,
            ),
            if (s.isChecklist) s.items.map { ChecklistItem(noteId = s.id, text = it.text, checked = it.checked) } else emptyList(),
        )
        state = state.copy(id = id, createdAt = createdAt, updatedAt = updatedAt)
        if (s.id == 0L) {
            handle[KEY_ID] = id
            pendingLabelId?.let { repo.setLabel(listOf(id), it, true) }
            pendingLabelId = null
            refreshExtras()
        }
    }

    private suspend fun refreshExtras() {
        val d = repo.get(state.id) ?: return
        state = state.copy(labels = d.sortedLabels, images = d.sortedImages)
    }

    /** Saves right away, e.g. when the app goes to the background. */
    fun flush() {
        saveJob?.cancel()
        appScope.launch { persist() }
    }

    override fun onCleared() {
        saveJob?.cancel()
        appScope.launch {
            persist()
            // Leaving a note without typing anything should not leave an empty card behind.
            val s = state
            if (s.id != 0L && s.isEmpty && !s.trashed && !discarded) repo.deleteForever(listOf(s.id))
        }
    }

    // ---- Undo / redo ----

    private fun snapshot() = Snapshot(state.title, state.content, state.isChecklist, state.items)

    /** Call before a change. Rapid consecutive edits collapse into one undo step. */
    private fun record(coalesce: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!coalesce || undoStack.isEmpty() || now - lastEdit > UNDO_COALESCE_MS) {
            undoStack.addLast(snapshot())
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        }
        lastEdit = now
        redoStack.clear()
    }

    private fun apply(s: Snapshot) {
        state = state.copy(
            title = s.title, content = s.content, isChecklist = s.isChecklist, items = s.items,
            canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty(),
        )
        lastEdit = 0
        edited()
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(snapshot())
        apply(s)
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshot())
        apply(s)
    }

    private fun change(coalesce: Boolean = true, f: (EditorState) -> EditorState) {
        if (state.trashed || !state.loaded) return
        record(coalesce)
        state = f(state).copy(canUndo = true, canRedo = false)
        edited()
    }

    // ---- Text ----

    fun setTitle(v: String) { if (v != state.title) change { it.copy(title = v) } }
    fun setContent(v: String) { if (v != state.content) change { it.copy(content = v) } }

    // ---- Checklist ----

    fun setItemText(key: Long, text: String) {
        if (state.items.none { it.key == key && it.text != text }) return
        if ('\n' !in text) {
            change { s -> s.copy(items = s.items.map { if (it.key == key) it.copy(text = text) else it }) }
            return
        }
        // Enter (or a multi-line paste) splits the item into several.
        val parts = text.split('\n')
        change(coalesce = false) { s ->
            val index = s.items.indexOfFirst { it.key == key }
            val current = s.items[index]
            val added = parts.drop(1).map { EditorItem(nextKey++, it, current.checked) }
            val items = s.items.toMutableList().apply {
                set(index, current.copy(text = parts.first()))
                addAll(index + 1, added)
            }
            s.copy(items = items, focusTarget = added.last().key)
        }
    }

    fun addItem() = change(coalesce = false) { s ->
        val item = EditorItem(nextKey++, "")
        s.copy(items = if (newItemsAtBottom) s.items + item else listOf(item) + s.items, focusTarget = item.key)
    }

    fun addItemAfter(key: Long) = change(coalesce = false) { s ->
        val index = s.items.indexOfFirst { it.key == key }
        val item = EditorItem(nextKey++, "", checked = s.items.getOrNull(index)?.checked ?: false)
        s.copy(items = s.items.toMutableList().apply { add(index + 1, item) }, focusTarget = item.key)
    }

    fun toggleItem(key: Long) = change(coalesce = false) { s ->
        s.copy(items = s.items.map { if (it.key == key) it.copy(checked = !it.checked) else it })
    }

    fun removeItem(key: Long) = change(coalesce = false) { s ->
        val removed = s.items.firstOrNull { it.key == key }
        // Move the cursor to the neighbouring item in the same (checked / unchecked) group.
        val group = s.items.filter { it.checked == removed?.checked }
        val i = group.indexOfFirst { it.key == key }
        val neighbour = group.getOrNull(i - 1) ?: group.getOrNull(i + 1)
        s.copy(items = s.items.filter { it.key != key }, focusTarget = neighbour?.key)
    }

    fun uncheckAll() = change(coalesce = false) { s -> s.copy(items = s.items.map { it.copy(checked = false) }) }
    fun deleteChecked() = change(coalesce = false) { s -> s.copy(items = s.items.filter { !it.checked }) }

    /** Switches between free text and a checklist, one line per item. */
    fun toggleChecklist() = change(coalesce = false) { s ->
        if (s.isChecklist) {
            s.copy(isChecklist = false, items = emptyList(), content = s.items.joinToString("\n") { it.text })
        } else {
            val items = s.content.lines().filter { it.isNotBlank() }.map { EditorItem(nextKey++, it) }
                .ifEmpty { listOf(EditorItem(nextKey++, "")) }
            s.copy(isChecklist = true, content = "", items = items, focusTarget = items.last().key)
        }
    }

    // ---- Note properties (not part of undo history, do not bump the edited time) ----

    fun setColor(color: Int) {
        if (state.trashed) return
        state = state.copy(color = color)
        scheduleSave()
    }

    fun togglePinned() {
        if (state.trashed) return
        val pinned = !state.pinned
        state = state.copy(pinned = pinned, archived = if (pinned) false else state.archived)
        scheduleSave()
    }

    /** Archives or unarchives, then reports what happened so the screen can close. */
    fun toggleArchived(onDone: () -> Unit) {
        if (state.trashed) return
        val archived = !state.archived
        state = state.copy(archived = archived, pinned = if (archived) false else state.pinned)
        flush()
        onDone()
    }

    fun trash(onDone: () -> Unit) {
        saveJob?.cancel()
        appScope.launch {
            persist()
            // From here on nothing may write the note again, or it would be pulled back out of the trash.
            discarded = true
            val id = state.id
            if (id != 0L) { if (state.isEmpty) repo.deleteForever(listOf(id)) else repo.trash(listOf(id)) }
        }
        onDone()
    }

    fun restore() = viewModelScope.launch {
        repo.restore(listOf(state.id))
        state = state.copy(trashed = false)
        messageChannel.send("Note restored")
    }

    fun deleteForever(onDone: () -> Unit) {
        discarded = true
        saveJob?.cancel()
        val id = state.id
        appScope.launch { if (id != 0L) repo.deleteForever(listOf(id)) }
        onDone()
    }

    fun duplicate() = viewModelScope.launch {
        persist()
        if (state.id == 0L) return@launch
        repo.duplicate(state.id)
        messageChannel.send("Copy created")
    }

    fun setReminder(at: Long?, repeat: Repeat) = viewModelScope.launch {
        persist(force = true)
        repo.setReminder(state.id, at, repeat)
        state = state.copy(reminderAt = at, reminderRepeat = if (at == null) Repeat.NONE else repeat)
    }

    fun setLabel(label: Label, on: Boolean) = viewModelScope.launch {
        persist(force = true)
        repo.setLabel(listOf(state.id), label.id, on)
        refreshExtras()
    }

    fun createLabel(name: String) = viewModelScope.launch {
        persist(force = true)
        repo.createLabel(name)?.let { repo.setLabel(listOf(state.id), it, true) }
        refreshExtras()
    }

    fun addImage(uri: Uri) = viewModelScope.launch {
        persist(force = true)
        if (repo.addImage(state.id, uri)) refreshExtras() else messageChannel.send("Couldn't add image")
    }

    fun removeImage(image: NoteImage) = viewModelScope.launch {
        repo.removeImage(image)
        refreshExtras()
    }

    fun imageFile(image: NoteImage) = repo.imageFile(image)

    companion object {
        const val KEY_ID = "id"
        const val KEY_CHECKLIST = "checklist"
        const val KEY_LABEL = "labelId"
        const val KEY_PICK_IMAGE = "pickImage"
        private const val SAVE_DEBOUNCE_MS = 400L
        private const val UNDO_COALESCE_MS = 700L
        private const val MAX_UNDO = 100

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JotterApp
                EditorViewModel(app.repository, app.settings, app.scope, createSavedStateHandle())
            }
        }
    }
}
