package com.ytstyle.bili.data.search

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.searchHistoryDataStore by preferencesDataStore(name = "ytbili_search_history")

/**
 * Recent search terms, newest first.
 *
 * Kept as one newline-joined string under a single preference key rather than a
 * serialized list: the values are plain text, the cap is tiny, and one key
 * means there is nothing to migrate if the shape ever changes.
 */
@Singleton
class SearchHistoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val ENTRIES = stringPreferencesKey("entries")
    }

    /** Newest first; an empty list when nothing has been searched yet. */
    val history: Flow<List<String>> =
        context.searchHistoryDataStore.data.map { prefs -> decode(prefs[Keys.ENTRIES]) }

    /**
     * Puts [query] at the front of the history.
     *
     * Blank terms are dropped, over-long terms are truncated rather than
     * rejected so a pasted wall of text still becomes a usable entry, and a
     * repeat of an existing term is moved to the front instead of duplicated.
     */
    suspend fun record(query: String) {
        val entry = sanitise(query).trim().take(MAX_ENTRY_LENGTH)
        if (entry.isEmpty()) return

        context.searchHistoryDataStore.edit { prefs ->
            val current = decode(prefs[Keys.ENTRIES])
            val updated = (listOf(entry) + current.filterNot { it == entry }).take(MAX_ENTRIES)
            prefs[Keys.ENTRIES] = updated.joinToString(DELIMITER)
        }
    }

    /** Removes one term by exact match; a term that is already gone is a no-op. */
    suspend fun remove(query: String) {
        context.searchHistoryDataStore.edit { prefs ->
            val updated = decode(prefs[Keys.ENTRIES]).filterNot { it == query }
            if (updated.isEmpty()) {
                prefs.remove(Keys.ENTRIES)
            } else {
                prefs[Keys.ENTRIES] = updated.joinToString(DELIMITER)
            }
        }
    }

    suspend fun clear() {
        context.searchHistoryDataStore.edit { prefs -> prefs.remove(Keys.ENTRIES) }
    }

    private fun decode(raw: String?): List<String> =
        raw?.split(DELIMITER)?.filter { it.isNotBlank() }.orEmpty()

    /**
     * The delimiter is a newline, so a newline inside an entry would let a
     * crafted query forge extra history rows; collapse both line-break styles
     * into spaces before anything is stored.
     */
    private fun sanitise(query: String): String = query.replace('\n', ' ').replace('\r', ' ')

    private companion object {
        /** Safe as a delimiter only because [sanitise] removes every newline. */
        const val DELIMITER = "\n"
        const val MAX_ENTRIES = 20
        const val MAX_ENTRY_LENGTH = 60
    }
}
