package com.ytstyle.bili.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap

/**
 * Envelope used by virtually every Bilibili endpoint.
 * `code == 0` means success; anything else carries a message in [message].
 */
@Serializable
data class BiliResponse<T>(
    val code: Int = -1,
    val message: String = "",
    @SerialName("ttl") val ttl: Int = 1,
    val data: T? = null,
) {
    val isSuccess: Boolean get() = code == 0 && data != null
}

@Serializable
data class NavData(
    val isLogin: Boolean = false,
    val uname: String = "",
    val face: String = "",
    val mid: Long = 0,
    val money: Double = 0.0,
    val moral: Int = 0,
    val level_info: LevelInfo? = null,
    val vipStatus: Int = 0,
    /**
     * Present only when signed in, and then as an **object**
     * (`{"path": ..., "text": ...}`), not a string. Typing it as `String` broke
     * the entire nav response and made a successful login look like a failure.
     */
    @Serializable(with = FlexibleTextSerializer::class)
    @SerialName("vip_label") val vipLabel: String = "",
    /** Carries the two rotating keys the WBI signer needs. */
    @SerialName("wbi_img") val wbiImg: WbiImg? = null,
)

@Serializable
data class WbiImg(
    @SerialName("img_url") val imgUrl: String = "",
    @SerialName("sub_url") val subUrl: String = "",
)

/** `b_3`/`b_4` are the `buvid3`/`buvid4` cookie values. */
@Serializable
data class FingerSpi(
    @SerialName("b_3") val b3: String = "",
    @SerialName("b_4") val b4: String = "",
)

@Serializable
data class LevelInfo(
    @SerialName("current_level") val currentLevel: Int = 0,
    @SerialName("current_min") val currentMin: Int = 0,
    @SerialName("next_exp") val nextExp: Int = 0,
)

@Serializable
data class RecommendFeed(
    val item: List<RecommendItem> = emptyList(),
)

@Serializable
data class RecommendItem(
    val id: Long = 0,
    val bvid: String = "",
    val cid: Long = 0,
    val goto: String = "",
    val uri: String = "",
    val pic: String = "",
    val title: String = "",
    val duration: Int = 0,
    val pubdate: Long = 0,
    val owner: Owner? = null,
    val stat: Stat? = null,
    // NOTE: `rcmd_reason` is deliberately not modelled. Bilibili returns it as
    // either null, a string, or an object depending on the feed, and the UI
    // does not render it, so typing it would only risk a serialization failure.
)

@Serializable
data class Owner(
    val mid: Long = 0,
    val name: String = "",
    val face: String = "",
)

@Serializable
data class Stat(
    val view: Long = 0,
    val like: Long = 0,
    val danmaku: Long = 0,
    val reply: Long = 0,
    val favorite: Long = 0,
    val coin: Long = 0,
    val share: Long = 0,
)



/** A page/part inside a video. Needed before we can request a stream URL. */
@Serializable
data class PageList(
    val cid: Long = 0,
    val page: Int = 0,
    val from: String = "",
    val part: String = "",
    val duration: Int = 0,
    val dimension: Dimension? = null,
)

@Serializable
data class Dimension(val width: Int = 0, val height: Int = 0)

@Serializable
data class ViewDetail(
    val bvid: String = "",
    val aid: Long = 0,
    val cid: Long = 0,
    val title: String = "",
    val pic: String = "",
    val desc: String = "",
    val duration: Int = 0,
    val pubdate: Long = 0,
    val owner: Owner? = null,
    val stat: Stat? = null,
    val pages: List<PageList> = emptyList(),
    /**
     * The collection this video belongs to, when it has one. Comes with the
     * detail call, so the 合集 entry needs no extra request.
     */
    @SerialName("ugc_season") val ugcSeason: UgcSeason? = null,
)

/**
 * Playback stream descriptor. Bilibili returns a DASH manifest plus a
 * progressive fallback list; we prefer DASH for quality selection.
 */
@Serializable
data class PlayUrlData(
    val quality: Int = 0,
    val format: String = "",
    val timelength: Long = 0,
    val accept_quality: List<Int> = emptyList(),
    val accept_description: List<String> = emptyList(),
    val durl: List<Durl> = emptyList(),
    /**
     * The PGC player returns a `durls` list where the archive player returns
     * `durl`. The one live PGC response captured so far carried `durls: []`
     * (DASH was present instead), so this list is modelled defensively: it
     * parses to nothing rather than failing, and DASH is what actually plays.
     */
    val durls: List<DurlGroup> = emptyList(),
    val dash: Dash? = null,
) {
    /**
     * Best progressive URL, whichever envelope the endpoint used. Null when the
     * response has no progressive fallback at all, which is normal for PGC.
     */
    val progressiveUrl: String?
        get() = durl.firstOrNull()?.url ?: durls.firstOrNull()?.durl?.firstOrNull()?.url
}

/** A `durls` entry: the PGC-shaped wrapper around a progressive URL list. */
@Serializable
data class DurlGroup(
    val order: Int = 0,
    val quality: Int = 0,
    val durl: List<Durl> = emptyList(),
)

@Serializable
data class Durl(
    val order: Int = 0,
    val length: Long = 0,
    val size: Long = 0,
    val url: String = "",
    val backup_url: List<String> = emptyList(),
)

@Serializable
data class Dash(
    val duration: Int = 0,
    val video: List<DashTrack> = emptyList(),
    val audio: List<DashTrack> = emptyList(),
)

@Serializable
data class DashTrack(
    val id: Int = 0,
    @SerialName("baseUrl") val baseUrl: String = "",
    @SerialName("backupUrl") val backupUrl: List<String> = emptyList(),
    val bandwidth: Long = 0,
    val mimeType: String = "",
    val codecs: String = "",
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("frameRate") val frameRate: String = "",
)

@Serializable
data class DanmakuData(
    val state: Int = 0,
    val text: String = "",
    val text_source: String = "",
)

@Serializable
data class LoginQrCode(
    val url: String = "",
    @SerialName("qrcode_key") val qrcodeKey: String = "",
)

@Serializable
data class LoginQrPoll(
    val url: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    val timestamp: Long = 0,
    val code: Int = 0,
    val message: String = "",
)

@Serializable
data class RelationData(
    val attribute: Int = 0,
)

/** The server's page size for 粉丝/关注 lists. */
const val RELATION_PAGE_SIZE = 20

/**
 * `data` of 粉丝/关注 列表.
 *
 * `list` is **nullable**, and that is load-bearing: an account that hides its
 * follow list is answered with `list: null` and `total: 0`, while an account
 * with genuinely nothing to show gets an empty array. Collapsing the two would
 * report "no followers" for somebody who simply declined to show them.
 */
@Serializable
data class RelationListData(
    val list: List<RelationUser>? = null,
    val total: Long = 0,
)

/** One row of a 粉丝/关注 list — the documented 关系列表对象. */
@Serializable
data class RelationUser(
    val mid: Long = 0,
    val uname: String = "",
    val face: String = "",
    val sign: String = "",
    /**
     * The relationship **from the caller's side**: 0 未关注, 2 已关注, 6 互粉.
     * This is what decides whether the 关注 row offers 取关.
     */
    val attribute: Int = 0,
    /** The list owner's 特别关注 mark. */
    val special: Int = 0,
) {
    /** True when the signed-in account already follows this person. */
    val followed: Boolean get() = attribute == 2 || attribute == 6
}

/**
 * Watch progress as `x/player/v2` reports it.
 *
 * Every field is optional on purpose: a signed-out caller gets zeros, and the
 * endpoint is also the source of several unrelated fields (subtitles, online
 * count, chapter markers) that this app does not read yet.
 */
@Serializable
data class PlayerProgressData(
    /** Milliseconds into the part; 0 when it has never been played. */
    @SerialName("last_play_time") val lastPlayTime: Long = 0,
    /** The `cid` of the part that was last watched — how multi-part videos resume correctly. */
    @SerialName("last_play_cid") val lastPlayCid: Long = 0,
    @SerialName("online_count") val onlineCount: Long = 0,
)

@Serializable
data class HasLikeData(
    /** Tolerates `true`, `1` and `"1"`; see [FlexibleBooleanSerializer]. */
    @Serializable(with = FlexibleBooleanSerializer::class) val like: Boolean = false,
)

@Serializable
data class FavFolder(
    val id: Long = 0,
    val fid: Long = 0,
    val mid: Long = 0,
    val title: String = "",
    @SerialName("media_count") val mediaCount: Int = 0,
)

@Serializable
data class FavFolderList(val list: List<FavFolder> = emptyList())

@Serializable
data class FavResource(
    val id: Long = 0,
    val type: Int = 0,
    val title: String = "",
    val cover: String = "",
    val intro: String = "",
    val duration: Int = 0,
    val upper: Owner? = null,
    /**
     * Play/danmaku/collect counts. These live in `cnt_info`, **not** in the
     * `stat` object the feed endpoints use — reading `stat` here is what made
     * every favourites row report "0次播放".
     *
     * Numbers go through [FlexibleTextSerializer] because Bilibili has been
     * seen sending counts as both numbers and quoted strings.
     */
    @SerialName("cnt_info") val cntInfo: FavCntInfo? = null,
    /** Present on video rows; saves an aid→bvid round trip before playing. */
    val bvid: String = "",
    @SerialName("bv_id") val bvId: String = "",
)

@Serializable
data class FavCntInfo(
    @Serializable(with = FlexibleTextSerializer::class)
    val collect: String = "",
    @Serializable(with = FlexibleTextSerializer::class)
    val play: String = "",
    @Serializable(with = FlexibleTextSerializer::class)
    val danmaku: String = "",
) {
    val playCount: Long get() = play.toLongOrNull() ?: 0L
    val danmakuCount: Long get() = danmaku.toLongOrNull() ?: 0L
}

@Serializable
data class FavResourceList(
    val info: FavFolder? = null,
    val medias: List<FavResource> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class SpaceVideoList(
    val list: SpaceVideoPage? = null,
    val page: SpacePage? = null,
)

@Serializable
data class SpaceVideoPage(val vlist: List<SpaceVideo> = emptyList())

@Serializable
data class SpacePage(val count: Int = 0, val num: Int = 0, val size: Int = 0)

@Serializable
data class SpaceVideo(
    val aid: Long = 0,
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    val length: String = "",
    val created: Long = 0,
    val play: Long = 0,
    val comment: Long = 0,
)

@Serializable
data class ReplyListData(
    val replies: List<Reply> = emptyList(),
    val page: ReplyPage? = null,
    @SerialName("top_replies") val topReplies: List<Reply>? = null,
    /** Cursor for the next page; `x/v2/reply/wbi/main` pages this way, not by `pn`. */
    val cursor: ReplyCursor? = null,
)

/**
 * The paging cursor of `x/v2/reply/wbi/main`.
 *
 * This endpoint **ignores `pn`**: measured against a 1686-comment video, `pn=1`,
 * `pn=2` and `pn=3` all returned the same first rpid. Only
 * `pagination_str={"offset":"…"}` advances, which is why the reader stopped at
 * one page with "没有更多评论了".
 */
@Serializable
data class ReplyCursor(
    @SerialName("is_end") val isEnd: Boolean = false,
    @SerialName("all_count") val allCount: Int = 0,
    @SerialName("pagination_reply") val paginationReply: ReplyPagination? = null,
)

@Serializable
data class ReplyPagination(
    @SerialName("next_offset") val nextOffset: String = "",
)

@Serializable
data class ReplyPage(val count: Int = 0, val num: Int = 0, val size: Int = 0)

@Serializable
data class Reply(
    val rpid: Long = 0,
    val oid: Long = 0,
    val mid: Long = 0,
    val root: Long = 0,
    val parent: Long = 0,
    val count: Int = 0,
    val like: Int = 0,
    /**
     * 1 when the signed-in user has liked this comment, 0 otherwise. Present
     * only for authenticated callers; absent means "not liked", which is the
     * right default for the toggle.
     */
    val action: Int = 0,
    val ctime: Long = 0,
    val content: ReplyContent? = null,
    val member: ReplyMember? = null,
    val replies: List<Reply>? = null,
    /** Carries the "IP属地：…" line and the sub-reply wording. */
    @SerialName("reply_control") val replyControl: ReplyControl? = null,
)

@Serializable
data class ReplyContent(
    val message: String = "",
    /**
     * Maps an emote token to its image, e.g. `"[大笑]" -> EmoteItem(...)`.
     * Populated by Bilibili for comments containing emotes; when it is absent
     * the token can only be rendered as text.
     */
    val emote: Map<String, EmoteItem> = emptyMap(),
    /**
     * Images attached to the comment.
     *
     * Verified against a live response (`diag_comment_pic_field2.py`, which
     * signs `x/v2/reply/wbi/main` and dumps every key seen inside `content`):
     * a comment with pictures carries
     * `pictures: [{img_src, img_width, img_height, img_size, top_right_icon?}]`
     * and `picture_scale`. Bilibili **omits both keys entirely** when a comment
     * has no image — which is why 120 comments scanned earlier showed no trace
     * of a picture field at all, and why this must default to an empty list
     * rather than being treated as always present.
     */
    val pictures: List<ReplyPicture> = emptyList(),
    /** Client display-scale hint that ships beside [pictures], e.g. `1.2`. */
    @SerialName("picture_scale") val pictureScale: Double = 1.0,
)

/**
 * One image inside a comment.
 *
 * `img_src` arrives over plain `http://`, like every other Bilibili image URL,
 * so it has to go through the same scheme upgrade before Coil will load it.
 */
@Serializable
data class ReplyPicture(
    @SerialName("img_src") val imgSrc: String = "",
    @SerialName("img_width") val imgWidth: Int = 0,
    @SerialName("img_height") val imgHeight: Int = 0,
) {
    /** width / height, clamped so a bogus dimension cannot produce a wild box. */
    val aspectRatio: Float
        get() = if (imgWidth > 0 && imgHeight > 0) {
            (imgWidth.toFloat() / imgHeight).coerceIn(0.4f, 2.5f)
        } else {
            1f
        }
}

@Serializable
data class ReplyMember(
    val mid: String = "",
    val uname: String = "",
    val avatar: String = "",
    val level_info: LevelInfo? = null,
)

@Serializable
data class SearchResultData(
    val result: List<SearchResultItem> = emptyList(),
    val page: Int = 0,
    @SerialName("numPages") val numPages: Int = 0,
)

@Serializable
data class SearchResultItem(
    val aid: Long = 0,
    val bvid: String = "",
    /** Contains `<em class="keyword">` highlight markup; strip before display. */
    val title: String = "",
    /** Protocol-relative, e.g. `//i2.hdslb.com/...`. */
    val pic: String = "",
    val author: String = "",
    val mid: Long = 0,
    /** Author avatar. */
    val uface: String = "",
    val play: Long = 0,
    val danmaku: Long = 0,
    /** `"MM:SS"` or `"HH:MM:SS"`, and sometimes minutes > 59 (e.g. `"266:42"`). */
    val duration: String = "",
    val description: String = "",
    val pubdate: Long = 0,
    /** Comment count. */
    val review: Long = 0,
    val favorites: Long = 0,
    val like: Long = 0,
)

/**
 * Shape of `s.search.bilibili.com/main/hotword`. It does not use the standard
 * `BiliResponse` envelope but does carry a `code`.
 */
@Serializable
data class HotwordResponse(
    val code: Int = -1,
    val list: List<HotwordItem> = emptyList(),
)

@Serializable
data class HotwordItem(
    val keyword: String = "",
    @SerialName("show_name") val showName: String = "",
)

/** Shape of `s.search.bilibili.com/main/suggest`. */
@Serializable
data class SuggestResponse(
    val code: Int = -1,
    val result: SuggestResult? = null,
)

@Serializable
data class SuggestResult(
    @SerialName("tag") val tags: List<SuggestTag> = emptyList(),
)

@Serializable
data class SuggestTag(
    /** Plain term, safe to insert into the field. */
    val value: String = "",
    /** Highlighted variant containing `<em>` markup. */
    val name: String = "",
    val term: String = "",
)

@Serializable
data class HistoryData(
    val list: List<HistoryItem> = emptyList(),
    val cursor: HistoryCursor? = null,
)

@Serializable
data class HistoryCursor(
    val max: Long = 0,
    val view_at: Long = 0,
    val business: String = "",
    val ps: Int = 0,
)

@Serializable
data class HistoryItem(
    val title: String = "",
    val cover: String = "",
    val history: HistoryMeta? = null,
    val videos: Int = 0,
    val author_name: String = "",
    val duration: Int = 0,
    /**
     * **Always empty on this endpoint.** The archive id lives in
     * [HistoryMeta.bvid] instead; this field exists only because a few other
     * Bilibili payloads put it here. Use [playableBvid].
     */
    val bvid: String = "",
    val cid: Long = 0,
) {
    /**
     * The bvid to open, taken from wherever this payload actually put it.
     *
     * Filtering or navigating on the top-level [bvid] is what made 观看记录 show
     * an empty list: the field is always blank here, so every row was dropped.
     */
    val playableBvid: String
        get() = history?.bvid?.takeIf { it.isNotEmpty() } ?: bvid

    /** Same idea for the cid, which is also nested. */
    val playableCid: Long
        get() = history?.cid?.takeIf { it > 0 } ?: cid
}

@Serializable
data class HistoryMeta(
    val oid: Long = 0,
    val bvid: String = "",
    val page: Int = 0,
    val cid: Long = 0,
    val business: String = "",
)

/**
 * Raw Bilibili Web API surface. All calls funnel through [BiliApi] so the
 * cookie/header handling stays in one interceptor.
 */
interface BiliApi {

    /** Current login state, coin balance, VIP status. */
    @GET("x/web-interface/nav")
    suspend fun nav(): BiliResponse<NavData>

    /**
     * Issues the device fingerprint cookies (`buvid3`/`buvid4`).
     *
     * Bilibili's risk control scores requests partly on these. Without them
     * some endpoints work but a portion of traffic is answered with HTTP 412,
     * so they are fetched once and then replayed on every request.
     */
    @GET("x/frontend/finger/spi")
    suspend fun fingerSpi(): BiliResponse<FingerSpi>

    /** Homepage recommendation feed (what the YouTube-style home renders). */
    @GET("x/web-interface/index/top/feed/rcmd")
    suspend fun recommendFeed(
        @Query("ps") pageSize: Int = 20,
        @Query("fresh_idx") freshIdx: Int = 1,
        @Query("feed_version") feedVersion: String = "V8",
    ): BiliResponse<RecommendFeed>

    /** Video detail incl. page list; required to resolve a cid. */
    @GET("x/web-interface/view")
    suspend fun viewDetail(
        @Query("bvid") bvid: String? = null,
        @Query("aid") aid: Long? = null,
    ): BiliResponse<ViewDetail>

    /**
     * Playback URLs. fnval=4048 requests DASH + all quality tiers.
     *
     * Every parameter is passed as a signed map because this is a `wbi`
     * endpoint: the caller adds `wts`/`w_rid` via [WbiSigner.sign].
     */
    @GET("x/player/wbi/playurl")
    suspend fun playUrl(@QueryMap params: Map<String, String>): BiliResponse<PlayUrlData>

    // ---- PGC (番剧 / 影视) ----
    //
    // These answer with `result`, not `data` — see PgcModels.kt.

    /** Season detail plus its episode list. Accepts either `season_id` or `ep_id`. */
    @GET("pgc/view/web/season")
    suspend fun pgcSeason(@QueryMap params: Map<String, String>): PgcResponse<PgcSeason>

    /**
     * Bangumi playback URLs. Same payload shape as the archive playurl, but on
     * the PGC host and keyed by `ep_id` instead of `bvid`.
     */
    @GET("pgc/player/web/playurl")
    suspend fun pgcPlayUrl(@QueryMap params: Map<String, String>): PgcResponse<PlayUrlData>

    /** Newest danmaku segment for a cid (6-minute buckets). */
    @GET("x/v2/dm/web/seg.so")
    suspend fun danmakuSeg(
        @Query("type") type: Int = 1,
        @Query("oid") cid: Long,
        @Query("segment_index") segmentIndex: Int = 1,
    ): okhttp3.ResponseBody

    @GET("x/relation")
    suspend fun relation(@Query("fid") mid: Long): BiliResponse<RelationData>

    @GET("x/web-interface/archive/relation")
    suspend fun archiveRelation(@Query("bvid") bvid: String): BiliResponse<HasLikeData>

    /**
     * How many coins the signed-in user has already given this archive. The
     * player needs this because coins are capped at two per video and are
     * additive, so "已投 N 币" cannot be derived from the page data.
     */
    @GET("x/web-interface/archive/coins")
    suspend fun archiveCoins(@Query("bvid") bvid: String): BiliResponse<ArchiveCoinData>

    /**
     * Whether this archive is in any of the signed-in user's folders.
     *
     * `x/v3/fav/resource/ids` — the endpoint usually quoted for this — answers
     * `-400 请求错误` even for a valid aid, so it is not used.
     */
    @GET("x/v2/fav/video/favoured")
    suspend fun favoured(@Query("aid") aid: Long): BiliResponse<FavouredData>

    @GET("x/v3/fav/folder/created/list-all")
    suspend fun favFolders(@Query("up_mid") mid: Long): BiliResponse<FavFolderList>

    @GET("x/v3/fav/resource/list")
    suspend fun favResources(
        @Query("media_id") mediaId: Long,
        @Query("pn") page: Int = 1,
        @Query("ps") pageSize: Int = 20,
    ): BiliResponse<FavResourceList>

    /**
     * Trending search terms. Served from `s.search.bilibili.com` rather than the
     * main API host, hence the absolute URL.
     */
    @GET("https://s.search.bilibili.com/main/hotword")
    suspend fun hotSearchWords(): HotwordResponse

    /** Typeahead suggestions. `name` carries `<em>` highlight markup; `value` is plain. */
    @GET("https://s.search.bilibili.com/main/suggest")
    suspend fun searchSuggest(
        @Query("term") term: String,
        @Query("main_ver") mainVer: String = "v1",
    ): SuggestResponse

    /**
     * The top-level comment list, one page at a time.
     *
     * `mode=3` orders by heat. The endpoint caps a page at 20 and reports the
     * total in `page.count`, which is what makes "load more" possible at all —
     * a single page is why the comment section used to look truncated on any
     * video with more than twenty comments.
     */
    @GET("x/v2/reply/wbi/main")
    suspend fun replies(@QueryMap params: Map<String, String>): BiliResponse<ReplyListData>

    /**
     * The full reply list *inside* one comment thread, keyed by the thread's
     * top-level `rpid`.
     *
     * The main endpoint only embeds a two-or-three reply preview per comment,
     * so a thread showing "共 37 条回复" needs this call to be readable.
     */
    @GET("x/v2/reply/reply")
    suspend fun replyThread(@QueryMap params: Map<String, String>): BiliResponse<ReplyListData>

    /**
     * Likes **or dislikes** a comment.
     *
     * `x/v2/reply/like` only understands like/unlike; disagrees need this one.
     * `action`: 1 = like, 2 = dislike, 0 = cancel either.
     */
    @FormUrlEncoded
    @POST("x/v2/reply/action")
    suspend fun replyAction(
        @Field("oid") oid: Long,
        @Field("type") type: Int,
        @Field("rpid") rpid: Long,
        @Field("action") action: Int,
        @Field("csrf") csrf: String,
    ): BiliResponse<Unit>

    /** Related videos for the rail under the player. */
    @GET("x/web-interface/archive/related")
    suspend fun related(@Query("bvid") bvid: String): BiliResponse<List<RecommendItem>>

    /**
     * Where this video was left off — the read half of cross-device resume.
     *
     * Answers **without a login** (verified live: `?bvid=…&cid=…` → `code 0`),
     * reporting zeros when signed out, so it is safe to call on every open. The
     * `aid` form returns `-400` unless the aid is valid, hence `bvid`+`cid`.
     *
     * `x/player/wbi/v2` carries the same payload; this unsiged one is used
     * because it needs no key rotation.
     */
    @GET("x/player/v2")
    suspend fun playerProgress(
        @Query("bvid") bvid: String,
        @Query("cid") cid: Long,
    ): BiliResponse<PlayerProgressData>

    /**
     * Reports watch progress — the write half, and the reason the position
     * follows the account to other devices.
     *
     * `progress` is **seconds** here, while the read side above reports
     * milliseconds. Mixing those up moves the resume point by a factor of 1000.
     */
    @FormUrlEncoded
    @POST("x/v2/history/report")
    suspend fun reportProgress(
        @Field("aid") aid: Long,
        @Field("cid") cid: Long,
        @Field("progress") progressSeconds: Long,
        @Field("csrf") csrf: String,
    ): BiliResponse<Unit>

    /**
     * Posts a danmaku at a playback position.
     *
     * `oid` is the **cid**, `progress` is milliseconds into the video, `mode`
     * is 1 for scrolling (4 bottom, 5 top), and `pool` 0 is the normal pool.
     *
     * @param color decimal RGB888, e.g. `16777215` is #FFFFFF.
     * @param colorful the 大会员 gradient style. `60001` selects 专属渐变彩色;
     *   null (white) is an ordinary solid colour, which any account may send.
     */
    @FormUrlEncoded
    @POST("x/v2/dm/post")
    suspend fun sendDanmaku(
        @Field("type") type: Int = 1,
        @Field("oid") oid: Long,
        @Field("msg") message: String,
        @Field("bvid") bvid: String,
        @Field("progress") progressMs: Long,
        @Field("mode") mode: Int = 1,
        @Field("fontsize") fontSize: Int = 25,
        @Field("color") color: Long = 16777215,
        @Field("colorful") colorful: Int? = null,
        @Field("pool") pool: Int = 0,
        @Field("csrf") csrf: String,
    ): BiliResponse<Unit>

    @GET("x/web-interface/wbi/search/type")
    suspend fun search(@QueryMap params: Map<String, String>): BiliResponse<SearchResultData>

    /**
     * The remaining search partitions. All three hit the same URL as [search]
     * and differ only in `search_type`, but each returns a different element
     * shape, so they are declared separately.
     */
    @GET("x/web-interface/wbi/search/type")
    suspend fun searchUsers(@QueryMap params: Map<String, String>): BiliResponse<UserSearchData>

    @GET("x/web-interface/wbi/search/type")
    suspend fun searchArticles(@QueryMap params: Map<String, String>): BiliResponse<ArticleSearchData>

    @GET("x/web-interface/wbi/search/type")
    suspend fun searchLiveRooms(@QueryMap params: Map<String, String>): BiliResponse<LiveRoomSearchData>

    /**
     * PGC search: `search_type=media_bangumi` (番剧/国创) or `media_ft` (影视).
     *
     * These return results only for keywords that actually match a PGC entry —
     * searching a game name legitimately yields an empty list, which is not a
     * failure.
     */
    @GET("x/web-interface/wbi/search/type")
    suspend fun searchMedia(@QueryMap params: Map<String, String>): BiliResponse<MediaSearchData>

    @GET("x/space/wbi/arc/search")
    suspend fun spaceVideosSigned(@QueryMap params: Map<String, String>): BiliResponse<SpaceVideoList>

    /** Space header: name, avatar, signature, level, VIP, live status. */
    @GET("x/space/wbi/acc/info")
    suspend fun spaceInfo(@QueryMap params: Map<String, String>): BiliResponse<SpaceProfile>

    /** Follower / following counts, which `acc/info` does not include. */
    @GET("x/relation/stat")
    suspend fun relationStat(@Query("vmid") mid: Long): BiliResponse<RelationStat>

    /**
     * 粉丝列表.
     *
     * The parameter is **`vmid`**. Two constraints worth knowing: the endpoint
     * only answers with a list when the caller is signed in, the `Referer` is a
     * `bilibili.com` subdomain, and the User-Agent does **not** contain
     * "python" (measured — this is why a probe script must not advertise
     * itself); and page depth is capped at 1000 for one's own account, 100 for
     * anybody else, past which the list simply comes back empty.
     *
     * `code = 22118` means 该用户隐私设置，粉丝列表不可见.
     */
    @GET("x/relation/fans")
    suspend fun fans(
        @Query("vmid") mid: Long,
        @Query("ps") pageSize: Int = RELATION_PAGE_SIZE,
        @Query("pn") page: Int = 1,
    ): BiliResponse<RelationListData>

    /**
     * 关注列表.
     *
     * Same host rules as [fans]. `code = 22115` means 用户已设置隐私，无法查看.
     * A hidden list can also arrive as `code = 0` with `data.list` **null**, so
     * the parser has to treat null and empty differently.
     */
    @GET("x/relation/followings")
    suspend fun followings(
        @Query("vmid") mid: Long,
        @Query("ps") pageSize: Int = RELATION_PAGE_SIZE,
        @Query("pn") page: Int = 1,
        @Query("order_type") orderType: String? = null,
    ): BiliResponse<RelationListData>

    // ---- live streaming (host is api.live.bilibili.com) ----

    @GET("https://api.live.bilibili.com/room/v1/Room/get_info")
    suspend fun liveRoomInfo(@Query("room_id") roomId: Long): BiliResponse<LiveRoomInfo>

    /**
     * Live stream URLs. Requests HLS in preference to FLV by asking for both
     * protocols; see [com.ytstyle.bili.ui.live.pickLiveStream].
     */
    @GET("https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo")
    suspend fun livePlayInfo(@QueryMap params: Map<String, String>): BiliResponse<LivePlayData>

    /**
     * The live danmaku credentials for [roomId], which must be the **real**
     * room id (`LiveRoomInfo.roomId`), not the short id in the URL.
     *
     * Note: this endpoint now answers `-352 风控校验失败` to every request shape
     * tried — the app's on-device log shows three consecutive `-352`s — so
     * [danmuConf] is used first and this is only a fallback.
     */
    @GET("https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo")
    suspend fun liveDanmuInfo(
        @Query("id") roomId: Long,
        @Query("type") type: Int = 0,
    ): BiliResponse<LiveDanmuInfo>

    /**
     * 弹幕 token 与服务器列表（旧端点），当前唯一能过风控的那条路.
     *
     * Verified end to end rather than assumed: the returned token authenticates
     * over WSS (`op 8` → `{"code":0}`) and the queue then delivers live
     * messages. See [LiveDanmuConf] for the field differences.
     */
    @GET("https://api.live.bilibili.com/room/v1/Danmu/getConf")
    suspend fun danmuConf(
        @Query("room_id") roomId: Long,
        @Query("platform") platform: String = "pc",
        @Query("player") player: String = "web",
    ): BiliResponse<LiveDanmuConf>

    // ---- watch later (稍后再看) ----

    @GET("x/v2/history/toview")
    suspend fun watchLater(): BiliResponse<WatchLaterData>

    @FormUrlEncoded
    @POST("x/v2/history/toview/add")
    suspend fun addWatchLater(
        @Field("bvid") bvid: String,
        @Field("csrf") csrf: String,
    ): BiliResponse<Unit>

    @FormUrlEncoded
    @POST("x/v2/history/toview/del")
    suspend fun removeWatchLater(
        @Field("aid") aid: Long,
        @Field("csrf") csrf: String,
    ): BiliResponse<Unit>

    // ---- emotes ----

    /** Emote panel. Returns an empty `packages` list when not signed in. */
    @GET("x/emote/user/panel")
    suspend fun emotePanel(
        @Query("business") business: String = "reply",
    ): BiliResponse<EmotePanelData>

    // ---- SMS code login ----

    // The three Geetest fields are nullable on purpose: Retrofit omits null
    // form fields, and Bilibili rejects a request that carries
    // `challenge=&validate=&seccode=` — it wants them absent when the challenge
    // is not being used. Sending empty strings was why both credential logins
    // answered `-400 参数错误`.

    @FormUrlEncoded
    @POST("https://passport.bilibili.com/x/passport-login/web/sms/send")
    suspend fun smsSend(
        @Field("tel") tel: String,
        @Field("cid") cid: Int,
        @Field("token") token: String?,
        @Field("challenge") challenge: String?,
        @Field("validate") validate: String?,
        @Field("seccode") seccode: String?,
        @Field("source") source: String = "main_web",
    ): BiliResponse<SmsSendResult>

    /**
     * Signs in with an SMS code.
     *
     * [captchaKey] is the `captcha_key` returned by [smsSend]; the endpoint
     * requires it to tie the code to the request that asked for it. It was
     * missing before, which made every SMS login fail.
     */
    @FormUrlEncoded
    @POST("https://passport.bilibili.com/x/passport-login/web/login/sms")
    suspend fun smsLogin(
        @Field("tel") tel: String,
        @Field("cid") cid: Int,
        @Field("code") code: String,
        @Field("captcha_key") captchaKey: String?,
        @Field("token") token: String?,
        @Field("challenge") challenge: String?,
        @Field("validate") validate: String?,
        @Field("seccode") seccode: String?,
        @Field("keep") keep: Int = 0,
        @Field("source") source: String = "main_web",
    ): BiliResponse<PasswordLoginResult>

    @GET("x/web-interface/history/cursor")
    suspend fun history(
        @Query("ps") pageSize: Int = 30,
    ): BiliResponse<HistoryData>

    // ---- write operations ----

    @FormUrlEncoded
    @POST("x/web-interface/archive/like")
    suspend fun like(
        @Field("bvid") bvid: String,
        @Field("like") like: Int,
        @Field("csrf") csrf: String,
    ): BiliResponse<Unit>

    @FormUrlEncoded
    @POST("x/web-interface/coin/add")
    suspend fun coin(
        @Field("bvid") bvid: String,
        @Field("multiply") multiply: Int,
        @Field("select_like") selectLike: Int,
        @Field("csrf") csrf: String,
    ): BiliResponse<Unit>

    /**
     * 追番 / 取消追番 for one season.
     *
     * `pgc/web/follow/add` and `pgc/web/follow/del`. Reachability was checked
     * anonymously: both answer `{"code":-401,"message":"未授权"}` without a
     * session, which is a real path refusing an unauthenticated caller — not the
     * 404 an invented path returns.
     */
    @FormUrlEncoded
    @POST("pgc/web/follow/add")
    suspend fun followSeason(
        @Field("season_id") seasonId: Long,
        @Field("csrf") csrf: String,
    ): BiliResponse<Unit>

    @FormUrlEncoded
    @POST("pgc/web/follow/del")
    suspend fun unfollowSeason(
        @Field("season_id") seasonId: Long,
        @Field("csrf") csrf: String,
    ): BiliResponse<Unit>

    @FormUrlEncoded
    @POST("x/v3/fav/resource/deal")
    suspend fun favorite(
        @Field("rid") rid: Long,
        @Field("type") type: Int,
        @Field("add_media_ids") addMediaIds: String,
        @Field("del_media_ids") delMediaIds: String,
        @Field("csrf") csrf: String,
    ): BiliResponse<Unit>

    /**
     * Posts a comment or a reply.
     *
     * [root] and [parent] are what turn a top-level comment into a reply: for a
     * reply to a top-level comment both are that comment's `rpid`; for a reply
     * to a *reply*, `root` stays the thread's top comment and `parent` is the
     * comment being answered. Retrofit omits null fields, which is what the
     * endpoint expects for a plain top-level comment.
     */
    @FormUrlEncoded
    @POST("x/v2/reply/add")
    suspend fun addReply(
        @Field("oid") oid: Long,
        @Field("type") type: Int,
        @Field("message") message: String,
        @Field("root") root: Long?,
        @Field("parent") parent: Long?,
        @Field("csrf") csrf: String,
    ): BiliResponse<Reply>

    @FormUrlEncoded
    @POST("x/v2/reply/like")
    suspend fun likeReply(
        @Field("oid") oid: Long,
        @Field("type") type: Int,
        @Field("rpid") rpid: Long,
        @Field("action") action: Int,
        @Field("csrf") csrf: String,
    ): BiliResponse<Unit>

    @FormUrlEncoded
    @POST("x/relation/modify")
    suspend fun modifyRelation(
        @Field("fid") fid: Long,
        @Field("act") act: Int,
        @Field("re_src") reSrc: Int,
        @Field("csrf") csrf: String,
    ): BiliResponse<Unit>

    // ---- login ----

    // QR login is retained as an API surface but is no longer used by the UI.
    // It also lives on the passport host.
    @GET("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
    suspend fun qrGenerate(): BiliResponse<LoginQrCode>

    @GET("https://passport.bilibili.com/x/passport-login/web/qrcode/poll")
    suspend fun qrPoll(@Query("qrcode_key") key: String): BiliResponse<LoginQrPoll>

    /**
     * Password-login support.
     *
     * These live on `passport.bilibili.com`, **not** `api.bilibili.com`; the
     * same paths on the API host return HTTP 404. Hence the absolute URLs.
     *
     * [loginKey] returns the RSA public key plus a `hash` that must be prefixed
     * to the password before encryption. [loginCaptcha] returns the Geetest
     * `gt`/`challenge` pair that the WebView solves.
     */
    @GET("https://passport.bilibili.com/x/passport-login/web/key")
    suspend fun loginKey(): BiliResponse<LoginKey>

    @GET("https://passport.bilibili.com/x/passport-login/captcha")
    suspend fun loginCaptcha(
        @Query("source") source: String = "main_web",
    ): BiliResponse<LoginCaptcha>

    @FormUrlEncoded
    @POST("https://passport.bilibili.com/x/passport-login/web/login")
    suspend fun passwordLogin(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("keep") keep: Int = 0,
        @Field("token") token: String?,
        @Field("challenge") challenge: String?,
        @Field("validate") validate: String?,
        @Field("seccode") seccode: String?,
        @Field("go_url") goUrl: String = "https://www.bilibili.com",
        @Field("source") source: String = "main_web",
    ): BiliResponse<PasswordLoginResult>

    @GET("x/passport-login/web/cookie/info")
    suspend fun cookieInfo(): BiliResponse<CookieInfo>

    @GET("x/web-interface/nav/stat")
    suspend fun navStat(): BiliResponse<NavStat>

    /**
     * How many people are watching this video right now.
     *
     * Best effort by design: the endpoint answered `-404` to every request made
     * while the landscape player was built, so the count is treated as unknown
     * when it does not arrive, and the line is left out rather than filled with
     * a zero.
     */
    @GET("x/player/online/total")
    suspend fun onlineTotal(
        @Query("bvid") bvid: String,
        @Query("cid") cid: Long,
    ): BiliResponse<OnlineTotalData>
}

/**
 * `data` of `x/player/online/total`.
 *
 * Both keys are read because the payload has carried `total` and `count` at
 * different times; whichever parses first wins. The fields are strings so a
 * quoted number does not fail the whole response.
 */
@Serializable
data class OnlineTotalData(
    @Serializable(with = FlexibleTextSerializer::class) val total: String = "",
    @Serializable(with = FlexibleTextSerializer::class) val count: String = "",
) {
    val online: Int get() = total.toIntOrNull() ?: count.toIntOrNull() ?: 0
}

@Serializable
data class CookieInfo(
    val refresh: Boolean = false,
    val timestamp: Long = 0,
)

@Serializable
data class LoginKey(
    /** Random salt prefixed to the password before RSA encryption. */
    val hash: String = "",
    /** PEM-encoded SPKI RSA public key. */
    val key: String = "",
)

@Serializable
data class LoginCaptcha(
    /** Opaque token echoed back on the login POST. */
    val token: String = "",
    val geetest: Geetest? = null,
)

@Serializable
data class Geetest(
    val gt: String = "",
    val challenge: String = "",
)

@Serializable
data class PasswordLoginResult(
    val status: Int = -1,
    /** Present when [status] is 2, i.e. the account needs extra verification. */
    val url: String = "",
    val message: String = "",
)

@Serializable
data class NavStat(
    val follower: Int = 0,
    val following: Int = 0,
    val dynamic_count: Int = 0,
)
