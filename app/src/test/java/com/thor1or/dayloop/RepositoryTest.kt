package com.thor1or.dayloop

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thor1or.dayloop.data.AppDb
import com.thor1or.dayloop.data.Habit
import com.thor1or.dayloop.data.PlanBlock
import com.thor1or.dayloop.logic.NlParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The rules that tie tasks, habits, the timer and the plan together, on a real database. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RepositoryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val repo get() = app.repo
    private val dao get() = repo.dao
    private val day = LocalDate.now().toEpochDay()
    private val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun dataFromTheFirstVersionSurvivesTheUpgrade() = runBlocking {
        // The database exactly as version 0.1 created it.
        val file = app.getDatabasePath("old.db").apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            listOf(
                "CREATE TABLE IF NOT EXISTS `groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS `tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `notes` TEXT NOT NULL, `groupId` INTEGER, `dueDay` INTEGER, `dueMinute` INTEGER, `durationMin` INTEGER, `recurrence` TEXT, `someday` INTEGER NOT NULL, `done` INTEGER NOT NULL, `doneAt` INTEGER, `createdAt` INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS `habits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `groupId` INTEGER, `daysMask` INTEGER NOT NULL, `reminderMinute` INTEGER, `targetMin` INTEGER NOT NULL, `planMin` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `createdDay` INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS `habit_checks` (`habitId` INTEGER NOT NULL, `day` INTEGER NOT NULL, PRIMARY KEY(`habitId`, `day`))",
                "CREATE TABLE IF NOT EXISTS `time_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `taskId` INTEGER, `habitId` INTEGER, `groupId` INTEGER, `blockId` INTEGER, `startMs` INTEGER NOT NULL, `endMs` INTEGER)",
                "CREATE TABLE IF NOT EXISTS `plan_blocks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` INTEGER NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `taskId` INTEGER, `habitId` INTEGER, `groupId` INTEGER, `desiredMin` INTEGER NOT NULL, `fixedStart` INTEGER, `rigid` INTEGER NOT NULL, `actualStart` INTEGER, `actualEnd` INTEGER)",
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'ac84f0367d74b7e8bea507352804280d')",
                "INSERT INTO tasks (title, notes, dueDay, someday, done, createdAt) VALUES ('Old task', 'n', 20000, 0, 0, 1)",
                "INSERT INTO habits (name, daysMask, targetMin, planMin, archived, createdDay) VALUES ('Old habit', 127, 20, 30, 0, 20000)",
                "INSERT INTO habit_checks VALUES (1, 20001)",
            ).forEach(db::execSQL)
            db.version = 1
        }
        val upgraded = AppDb.open(app, "old.db")
        val task = upgraded.dao().tasksNow().single()
        assertEquals("Old task", task.title)
        assertEquals(4, task.priority)
        val habit = upgraded.dao().habitsNow().single()
        assertEquals("Old habit", habit.name)
        assertEquals(1, habit.sessions)
        assertEquals(1, upgraded.dao().checksNow().size)
        upgraded.close()
    }

    @Test fun aHabitSplitIntoSessionsCountsOnceAllTheTimeIsDone() = runBlocking {
        val study = repo.saveHabit(Habit(name = "Study", targetMin = 120, sessions = 2, createdDay = day))
        assertEquals(2, repo.addHabitsToPlan(day).let { dao.blocksNow(day).size })
        val (first, second) = dao.blocksNow(day)
        assertEquals(listOf(60, 60), listOf(first.desiredMin, second.desiredMin))
        assertEquals(listOf(study, study), listOf(first.habitId, second.habitId))

        repo.setBlockTimes(first.id, 9 * 60, 10 * 60)
        assertEquals(0, dao.isChecked(study, day))
        repo.setBlockTimes(second.id, 15 * 60, 16 * 60)
        assertEquals(1, dao.isChecked(study, day))
    }

    @Test fun finishingOneSessionEarlyDoesNotTickTheHabit() = runBlocking {
        val study = repo.saveHabit(Habit(name = "Study", targetMin = 120, sessions = 2, createdDay = day))
        repo.addHabitsToPlan(day)
        val (first, second) = dao.blocksNow(day)
        repo.beginBlock(first)
        repo.stopTimer(complete = true)
        assertEquals(0, dao.isChecked(study, day))
        // Pressing Done on the last session does, even if it ran short.
        repo.beginBlock(dao.block(second.id)!!)
        repo.stopTimer(complete = true)
        assertEquals(1, dao.isChecked(study, day))
    }

    @Test fun anyActivityCanBeSplitAndATaskIsDoneWithItsLastPart() = runBlocking {
        val id = repo.addTask(NlParser.parse("Write report for 90m", LocalDate.now(), LocalTime.NOON))
        repo.addTasksToPlan(day, dao.tasksNow())
        repo.addBlock(PlanBlock(day = day, position = 0, title = "Other", desiredMin = 30))
        repo.splitBlock(dao.blocksNow(day).first().id, 3)
        val blocks = dao.blocksNow(day)
        assertEquals(listOf("Write report", "Write report", "Write report", "Other"), blocks.map { it.title })
        assertEquals(listOf(30, 30, 30, 30), blocks.map { it.desiredMin })
        assertEquals(listOf(0, 1, 2, 3), blocks.map { it.position })

        repo.beginBlock(blocks[0]); repo.stopTimer(complete = true)
        repo.beginBlock(dao.block(blocks[1].id)!!); repo.stopTimer(complete = true)
        assertFalse(dao.task(id)!!.done)
        repo.beginBlock(dao.block(blocks[2].id)!!); repo.stopTimer(complete = true)
        assertTrue(dao.task(id)!!.done)
    }

    @Test fun startAndEndTimesCanBeCorrectedAfterwards() = runBlocking {
        repo.addBlock(PlanBlock(day = day, position = 0, title = "A", desiredMin = 60))
        repo.addBlock(PlanBlock(day = day, position = 0, title = "B", desiredMin = 60))
        val (a, b) = dao.blocksNow(day)
        // B was begun, but really A ran 08:00-09:00 and B has been running since 09:30.
        repo.beginBlock(b)
        repo.setBlockTimes(a.id, 8 * 60, 9 * 60)
        repo.setBlockTimes(b.id, 9 * 60 + 30, null)

        val blocks = dao.blocksNow(day)
        assertEquals(listOf("A", "B"), blocks.map { it.title })
        assertEquals(8 * 60 to 9 * 60, blocks[0].actualStart to blocks[0].actualEnd)
        assertEquals(9 * 60 + 30 to null, blocks[1].actualStart to blocks[1].actualEnd)
        val running = dao.runningNow()!!
        assertEquals(b.id, running.blockId)
        assertEquals(midnight + (9 * 60 + 30) * 60_000L, running.startMs)
        val entryA = dao.entryForBlock(a.id)!!
        assertEquals(60, (entryA.endMs!! - entryA.startMs) / 60_000)
        assertEquals(1, dao.entriesNow().count { it.endMs == null })

        // The same correction made on the time entry reaches the plan.
        repo.setEntryTimes(running.id, midnight + 10 * 60 * 60_000L, midnight + 11 * 60 * 60_000L)
        assertEquals(10 * 60 to 11 * 60, dao.block(b.id)!!.let { it.actualStart to it.actualEnd })
        assertNull(dao.runningNow())

        // Clearing the times puts an activity back to "not started".
        repo.setBlockTimes(a.id, null, null)
        assertNull(dao.block(a.id)!!.actualStart)
        assertNull(dao.entryForBlock(a.id))
    }

    @Test fun movingAnActivityPinsItAndReordersTheDay() = runBlocking {
        listOf("A", "B", "C").forEach { repo.addBlock(PlanBlock(day = day, position = 0, title = it, desiredMin = 60)) }
        val c = dao.blocksNow(day).last()
        // The day starts at 07:00, so 07:30 falls inside A: C goes right after it.
        repo.moveBlockToTime(c.id, 7 * 60 + 30)
        val blocks = dao.blocksNow(day)
        assertEquals(listOf("A", "C", "B"), blocks.map { it.title })
        assertEquals(7 * 60 + 30, blocks[1].fixedStart)
    }

    @Test fun priorityAndGroupComeFromTheTypedLine() = runBlocking {
        val id = repo.addTask(NlParser.parse("Pay rent 0900 p1 @home", LocalDate.now(), LocalTime.MIDNIGHT))
        val task = dao.task(id)!!
        assertEquals("Pay rent", task.title)
        assertEquals(1, task.priority)
        assertEquals(9 * 60, task.dueMinute)
        assertNotNull(task.groupId)
        assertEquals("Home", dao.groupsNow().single().name)
    }
}
