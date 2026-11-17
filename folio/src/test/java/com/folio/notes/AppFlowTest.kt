package com.folio.notes

import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.notes.data.Folder
import com.folio.notes.data.Note
import com.folio.notes.data.ThemeMode
import com.folio.notes.markdown.MarkdownStyler
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.time.Duration
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

    private val app get() = rule.activity.application as FolioApp
    private fun notes(): List<Note> = runBlocking { app.repository.allNotes() }
    private fun note(title: String) = notes().firstOrNull { it.title == title }
    private fun seed(content: String, folder: Long = Folder.DEFAULT_ID, modifiedAt: Long? = null, pinned: Boolean = false): Long =
        runBlocking { app.repository.save(0, folder, content, null, modifiedAt = modifiedAt, createdAt = modifiedAt, pinned = pinned) }

    /** The settings store is one per process, so it outlives each test's fresh application. */
    @Before
    fun resetSettings() = runBlocking { app.settings.clear(); app.vault.close() }

    private fun shot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/screenshots/$name.png")
    }

    private fun waitForText(text: String) = waitFor("text \"$text\"") { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    private fun click(description: String) = rule.onNodeWithContentDescription(description).performClick()
    private fun clickText(text: String) = rule.onNodeWithText(text).performClick()
    private fun back() { rule.waitForIdle(); Espresso.pressBack(); rule.waitForIdle() }

    /** Polls [condition], letting delayed main-thread work (e.g. the debounced autosave) run in between. */
    private fun waitFor(what: String, condition: () -> Boolean) =
        try {
            rule.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                condition()
            }
        } catch (e: Throwable) {
            runCatching { println(rule.onAllNodes(androidx.compose.ui.test.isRoot()).printToString(40)) }
            throw AssertionError("Timed out waiting for: $what", e)
        }

    private fun editor() = rule.onAllNodesWithTag("text").onFirst()

    /** Taps the tick box drawn on the [index]-th checklist line of the first text block. */
    private fun tapCheckbox(index: Int) {
        val node = editor().fetchSemanticsNode()
        val results = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val layout = results.first()
        val shown = layout.layoutInput.text.text
        var at = -1
        repeat(index + 1) { at = shown.indexOf(MarkdownStyler.BOX, at + 1) }
        val center = layout.getBoundingBox(at).center
        editor().performTouchInput { click(Offset(center.x, center.y)) }
        rule.waitForIdle()
    }

    @Test
    fun writeChecklistNote_formatting_autosave() {
        waitForText("No Notes")
        shot("01-empty")
        click("New note")
        editor().performTextInput("Groceries")
        editor().performTextInput("\n")
        click("Checklist")
        editor().performTextInput("Milk")
        editor().performTextInput("\n")
        editor().performTextInput("Eggs")
        editor().performTextInput("\n")
        editor().performTextInput("\n") // an empty item ends the list
        click("Format")
        click("Bold")
        editor().performTextInput("soon")
        click("Bold")
        editor().performTextInput(" #shopping")
        waitFor("autosave") { note("Groceries") != null }
        assertEquals("Groceries\n- [ ] Milk\n- [ ] Eggs\n**soon** #shopping", note("Groceries")!!.content)

        tapCheckbox(0)
        waitFor("tick saved") { note("Groceries")?.content?.contains("- [x] Milk") == true }
        shot("02-editor-checklist")

        click("Undo")
        waitFor("undo saved") { note("Groceries")?.content?.contains("- [ ] Milk") == true }
        click("Redo")
        waitFor("redo saved") { note("Groceries")?.content?.contains("- [x] Milk") == true }

        click("Back")
        waitForText("Groceries")
        rule.onNodeWithText("☑ Milk", substring = true).assertIsDisplayed()
        assertEquals(1, notes().size)
        assertEquals(listOf("shopping"), runBlocking { app.repository.tags.first() }.map { it.tag })
        shot("03-list")
    }

    @Test
    fun emptyNoteIsDiscarded() {
        waitForText("No Notes")
        click("New note")
        click("Back")
        waitForText("No Notes")
        assertTrue(notes().isEmpty())

        click("New note")
        editor().performTextInput("x")
        waitFor("autosave") { notes().size == 1 }
        editor().performTextReplacement("")
        click("Back")
        waitFor("empty note removed") { notes().isEmpty() }
    }

    @Test
    fun tableAndSketch() {
        waitForText("No Notes")
        click("New note")
        editor().performTextInput("Budget")
        click("Table")
        rule.onNodeWithContentDescription("Cell 1, 1").performTextInput("Item")
        rule.onNodeWithContentDescription("Cell 1, 2").performTextInput("Cost")
        rule.onNodeWithContentDescription("Cell 2, 1").performTextInput("Rent")
        clickText("+ Row")
        clickText("+ Column")
        rule.onNodeWithContentDescription("Cell 3, 3").performTextInput("x|y")
        waitFor("table saved") { note("Budget")?.content?.contains("| Rent |") == true }
        assertEquals("Budget\n| Item | Cost |  |\n| --- | --- | --- |\n| Rent |  |  |\n|  |  | x\\|y |\n", note("Budget")!!.content)

        click("Sketch")
        rule.onNodeWithTag("sketchCanvas").performTouchInput { swipeRight() }
        rule.onNodeWithTag("sketchCanvas").performTouchInput { swipeLeft(startX = 600f, endX = 200f) }
        shot("04-sketch")
        clickText("Done")
        waitFor("sketch attached") { note("Budget")?.attachmentList?.size == 1 }
        rule.waitUntilAtLeastOneExists(hasContentDescription("Picture"), 5_000)
        assertTrue(app.repository.attachments.file(note("Budget")!!.attachmentList.single()).length() > 0)
        shot("05-table-and-picture")

        click("Remove picture")
        waitFor("picture removed") { note("Budget")?.attachmentList?.isEmpty() == true }
        click("Delete table")
        waitFor("table removed") { note("Budget")?.content?.trim() == "Budget" }
    }

    @Test
    fun folders_move_pin_trash_restore() {
        val a = seed("Alpha\nfirst body")
        seed("Beta\nsecond body #work")
        waitForText("Alpha")
        click("Folders")
        waitForText("All Notes")
        // Robolectric cannot settle a dialog that holds a text field, so the two naming prompts are skipped
        // and the folders made the way the prompts make them.
        val work = runBlocking { app.repository.createFolder("Work") }
        runBlocking { app.repository.createFolder("Projects", work) }
        rule.waitForIdle()
        waitForText("Projects")
        rule.onNodeWithText("Work").performTouchInput { longClick() }
        rule.onNodeWithText("New Subfolder").assertIsDisplayed()
        back()
        rule.onNodeWithText("#work").assertIsDisplayed()
        shot("06-folders")

        clickText("All Notes")
        waitForText("Alpha")
        rule.onNodeWithContentDescription("Note: Alpha").performTouchInput { longClick() }
        clickText("Move")
        rule.onAllNodesWithTag("pickFolder").onLast().performClick() // Projects
        val projects = runBlocking { app.repository.folders() }.first { it.name == "Projects" }
        waitFor("moved") { notes().first { it.id == a }.folderId == projects.id }
        assertEquals("Work", runBlocking { app.repository.folders() }.first { it.id == projects.parentId }.name)

        rule.onNodeWithContentDescription("Note: Beta").performTouchInput { longClick() }
        clickText("Pin Note")
        waitForText("Pinned")
        shot("07-pinned")

        rule.onNodeWithContentDescription("Note: Alpha").performTouchInput { swipeLeft() }
        waitFor("trashed") { notes().first { it.id == a }.deletedAt != null }
        waitForText("Undo")
        clickText("Undo")
        waitFor("restored") { notes().first { it.id == a }.deletedAt == null }

        // Deleting the parent folder sends its sub-folder's notes to Recently Deleted.
        click("Folders")
        rule.onNodeWithText("Work").performTouchInput { longClick() }
        clickText("Delete")
        rule.onAllNodesWithText("Delete").onLast().performClick()
        waitFor("folder gone") { runBlocking { app.repository.folders() }.size == 1 }
        val alpha = notes().first { it.id == a }
        assertNotNull(alpha.deletedAt)
        assertEquals(Folder.DEFAULT_ID, alpha.folderId)
        waitForText("Recently Deleted")
        clickText("Recently Deleted")
        waitForText("Alpha")
        shot("08-trash")
        rule.onNodeWithContentDescription("Note: Alpha").performTouchInput { longClick() }
        clickText("Delete Permanently")
        rule.onAllNodesWithText("Delete").onLast().performClick()
        waitFor("deleted for good") { notes().none { it.id == a } }
    }

    @Test
    fun search_tags_gallery_select() {
        seed("Meeting notes\nDiscuss the *quarterly* roadmap #work")
        seed("Recipe\nPasta with tomatoes #food")
        seed("Ideas\nA 100% wild thought")
        waitForText("Recipe")
        rule.onNodeWithTag("search").performTextInput("roadmap")
        rule.waitUntilDoesNotExist(hasText("Recipe"), 5_000)
        rule.onNodeWithText("Meeting notes").assertIsDisplayed()
        rule.onNodeWithTag("search").performTextReplacement("100%")
        waitForText("Ideas")
        rule.onAllNodesWithTag("noteItem").assertCountEquals(1)
        click("Clear search")
        waitForText("Recipe")

        click("More")
        clickText("View as Gallery")
        waitFor("gallery on") { runBlocking { app.settings.current() }.gallery }
        waitForText("Pasta with tomatoes #food")
        shot("09-gallery")
        click("More")
        clickText("View as List")

        click("More")
        clickText("Select Notes")
        rule.onNodeWithContentDescription("Note: Recipe").performClick()
        rule.onNodeWithContentDescription("Note: Ideas").performClick()
        waitForText("2 Selected")
        clickText("Delete")
        waitFor("both trashed") { notes().count { it.deletedAt != null } == 2 }

        click("Folders")
        waitForText("#work")
        rule.onAllNodesWithText("#food").assertCountEquals(0) // its note is in the trash
        clickText("#work")
        waitForText("Meeting notes")
        rule.onAllNodesWithTag("noteItem").assertCountEquals(1)
    }

    @Test
    fun lockedNotesAreEncryptedAndNeedThePassword() {
        val id = seed("Secret plan\nThe treasure is under the oak #private")
        waitForText("Secret plan")
        rule.onNodeWithContentDescription("Note: Secret plan").performTouchInput { longClick() }
        // (The password prompt itself cannot run under Robolectric; see folders_move_pin_trash_restore.)
        runBlocking { app.vault.setPassword("hunter2") }
        clickText("Lock Note")
        waitFor("locked") { notes().first { it.id == id }.locked }
        val stored = notes().first { it.id == id }
        assertFalse(stored.content.contains("treasure"))
        assertEquals("", stored.preview)
        assertTrue(runBlocking { app.repository.tags.first() }.isEmpty())
        waitForText("Locked")

        // The vault is open right after setting the password; closing it hides the note.
        click("More")
        clickText("Lock Notes Now")
        rule.onNodeWithContentDescription("Note: Secret plan").performClick()
        waitForText("This note is locked.")
        shot("10-locked")
        assertFalse(runBlocking { app.vault.open("wrong") })
        assertTrue(runBlocking { app.vault.open("hunter2") })
        rule.waitUntilAtLeastOneExists(hasText("treasure", substring = true), 5_000)

        // Edits to a locked note are saved encrypted.
        editor().performTextInput("!")
        click("Back")
        waitFor("saved") { runBlocking { app.repository.content(notes().first { it.id == id }, app.vault.key.value) }?.contains("!") == true }
        assertFalse(notes().first { it.id == id }.content.contains("treasure"))

        rule.onNodeWithContentDescription("Note: !Secret plan").performTouchInput { longClick() }
        clickText("Remove Lock")
        waitFor("unlocked") { !notes().first { it.id == id }.locked }
        assertTrue(notes().first { it.id == id }.content.contains("treasure"))
        assertEquals(listOf("private"), runBlocking { app.repository.tags.first() }.map { it.tag })
    }

    @Test
    fun richNoteScreenshots() {
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        val id = seed(
            "Lisbon trip\n" +
                "## Before we go\n" +
                "- [x] Book **flights** for the 12th\n" +
                "- [x] Reserve the hotel in *Alfama*\n" +
                "- [ ] Buy ==travel insurance==\n" +
                "- [ ] Print the [tram map](https://example.com/map)\n" +
                "## Ideas\n" +
                "1. Sunset at the Miradouro\n" +
                "2. Day trip to Sintra\n" +
                "   - Palace in the morning\n" +
                "> Pack light, walk a lot.\n" +
                "\n" +
                "| Day | Plan |\n| --- | --- |\n| Fri | Arrive, dinner |\n| Sat | Old town |\n" +
                "Budget is `~€900` each. #travel #2026",
            modifiedAt = now, pinned = true,
        )
        seed("Shopping list\n- [ ] Oat milk\n- [ ] Coffee beans", modifiedAt = now - 3_600_000)
        seed("Book notes\nThe chapter on habit loops was the best one.", modifiedAt = now - day)
        seed("Garden plan\nTomatoes on the south side #home", modifiedAt = now - 4 * day)
        seed("Standup talking points\nShipped the importer; next is search.", modifiedAt = now - 20 * day)
        seed("Gift ideas\nA film camera for Ana", modifiedAt = now - 200 * day)
        waitForText("Lisbon trip")
        shot("11-list-sections")

        rule.onNodeWithContentDescription("Note: Lisbon trip").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Sunset at the Miradouro", substring = true), 5_000)
        shot("12-editor-rich")
        click("Format")
        shot("13-editor-format-bar")
        click("Share and export")
        waitForText("Share & Export")
        shot("14-export")
        clickText("Cancel")

        runBlocking { app.settings.setSyntax(com.folio.notes.markdown.SyntaxMode.SHOW) }
        shot("15-editor-syntax-shown")
        runBlocking { app.settings.setSyntax(com.folio.notes.markdown.SyntaxMode.HIDE); app.settings.setTheme(ThemeMode.DARK) }
        shot("16-editor-dark")
        click("Back")
        waitForText("Shopping list")
        shot("17-list-dark")
        click("Folders")
        click("Settings")
        waitForText("Locked Notes")
        shot("18-settings-dark")
        assertNull(notes().first { it.id == id }.deletedAt)
    }
}
