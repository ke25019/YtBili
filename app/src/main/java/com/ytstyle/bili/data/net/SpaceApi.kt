package com.ytstyle.bili.data.net

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import javax.inject.Singleton

/**
 * Space-page calls that the shared [BiliApi] does not expose.
 *
 * Everything here is optional decoration for
 * [com.ytstyle.bili.ui.space.SpaceScreen]: the banner, the "获赞" counter and
 * folder covers. Every caller must be able to drop a failed response on the
 * floor and keep rendering, which is why each model is fully defaulted.
 */
interface SpaceApi {

    /**
     * `x/space/upstat` — the aggregate counters shown on a space page.
     *
     * Only the likes total is used. [SpaceUpstat.likes] is nullable so the UI
     * can tell "the payload had no likes field / the call failed" (render a
     * dash) apart from a genuine zero (render "0").
     */
    @GET("x/space/upstat")
    suspend fun upstat(@Query("mid") mid: Long): BiliResponse<SpaceUpstat>

    /**
     * `x/space/acc/info` — the legacy (unsigned) profile payload. It still
     * carries the space banner (`top_photo`) and the "IP属地" line for some
     * accounts. The WBI-signed copy used by [BiliApi.spaceInfo] is parsed into
     * [SpaceProfile], which has no field for either, so they are read here and
     * treated as optional.
     */
    @GET("x/space/acc/info")
    suspend fun accInfo(@Query("mid") mid: Long): BiliResponse<SpaceAccInfo>

    /**
     * 最近点赞的视频.
     *
     * The parameter is **`vmid`**, not `mid`: sending `mid` is answered with
     * `-400 请求错误`, which is how this was pinned down. `53013` means the owner
     * keeps their likes private, and `data` then arrives as `null` rather than as
     * an empty list — so "private" and "nothing liked" stay distinguishable.
     */
    @GET("x/space/like/video")
    suspend fun likedVideos(@Query("vmid") mid: Long): BiliResponse<SpaceLikedVideos>

    /**
     * `x/v3/fav/folder/created/list` — public favourite folders.
     *
     * [FavFolder] (in `BiliApi.kt`) has no cover field, so the covers are
     * fetched separately and merged by folder id. The list itself still comes
     * from `BiliRepository.favFolders`.
     */
    @GET("x/v3/fav/folder/created/list")
    suspend fun folderCovers(
        @Query("up_mid") upMid: Long,
    ): BiliResponse<SpaceFolderCoverList>
}

/** `data` of `x/space/upstat`. `likes` is null when the key is absent. */
@Serializable
data class SpaceUpstat(
    val likes: Long? = null,
)

/** The two optional bits of `x/space/acc/info` the space header can use. */
@Serializable
data class SpaceAccInfo(
    @SerialName("top_photo") val topPhoto: String = "",
    @SerialName("ip_location") val ipLocation: String = "",
)

/** `data` of `x/space/like/video`: `{ list: [...] }`. */
@Serializable
data class SpaceLikedVideos(
    val list: List<SpaceLikedVideo> = emptyList(),
)

/**
 * One 最近点赞 entry.
 *
 * The documentation describes these as matching the video-detail object, and the
 * sample shows `data.list`, so the archive-shaped subset the grid needs is
 * declared here rather than reusing the space-search model — their field names
 * genuinely differ (`stat.view` here, `play` there).
 */
@Serializable
data class SpaceLikedVideo(
    val aid: Long = 0,
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    /** Seconds here; 稍后再看 uses the same archive convention. */
    val duration: Int = 0,
    val pubdate: Long = 0,
    val owner: Owner? = null,
    val stat: Stat? = null,
)

/** One folder entry as returned by the created-folders listing. */
@Serializable
data class SpaceFolderCover(
    val id: Long = 0,
    val fid: Long = 0,
    val cover: String = "",
)

@Serializable
data class SpaceFolderCoverList(
    val list: List<SpaceFolderCover> = emptyList(),
)

@Module
@InstallIn(SingletonComponent::class)
object SpaceApiModule {

    @Provides
    @Singleton
    fun provideSpaceApi(@ApiRetrofit retrofit: Retrofit): SpaceApi =
        retrofit.create(SpaceApi::class.java)
}
