package com.thor1or.dayloop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** An area of life (Work, Health, …) shared by tasks, habits, plan blocks and time entries. */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int,
)

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val groupId: Long? = null,
    /** Epoch day; null means the inbox. */
    val dueDay: Long? = null,
    /** Minutes after midnight; a reminder fires then. */
    val dueMinute: Int? = null,
    val durationMin: Int? = null,
    /** See [com.thor1or.dayloop.logic.Recurrence.encode]. */
    val recurrence: String? = null,
    val someday: Boolean = false,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val groupId: Long? = null,
    /** Bit 0 = Monday … bit 6 = Sunday. */
    val daysMask: Int = 127,
    /** Minutes after midnight for the daily reminder, or null. */
    val reminderMinute: Int? = null,
    /** When above zero the habit completes itself once this much time is tracked in a day. */
    val targetMin: Int = 0,
    /** Length used when the habit is put on a day plan. */
    val planMin: Int = 30,
    val archived: Boolean = false,
    /** Epoch day. */
    val createdDay: Long,
)

@Entity(tableName = "habit_checks", primaryKeys = ["habitId", "day"])
data class HabitCheck(val habitId: Long, val day: Long)

@Entity(tableName = "time_entries")
data class TimeEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val taskId: Long? = null,
    val habitId: Long? = null,
    val groupId: Long? = null,
    val blockId: Long? = null,
    val startMs: Long,
    /** Null while the timer is running. */
    val endMs: Long? = null,
)

/** One activity on a day plan. Day [TEMPLATE_DAY] holds the reusable template. */
@Entity(tableName = "plan_blocks")
data class PlanBlock(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val position: Int,
    val title: String,
    val taskId: Long? = null,
    val habitId: Long? = null,
    val groupId: Long? = null,
    val desiredMin: Int,
    /** Fixed start (F), minutes after midnight. */
    val fixedStart: Int? = null,
    /** Rigid length (R): never compressed. */
    val rigid: Boolean = false,
    val actualStart: Int? = null,
    val actualEnd: Int? = null,
) {
    companion object {
        const val TEMPLATE_DAY = -1L
    }
}
