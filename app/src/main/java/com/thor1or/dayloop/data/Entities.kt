package com.thor1or.dayloop.data

import androidx.room.ColumnInfo
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
    /** 1 (highest) to 4 (lowest, the default). */
    @ColumnInfo(defaultValue = "4") val priority: Int = 4,
    /** Set on a subtask: the task it belongs to. */
    val parentId: Long? = null,
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
    /** How many separate sessions the habit is split into when it is put on a day plan. */
    @ColumnInfo(defaultValue = "1") val sessions: Int = 1,
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
    /** A night's sleep rather than tracked work. */
    @ColumnInfo(defaultValue = "0") val sleep: Boolean = false,
)

/** A named, reusable day plan. Its activities are the plan blocks stored under [day]. */
@Entity(tableName = "templates")
data class Template(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Weekdays this template is offered for on an empty plan. Bit 0 = Monday … bit 6 = Sunday. */
    val daysMask: Int = 0,
) {
    /** Templates live on negative "days", out of the way of real dates. */
    val day: Long get() = -id
}

/** One activity on a day plan, or on a template (negative day, see [Template.day]). */
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
    /** Alert when the planned time for this activity is up, even if alerts are off in general. */
    @ColumnInfo(defaultValue = "0") val alarm: Boolean = false,
    /** Not planned: added because a timer was started for something that was not on the plan. */
    @ColumnInfo(defaultValue = "0") val adhoc: Boolean = false,
)
