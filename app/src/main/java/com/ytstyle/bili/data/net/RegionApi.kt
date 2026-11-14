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
     * The partition's newest uploads.
     *
     * Failure is not fatal at the call site: the home screen treats an empty
     * result as "keep the ranking feed", so a broken endpoint cannot take the
     * partition down with it.
     */
    suspend fun regionNewest(rid: Int, page: Int = 1): Result<List<RegionItem>> =
        withContext(Dispatchers.IO) {
            try {
                val response = service.regionNewest(rid = rid, pn = page)
                val payload = response.data
                if (response.code == 0 && payload != null) {
                    Result.Ok(payload.archives.filter { it.bvid.isNotEmpty() })
                } else {
                    Result.Err(
                        response.message.ifBlank { "分区最新加载失败 (code=${response.code})" },
                        response.code,
                    )
                }
            } catch (e: Exception) {
                Result.Err(e.message ?: "网络异常，请稍后重试")
            }
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
