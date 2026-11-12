package com.jotter.notes.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jotter.notes.data.Repeat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

object ReminderScheduler {
    const val EXTRA_NOTE_ID = "note_id"

    private fun pendingIntent(context: Context, noteId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, noteId.toInt(),
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_NOTE_ID, noteId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun schedule(context: Context, noteId: Long, at: Long) {
        if (at <= System.currentTimeMillis()) return
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, noteId)
        // Exact alarms need a user-granted permission on Android 12+; fall back to a
        // slightly less precise alarm rather than failing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(context: Context, noteId: Long) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context, noteId))
    }

    /** First occurrence of a repeating reminder strictly after [now]; null when it does not repeat. */
    fun nextOccurrence(at: Long, repeat: Repeat, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (repeat == Repeat.NONE) return null
        val start = Instant.ofEpochMilli(at).atZone(zone)
        // Always step from the original date so "monthly on the 31st" does not drift to the 28th.
        var n = 1L
        var next: ZonedDateTime
        do {
            next = when (repeat) {
                Repeat.DAILY -> start.plusDays(n)
                Repeat.WEEKLY -> start.plusWeeks(n)
                Repeat.MONTHLY -> start.plusMonths(n)
                Repeat.YEARLY -> start.plusYears(n)
                Repeat.NONE -> return null
            }
            n++
        } while (next.toInstant().toEpochMilli() <= now)
        return next.toInstant().toEpochMilli()
    }
}
