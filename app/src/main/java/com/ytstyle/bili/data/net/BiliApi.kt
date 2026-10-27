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
    val dash: Dash? = null,
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

@Serializable
data class HasLikeData(val like: Boolean = false)

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
)

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
    val ctime: Long = 0,
    val content: ReplyContent? = null,
    val member: ReplyMember? = null,
    val replies: List<Reply>? = null,
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
)

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
    val bvid: String = "",
    val cid: Long = 0,
    val progress: Int = -1,
)

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

    @GET("x/v2/reply/wbi/main")
    suspend fun replies(@QueryMap params: Map<String, String>): BiliResponse<ReplyListData>

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

    // ---- live streaming (host is api.live.bilibili.com) ----

    @GET("https://api.live.bilibili.com/room/v1/Room/get_info")
    suspend fun liveRoomInfo(@Query("room_id") roomId: Long): BiliResponse<LiveRoomInfo>

    /**
     * Live stream URLs. Requests HLS in preference to FLV by asking for both
     * protocols; see [com.ytstyle.bili.ui.live.pickLiveStream].
     */
    @GET("https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo")
    suspend fun livePlayInfo(@QueryMap params: Map<String, String>): BiliResponse<LivePlayData>

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

    @FormUrlEncoded
    @POST("https://passport.bilibili.com/x/passport-login/web/sms/send")
    suspend fun smsSend(
        @Field("tel") tel: String,
        @Field("cid") cid: Int,
        @Field("token") token: String,
        @Field("challenge") challenge: String,
        @Field("validate") validate: String,
        @Field("seccode") seccode: String,
        @Field("source") source: String = "main_web",
    ): BiliResponse<SmsSendResult>

    @FormUrlEncoded
    @POST("https://passport.bilibili.com/x/passport-login/web/login/sms")
    suspend fun smsLogin(
        @Field("tel") tel: String,
        @Field("cid") cid: Int,
        @Field("code") code: String,
        @Field("token") token: String,
        @Field("challenge") challenge: String,
        @Field("validate") validate: String,
        @Field("seccode") seccode: String,
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

    @FormUrlEncoded
    @POST("x/v3/fav/resource/deal")
    suspend fun favorite(
        @Field("rid") rid: Long,
        @Field("type") type: Int,
        @Field("add_media_ids") addMediaIds: String,
        @Field("del_media_ids") delMediaIds: String,
        @Field("csrf") csrf: String,
    ): BiliResponse<Unit>

    @FormUrlEncoded
    @POST("x/v2/reply/add")
    suspend fun addReply(
        @Field("oid") oid: Long,
        @Field("type") type: Int,
        @Field("message") message: String,
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
        @Field("token") token: String,
        @Field("challenge") challenge: String,
        @Field("validate") validate: String,
        @Field("seccode") seccode: String,
        @Field("go_url") goUrl: String = "https://www.bilibili.com",
        @Field("source") source: String = "main_web",
    ): BiliResponse<PasswordLoginResult>

    @GET("x/passport-login/web/cookie/info")
    suspend fun cookieInfo(): BiliResponse<CookieInfo>

    @GET("x/web-interface/nav/stat")
    suspend fun navStat(): BiliResponse<NavStat>
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
