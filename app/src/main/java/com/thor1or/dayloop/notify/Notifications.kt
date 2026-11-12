package com.thor1or.dayloop.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.thor1or.dayloop.R
import com.thor1or.dayloop.data.Habit
import com.thor1or.dayloop.data.Task
import com.thor1or.dayloop.data.TimeEntry
import com.thor1or.dayloop.ui.MainActivity

object Notifications {
    private const val CH_REMINDERS = "reminders"
    private const val CH_TIMER = "timer"
    private const val CH_PROMPTS = "prompts"

    const val TIMER_ID = 9_000_001
    const val MORNING_ID = 9_000_002
    const val EVENING_ID = 9_000_003

    const val ACTION_DONE = "com.thor1or.dayloop.DONE"
    const val ACTION_SNOOZE = "com.thor1or.dayloop.SNOOZE"
    const val ACTION_START = "com.thor1or.dayloop.START"
    const val ACTION_STOP_TIMER = "com.thor1or.dayloop.STOP_TIMER"

    fun taskNotificationId(id: Long) = (id % 1_000_000).toInt()
    fun habitNotificationId(id: Long) = 1_000_000 + (id % 1_000_000).toInt()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDERS, "Task and habit reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_TIMER, "Running timer", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PROMPTS, "Daily planning and review", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun open(context: Context, tab: String): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .setData(Uri.parse("dayloop://open/$tab"))
            .putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(context: Context, action: String, kind: String, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ActionReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("dayloop://$kind/$id"))
                .putExtra("kind", kind)
                .putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun post(context: Context, id: Int, b: NotificationCompat.Builder) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(id, b.build())
        } catch (_: SecurityException) {
            // Notification permission was withdrawn; nothing to show.
        }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    private fun reminder(context: Context, title: String, text: String, kind: String, id: Long, tab: String) =
        NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open(context, tab))
            .addAction(0, "Done", action(context, ACTION_DONE, kind, id))
            .addAction(0, "Start timer", action(context, ACTION_START, kind, id))
            .addAction(0, "Snooze 10m", action(context, ACTION_SNOOZE, kind, id))

    fun showTask(context: Context, t: Task) =
        post(context, taskNotificationId(t.id), reminder(context, t.title, "Task due now", "task", t.id, "tasks"))

    fun showHabit(context: Context, h: Habit) =
        post(context, habitNotificationId(h.id), reminder(context, h.name, "Time for your habit", "habit", h.id, "habits"))

    fun showPrompt(context: Context, morning: Boolean) {
        val b = NotificationCompat.Builder(context, CH_PROMPTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (morning) "Plan your day" else "Review your day")
            .setContentText(
                if (morning) "Pull today's tasks and habits into a schedule."
                else "See how the day went against the plan."
            )
            .setAutoCancel(true)
            .setContentIntent(open(context, if (morning) "plan" else "stats"))
        post(context, if (morning) MORNING_ID else EVENING_ID, b)
    }

    /** The running timer: an ongoing notification that counts up by itself. */
    fun showTimer(context: Context, e: TimeEntry) {
        val b = NotificationCompat.Builder(context, CH_TIMER)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(e.title.ifBlank { "Timer" })
            .setContentText("Timer running")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(e.startMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setContentIntent(open(context, "plan"))
            .addAction(0, "Stop", action(context, ACTION_STOP_TIMER, "timer", e.id))
        post(context, TIMER_ID, b)
    }
}
