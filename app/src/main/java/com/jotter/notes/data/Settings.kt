package com.jotter.notes.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) { SYSTEM("System default"), LIGHT("Light"), DARK("Dark") }

data class Settings(
    val gridView: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val newItemsAtBottom: Boolean = true,
    val sortByModified: Boolean = false,
)

class SettingsStore(context: Context) {
    private val store = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }
    private val grid = booleanPreferencesKey("grid_view")
    private val theme = intPreferencesKey("theme")
    private val dynamic = booleanPreferencesKey("dynamic_color")
    private val bottom = booleanPreferencesKey("new_items_bottom")
    private val modified = booleanPreferencesKey("sort_modified")

    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            gridView = p[grid] ?: true,
            theme = ThemeMode.entries.getOrElse(p[theme] ?: 0) { ThemeMode.SYSTEM },
            dynamicColor = p[dynamic] ?: false,
            newItemsAtBottom = p[bottom] ?: true,
            sortByModified = p[modified] ?: false,
        )
    }

    suspend fun setGridView(v: Boolean) = store.edit { it[grid] = v }
    suspend fun setTheme(v: ThemeMode) = store.edit { it[theme] = v.ordinal }
    suspend fun setDynamicColor(v: Boolean) = store.edit { it[dynamic] = v }
    suspend fun setNewItemsAtBottom(v: Boolean) = store.edit { it[bottom] = v }
    suspend fun setSortByModified(v: Boolean) = store.edit { it[modified] = v }
}
