package com.thor1or.dayloop.data

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import com.thor1or.dayloop.logic.Parsed
import com.thor1or.dayloop.logic.PlanEngine
import com.thor1or.dayloop.logic.Recurrence
import com.thor1or.dayloop.notify.Notifications
import com.thor1or.dayloop.notify.Reminders
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/** Every change to the data goes through here, so reminders and the timer stay in step. */
class Repository(
    private val context: Context,
    val dao: AppDao,
    val prefs: Prefs,
    val reminders: Reminders,
) {
    private fun today() = LocalDate.now()
    private fun startOfDayMs(day: Long) =
        LocalDate.ofEpochDay(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun minuteOfDay(day: Long, ms: Long) = ((ms - startOfDayMs(day)) / 60_000).toInt()

    // ---- groups --------------------------------------------------------------------------

    suspend fun saveGroup(g: GroupEntity) = dao.putGroup(g)

    suspend fun deleteGroup(g: GroupEntity) {
        dao.clearGroupFromTasks(g.id)
        dao.clearGroupFromHabits(g.id)
        dao.clearGroupFromEntries(g.id)
        dao.clearGroupFromBlocks(g.id)
        dao.deleteGroup(g)
    }

    /** Finds a group by the name typed after '#', creating it when it is new. */
    suspend fun groupIdFor(name: String?): Long? {
        if (name.isNullOrBlank()) return null
        val all = dao.groupsNow()
        val hit = all.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: all.firstOrNull { it.name.startsWith(name, ignoreCase = true) }
        if (hit != null) return hit.id
        val color = GROUP_COLORS[all.size % GROUP_COLORS.size]
        return dao.putGroup(GroupEntity(name = name.replaceFirstChar { it.uppercase() }, color = color))
    }

    // ---- tasks ---------------------------------------------------------------------------

    suspend fun addTask(p: Parsed, defaultGroupId: Long? = null): Long {
        val t = Task(
            title = p.title,
            groupId = groupIdFor(p.group) ?: defaultGroupId,
            dueDay = p.date?.toEpochDay(),
            dueMinute = p.time?.let { it.hour * 60 + it.minute },
            durationMin = p.durationMin,
            recurrence = p.recurrence?.encode(),
            priority = p.priority ?: 4,
        )
        val id = dao.putTask(t)
        reminders.task(t.copy(id = id))
        return id
    }

    suspend fun saveTask(t: Task) {
        dao.putTask(t)
        reminders.task(t)
    }

    suspend fun deleteTask(id: Long) {
        reminders.cancelTask(id)
        Notifications.cancel(context, Notifications.taskNotificationId(id))
        dao.deleteTask(id)
    }

    suspend fun setTaskDone(id: Long, done: Boolean) {
        val t = dao.task(id) ?: return
        if (t.done == done) return
        val rec = if (done) Recurrence.decode(t.recurrence) else null
        // A repeating task leaves a finished copy behind and moves on to its next date.
        val updated = t.copy(
            done = done,
            doneAt = if (done) System.currentTimeMillis() else null,
            recurrence = if (rec != null) null else t.recurrence,
        )
        dao.putTask(updated)
        reminders.task(updated)
        Notifications.cancel(context, Notifications.taskNotificationId(id))
        if (rec != null) {
            val from = maxOf(t.dueDay?.let(LocalDate::ofEpochDay) ?: today(), today())
            val next = t.copy(id = 0, dueDay = rec.next(from).toEpochDay(), createdAt = System.currentTimeMillis())
            reminders.task(next.copy(id = dao.putTask(next)))
        }
    }

    suspend fun moveOverdueToToday() {
        val today = today().toEpochDay()
        dao.tasksNow().filter { !it.done && it.dueDay != null && it.dueDay < today }.forEach {
            saveTask(it.copy(dueDay = today, dueMinute = null))
        }
    }

    // ---- habits --------------------------------------------------------------------------

    suspend fun saveHabit(h: Habit): Long {
        val id = dao.putHabit(h)
        reminders.habit(h.copy(id = id))
        return id
    }

    suspend fun deleteHabit(id: Long) {
        reminders.cancelHabit(id)
        dao.deleteChecks(id)
        dao.deleteHabit(id)
    }

    suspend fun setHabitChecked(habitId: Long, day: Long, checked: Boolean) {
        if (checked) dao.check(HabitCheck(habitId, day)) else dao.uncheck(habitId, day)
        if (checked && day == today().toEpochDay()) {
            Notifications.cancel(context, Notifications.habitNotificationId(habitId))
        }
    }

    // ---- timer ---------------------------------------------------------------------------

    /**
     * Starts tracking time. When the task or habit has an activity waiting on today's plan (or
     * [blockId] names one), that activity begins too — starting a timer and beginning a plan
     * activity are the same act.
     */
    suspend fun startTimer(
        title: String,
        taskId: Long? = null,
        habitId: Long? = null,
        groupId: Long? = null,
        blockId: Long? = null,
    ) {
        stopTimer(complete = false)
        val now = System.currentTimeMillis()
        val day = today().toEpochDay()
        val blocks = dao.blocksNow(day)
        val block = blocks.firstOrNull { it.id == blockId } ?: blocks.firstOrNull {
            it.actualStart == null &&
                ((taskId != null && it.taskId == taskId) || (habitId != null && it.habitId == habitId))
        }.takeIf { blockId == null }
        if (block != null) {
            // The activity moves up to follow whatever has already been started.
            val rest = blocks.filter { it.id != block.id }
            val at = rest.indexOfLast { it.actualStart != null } + 1
            val begun = block.copy(actualStart = max(0, minuteOfDay(day, now)), actualEnd = null)
            val ordered = rest.toMutableList().apply { add(at, begun) }
            dao.updateBlocks(ordered.mapIndexed { i, b -> b.copy(position = i) })
        }
        val entry = TimeEntry(
            title = title,
            taskId = taskId ?: block?.taskId,
            habitId = habitId ?: block?.habitId,
            groupId = groupId ?: block?.groupId,
            blockId = block?.id,
            startMs = now,
        )
        Notifications.showTimer(context, entry.copy(id = dao.putEntry(entry)))
    }

    /** Stops the running timer; with [complete] the linked task or habit is ticked off as well. */
    suspend fun stopTimer(complete: Boolean) {
        val e = dao.runningNow() ?: return
        val now = System.currentTimeMillis()
        dao.putEntry(e.copy(endMs = now))
        Notifications.cancel(context, Notifications.TIMER_ID)
        e.blockId?.let { dao.block(it) }?.let { b ->
            val start = b.actualStart ?: minuteOfDay(b.day, e.startMs)
            dao.putBlock(b.copy(actualStart = start, actualEnd = max(start + 1, minuteOfDay(b.day, now))))
        }
        val day = dayOf(e.startMs)
        // An activity split into several sessions only counts as finished with its last one.
        val block = e.blockId?.let { dao.block(it) }
        val open = dao.blocksNow(block?.day ?: day).filter { it.actualEnd == null && it.id != e.blockId }
        if (complete && e.taskId != null && open.none { it.taskId == e.taskId }) setTaskDone(e.taskId, true)
        if (e.habitId != null) {
            val lastSession = complete && open.none { it.habitId == e.habitId }
            if (lastSession || timedGoalReached(e.habitId, day)) setHabitChecked(e.habitId, day, true)
        }
    }

    private fun dayOf(ms: Long) =
        LocalDate.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault()).toEpochDay()

    /** A timed habit completes itself once enough time is tracked that day, over any number of sessions. */
    private suspend fun timedGoalReached(habitId: Long, day: Long): Boolean {
        val h = dao.habit(habitId) ?: return false
        if (h.targetMin <= 0) return false
        val from = startOfDayMs(day)
        val now = System.currentTimeMillis()
        val tracked = dao.habitEntries(h.id, from, from + 86_400_000L).sumOf { (it.endMs ?: now) - it.startMs } / 60_000
        return tracked >= h.targetMin
    }

    /**
     * Corrects when a time entry started and ended (null = still running). The plan activity it
     * belongs to follows.
     */
    suspend fun setEntryTimes(id: Long, startMs: Long, endMs: Long?) {
        val e = dao.entry(id) ?: return
        val wasRunning = e.endMs == null
        val updated = e.copy(startMs = startMs, endMs = endMs)
        dao.putEntry(updated)
        if (wasRunning) {
            if (endMs == null) Notifications.showTimer(context, updated) else Notifications.cancel(context, Notifications.TIMER_ID)
        }
        e.blockId?.let { dao.block(it) }?.let { b ->
            val start = minuteOfDay(b.day, startMs)
            dao.putBlock(b.copy(actualStart = start, actualEnd = endMs?.let { max(start + 1, minuteOfDay(b.day, it)) }))
            sortStarted(b.day)
        }
        if (endMs != null && e.habitId != null && timedGoalReached(e.habitId, dayOf(startMs))) {
            setHabitChecked(e.habitId, dayOf(startMs), true)
        }
    }

    suspend fun saveEntry(e: TimeEntry) = dao.putEntry(e)
    suspend fun deleteEntry(e: TimeEntry) {
        if (e.endMs == null) Notifications.cancel(context, Notifications.TIMER_ID)
        dao.deleteEntry(e.id)
    }

    // ---- plan ----------------------------------------------------------------------------

    private fun engineBlocks(blocks: List<PlanBlock>) = blocks.map {
        PlanEngine.Block(it.id, it.desiredMin, it.fixedStart, it.rigid, it.actualStart, it.actualEnd)
    }

    /**
     * Adds an activity. Flexible ones go to the end; one with a fixed start is placed where
     * that time falls in the current schedule.
     */
    suspend fun addBlock(b: PlanBlock) {
        val blocks = dao.blocksNow(b.day)
        val at = if (b.day == PlanBlock.TEMPLATE_DAY) blocks.size else indexFor(blocks, b.fixedStart)
        val ordered = blocks.toMutableList().apply { add(at, b.copy(id = 0)) }
        ordered.forEachIndexed { i, x ->
            if (x.id == 0L) dao.putBlock(x.copy(position = i)) else if (x.position != i) dao.putBlock(x.copy(position = i))
        }
    }

    /** Where an activity with this fixed start belongs among [blocks]; the end when it has none. */
    private fun indexFor(blocks: List<PlanBlock>, fixedStart: Int?): Int {
        if (fixedStart == null) return blocks.size
        val s = prefs.settings.value
        val slots = PlanEngine.compute(engineBlocks(blocks), s.dayStart, s.dayEnd).slots
        val i = slots.indexOfFirst { it.state == PlanEngine.State.PENDING && it.start >= fixedStart }
        return if (i >= 0) i else blocks.size
    }

    suspend fun saveBlock(b: PlanBlock) = dao.putBlock(b)

    /** Pins a waiting activity to [fixedStart] and moves it to where that time falls in the day. */
    suspend fun moveBlockToTime(id: Long, fixedStart: Int) {
        val b = dao.block(id) ?: return
        if (b.actualStart != null) return
        val rest = dao.blocksNow(b.day).filter { it.id != id }
        val ordered = rest.toMutableList().apply { add(indexFor(rest, fixedStart), b.copy(fixedStart = fixedStart)) }
        dao.updateBlocks(ordered.mapIndexed { i, x -> x.copy(position = i) })
    }

    /** Splits a waiting activity into [parts] equal sessions that all count towards the same task or habit. */
    suspend fun splitBlock(id: Long, parts: Int) {
        val b = dao.block(id) ?: return
        if (parts < 2 || b.actualStart != null) return
        val each = max(5, b.desiredMin / parts)
        val blocks = dao.blocksNow(b.day).toMutableList()
        val i = blocks.indexOfFirst { it.id == id }
        blocks[i] = b.copy(desiredMin = each)
        repeat(parts - 1) { blocks.add(i + 1, b.copy(id = 0, desiredMin = each, fixedStart = null)) }
        blocks.forEachIndexed { k, x -> dao.putBlock(x.copy(position = k)) }
    }

    /** Started activities first, in the order they really happened; the rest keep their order. */
    private suspend fun sortStarted(day: Long) {
        val blocks = dao.blocksNow(day)
        val ordered = blocks.filter { it.actualStart != null }.sortedBy { it.actualStart } +
            blocks.filter { it.actualStart == null }
        dao.updateBlocks(ordered.mapIndexed { i, x -> x.copy(position = i) })
    }

    /**
     * Sets when an activity really started and ended, after the fact. No start means it has not
     * happened; a start without an end makes it the running activity (whatever else was running
     * is stopped). The time entry behind it is created or corrected to match.
     */
    suspend fun setBlockTimes(id: Long, start: Int?, end: Int?) {
        val b = dao.block(id) ?: return
        val entry = dao.entryForBlock(id)
        val running = dao.runningNow()
        if (start == null) {
            if (entry != null) deleteEntry(entry)
            dao.putBlock(b.copy(actualStart = null, actualEnd = null))
            return
        }
        if (end == null && running != null && running.id != entry?.id) stopTimer(complete = false)
        val base = startOfDayMs(b.day)
        val e = (entry ?: TimeEntry(title = b.title, taskId = b.taskId, habitId = b.habitId, groupId = b.groupId, blockId = id, startMs = 0))
            .copy(startMs = base + start * 60_000L, endMs = end?.let { base + max(start + 1, it) * 60_000L })
        val saved = e.copy(id = dao.putEntry(e))
        when {
            end == null -> Notifications.showTimer(context, saved)
            entry != null && running?.id == entry.id -> Notifications.cancel(context, Notifications.TIMER_ID)
        }
        dao.block(id)?.let { dao.putBlock(it.copy(actualStart = start, actualEnd = end?.let { x -> max(start + 1, x) })) }
        sortStarted(b.day)
        if (end != null && b.habitId != null && timedGoalReached(b.habitId, b.day)) setHabitChecked(b.habitId, b.day, true)
    }

    suspend fun deleteBlock(b: PlanBlock) {
        if (dao.runningNow()?.blockId == b.id) stopTimer(complete = false)
        dao.deleteBlock(b.id)
    }

    suspend fun clearPlan(day: Long) {
        val running = dao.runningNow()?.blockId
        if (running != null && dao.block(running)?.day == day) stopTimer(complete = false)
        dao.deleteBlocks(day)
    }

    suspend fun moveBlock(b: PlanBlock, delta: Int) {
        val blocks = dao.blocksNow(b.day).toMutableList()
        val i = blocks.indexOfFirst { it.id == b.id }
        val j = i + delta
        if (i < 0 || j !in blocks.indices) return
        blocks.add(j, blocks.removeAt(i))
        dao.updateBlocks(blocks.mapIndexed { k, x -> x.copy(position = k) })
    }

    suspend fun beginBlock(b: PlanBlock) = startTimer(b.title, b.taskId, b.habitId, b.groupId, b.id)

    suspend fun addTasksToPlan(day: Long, tasks: List<Task>) {
        val have = dao.blocksNow(day).mapNotNull { it.taskId }.toSet()
        tasks.filter { it.id !in have }.forEach { t ->
            addBlock(
                PlanBlock(
                    day = day, position = 0, title = t.title, taskId = t.id, groupId = t.groupId,
                    desiredMin = t.durationMin ?: 30,
                    fixedStart = t.dueMinute.takeIf { t.dueDay == day },
                )
            )
        }
    }

    /** Adds every habit that is due on [day] and not on the plan yet. Returns how many. */
    suspend fun addHabitsToPlan(day: Long): Int {
        val have = dao.blocksNow(day).mapNotNull { it.habitId }.toSet()
        val bit = 1 shl (LocalDate.ofEpochDay(day).dayOfWeek.value - 1)
        val due = dao.habitsNow().filter { !it.archived && it.daysMask and bit != 0 && it.id !in have }
        due.sortedBy { it.reminderMinute ?: Int.MAX_VALUE }.forEach { h ->
            // A habit done in several sessions gets one activity per session.
            val sessions = h.sessions.coerceIn(1, 8)
            val total = if (h.targetMin > 0) h.targetMin else h.planMin
            repeat(sessions) { n ->
                addBlock(
                    PlanBlock(
                        day = day, position = 0, title = h.name, habitId = h.id, groupId = h.groupId,
                        desiredMin = max(5, total / sessions),
                        fixedStart = h.reminderMinute.takeIf { n == 0 },
                    )
                )
            }
        }
        return due.size
    }

    suspend fun saveTemplate(day: Long) {
        dao.deleteBlocks(PlanBlock.TEMPLATE_DAY)
        dao.blocksNow(day).forEachIndexed { i, b ->
            dao.putBlock(
                b.copy(id = 0, day = PlanBlock.TEMPLATE_DAY, position = i, taskId = null, actualStart = null, actualEnd = null)
            )
        }
    }

    suspend fun loadTemplate(day: Long): Int {
        val template = dao.blocksNow(PlanBlock.TEMPLATE_DAY)
        val base = dao.blocksNow(day).size
        template.forEachIndexed { i, b -> dao.putBlock(b.copy(id = 0, day = day, position = base + i)) }
        return template.size
    }

    /** Copies the day's timed calendar events in as fixed, rigid activities. Needs READ_CALENDAR. */
    suspend fun importCalendar(day: Long): Int {
        val from = startOfDayMs(day)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, from + 86_400_000L)
            it.build()
        }
        val have = dao.blocksNow(day).map { it.title to it.fixedStart }.toSet()
        var added = 0
        val cols = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
        )
        context.contentResolver.query(uri, cols, null, null, CalendarContract.Instances.BEGIN)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(3) != 0) continue
                val title = c.getString(0)?.ifBlank { null } ?: "Event"
                val begin = c.getLong(1)
                if (begin < from) continue
                val start = minuteOfDay(day, begin)
                if (title to start in have) continue
                addBlock(
                    PlanBlock(
                        day = day, position = 0, title = title,
                        desiredMin = max(5, ((c.getLong(2) - begin) / 60_000).toInt()),
                        fixedStart = start, rigid = true,
                    )
                )
                added++
            }
        }
        return added
    }

    // ---- housekeeping --------------------------------------------------------------------

    /** Sets every alarm again and restores the timer notification. */
    suspend fun rescheduleAll() {
        dao.tasksNow().forEach { reminders.task(it) }
        dao.habitsNow().forEach { reminders.habit(it) }
        reminders.prompts(prefs.settings.value)
        dao.runningNow()?.let { Notifications.showTimer(context, it) }
    }

    companion object {
        val GROUP_COLORS = listOf(
            0xFF4F7CAC.toInt(), 0xFF5FA777.toInt(), 0xFFD98E4A.toInt(), 0xFFB5657A.toInt(),
            0xFF8B6FB8.toInt(), 0xFF3F9E9E.toInt(), 0xFFC2A83E.toInt(), 0xFF8A8F98.toInt(),
        )
    }
}
