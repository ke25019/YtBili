package com.ytstyle.bili.data.net

import com.ytstyle.bili.data.repo.Result
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A partition's ranking list.
 *
 * `x/web-interface/ranking/v2` returns the whole 90-odd-item list in one call —
 * there is no offset parameter — which is why the home view model keeps it as a
 * pool and hands it to the list a page at a time.
 *
 * The elements are modelled as [RecommendItem]: the two payloads differ in
 * fields the card does not read, and mapping them to one shape means
 * [com.ytstyle.bili.ui.components.VideoCard] and the LazyColumn keys need no
 * special case.
 */
/**
 * A ranking element. Same shape as a recommendation row — the card, the
 * de-duplication by bvid and the LazyColumn keys are shared, so they are the
 * same type rather than two that have to be converted at every boundary.
 */
typealias RegionItem = RecommendItem

@Serializable
data class RegionRanking(
    val list: List<RegionItem> = emptyList(),
)

@Serializable
data class RegionRankingResponse(
    val code: Int = -1,
    val message: String = "",
    val data: RegionRanking? = null,
)

/**
 * A partition's newest uploads (`x/web-interface/newlist`).
 *
 * This exists because the ranking list is a *snapshot*: pulling to refresh a
 * partition returned the same 90 videos every time, while 全部 kept producing
 * new recommendations. The newest list is the only per-partition feed that
 * actually changes between refreshes.
 */
@Serializable
data class RegionNewest(
    val archives: List<RegionItem> = emptyList(),
)

@Serializable
data class RegionNewestResponse(
    val code: Int = -1,
    val message: String = "",
    val data: RegionNewest? = null,
)

/**
 * A recommendation row from `x/web-interface/region/feed/rcmd`.
 *
 * The names differ from [RecommendItem] (`cover` rather than `pic`, `author`
 * rather than `owner`), so the payload is modelled as-is and mapped afterwards.
 * Bilibili also started requiring a **`buvid3` cookie** for this endpoint in
 * November 2026: without it every request gets HTTP 412 and an HTML error page.
 * The shared OkHttp client already attaches the device fingerprint, which is why
 * this call works without a login.
 */
@Serializable
data class RegionFeedArchive(
    val aid: Long = 0,
    val bvid: String = "",
    val cid: Long = 0,
    val title: String = "",
    val cover: String = "",
    val duration: Int = 0,
    val pubdate: Long = 0,
    val stat: RegionFeedStat? = null,
    val author: RegionFeedAuthor? = null,
)

@Serializable
data class RegionFeedStat(
    val view: Long = 0,
    val like: Long = 0,
    val danmaku: Long = 0,
)

@Serializable
data class RegionFeedAuthor(
    val mid: Long = 0,
    val name: String = "",
    val face: String = "",
)

@Serializable
data class RegionFeedData(
    val archives: List<RegionFeedArchive> = emptyList(),
)

@Serializable
data class RegionFeedResponse(
    val code: Int = -1,
    val message: String = "",
    val data: RegionFeedData? = null,
)

/**
 * Which chips get Bilibili's per-partition **recommendation** feed.
 *
 * Probed against the live endpoint: only the legacy main regions answer
 * `region/feed/rcmd` (1 动画, 3 音乐, 4 游戏, 5 娱乐, 36 知识). The rest answer
 * `62013` (no channel feed) and keep using the ranking snapshot.
 */
internal val REGION_FEED_RIDS = setOf(1, 3, 4, 5, 36)

/** True when the partition can supply a changing recommendation list. */
internal fun supportsRegionFeed(rid: Int): Boolean = rid in REGION_FEED_RIDS

interface RegionService {
    /** @param rid Bilibili's region id (1 = 动画, 4 = 游戏, …). */
    @GET("x/web-interface/ranking/v2")
    suspend fun regionRanking(
        @Query("rid") rid: Int,
        @Query("type") type: String = "all",
    ): RegionRankingResponse

    /** Newest uploads in a partition, newest first, paginated by [pn]. */
    @GET("x/web-interface/newlist")
    suspend fun regionNewest(
        @Query("rid") rid: Int,
        @Query("pn") pn: Int = 1,
        @Query("ps") ps: Int = 20,
    ): RegionNewestResponse

    /**
     * A partition's recommendation feed.
     *
     * `from_region` is required — the endpoint answers `-400` without it — and
     * `display_id` is the legacy region id, not the new `tid_v2`.
     */
    @GET("x/web-interface/region/feed/rcmd")
    suspend fun regionFeed(
        @Query("display_id") displayId: Int,
        @Query("request_cnt") requestCount: Int = 20,
        @Query("from_region") fromRegion: Int = 1005,
        @Query("device") device: String = "web",
        @Query("plat") plat: Int = 30,
        @Query("web_location") webLocation: String = "333.999",
    ): RegionFeedResponse
}

/**
 * Region feeds for the home chips.
 *
 * Kept apart from [BiliApi] because it is only used here; the shared OkHttp
 * client already attaches the session cookie, desktop UA and Referer.
 */
@Singleton
class RegionApi @Inject constructor(
    private val service: RegionService,
) {
    /**
     * The region's ranking, mapped into the app's [Result] envelope.
     *
     * An empty list is returned as-is rather than as an error: a partition with
     * nothing in it is a legitimate answer, and the screen says so.
     */
    suspend fun regionRanking(rid: Int): Result<RegionRanking> = withContext(Dispatchers.IO) {
        try {
            val response = service.regionRanking(rid)
            val payload = response.data
            if (response.code == 0 && payload != null) {
                Result.Ok(payload)
            } else {
                Result.Err(
                    response.message.ifBlank { "分区加载失败 (code=${response.code})" },
                    response.code,
                )
            }
        } catch (e: Exception) {
            Result.Err(e.message ?: "网络异常，请稍后重试")
        }
    }

    /**
     * The partition's recommendation feed, mapped onto the card model.
     *
     * This is the list the web channel pages use: it changes between requests,
     * unlike `ranking/v2`, which is a daily snapshot. Empty/`62013` answers are
     * errors rather than an empty page, because the caller has a ranking to fall
     * back to.
     */
    suspend fun regionFeed(rid: Int, requestCount: Int = 20): Result<List<RegionItem>> =
        withContext(Dispatchers.IO) {
            try {
                val response = service.regionFeed(displayId = rid, requestCount = requestCount)
                val archives = response.data?.archives.orEmpty()
                if (response.code == 0) {
                    Result.Ok(
                        archives.mapNotNull { archive ->
                            archive.toCard()
                        },
                    )
                } else {
                    Result.Err(
                        response.message.ifBlank { "分区推荐加载失败 (code=${response.code})" },
                        response.code,
                    )
                }
            } catch (e: Exception) {
                Result.Err(e.message ?: "网络异常，请稍后重试")
            }
        }

    /** Names differ from [RecommendItem]; this is the one place they are bridged. */
    private fun RegionFeedArchive.toCard(): RegionItem? {
        if (bvid.isBlank()) return null
        return RecommendItem(
            id = aid,
            bvid = bvid,
            cid = cid,
            goto = "av",
            pic = cover,
            title = title,
            duration = duration,
            pubdate = pubdate,
            owner = author?.let { Owner(mid = it.mid, name = it.name, face = it.face) },
            stat = stat?.let {
                Stat(view = it.view, like = it.like, danmaku = it.danmaku)
            },
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object RegionModule {
    @Provides
    @Singleton
    fun provideRegionService(@ApiRetrofit retrofit: Retrofit): RegionService =
        retrofit.create(RegionService::class.java)
}
