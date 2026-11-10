package com.ytstyle.bili.data.net

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import javax.inject.Singleton

/**
 * The two 追更 sources the app supports.
 *
 * Only these two are exposed on purpose. The 课堂 (cheese) and 追漫 (manga)
 * endpoints need paths that could not be confirmed, and a tab that can only
 * ever answer "load failed" is worse than no tab at all, so they are simply
 * absent from this API and from the UI.
 */
interface FollowApi {

    /**
     * `type = 1` is 追番, `type = 2` is 追剧 (影视). `follow_status = 0` asks for
     * every follow (1 would be "watching" only). The list is the caller's own,
     * so the session cookie is what makes it non-empty.
     */
    @GET("x/space/bangumi/follow/list")
    suspend fun followBangumi(
        @Query("vmid") vmid: Long,
        @Query("type") type: Int,
        @Query("follow_status") followStatus: Int = 0,
        @Query("ps") pageSize: Int = 30,
        @Query("pn") page: Int = 1,
    ): BiliResponse<BangumiFollowData>

    /** 合集 (series) and 合集/season tabs of a space, newest content first. */
    @GET("x/polymer/web-space/seasons_series_list")
    suspend fun seasonsSeries(
        @Query("mid") mid: Long,
        @Query("page_num") pageNum: Int = 1,
        @Query("page_size") pageSize: Int = 30,
    ): BiliResponse<SeasonsSeriesData>
}

@Serializable
data class BangumiFollowData(
    val list: List<BangumiFollowItem> = emptyList(),
)

/**
 * One followed season/film. `new_ep.desc` is the wording the client shows
 * ("更新至第12话", "全38话"); when it is missing the count is a usable fallback.
 *
 * `total_count` goes through [FlexibleTextSerializer] because Bilibili has
 * been seen sending counts as either a number or a quoted string, and a strict
 * `Int` would make that whole tab a parse failure.
 */
@Serializable
data class BangumiFollowItem(
    @SerialName("season_id") val seasonId: Long = 0,
    val title: String = "",
    val cover: String = "",
    @Serializable(with = FlexibleTextSerializer::class)
    @SerialName("total_count") val totalCount: String = "",
    @SerialName("new_ep") val newEp: BangumiNewEp? = null,
)

@Serializable
data class BangumiNewEp(
    @Serializable(with = FlexibleTextSerializer::class)
    val desc: String = "",
)

/**
 * `items_lists` is typed as raw JSON because the endpoint answers with two
 * different shapes (a flat array of `{meta, recent_aids}` versus an object
 * holding `seasons_list` / `series_list`). A typed field would make one of
 * them a parse failure, so the shape is resolved in
 * [toFollowEntries] instead. Every field is optional for the same reason.
 */
@Serializable
data class SeasonsSeriesData(
    @SerialName("items_lists") val itemsLists: JsonElement? = null,
)

/** Framework-free view of one 追更 row, shared by both sources. */
data class FollowEntry(
    val key: String = "",
    val title: String = "",
    val cover: String = "",
    /** The wording a 番剧/剧集 payload carries, already display-ready. */
    val progress: String = "",
    /** 合集 video count; the UI renders this as "N个内容". */
    val contentCount: Long = 0,
    /** 番剧/剧集 route on [seasonId], 合集 routes on [bvid] (or [aid]). */
    val kind: FollowKind = FollowKind.BANGUMI,
    val seasonId: Long = 0,
    val aid: Long = 0,
    val bvid: String = "",
)

enum class FollowKind { BANGUMI, CINEMA, COLLECTION }

/**
 * Walks whatever `items_lists` turned out to be and pulls every collection
 * entry out of it. Depth is capped: the payload nests containers a couple of
 * levels deep, and a malformed/self-referencing body must not spin here.
 */
fun SeasonsSeriesData.toFollowEntries(): List<FollowEntry> {
    val root = itemsLists ?: return emptyList()
    val out = ArrayList<FollowEntry>()
    collectCollections(root, out, 0)
    return out.mapIndexed { index, entry -> entry.copy(key = "c:$index") }
}

private const val MAX_WALK_DEPTH = 5

private fun collectCollections(node: JsonElement?, out: MutableList<FollowEntry>, depth: Int) {
    if (node == null || depth > MAX_WALK_DEPTH) return
    when (node) {
        is JsonArray -> node.forEach { collectCollections(it, out, depth + 1) }
        is JsonObject -> {
            // `{meta, recent_aids}` is one collection; anything else is a
            // container whose values hold the collections.
            val isEntry = node.containsKey("meta") || node.containsKey("recent_aids")
            if (isEntry) {
                parseCollection(node)?.let(out::add)
            } else {
                node.values.forEach { collectCollections(it, out, depth + 1) }
            }
        }
        else -> Unit
    }
}

/**
 * Reads one collection.
 *
 * The live payload's shape is `{"meta": {...}, "recent_aids": [...],
 * "archives": [...]}`: `meta` carries `name`/`cover`/`total`/`season_id`, and
 * `archives[0]` carries the newest video's `bvid`. Both are used, because a
 * bvid here means a tap can go straight to the player instead of spending a
 * request resolving an aid. Every lookup falls back to something so a shape
 * drift degrades one row instead of the whole tab.
 */
private fun parseCollection(obj: JsonObject): FollowEntry? {
    val meta = obj["meta"] as? JsonObject
    val archives = (obj["archives"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()
    val first = archives.firstOrNull()

    val title = meta?.stringOrNull("name").orEmpty()
        .ifBlank { meta?.stringOrNull("title").orEmpty() }
        .ifBlank { obj.stringOrNull("name").orEmpty() }
        .ifBlank { obj.stringOrNull("title").orEmpty() }

    val cover = meta?.stringOrNull("cover").orEmpty()
        .ifBlank { obj.stringOrNull("cover").orEmpty() }
        .ifBlank { first?.stringOrNull("pic").orEmpty() }

    val bvid = meta?.stringOrNull("bvid").orEmpty()
        .ifBlank { obj.stringOrNull("bvid").orEmpty() }
        .ifBlank { first?.stringOrNull("bvid").orEmpty() }

    val recentAids = (obj["recent_aids"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
        .orEmpty()
        .ifEmpty { archives.mapNotNull { it.longOrNull("aid") } }

    val total = meta?.longOrNull("total") ?: 0L

    if (title.isBlank() && cover.isBlank() && total <= 0 && recentAids.isEmpty()) return null

    return FollowEntry(
        key = "c:${title}",
        title = title.ifBlank { "合集" },
        cover = cover,
        contentCount = total,
        kind = FollowKind.COLLECTION,
        aid = recentAids.firstOrNull() ?: 0L,
        bvid = bvid,
    )
}

private fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.longOrNull(name: String): Long? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    primitive.longOrNull?.let { return it }
    return primitive.contentOrNull?.let { text -> text.toLongOrNull() }
}

@Module
@InstallIn(SingletonComponent::class)
object FollowApiModule {

    @Provides
    @Singleton
    fun provideFollowApi(@ApiRetrofit retrofit: Retrofit): FollowApi =
        retrofit.create(FollowApi::class.java)
}
