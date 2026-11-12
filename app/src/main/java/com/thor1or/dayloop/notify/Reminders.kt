package com.thor1or.dayloop.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.thor1or.dayloop.data.Habit
import com.thor1or.dayloop.data.Settings
import com.thor1or.dayloop.data.Task
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Schedules the alarms behind task reminders, habit reminders and the two daily prompts. */
class Reminders(private val context: Context) {
    private val am = context.getSystemService(AlarmManager::class.java)

    fun canBeExact(): Boolean = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    private fun intent(kind: String, id: Long, snooze: Boolean = false): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, AlarmReceiver::class.java)
                .setData(Uri.parse("dayloop://alarm/$kind/$id" + if (snooze) "/snooze" else ""))
                .putExtra("kind", kind)
                .putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun set(at: LocalDateTime, pi: PendingIntent) {
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (canBeExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
    }

    private fun time(minute: Int): LocalTime = LocalTime.of((minute / 60).coerceIn(0, 23), minute % 60)

    fun task(t: Task) {
        val pi = intent("task", t.id)
        am.cancel(pi)
        if (t.done || t.dueDay == null || t.dueMinute == null) return
        val at = LocalDateTime.of(LocalDate.ofEpochDay(t.dueDay), time(t.dueMinute))
        if (at.isAfter(LocalDateTime.now())) set(at, pi)
    }

    fun cancelTask(id: Long) {
        am.cancel(intent("task", id))
        am.cancel(intent("task", id, snooze = true))
    }

    /** Sets the next reminder for a habit: the first scheduled day whose time is still ahead. */
    fun habit(h: Habit) {
        val pi = intent("habit", h.id)
        am.cancel(pi)
        val minute = h.reminderMinute ?: return
        if (h.archived || h.daysMask and 127 == 0) return
        val now = LocalDateTime.now()
        var day = now.toLocalDate()
        repeat(8) {
            val at = LocalDateTime.of(day, time(minute))
            if (h.daysMask and (1 shl (day.dayOfWeek.value - 1)) != 0 && at.isAfter(now)) {
                set(at, pi)
                return
            }
            day = day.plusDays(1)
        }
    }

    fun cancelHabit(id: Long) {
        am.cancel(intent("habit", id))
        am.cancel(intent("habit", id, snooze = true))
    }

    fun prompts(s: Settings) {
        prompt("morning", s.morningPrompt)
        prompt("evening", s.eveningPrompt)
    }

    private fun prompt(kind: String, minute: Int?) {
        val pi = intent(kind, 0)
        am.cancel(pi)
        if (minute == null) return
        val now = LocalDateTime.now()
        var at = LocalDateTime.of(now.toLocalDate(), time(minute))
        if (!at.isAfter(now)) at = at.plusDays(1)
        set(at, pi)
    }

    fun snooze(kind: String, id: Long, minutes: Long = 10) =
        set(LocalDateTime.now().plusMinutes(minutes), intent(kind, id, snooze = true))
}
