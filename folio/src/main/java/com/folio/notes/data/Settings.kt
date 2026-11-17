package com.folio.notes.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.folio.notes.markdown.SyntaxMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class SortBy { EDITED, CREATED, TITLE }

data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val sortBy: SortBy = SortBy.EDITED,
    val groupByDate: Boolean = true,
    val gallery: Boolean = false,
    val syntax: SyntaxMode = SyntaxMode.HIDE,
    val textScale: Float = 1f,
    /** Salt and check value of the locked-notes password; null until one is set. */
    val vaultSalt: String? = null,
    val vaultCheck: String? = null,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val theme = stringPreferencesKey("theme")
    private val sortBy = stringPreferencesKey("sort_by")
    private val groupByDate = booleanPreferencesKey("group_by_date")
    private val gallery = booleanPreferencesKey("gallery")
    private val syntax = stringPreferencesKey("syntax")
    private val textScale = floatPreferencesKey("text_scale")
    private val vaultSalt = stringPreferencesKey("vault_salt")
    private val vaultCheck = stringPreferencesKey("vault_check")

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            theme = p.enum(theme, ThemeMode.SYSTEM),
            sortBy = p.enum(sortBy, SortBy.EDITED),
            groupByDate = p[groupByDate] ?: true,
            gallery = p[gallery] ?: false,
            syntax = p.enum(syntax, SyntaxMode.HIDE),
            textScale = p[textScale] ?: 1f,
            vaultSalt = p[vaultSalt],
            vaultCheck = p[vaultCheck],
        )
    }

    suspend fun current() = settings.first()

    suspend fun clear() { context.dataStore.edit { it.clear() } }
    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[theme] = v.name }
    suspend fun setSortBy(v: SortBy) = context.dataStore.edit { it[sortBy] = v.name }
    suspend fun setGroupByDate(v: Boolean) = context.dataStore.edit { it[groupByDate] = v }
    suspend fun setGallery(v: Boolean) = context.dataStore.edit { it[gallery] = v }
    suspend fun setSyntax(v: SyntaxMode) = context.dataStore.edit { it[syntax] = v.name }
    suspend fun setTextScale(v: Float) = context.dataStore.edit { it[textScale] = v }
    suspend fun setVault(salt: String?, check: String?) = context.dataStore.edit {
        if (salt == null || check == null) { it.remove(vaultSalt); it.remove(vaultCheck) }
        else { it[vaultSalt] = salt; it[vaultCheck] = check }
    }
}
