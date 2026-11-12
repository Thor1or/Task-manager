package com.jotter.notes

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import android.os.Looper
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.test.espresso.Espresso
import java.time.Duration
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.jotter.notes.data.ChecklistItem
import com.jotter.notes.data.Note
import com.jotter.notes.data.Repeat
import com.jotter.notes.data.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Drives the real activity, database and view models end to end. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class AppFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app get() = rule.activity.application as JotterApp
    private fun notes() = runBlocking { app.repository.getAll() }
    private fun note(title: String) = notes().firstOrNull { it.note.title == title }

    @Before
    fun grantNotifications() {
        shadowOf(rule.activity.application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/screenshots/$name.png")
    }

    private fun waitForText(text: String) = rule.waitUntilAtLeastOneExists(hasText(text), 5_000)
    private fun waitGone(text: String) = rule.waitUntilDoesNotExist(hasText(text), 5_000)
    private fun click(description: String) = rule.onNodeWithContentDescription(description).performClick()
    /** Back key delivered to whichever window has focus (activity, dialog or bottom sheet). */
    private fun back() { rule.waitForIdle(); Espresso.pressBack(); rule.waitForIdle() }

    /** Polls [condition], letting delayed main-thread work (e.g. the debounced autosave) run in between. */
    private fun waitFor(what: String, condition: () -> Boolean) =
        try {
            rule.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                condition()
            }
        } catch (e: Throwable) { throw AssertionError("Timed out waiting for: $what", e) }

    private val isDrawerEntry = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private fun drawerEntry(name: String) = rule.onNode(hasText(name) and isDrawerEntry)
    /** A popup-menu or dialog button, as opposed to the drawer entry of the same name. */
    private fun clickAction(name: String) = rule.onNode(hasText(name) and !isDrawerEntry and hasClickAction()).performClick()

    private fun openDrawerEntry(name: String) {
        click("Open navigation drawer")
        rule.waitForIdle()
        drawerEntry(name).performClick()
        rule.waitForIdle()
    }

    private fun seed(note: Note, vararg items: String) =
        runBlocking { app.repository.save(note, items.map { ChecklistItem(noteId = 0, text = it) }) }

    @Test
    fun emptyAppShowsHint() {
        waitForText("Notes you add appear here")
        shot("01-empty")
    }

    @Test
    fun writeTextNote_pinColorLabelReminder() {
        waitForText("Notes you add appear here")
        click("New note")
        rule.onNodeWithTag("title").performTextInput("Trip plan")
        rule.onNodeWithTag("content").performTextInput("Book flights\nReserve hotel")
        click("Pin")
        rule.onNodeWithContentDescription("Unpin").assertIsDisplayed()

        click("Color")
        rule.waitUntilAtLeastOneExists(hasContentDescription("Color Sand"), 5_000)
        click("Color Sand")
        rule.onNode(hasContentDescription("Close sheet")).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntilDoesNotExist(hasContentDescription("Color Sand"), 5_000)

        click("More")
        rule.onNodeWithText("Labels").performClick()
        rule.onNodeWithTag("labelQuery").performTextInput("Travel")
        rule.onNodeWithText("Create \"Travel\"").performClick()
        waitFor("label attached") { note("Trip plan")?.labels?.map { it.name } == listOf("Travel") }
        shot("11-labels-picker")
        click("Close labels")
        rule.onNodeWithTag("title").assertIsDisplayed()

        click("Reminder")
        rule.onNodeWithTag("reminderRepeat").performClick()
        rule.onNodeWithText("Weekly").performClick()
        rule.onNodeWithText("Save").performClick()
        waitFor("reminder stored") { note("Trip plan")?.note?.reminderAt != null }
        shot("03-editor")

        click("Navigate up")
        waitForText("Pinned")
        rule.onNodeWithText("Trip plan").assertIsDisplayed()
        rule.onNode(hasTestTag("noteCard") and hasText("Travel")).assertIsDisplayed()

        val saved = note("Trip plan")!!
        assertEquals("Book flights\nReserve hotel", saved.note.content)
        assertTrue(saved.note.pinned)
        assertEquals(3, saved.note.color)
        assertEquals(Repeat.WEEKLY.name, saved.note.reminderRepeat)
        assertEquals(1, notes().size)
    }

    @Test
    fun leavingAnUntouchedNoteCreatesNothing() {
        waitForText("Notes you add appear here")
        click("New note")
        rule.onNodeWithTag("title").assertIsDisplayed()
        click("Navigate up")
        waitForText("Notes you add appear here")
        assertTrue(notes().isEmpty())

        // A note that was typed into and then cleared again is also dropped.
        click("New note")
        rule.onNodeWithTag("title").performTextInput("x")
        waitFor("autosave") { notes().size == 1 }
        rule.onNodeWithTag("title").performTextReplacement("")
        click("Navigate up")
        waitFor("empty note removed") { notes().isEmpty() }
    }

    @Test
    fun checklist_splitOnEnter_check_undo_convert() {
        waitForText("Notes you add appear here")
        click("New list")
        rule.onNodeWithTag("title").performTextInput("Groceries")
        rule.onAllNodesWithTag("item").onFirst().performTextInput("Milk\nEggs\nBread")
        rule.onAllNodesWithTag("item").assertCountEquals(3)

        rule.onAllNodes(isToggleable()).onFirst().performClick()
        rule.onNodeWithText("1 checked item").assertIsDisplayed()
        shot("04-checklist")

        click("Undo")
        rule.onAllNodesWithText("1 checked item").assertCountEquals(0)
        click("Redo")
        rule.onNodeWithText("1 checked item").assertIsDisplayed()

        rule.onNodeWithTag("addItem").performClick()
        rule.onAllNodesWithTag("item").assertCountEquals(4)

        click("Navigate up")
        waitForText("Groceries")
        waitFor("list saved") { note("Groceries")?.items?.count { it.checked } == 1 }
        val saved = note("Groceries")!!
        assertTrue(saved.note.isChecklist)
        assertEquals(listOf("Milk" to true, "Eggs" to false, "Bread" to false),
            saved.sortedItems.filter { it.text.isNotBlank() }.map { it.text to it.checked })
        rule.onNodeWithText("Eggs").assertIsDisplayed()

        // Turning the list back into text keeps one line per item.
        rule.onNodeWithText("Groceries").performClick()
        click("Add")
        rule.onNodeWithText("Hide checkboxes").performClick()
        rule.onNodeWithTag("content").assertIsDisplayed()
        click("Navigate up")
        waitFor("converted to text") { note("Groceries")?.note?.isChecklist == false }
        assertTrue(note("Groceries")!!.note.content.startsWith("Milk\nEggs\nBread"))
    }

    @Test
    fun search_select_archive_trash_restore() {
        seed(Note(title = "Trip plan", content = "Book flights", color = 3, pinned = true))
        seed(Note(title = "Groceries", isChecklist = true, color = 4), "Milk", "Eggs", "Bread")
        seed(Note(title = "Ideas", content = "Learn the ukulele", color = 8))
        seed(Note(content = "Call the dentist on Monday"))
        waitForText("Groceries")
        shot("02-home")

        // Search matches checklist items and hides everything else.
        rule.onNodeWithTag("searchPill").performClick()
        shot("05-search")
        rule.onNodeWithTag("searchField").performTextInput("eggs")
        waitGone("Trip plan")
        rule.onNodeWithText("Groceries").assertIsDisplayed()
        click("Close search")
        waitForText("Trip plan")

        // Multi-select two notes and archive them, then undo.
        rule.onNodeWithText("Ideas").performTouchInput { longClick() }
        rule.onNodeWithText("Groceries").performClick()
        rule.onNodeWithText("2").assertIsDisplayed()
        shot("06-selection")
        click("More")
        clickAction("Archive")
        waitForText("2 notes archived")
        waitFor("archived") { note("Ideas")!!.note.archived && note("Groceries")!!.note.archived }
        rule.onNodeWithText("Undo").performClick()
        waitFor("undo archive") { !note("Ideas")!!.note.archived && !note("Groceries")!!.note.archived }
        waitForText("Ideas")

        // Label two notes at once from the selection bar.
        rule.onNodeWithText("Ideas").performTouchInput { longClick() }
        rule.onNodeWithText("Groceries").performClick()
        click("Change labels")
        rule.onNodeWithTag("labelQuery").performTextInput("Home")
        rule.onNodeWithText("Create \"Home\"").performClick()
        waitFor("both labelled") { listOf("Ideas", "Groceries").all { t -> note(t)!!.labels.any { it.name == "Home" } } }
        back()
        rule.onNodeWithTag("searchPill").assertIsDisplayed()
        rule.onAllNodes(hasTestTag("noteCard") and hasText("Home")).assertCountEquals(2)

        // Recolour from the selection bar.
        rule.onNodeWithText("Ideas").performTouchInput { longClick() }
        click("Change color")
        rule.waitUntilAtLeastOneExists(hasContentDescription("Color Coral"), 5_000)
        click("Color Coral")
        waitFor("recoloured") { note("Ideas")!!.note.color == 1 }

        // Delete one note, find it in Trash, restore it.
        rule.onNodeWithText("Ideas").performTouchInput { longClick() }
        click("More")
        clickAction("Delete")
        waitFor("trashed") { note("Ideas")!!.note.trashedAt != null }
        waitGone("Ideas")
        openDrawerEntry("Trash")
        waitForText("Ideas")
        shot("07-trash")
        rule.onNodeWithText("Ideas").performTouchInput { longClick() }
        click("Restore")
        waitFor("restored") { note("Ideas")!!.note.trashedAt == null }
        waitForText("No notes in Trash")

        // Back leaves Trash for the main list.
        back()
        waitForText("Ideas")
        rule.onNodeWithTag("searchPill").assertIsDisplayed()
        assertEquals(4, notes().size)
    }

    @Test
    fun trashedNoteIsReadOnlyAndCanBeDeletedForever() {
        val id = seed(Note(title = "Old", content = "stuff"))
        runBlocking { app.repository.trash(listOf(id)) }
        openDrawerEntry("Trash")
        waitForText("Old")
        rule.onNodeWithText("Old").performClick()
        waitForText("Note in Trash")
        rule.onNodeWithText("Delete forever").performClick()
        rule.onNodeWithText("Delete").performClick()
        waitFor("deleted") { notes().isEmpty() }
        waitForText("No notes in Trash")
    }

    @Test
    fun deleteFromEditorMovesNoteToTrashAndKeepsItThere() {
        seed(Note(title = "Draft", content = "text"))
        waitForText("Draft")
        rule.onNodeWithText("Draft").performClick()
        rule.onNodeWithTag("title").performTextInput("!")
        click("More")
        rule.onNodeWithText("Delete").performClick()
        waitFor("in trash with the last edit") { notes().singleOrNull()?.let { it.note.trashedAt != null && it.note.title.contains("!") } == true }
        waitForText("Notes you add appear here")
        rule.waitForIdle()
        assertNotNull("closing the editor must not pull it back out of the trash", notes().single().note.trashedAt)
    }

    @Test
    fun labels_createFilterAndDelete() {
        val a = seed(Note(title = "Work note"))
        seed(Note(title = "Other note"))
        runBlocking { app.repository.setLabel(listOf(a), app.repository.createLabel("Work")!!, true) }
        waitForText("Work note")

        openDrawerEntry("Work")
        waitGone("Other note")
        rule.onNodeWithText("Work note").assertIsDisplayed()

        // A note created while a label is open gets that label.
        click("New note")
        rule.onNodeWithTag("title").performTextInput("From label")
        click("Navigate up")
        waitFor("new note labelled") { note("From label")?.labels?.map { it.name } == listOf("Work") }

        click("Open navigation drawer")
        rule.onNodeWithText("Edit labels").performClick()
        shot("08-labels")
        rule.onNodeWithTag("newLabel").performTextInput("Home")
        click("Create label")
        waitForText("Home")
        click("Delete label Work")
        rule.onNodeWithText("Delete").performClick()
        waitFor("label removed from notes") { note("Work note")!!.labels.isEmpty() }
        click("Navigate up")
        // The label being viewed no longer exists, so the main list is shown again.
        waitForText("Other note")
        assertNull(runBlocking { app.database.noteDao().labelByName("Work") })
    }

    @Test
    fun settings_darkThemeAndListView() {
        seed(Note(title = "Trip plan", content = "Book flights", color = 3, pinned = true))
        seed(Note(title = "Groceries", isChecklist = true, color = 4), "Milk", "Eggs")
        seed(Note(content = "Call the dentist on Monday"))
        waitForText("Groceries")
        click("Single-column view")
        rule.waitUntilAtLeastOneExists(hasContentDescription("Multi-column view"), 5_000)

        openDrawerEntry("Settings")
        shot("09-settings")
        rule.onNodeWithText("Theme").performClick()
        rule.onNodeWithText("Dark").performClick()
        waitFor("theme stored") { runBlocking { app.settings.settings.first().theme } == ThemeMode.DARK }
        waitForText("Dark")
        click("Navigate up")
        waitForText("Groceries")
        shot("10-dark-list")
        assertFalse(runBlocking { app.settings.settings.first().gridView })
    }
}
