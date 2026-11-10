package com.ytstyle.bili.data.download

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The download index, kept in DataStore as one JSON array.
 *
 * Same shape as [com.ytstyle.bili.data.settings.SettingsStore]: a preferences
 * DataStore whose single key holds an encoded document. That keeps the whole
 * feature dependency-free — no Room, no new plugin — and the array is small
 * enough (a few hundred entries at most) that rewriting it on every change is
 * cheaper than maintaining a schema.
 *
 * Writes are serialised by DataStore itself, so concurrent upserts cannot
 * interleave. Decoding failures degrade to an empty list rather than crashing.
 */
@Singleton
class DownloadStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private object Keys {
        val INDEX = stringPreferencesKey("index")
    }

    private val serializer = ListSerializer(DownloadItem.serializer())

    /** Newest first, as the manager screen wants it. */
    val items: Flow<List<DownloadItem>> = context.downloadDataStore.data.map { prefs ->
        decode(prefs[Keys.INDEX]).sortedByDescending { it.createdAt }
    }

    suspend fun snapshot(): List<DownloadItem> = items.first()

    suspend fun item(id: String): DownloadItem? = items.first().firstOrNull { it.id == id }

    /** Insert or replace the entry with the same [DownloadItem.id]. */
    suspend fun upsert(item: DownloadItem) {
        context.downloadDataStore.edit { prefs ->
            val current = decode(prefs[Keys.INDEX])
            val next = current.filterNot { it.id == item.id } + item
            prefs[Keys.INDEX] = encode(next)
        }
    }

    /** Drops the metadata only; deleting files is the caller's job. */
    suspend fun remove(id: String) {
        context.downloadDataStore.edit { prefs ->
            val current = decode(prefs[Keys.INDEX])
            prefs[Keys.INDEX] = encode(current.filterNot { it.id == id })
        }
    }

    /** Deletes every entry. Used when the whole cache is cleared. */
    suspend fun clear() {
        context.downloadDataStore.edit { it.remove(Keys.INDEX) }
    }

    // ---- file layout ----

    /** `filesDir/downloads` — private storage, so no runtime permission is needed. */
    fun root(): File = File(context.filesDir, "downloads")

    fun directory(bvid: String): File = File(root(), bvid)

    fun videoFile(bvid: String): File = File(directory(bvid), "video.m4s")

    fun audioFile(bvid: String): File = File(directory(bvid), "audio.m4s")

    /** Removes the whole per-video directory, including any stale `.part` file. */
    fun deleteFiles(bvid: String) {
        runCatching { directory(bvid).deleteRecursively() }
    }

    private fun decode(raw: String?): List<DownloadItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrElse { emptyList() }
    }

    private fun encode(items: List<DownloadItem>): String =
        json.encodeToString(serializer, items)
}

private val Context.downloadDataStore by preferencesDataStore(name = "ytbili_downloads")
