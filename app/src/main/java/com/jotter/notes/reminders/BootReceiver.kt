package com.jotter.notes.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jotter.notes.JotterApp
import kotlinx.coroutines.launch

/** Alarms are wiped on reboot and on app update, so register them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as JotterApp
        val pending: PendingResult? = goAsync()
        app.scope.launch {
            try {
                app.repository.rescheduleAllReminders()
            } finally {
                pending?.finish()
            }
        }
    }
}
