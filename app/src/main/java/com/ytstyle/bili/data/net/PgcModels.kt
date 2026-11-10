package com.ytstyle.bili.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// PGC (番剧 / 国创 / 影视) playback.
//
// Two things about this family of endpoints were confirmed against the live
// API before anything here was modelled:
//
//  1. **They do not use the standard envelope.** Every other endpoint in this
//     app puts its payload in `data`; these put it in `result`. Verified on
//     `pgc/view/web/season?season_id=46585` and
//     `pgc/player/web/playurl?ep_id=1633643`, both of which answered
//     `{"code":0,"message":"success","result":{...}}` with no `data` key at all.
//
//  2. **An episode's `duration` is in milliseconds**, unlike the archive
//     endpoints where it is seconds. `灵笼 第二季` EP1 reports 2458000, and the
//     playurl response agrees with `dash.duration = 2458`.
// ---------------------------------------------------------------------------

/** PGC envelope: the payload lives in [result]. */
@Serializable
data class PgcResponse<T>(
    val code: Int = -1,
    val message: String = "",
    val result: T? = null,
)

/** `pgc/view/web/season` — a whole season plus its episode list. */
@Serializable
data class PgcSeason(
    @SerialName("season_id") val seasonId: Long = 0,
    @SerialName("media_id") val mediaId: Long = 0,
    val title: String = "",
    @SerialName("season_title") val seasonTitle: String = "",
    val cover: String = "",
    @SerialName("square_cover") val squareCover: String = "",
    val evaluate: String = "",
    @SerialName("season_type_name") val seasonTypeName: String = "",
    /** Number of episodes in the season. */
    val total: Int = 0,
    val stat: PgcStat? = null,
    /** Main episodes. `section` (PVs, 花絮) is deliberately not modelled. */
    val episodes: List<PgcEpisode> = emptyList(),
    @SerialName("new_ep") val newEp: PgcNewEp? = null,
    @SerialName("user_status") val userStatus: PgcUserStatus? = null,
    @SerialName("up_info") val upInfo: PgcUpInfo? = null,
    val rating: PgcRating? = null,
)

@Serializable
data class PgcStat(
    val views: Long = 0,
    val likes: Long = 0,
    val coins: Long = 0,
    /** Favourites of the season, **not** the follower count (`favorites`). */
    val favorite: Long = 0,
    /** Series followers (追番), which the UI calls 追番. */
    val favorites: Long = 0,
    val danmakus: Long = 0,
    val reply: Long = 0,
    val share: Long = 0,
    @SerialName("follow_text") val followText: String = "",
)

@Serializable
data class PgcEpisode(
    /** `id` and `ep_id` are the same value on this endpoint. */
    val id: Long = 0,
    @SerialName("ep_id") val epId: Long = 0,
    val aid: Long = 0,
    val bvid: String = "",
    val cid: Long = 0,
    /** Episode number as text, e.g. "1". */
    val title: String = "",
    @SerialName("long_title") val longTitle: String = "",
    val cover: String = "",
    /** **Milliseconds** — see the note at the top of this file. */
    val duration: Long = 0,
    @SerialName("pub_time") val pubTime: Long = 0,
    val badge: String = "",
) {
    /** The id the playurl endpoint wants. */
    val playId: Long get() = if (epId > 0) epId else id

    /** Seconds, for the UI. */
    val durationSeconds: Int get() = (duration / 1000).toInt()
}

@Serializable
data class PgcNewEp(
    val id: Long = 0,
    val title: String = "",
    val desc: String = "",
)

@Serializable
data class PgcUserStatus(
    /** 1 when the signed-in user follows the season. */
    val follow: Int = 0,
    @SerialName("follow_status") val followStatus: Int = 0,
    /** 1 when the request was authenticated at all. */
    val login: Int = 0,
)

@Serializable
data class PgcUpInfo(
    val mid: Long = 0,
    val uname: String = "",
    val avatar: String = "",
    val follower: Long = 0,
    @SerialName("is_follow") val isFollow: Int = 0,
)

@Serializable
data class PgcRating(
    val score: Double = 0.0,
    val count: Long = 0,
)

// ---------------------------------------------------------------------------
// Interaction state
//
// Both of these need a signed-in session; anonymous callers get code -101, so
// the models must stay tolerant and default to "no interaction" rather than
// failing the whole screen.
// ---------------------------------------------------------------------------

/** `x/web-interface/archive/coins` — coins already given to this archive. */
@Serializable
data class ArchiveCoinData(
    val multiply: Int = 0,
)

/** `x/v2/fav/video/favoured` — whether this archive is in any of my folders. */
@Serializable
data class FavouredData(
    /** Tolerates `true`, `1` and `"1"`; see [FlexibleBooleanSerializer]. */
    @Serializable(with = FlexibleBooleanSerializer::class) val favoured: Boolean = false,
    val count: Int = 0,
)
