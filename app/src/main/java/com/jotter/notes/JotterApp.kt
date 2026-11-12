package com.jotter.notes

import android.app.Application
import com.jotter.notes.data.AppDatabase
import com.jotter.notes.data.Backup
import com.jotter.notes.data.NotesRepository
import com.jotter.notes.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class JotterApp : Application() {
    /** Outlives any screen, so saves started while leaving the editor always finish. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database by lazy { AppDatabase.create(this) }
    val repository by lazy { NotesRepository(this, database) }
    val settings by lazy { SettingsStore(this) }
    val backup by lazy { Backup(repository) }

    override fun onCreate() {
        super.onCreate()
        scope.launch { repository.purgeExpiredTrash() }
    }
}
