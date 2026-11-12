package com.thor1or.dayloop

import android.app.Application
import android.content.Context
import com.thor1or.dayloop.data.AppDb
import com.thor1or.dayloop.data.Prefs
import com.thor1or.dayloop.data.Repository
import com.thor1or.dayloop.notify.Notifications
import com.thor1or.dayloop.notify.Reminders

class App : Application() {
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        repo = Repository(this, AppDb.open(this).dao(), Prefs(this), Reminders(this))
    }
}

val Context.repo: Repository get() = (applicationContext as App).repo
