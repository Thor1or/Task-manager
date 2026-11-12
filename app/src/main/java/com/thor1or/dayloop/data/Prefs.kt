package com.thor1or.dayloop.data

import android.content.Context
import android.text.format.DateFormat
import com.thor1or.dayloop.logic.DateOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    val dateOrder: DateOrder,
    /** The plan's day runs between these two minutes after midnight. */
    val dayStart: Int = 7 * 60,
    val dayEnd: Int = 23 * 60,
    /** Daily "plan your day" and "review your day" prompts; null turns one off. */
    val morningPrompt: Int? = 8 * 60,
    val eveningPrompt: Int? = 21 * 60,
    /** Folder picked for the automatic daily backup. */
    val backupFolder: String? = null,
    val lastBackupDay: Long = 0,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val defaultOrder =
        if (DateFormat.getDateFormatOrder(context).firstOrNull() == 'M') DateOrder.MDY else DateOrder.DMY

    private val state = MutableStateFlow(load())
    val settings: StateFlow<Settings> = state

    private fun load() = Settings(
        dateOrder = sp.getString("dateOrder", null)?.let { runCatching { DateOrder.valueOf(it) }.getOrNull() }
            ?: defaultOrder,
        dayStart = sp.getInt("dayStart", 7 * 60),
        dayEnd = sp.getInt("dayEnd", 23 * 60),
        morningPrompt = sp.getInt("morningPrompt", 8 * 60).takeIf { it >= 0 },
        eveningPrompt = sp.getInt("eveningPrompt", 21 * 60).takeIf { it >= 0 },
        backupFolder = sp.getString("backupFolder", null),
        lastBackupDay = sp.getLong("lastBackupDay", 0),
    )

    fun update(change: (Settings) -> Settings) {
        val s = change(state.value)
        sp.edit()
            .putString("dateOrder", s.dateOrder.name)
            .putInt("dayStart", s.dayStart)
            .putInt("dayEnd", s.dayEnd)
            .putInt("morningPrompt", s.morningPrompt ?: -1)
            .putInt("eveningPrompt", s.eveningPrompt ?: -1)
            .putString("backupFolder", s.backupFolder)
            .putLong("lastBackupDay", s.lastBackupDay)
            .apply()
        state.value = s
    }
}
