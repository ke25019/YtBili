package com.ytstyle.bili.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Search partitions
//
// Bilibili serves every partition from one endpoint and discriminates on the
// `search_type` parameter, but the `result` element shape differs per type.
// Each is therefore modelled separately rather than shoehorned into one type.
// ---------------------------------------------------------------------------

/** `search_type=bili_user` */
@Serializable
data class UserSearchData(
    val result: List<UserSearchItem> = emptyList(),
    val page: Int = 0,
    @SerialName("numPages") val numPages: Int = 0,
    @SerialName("numResults") val numResults: Int = 0,
)

@Serializable
data class UserSearchItem(
    val mid: Long = 0,
    val uname: String = "",
    /** Protocol-relative avatar URL. */
    val upic: String = "",
    val fans: Long = 0,
    val videos: Long = 0,
    val level: Int = 0,
    val usign: String = "",
    val gender: Int = 0,
    @SerialName("official_verify") val officialVerify: OfficialVerify? = null,
    @SerialName("room_id") val roomId: Long = 0,
    @SerialName("is_live") val isLive: Int = 0,
    @SerialName("is_upuser") val isUpUser: Int = 0,
)

@Serializable
data class OfficialVerify(
    /** -1 = none, 0 = personal, 1 = organisation. */
    val type: Int = -1,
    val desc: String = "",
)

/** `search_type=article` */
@Serializable
data class ArticleSearchData(
    val result: List<ArticleSearchItem> = emptyList(),
    val page: Int = 0,
    @SerialName("numPages") val numPages: Int = 0,
)

@Serializable
data class ArticleSearchItem(
    val id: Long = 0,
    /** Carries `<em>` highlight markup. */
    val title: String = "",
    val desc: String = "",
    /** Protocol-relative cover URLs; the first is the thumbnail. */
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
    val author: String = "",
    val mid: Long = 0,
    @SerialName("pub_time") val pubTime: Long = 0,
    val view: Long = 0,
    val like: Long = 0,
    val reply: Long = 0,
) {
    val cover: String get() = imageUrls.firstOrNull().orEmpty()
}

/**
 * `search_type=media_bangumi` (番剧/国创) and `media_ft` (影视).
 *
 * Both return the same element shape, so one model covers each. Note these
 * results are PGC entries, not archives: they have a `season_id` and open in the
 * bangumi player rather than the normal video player.
 */
@Serializable
data class MediaSearchData(
    val result: List<MediaSearchItem> = emptyList(),
    val page: Int = 0,
    @SerialName("numPages") val numPages: Int = 0,
    @SerialName("numResults") val numResults: Int = 0,
)

@Serializable
data class MediaSearchItem(
    @SerialName("media_id") val mediaId: Long = 0,
    @SerialName("season_id") val seasonId: Long = 0,
    @SerialName("pgc_season_id") val pgcSeasonId: Long = 0,
    /** Carries `<em>` highlight markup. */
    val title: String = "",
    @SerialName("org_title") val orgTitle: String = "",
    val cover: String = "",
    val desc: String = "",
    /** e.g. 番剧 / 国创 / 电影 / 电视剧 / 纪录片. */
    @SerialName("season_type_name") val seasonTypeName: String = "",
    /** e.g. "全12话". */
    @SerialName("index_show") val indexShow: String = "",
    @SerialName("ep_size") val epSize: Int = 0,
    val areas: String = "",
    val styles: String = "",
    /** Deep link, e.g. `https://www.bilibili.com/bangumi/play/ss46585`. */
    @SerialName("goto_url") val gotoUrl: String = "",
    @SerialName("media_score") val mediaScore: MediaScore? = null,
) {
    @Serializable
    data class MediaScore(val score: Double = 0.0, @SerialName("user_count") val userCount: Long = 0)
}

/** `search_type=live_room` */
@Serializable
data class LiveRoomSearchData(
    val result: List<LiveRoomSearchItem> = emptyList(),
    val page: Int = 0,
)

@Serializable
data class LiveRoomSearchItem(
    /** Both `id` and `roomid` carry the room id on this endpoint. */
    val roomid: Long = 0,
    val uid: Long = 0,
    val uname: String = "",
    val title: String = "",
    val cover: String = "",
    val uface: String = "",
    val online: Long = 0,
    val attentions: Long = 0,
    @SerialName("live_status") val liveStatus: Int = 0,
    @SerialName("cate_name") val cateName: String = "",
    val area: Int = 0,
)

// ---------------------------------------------------------------------------
// User space
// ---------------------------------------------------------------------------

/** Response of `x/space/wbi/acc/info` (WBI-signed). */
@Serializable
data class SpaceProfile(
    val mid: Long = 0,
    val name: String = "",
    val face: String = "",
    val sign: String = "",
    val sex: String = "",
    /** 0-6. */
    val level: Int = 0,
    val birthday: String = "",
    val coins: Double = 0.0,
    val moral: Int = 0,
    val official: SpaceOfficial? = null,
    val vip: SpaceVip? = null,
    @SerialName("live_room") val liveRoom: SpaceLiveRoom? = null,
)

@Serializable
data class SpaceOfficial(
    val role: Int = 0,
    val title: String = "",
    val desc: String = "",
    val type: Int = -1,
)

@Serializable
data class SpaceVip(
    val type: Int = 0,
    val status: Int = 0,
    val label: SpaceVipLabel? = null,
)

@Serializable
data class SpaceVipLabel(
    val text: String = "",
)

/** The `live_room` sub-object embedded in a space profile. */
@Serializable
data class SpaceLiveRoom(
    val roomid: Long = 0,
    @SerialName("liveStatus") val liveStatus: Int = 0,
    val title: String = "",
    val cover: String = "",
    val online: Long = 0,
)

/** `x/relation/stat` — follower/following counts, not present in acc/info. */
@Serializable
data class RelationStat(
    val mid: Long = 0,
    val following: Long = 0,
    val follower: Long = 0,
)

// ---------------------------------------------------------------------------
// Live streaming
// ---------------------------------------------------------------------------

@Serializable
data class LiveRoomInfo(
    @SerialName("room_id") val roomId: Long = 0,
    @SerialName("short_id") val shortId: Long = 0,
    val uid: Long = 0,
    /** 0 = offline, 1 = live, 2 = round-play. */
    @SerialName("live_status") val liveStatus: Int = 0,
    val title: String = "",
    val description: String = "",
    @SerialName("area_name") val areaName: String = "",
    @SerialName("parent_area_name") val parentAreaName: String = "",
    val attention: Long = 0,
    val online: Long = 0,
    /** Live thumbnail. */
    val keyframe: String = "",
    @SerialName("user_cover") val userCover: String = "",
    val tags: String = "",
)

@Serializable
data class LivePlayData(
    @SerialName("room_id") val roomId: Long = 0,
    @SerialName("live_status") val liveStatus: Int = 0,
    @SerialName("playurl_info") val playurlInfo: LivePlayurlInfo? = null,
)

@Serializable
data class LivePlayurlInfo(
    val playurl: LivePlayurl? = null,
)

@Serializable
data class LivePlayurl(
    val stream: List<LiveStream> = emptyList(),
)

@Serializable
data class LiveStream(
    /** `http_stream` (FLV) or `http_hls` (TS/fMP4). */
    @SerialName("protocol_name") val protocolName: String = "",
    val format: List<LiveFormat> = emptyList(),
)

@Serializable
data class LiveFormat(
    @SerialName("format_name") val formatName: String = "",
    val codec: List<LiveCodec> = emptyList(),
)

@Serializable
data class LiveCodec(
    @SerialName("codec_name") val codecName: String = "",
    @SerialName("current_qn") val currentQn: Int = 0,
    @SerialName("accept_qn") val acceptQn: List<Int> = emptyList(),
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("url_info") val urlInfo: List<LiveUrlInfo> = emptyList(),
)

@Serializable
data class LiveUrlInfo(
    val host: String = "",
    /** Query string that must be appended verbatim; it carries the signature. */
    val extra: String = "",
    @SerialName("stream_ttl") val streamTtl: Int = 0,
)

// ---------------------------------------------------------------------------
// Emotes
// ---------------------------------------------------------------------------

/**
 * Response of `x/emote/user/panel`.
 *
 * `packages` comes back empty for anonymous callers, so the panel is only
 * useful once signed in. Comment-level resolution does not depend on it: the
 * reply payload carries its own `content.emote` map.
 */
@Serializable
data class EmotePanelData(
    val packages: List<EmotePackage> = emptyList(),
)

@Serializable
data class EmotePackage(
    @SerialName("emote") val emotes: List<EmoteItem> = emptyList(),
    val text: String = "",
)

@Serializable
data class EmoteItem(
    /** The `[token]` that appears in comment text. */
    val text: String = "",
    val url: String = "",
    @SerialName("gif_url") val gifUrl: String = "",
    @SerialName("webp_url") val webpUrl: String = "",
    val width: Int = 0,
    val height: Int = 0,
) {
    /** Animated variants are preferred when present. */
    val bestUrl: String get() = webpUrl.ifEmpty { gifUrl.ifEmpty { url } }
}

// ---------------------------------------------------------------------------
// Watch later (稍后再看)
// ---------------------------------------------------------------------------

@Serializable
data class WatchLaterData(
    val count: Int = 0,
    val list: List<WatchLaterItem> = emptyList(),
)

@Serializable
data class WatchLaterItem(
    val aid: Long = 0,
    val bvid: String = "",
    val cid: Long = 0,
    val title: String = "",
    val pic: String = "",
    /** Seconds. */
    val duration: Int = 0,
    val pubdate: Long = 0,
    val owner: Owner? = null,
    val stat: Stat? = null,
    /** 1 when already watched. */
    @SerialName("viewed") val viewed: Int = 0,
    @SerialName("progress") val progress: Int = -1,
)

// ---------------------------------------------------------------------------
// SMS login
// ---------------------------------------------------------------------------

/** Result of requesting an SMS code. */
@Serializable
data class SmsSendResult(
    /** Echoed back on the login request. */
    @SerialName("captcha_key") val captchaKey: String = "",
)
