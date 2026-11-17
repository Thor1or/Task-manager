package com.folio.notes

import android.app.Application
import com.folio.notes.data.AppDatabase
import com.folio.notes.data.Attachments
import com.folio.notes.data.Exporter
import com.folio.notes.data.NotesRepository
import com.folio.notes.data.SettingsStore
import com.folio.notes.data.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FolioApp : Application() {
    /** Outlives any screen, so saves started while leaving the editor always finish. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database by lazy { AppDatabase.create(this) }
    val settings by lazy { SettingsStore(this) }
    val vault by lazy { Vault(settings) }
    val repository by lazy { NotesRepository(database, Attachments(this)) }
    val exporter by lazy { Exporter(repository) }

    /** Text shared from another app, waiting to become a new note. */
    private var sharedText: String? = null
    fun shareText(text: String) { sharedText = text }
    fun takeSharedText(): String? = sharedText.also { sharedText = null }

    /**
     * The vault closes whenever the app leaves the screen – except once after this is called, for trips to a
     * system picker or share sheet that the user starts from inside a locked note.
     */
    private var holdVault = false
    fun holdVaultOnce() { holdVault = true }
    fun onLeftScreen() { if (holdVault) holdVault = false else vault.close() }
    fun onReturned() { holdVault = false }

    override fun onCreate() {
        super.onCreate()
        scope.launch { repository.purge() }
    }
}
