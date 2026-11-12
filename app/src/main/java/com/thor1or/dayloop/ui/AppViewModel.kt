package com.thor1or.dayloop.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thor1or.dayloop.data.Backup
import com.thor1or.dayloop.data.Repository
import com.thor1or.dayloop.repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo: Repository = app.repo
    private val dao = repo.dao

    private fun <T> Flow<T>.state(initial: T) = stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    val settings = repo.prefs.settings
    val groups = dao.groups().state(emptyList())
    val tasks = dao.tasks().state(emptyList())
    val habits = dao.habits().state(emptyList())
    val checks = dao.checks().state(emptyList())
    val running = dao.running().state(null)

    /** The day shown on the Plan tab, as an epoch day. */
    val planDay = MutableStateFlow(LocalDate.now().toEpochDay())
    /** The date that was "today" when the plan was last looked at; see PlanScreen. */
    var lastToday = planDay.value
    val blocks = planDay.flatMapLatest { dao.blocks(it) }.state(emptyList())

    /** Tracked time and finished plan activities from the last five weeks, for the statistics. */
    val entries = dao.entriesSince(System.currentTimeMillis() - 35L * 86_400_000).state(emptyList())
    val finishedBlocks = dao.finishedBlocksSince(LocalDate.now().toEpochDay() - 35).state(emptyList())

    /** Short messages for the snackbar. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun say(text: String) {
        messages.tryEmit(text)
    }

    /** Runs a change off the main thread. */
    fun act(block: suspend Repository.() -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.block()
            } catch (e: Exception) {
                say(e.message ?: "Something went wrong")
            }
        }
    }

    init {
        act {
            rescheduleAll()
            Backup.autoBackup(getApplication(), dao, prefs)
        }
    }
}
