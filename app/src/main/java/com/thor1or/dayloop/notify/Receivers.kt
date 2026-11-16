package com.thor1or.dayloop.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thor1or.dayloop.repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

/** An alarm went off: show the reminder and line up the next one. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind") ?: return
        val id = intent.getLongExtra("id", 0)
        val repo = context.repo
        async {
            when (kind) {
                "task" -> repo.dao.task(id)?.takeIf { !it.done }?.let { Notifications.showTask(context, it) }
                "habit" -> repo.dao.habit(id)?.let { h ->
                    val today = LocalDate.now()
                    val due = h.daysMask and (1 shl (today.dayOfWeek.value - 1)) != 0
                    if (!h.archived && due && repo.dao.isChecked(id, today.toEpochDay()) == 0) {
                        Notifications.showHabit(context, h)
                    }
                    repo.reminders.habit(h)
                }
                "blockend" -> repo.dao.runningNow()?.takeIf { it.blockId == id }?.let { Notifications.showTimeUp(context, it) }
                "morning", "evening" -> {
                    Notifications.showPrompt(context, kind == "morning")
                    repo.reminders.prompts(repo.prefs.settings.value)
                }
            }
        }
    }
}

/** A button on a notification was pressed. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind") ?: return
        val id = intent.getLongExtra("id", 0)
        val repo = context.repo
        val action = intent.action
        async {
            when (kind) {
                "task" -> Notifications.cancel(context, Notifications.taskNotificationId(id))
                "habit" -> Notifications.cancel(context, Notifications.habitNotificationId(id))
                "timer" -> Notifications.cancel(context, Notifications.TIME_UP_ID)
            }
            when (action) {
                Notifications.ACTION_STOP_TIMER -> repo.stopTimer(complete = false)
                Notifications.ACTION_SNOOZE -> repo.reminders.snooze(kind, id)
                Notifications.ACTION_DONE -> when (kind) {
                    "task" -> repo.setTaskDone(id, true)
                    "habit" -> repo.setHabitChecked(id, LocalDate.now().toEpochDay(), true)
                    "timer" -> repo.stopTimer(complete = true)
                }
                Notifications.ACTION_START -> when (kind) {
                    "task" -> repo.dao.task(id)?.let { repo.startTimer(it.title, taskId = it.id, groupId = it.groupId) }
                    "habit" -> repo.dao.habit(id)?.let { repo.startTimer(it.name, habitId = it.id, groupId = it.groupId) }
                }
            }
        }
    }
}

/** Alarms do not survive a reboot, an app update or a clock change: set them all again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repo = context.repo
        async { repo.rescheduleAll() }
    }
}
