package com.thor1or.dayloop

import android.app.Application
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.thor1or.dayloop.data.Habit
import com.thor1or.dayloop.data.PlanBlock
import com.thor1or.dayloop.data.TimeEntry
import com.thor1or.dayloop.logic.NlParser
import com.thor1or.dayloop.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime

/**
 * Starts the real app on the JVM with some data, walks through every tab and the main flows,
 * and saves a screenshot of each under app/build/screens.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SmokeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val repo get() = app.repo
    private val today = LocalDate.now()
    private val day = today.toEpochDay()

    init {
        // Like a real phone: nothing takes keyboard focus until it is tapped.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        // Data must exist before the activity (and its view model) starts.
        runBlocking { seed() }
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** The clock is driven by hand: the running timer redraws every second and never idles. */
    private fun pump() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        rule.mainClock.advanceTimeBy(100)
    }

    private fun settle() {
        repeat(12) {
            pump()
            Thread.sleep(15)
        }
    }

    /** Waits for [text] to show up: database changes reach the screen from a background thread. */
    private fun await(text: String) {
        rule.waitUntil(10_000) {
            pump()
            rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun click(text: String, index: Int = 0) {
        await(text)
        // The click action is invoked directly; simulated touches are dropped now and then
        // while the test clock is held still.
        rule.onAllNodesWithText(text)[index].performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    /**
     * Closes the open dialog with the back button. Dialog contents are not queried: Robolectric
     * keeps re-measuring dialog windows, so Compose never reports them as idle.
     */
    private fun closeDialog() {
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog
        assertTrue(dialog.isShowing)
        dialog.onBackPressedDispatcher.onBackPressed()
        settle()
        assertTrue(!dialog.isShowing)
    }

    /** Clicks [text] and waits for the dialog it opens. */
    private fun openDialog(text: String) {
        val before = org.robolectric.shadows.ShadowDialog.getShownDialogs().size
        click(text)
        rule.waitUntil(10_000) {
            pump()
            val all = org.robolectric.shadows.ShadowDialog.getShownDialogs()
            all.size > before && all.last().isShowing
        }
        settle()
    }

    private fun shot(name: String) {
        settle()
        captureScreenRoboImage("build/screens/$name.png")
    }

    private suspend fun seed() {
        val work = repo.groupIdFor("Work")
        val health = repo.groupIdFor("Health")
        val home = repo.groupIdFor("Home")
        fun parse(s: String) = NlParser.parse(s, today, LocalTime.of(6, 0))
        repo.addTask(parse("Write quarterly report today for 90m #work p1"))
        repo.addTask(parse("Call the bank today 14:30 #home"))
        repo.addTask(parse("Buy bread tomorrow at 9am #home"))
        repo.addTask(parse("Renew passport in 3 days p2"))
        repo.addTask(parse("Idea: trip to the coast"))
        repo.addTask(parse("Water plants every mon, thu #home"))
        val overdue = repo.addTask(parse("Reply to landlord"))
        repo.saveTask(repo.dao.task(overdue)!!.copy(dueDay = day - 2))

        val meditate = repo.saveHabit(Habit(name = "Meditate", groupId = health, reminderMinute = 7 * 60 + 30, targetMin = 10, createdDay = day - 120))
        val read = repo.saveHabit(Habit(name = "Read", groupId = home, planMin = 60, sessions = 2, createdDay = day - 120))
        val run = repo.saveHabit(Habit(name = "Run", groupId = health, daysMask = 0b0010101, planMin = 40, createdDay = day - 120))
        for (d in 1..120L) {
            if (d % 5 != 0L) repo.setHabitChecked(meditate, day - d, true)
            if (d % 3 != 0L) repo.setHabitChecked(read, day - d, true)
            if (d % 2 == 0L) repo.setHabitChecked(run, day - d, true)
        }

        // A plan with two fixed points and more work than fits before lunch.
        repo.addHabitsToPlan(day)
        repo.addTasksToPlan(day, repo.dao.tasksNow().filter { it.dueDay == day })
        repo.addBlock(PlanBlock(day = day, position = 0, title = "Deep work", groupId = work, desiredMin = 180))
        repo.addBlock(PlanBlock(day = day, position = 0, title = "Lunch", desiredMin = 45, fixedStart = 12 * 60 + 30, rigid = true))
        repo.addBlock(PlanBlock(day = day, position = 0, title = "Email and admin", groupId = work, desiredMin = 40))

        val start = System.currentTimeMillis() - 3 * 3_600_000
        repo.saveEntry(TimeEntry(title = "Deep work", groupId = work, startMs = start, endMs = start + 80 * 60_000))
        repo.saveEntry(TimeEntry(title = "Meditate", habitId = meditate, groupId = health, startMs = start + 90 * 60_000, endMs = start + 102 * 60_000))
    }

    @Test
    fun everyScreenRendersAndTheMainFlowsWork() {
        rule.mainClock.autoAdvance = false
        settle()

        // Plan
        await("Deep work")
        await("Lunch")
        shot("1-plan")

        // The same day as time blocks, then back to the list.
        click("Blocks")
        shot("1b-plan-blocks")
        click("List")

        // Begin an activity: it becomes the running timer, shown in the bar.
        click("Begin", 2)
        rule.waitUntil(5_000) { runBlocking { repo.dao.runningNow() } != null }
        await("Stop")
        shot("2-plan-running")
        click("Stop")
        rule.waitUntil(5_000) { runBlocking { repo.dao.runningNow() } == null }
        assertEquals(1, runBlocking { repo.dao.blocksNow(day) }.count { it.actualEnd != null })

        // Edit an activity
        openDialog("Lunch")
        shot("3-plan-edit")
        closeDialog()

        // Tasks: type a line in natural language and add it.
        click("Tasks")
        await("Reply to landlord")
        await("Add a task: buy bread at 9am on 10/12")
        rule.onNodeWithText("Add a task: buy bread at 9am on 10/12").performTextInput("Dentist next fri 15:00 for 1h")
        shot("4-tasks-typing")
        val before = runBlocking { repo.dao.tasksNow() }.size
        rule.onNodeWithText("Dentist next fri 15:00 for 1h").performImeAction()
        rule.waitUntil(5_000) { runBlocking { repo.dao.tasksNow() }.size == before + 1 }
        val dentist = runBlocking { repo.dao.tasksNow() }.first { it.title == "Dentist" }
        assertEquals(15 * 60, dentist.dueMinute)
        assertEquals(60, dentist.durationMin)
        openDialog("Dentist")
        shot("5-task-edit")
        closeDialog()

        // Habits
        click("Habits")
        await("Meditate")
        shot("6-habits")
        openDialog("Read")
        shot("7-habit-edit")
        closeDialog()

        // Review
        click("Review")
        await("By group")
        shot("8-review")

        // Settings
        click("Settings")
        await("Groups")
        shot("9-settings")

        // Dark theme, chosen in the app regardless of the system setting.
        click("Dark")
        shot("11-dark-settings")
        click("Plan")
        click("Blocks")
        shot("12-dark-plan-blocks")
        click("Tasks")
        shot("13-dark-tasks")

        // Timer picker
        openDialog("Start a timer")
        shot("10-timer-picker")
        closeDialog()
    }
}
