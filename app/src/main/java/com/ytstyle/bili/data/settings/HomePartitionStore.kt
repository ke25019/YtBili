package com.ytstyle.bili.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.partitionDataStore by preferencesDataStore(name = "ytbili_home_partitions")

/**
 * One chip in the home feed's row.
 *
 * `rid` is Bilibili's region id, or `null` for 全部 — which is not a region at
 * all but the generic recommendation feed, hence the nullable type rather than a
 * magic number.
 */
@Serializable
data class HomePartition(
    val rid: Int? = null,
    val label: String = "",
)

/** The chips that ship by default, in order. */
val DEFAULT_HOME_PARTITIONS: List<HomePartition> = listOf(
    HomePartition(null, "全部"),
    HomePartition(1, "动画"),
    HomePartition(4, "游戏"),
    HomePartition(3, "音乐"),
    HomePartition(188, "科技"),
    HomePartition(160, "生活"),
    HomePartition(181, "影视"),
    HomePartition(36, "知识"),
)

/** Everything the user can add in edit mode. */
val AVAILABLE_HOME_PARTITIONS: List<HomePartition> = DEFAULT_HOME_PARTITIONS + listOf(
    HomePartition(234, "运动"),
    HomePartition(223, "汽车"),
    HomePartition(217, "动物"),
    HomePartition(211, "美食"),
    HomePartition(119, "鬼畜"),
    HomePartition(129, "舞蹈"),
    HomePartition(155, "时尚"),
    HomePartition(5, "娱乐"),
    HomePartition(181, "影视"),
    HomePartition(177, "纪录片"),
).distinctBy { it.rid }

/**
 * Persists which chips the home feed shows and in what order.
 *
 * The row used to be a hard-coded list; this makes it the user's. Every mutator
 * is deliberately forgiving: it refuses to empty the row and ignores moves off
 * either end, because an edit mode that can leave the user with nothing to tap
 * is a trap.
 */
@Singleton
class HomePartitionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val PARTITIONS = stringPreferencesKey("partitions")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The configured row; never empty. */
    val partitions: Flow<List<HomePartition>> =
        context.partitionDataStore.data.map { prefs ->
            decode(prefs[Keys.PARTITIONS]).ifEmpty { DEFAULT_HOME_PARTITIONS }
        }

    suspend fun add(partition: HomePartition) {
        context.partitionDataStore.edit { prefs ->
            val current = decode(prefs[Keys.PARTITIONS]).ifEmpty { DEFAULT_HOME_PARTITIONS }
            if (current.any { it.rid == partition.rid }) return@edit
            prefs[Keys.PARTITIONS] = encode(current + partition)
        }
    }

    /** Removes one chip, unless it is the last one left. */
    suspend fun remove(rid: Int?) {
        context.partitionDataStore.edit { prefs ->
            val current = decode(prefs[Keys.PARTITIONS]).ifEmpty { DEFAULT_HOME_PARTITIONS }
            val next = current.filterNot { it.rid == rid }
            if (next.isEmpty()) return@edit
            prefs[Keys.PARTITIONS] = encode(next)
        }
    }

    /** Moves a chip by [offset] (-1 earlier, +1 later), clamped to the ends. */
    suspend fun move(rid: Int?, offset: Int) {
        context.partitionDataStore.edit { prefs ->
            val current = decode(prefs[Keys.PARTITIONS]).ifEmpty { DEFAULT_HOME_PARTITIONS }
            val from = current.indexOfFirst { it.rid == rid }
            if (from < 0) return@edit
            val to = (from + offset).coerceIn(0, current.lastIndex)
            if (to == from) return@edit
            val next = current.toMutableList()
            next.add(to, next.removeAt(from))
            prefs[Keys.PARTITIONS] = encode(next)
        }
    }

    suspend fun reset() {
        context.partitionDataStore.edit { prefs -> prefs.remove(Keys.PARTITIONS) }
    }

    private fun decode(raw: String?): List<HomePartition> =
        raw?.let { runCatching { json.decodeFromString<List<HomePartition>>(it) }.getOrNull() }
            .orEmpty()

    private fun encode(list: List<HomePartition>): String = json.encodeToString(list)
}
