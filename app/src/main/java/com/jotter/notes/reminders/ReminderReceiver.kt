package com.jotter.notes.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jotter.notes.JotterApp
import com.jotter.notes.MainActivity
import com.jotter.notes.R
import com.jotter.notes.data.Repeat
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getLongExtra(ReminderScheduler.EXTRA_NOTE_ID, -1)
        if (noteId < 0) return
        val app = context.applicationContext as JotterApp
        val pending: PendingResult? = goAsync()
        app.scope.launch {
            try {
                val details = app.repository.get(noteId) ?: return@launch
                val note = details.note
                val at = note.reminderAt
                if (note.trashedAt != null || at == null) return@launch
                val title = note.title.ifBlank { "Reminder" }
                val body = details.bodyAsText().ifBlank { note.title }
                notify(context, noteId, title, body)
                ReminderScheduler.nextOccurrence(at, Repeat.of(note.reminderRepeat), System.currentTimeMillis())
                    ?.let { app.repository.setReminder(noteId, it, Repeat.of(note.reminderRepeat)) }
            } finally {
                pending?.finish()
            }
        }
    }

    private fun notify(context: Context, noteId: Long, title: String, body: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, noteId.toInt(),
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_NOTE)
                .putExtra(ReminderScheduler.EXTRA_NOTE_ID, noteId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            noteId.toInt(),
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val CHANNEL = "reminders"
    }
}
