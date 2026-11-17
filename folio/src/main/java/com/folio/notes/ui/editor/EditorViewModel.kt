package com.folio.notes.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.folio.notes.FolioApp
import com.folio.notes.data.ExportFormat
import com.folio.notes.data.Folder
import com.folio.notes.data.Note
import com.folio.notes.data.Settings
import com.folio.notes.markdown.Block
import com.folio.notes.markdown.Blocks
import com.folio.notes.markdown.ImageBlock
import com.folio.notes.markdown.Markdown
import com.folio.notes.markdown.MarkdownEditing
import com.folio.notes.markdown.SyntaxMode
import com.folio.notes.markdown.TableBlock
import com.folio.notes.markdown.TextBlock
import java.io.OutputStream
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Asks the screen to move keyboard focus to a block; [token] makes repeated requests distinct. */
data class FocusRequest(val blockId: Long, val token: Long)

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModel(application: Application, private val handle: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as FolioApp
    private val repository = app.repository
    private val vault = app.vault

    private val noteId = MutableStateFlow(handle.get<Long>(KEY_ID) ?: 0L)
    private var folderId = handle.get<Long>(KEY_FOLDER)?.takeIf { it > 0 } ?: Folder.DEFAULT_ID

    /** The stored note – pin, lock, folder, dates. Null while a new note has not been saved yet. */
    val note = noteId.flatMapLatest { if (it == 0L) flowOf(null) else repository.observeNote(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val folders = repository.folders.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings = app.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    var blocks by mutableStateOf<List<Block>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    /** The note is locked and the vault is closed: nothing can be shown or saved. */
    var needsUnlock by mutableStateOf(false); private set
    var missing by mutableStateOf(false); private set
    var readOnly by mutableStateOf(false); private set
    var focusedId by mutableStateOf<Long?>(null)
    var focusRequest by mutableStateOf<FocusRequest?>(null); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    val isNew get() = noteId.value == 0L
    val currentFolderId get() = note.value?.folderId ?: folderId

    private val undo = ArrayDeque<List<Block>>()
    private val redo = ArrayDeque<List<Block>>()
    private var lastTyping = 0L
    private var saveJob: Job? = null
    private val saveMutex = Mutex()
    private var locked = false
    private var closed = false
    private var token = 0L

    init {
        viewModelScope.launch {
            load(initialText = if (noteId.value == 0L) app.takeSharedText() else null)
            // Follow the vault: closing it hides a locked note, opening it brings the note back.
            vault.key.drop(1).collect { key ->
                if (!locked) return@collect
                if (key == null) { blocks = emptyList(); clearHistory(); needsUnlock = true }
                else if (needsUnlock) load(null)
            }
        }
    }

    private suspend fun load(initialText: String?) {
        val id = noteId.value
        if (id == 0L) {
            blocks = Blocks.parse(initialText.orEmpty())
            if (!initialText.isNullOrBlank()) scheduleSave()
            requestFocus(blocks.first().id)
        } else {
            val stored = repository.note(id)
            if (stored == null) { missing = true; return }
            locked = stored.locked
            readOnly = stored.deletedAt != null
            val content = repository.content(stored, vault.key.value)
            needsUnlock = content == null
            if (content != null) blocks = Blocks.parse(content)
        }
        loaded = true
    }

    private fun requestFocus(blockId: Long) { focusRequest = FocusRequest(blockId, ++token) }

    // ---- History ----------------------------------------------------------------------------------------

    private fun clearHistory() { undo.clear(); redo.clear(); canUndo = false; canRedo = false }

    /** Replaces the document. Typing within a second of more typing shares one undo step. */
    private fun commit(newBlocks: List<Block>, typing: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!typing || now - lastTyping > 1_000 || undo.isEmpty()) {
            undo.addLast(blocks)
            if (undo.size > 200) undo.removeFirst()
        }
        lastTyping = if (typing) now else 0
        redo.clear()
        blocks = newBlocks
        canUndo = true
        canRedo = false
        scheduleSave()
    }

    fun undo() = step(undo, redo)
    fun redo() = step(redo, undo)

    private fun step(from: ArrayDeque<List<Block>>, to: ArrayDeque<List<Block>>) {
        val previous = from.removeLastOrNull() ?: return
        to.addLast(blocks)
        blocks = previous
        lastTyping = 0
        canUndo = undo.isNotEmpty()
        canRedo = redo.isNotEmpty()
        scheduleSave()
    }

    // ---- Text -------------------------------------------------------------------------------------------

    private fun replace(block: Block) = blocks.map { if (it.id == block.id) block else it }

    fun onTextChange(blockId: Long, value: TextFieldValue) {
        val block = blocks.firstOrNull { it.id == blockId } as? TextBlock ?: return
        if (readOnly) return
        val edited = MarkdownEditing.onEdit(block.value, value, settings.value.syntax == SyntaxMode.HIDE)
        if (edited.text == block.text) blocks = replace(block.copy(value = edited)) // caret moved: not an edit
        else commit(replace(block.copy(value = edited)), typing = true)
    }

    /** The text block formatting applies to: the focused one, else the last. */
    val currentText: TextBlock?
        get() = when (val focused = blocks.firstOrNull { it.id == focusedId }) {
            is TextBlock -> focused
            null -> blocks.lastOrNull { it is TextBlock } as? TextBlock
            else -> null // the caret is in a table cell
        }

    fun format(transform: (TextFieldValue) -> TextFieldValue) {
        val block = currentText ?: return
        if (readOnly) return
        val out = transform(block.value)
        if (out != block.value) commit(replace(block.copy(value = out)))
        if (focusedId != block.id) requestFocus(block.id)
    }

    fun toggleCheckbox(blockId: Long, lineStart: Int) {
        val block = blocks.firstOrNull { it.id == blockId } as? TextBlock ?: return
        if (readOnly) return
        val text = MarkdownEditing.toggleCheckbox(block.text, lineStart)
        if (text != block.text) commit(replace(block.copy(value = block.value.copy(text = text))))
    }

    fun focusEnd() {
        val last = blocks.lastOrNull { it is TextBlock } as? TextBlock ?: return
        blocks = replace(last.copy(value = last.value.copy(selection = TextRange(last.text.length))))
        requestFocus(last.id)
    }

    // ---- Pictures and tables ----------------------------------------------------------------------------

    /** Puts [block] on its own line at the caret, splitting the text around it. */
    private fun insertBlock(block: Block) {
        val current = currentText
        if (current == null) { commit(Blocks.normalize(blocks + block)); return }
        val text = current.text
        val caret = current.value.selection.max.coerceIn(0, text.length)
        val line = Markdown.lineAt(Markdown.parseLines(text), caret)
        val blank = text.substring(line.start, line.end).isBlank()
        val before = if (blank) text.substring(0, maxOf(0, line.start - 1)) else text.substring(0, line.end)
        val after = if (line.end < text.length) text.substring(line.end + 1) else ""
        val tail = Blocks.text(after)
        val replacement = buildList {
            if (!(blank && line.start == 0)) add(current.copy(value = TextFieldValue(before)))
            add(block)
            add(tail)
        }
        commit(Blocks.normalize(blocks.flatMap { if (it.id == current.id) replacement else listOf(it) }))
        if (block is ImageBlock) requestFocus(tail.id)
    }

    fun removeBlock(id: Long) {
        if (readOnly) return
        commit(Blocks.normalize(blocks.filter { it.id != id }))
    }

    fun addImage(uri: Uri) {
        val key = attachmentKey() ?: if (locked) return else null
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { repository.attachments.add(uri, key) } ?: return@launch
            insertBlock(ImageBlock(Blocks.newId(), name))
        }
    }

    fun addImage(bitmap: Bitmap) {
        val key = attachmentKey() ?: if (locked) return else null
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { repository.attachments.add(bitmap, key) }
            insertBlock(ImageBlock(Blocks.newId(), name))
        }
    }

    /** Pictures of a locked note are encrypted like its text. */
    private fun attachmentKey(): SecretKey? = if (locked) vault.key.value else null

    fun insertTable() {
        if (readOnly) return
        insertBlock(TableBlock(Blocks.newId(), listOf(listOf("", ""), listOf("", ""))))
    }

    private fun table(id: Long, typing: Boolean = false, change: (List<List<String>>) -> List<List<String>>) {
        val block = blocks.firstOrNull { it.id == id } as? TableBlock ?: return
        if (readOnly) return
        val rows = change(block.rows)
        if (rows.isEmpty() || rows.first().isEmpty()) removeBlock(id) else commit(replace(block.copy(rows = rows)), typing)
    }

    fun setCell(id: Long, row: Int, column: Int, text: String) = table(id, typing = true) { rows ->
        rows.mapIndexed { r, cells -> if (r != row) cells else cells.mapIndexed { c, cell -> if (c == column) text else cell } }
    }
    fun addRow(id: Long) = table(id) { rows -> rows + listOf(List(rows.first().size) { "" }) }
    fun addColumn(id: Long) = table(id) { rows -> rows.map { it + "" } }
    fun removeRow(id: Long) = table(id) { rows -> rows.dropLast(1) }
    fun removeColumn(id: Long) = table(id) { rows -> rows.map { it.dropLast(1) } }

    // ---- Saving -----------------------------------------------------------------------------------------

    val markdown get() = Blocks.serialize(blocks)

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(500); flush() }
    }

    /**
     * Writes the note now. Runs on the application scope so it finishes even if the screen is going away; the
     * key is captured here because the vault may close before the write happens.
     */
    fun flush(thenDiscardIfEmpty: Boolean = false): Job? {
        saveJob?.cancel()
        if (!loaded || needsUnlock || readOnly || closed) return null
        val content = markdown
        val key = vault.key.value
        return app.scope.launch {
            saveMutex.withLock {
                val id = noteId.value
                if (content.isBlank()) {
                    if (id != 0L && thenDiscardIfEmpty) repository.deleteForever(listOf(id))
                    else if (id != 0L) repository.save(id, folderId, content, key)
                } else {
                    val saved = repository.save(id, folderId, content, key)
                    if (id == 0L) { noteId.value = saved; handle[KEY_ID] = saved }
                }
            }
        }
    }

    /** Leaving the editor: save, and drop the note if nothing was written in it. */
    fun close() {
        flush(thenDiscardIfEmpty = true)
        closed = true
    }

    /** Makes sure the note exists in the database, for actions that need its id. Null for an empty new note. */
    private suspend fun ensureSaved(): Long? {
        flush()?.join()
        return noteId.value.takeIf { it != 0L }
    }

    // ---- Note actions -----------------------------------------------------------------------------------

    fun togglePin() = viewModelScope.launch {
        val id = ensureSaved() ?: return@launch
        repository.setPinned(listOf(id), note.value?.pinned != true)
    }

    fun moveTo(folder: Long) = viewModelScope.launch {
        folderId = folder
        noteId.value.takeIf { it != 0L }?.let { repository.move(listOf(it), folder) }
    }

    fun trash(onDone: () -> Unit) = viewModelScope.launch {
        val id = ensureSaved()
        closed = true
        if (id != null) repository.trash(listOf(id))
        onDone()
    }

    fun restore() = viewModelScope.launch {
        repository.restore(listOf(noteId.value))
        readOnly = false
    }

    fun lock(key: SecretKey) = viewModelScope.launch {
        val id = ensureSaved() ?: return@launch
        repository.lock(id, key)
        locked = true
    }

    fun removeLock(key: SecretKey) = viewModelScope.launch {
        val id = ensureSaved() ?: return@launch
        if (repository.unlock(id, key)) locked = false
    }

    fun lockNow() {
        flush()
        vault.close()
    }

    // ---- Export -----------------------------------------------------------------------------------------

    val title get() = Markdown.titleAndPreview(markdown).first.ifBlank { "Note" }
    fun exportTarget(format: ExportFormat) = app.exporter.target(title, markdown, format)
    suspend fun export(format: ExportFormat, out: OutputStream) = app.exporter.export(markdown, format, vault.key.value, out)
    fun html() = app.exporter.html(title, markdown, vault.key.value)

    override fun onCleared() { if (!closed) flush() }

    companion object {
        const val KEY_ID = "id"
        const val KEY_FOLDER = "folder"
    }
}
