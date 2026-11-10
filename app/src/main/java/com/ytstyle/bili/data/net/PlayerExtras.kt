package com.ytstyle.bili.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Everything the rebuilt video page needs beyond the basic view payload:
// the 合集 (UGC season) a video belongs to, the related-video rail, and the
// extra fields a comment row renders.
// ---------------------------------------------------------------------------

/**
 * `x/web-interface/view` → `data.ugc_season`.
 *
 * Present only when the video is part of a collection. This is preferred over
 * a separate seasons endpoint because the payload already arrives with the
 * detail call, so the 合集 entry costs no extra request.
 */
@Serializable
data class UgcSeason(
    val id: Long = 0,
    val title: String = "",
    val cover: String = "",
    val intro: String = "",
    @SerialName("ep_count") val epCount: Int = 0,
    val sections: List<UgcSection> = emptyList(),
) {
    /** Every episode across every section, in order. */
    val allEpisodes: List<UgcEpisode> get() = sections.flatMap { it.episodes }
}

@Serializable
data class UgcSection(
    val id: Long = 0,
    val title: String = "",
    val episodes: List<UgcEpisode> = emptyList(),
)

@Serializable
data class UgcEpisode(
    val aid: Long = 0,
    val bvid: String = "",
    val cid: Long = 0,
    /** The episode's index inside the collection, as text. */
    val title: String = "",
    /** `page.part`, i.e. the video's own title. */
    val page: UgcPage? = null,
    /** Carries the thumbnail and stats the row renders. */
    val arc: UgcArc? = null,
) {
    val displayTitle: String
        get() = arc?.title?.takeIf { it.isNotEmpty() }
            ?: page?.part?.takeIf { it.isNotEmpty() }
            ?: title

    val durationSeconds: Int get() = arc?.duration ?: page?.duration ?: 0
}

@Serializable
data class UgcPage(
    val cid: Long = 0,
    val page: Int = 0,
    val part: String = "",
    val duration: Int = 0,
)

@Serializable
data class UgcArc(
    val aid: Long = 0,
    val pic: String = "",
    val title: String = "",
    val duration: Int = 0,
    val pubdate: Long = 0,
    val stat: Stat? = null,
)

/**
 * Carried by each reply as `reply_control`.
 *
 * `location` is the "IP属地：河南" string the official client prints under the
 * timestamp. It is a plain string; Bilibili has been known to send it empty for
 * older comments, which the UI simply skips.
 */
@Serializable
data class ReplyControl(
    val location: String = "",
    @SerialName("sub_reply_entry_text") val subReplyEntryText: String = "",
    @SerialName("sub_reply_title_text") val subReplyTitleText: String = "",
    @SerialName("time_desc") val timeDesc: String = "",
)

/** `x/web-interface/archive/related` reuses the recommendation row shape. */
typealias RelatedVideos = List<RecommendItem>
