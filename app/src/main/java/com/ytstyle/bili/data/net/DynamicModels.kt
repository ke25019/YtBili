package com.ytstyle.bili.data.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** What a dynamic actually is, decided by the parser so the UI never guesses. */
enum class DynamicKind { VIDEO, IMAGES, ARTICLE, TEXT, LIVE, FORWARD, UNKNOWN }

/**
 * One dynamic, flattened from the `modules` tree.
 *
 * The payload nests everything under `modules.module_dynamic.major.<type>`, and
 * which `<type>` is filled decides how the card is drawn. Every field has a safe
 * default: a shape change must degrade one card, never the whole feed.
 */
data class DynamicEntry(
    val id: String = "",
    val kind: DynamicKind = DynamicKind.UNKNOWN,
    val authorName: String = "",
    val authorFace: String = "",
    val authorMid: Long = 0,
    /** Already display-ready on the wire ("2天前", "8月6日") — do not reformat. */
    val pubTimeLabel: String = "",
    val isPinned: Boolean = false,
    val text: String = "",
    val imageUrls: List<String> = emptyList(),
    val videoBvid: String = "",
    val videoTitle: String = "",
    val videoCover: String = "",
    val videoDurationLabel: String = "",
    /** Also pre-formatted ("78.7万"), which is why it stays a string. */
    val videoViewText: String = "",
    val articleTitle: String = "",
    val articleCover: String = "",
    /** The quoted original of a 转发, resolved one level deep. */
    val forwardAuthorName: String = "",
    val forwardText: String = "",
    val forwardImageUrls: List<String> = emptyList(),
    val forwardVideoBvid: String = "",
    val forwardVideoTitle: String = "",
    val forwardVideoCover: String = "",
    val liveTitle: String = "",
    val liveCover: String = "",
    val likeCount: Long = 0,
    val commentCount: Long = 0,
    val forwardCount: Long = 0,
)

/** A followed uploader in the left rail. */
data class DynamicRailUser(
    val mid: Long = 0,
    val name: String = "",
    val face: String = "",
)

/**
 * One page of the feed. [offset] is the opaque cursor for the next request and
 * [hasMore] stops the paging.
 */
data class DynamicFeedPage(
    val entries: List<DynamicEntry> = emptyList(),
    val offset: String = "",
    val hasMore: Boolean = false,
)

/** Convenience for callers that only want the rows. */
fun parseDynamicFeed(payload: JsonElement?): List<DynamicEntry> = parseDynamicPage(payload).entries

/**
 * Walks a `feed/all` or `feed/space` response.
 *
 * Returns an empty page for anything unexpected — a `-101` body, a null, an
 * array where an object was promised — so a bad response shows the empty state
 * instead of throwing inside a coroutine.
 */
fun parseDynamicPage(payload: JsonElement?): DynamicFeedPage {
    val root = payload as? JsonObject ?: return DynamicFeedPage()
    // Accept **both** shapes: the full `{code, message, data:{…}}` envelope and
    // the bare `data` object. Callers hand over `BiliResponse.data`, while a
    // saved response and the unit tests start at the envelope. Unwrapping
    // unconditionally is what made this screen render "还没有动态" while the log
    // showed `feed/all -> 200` for every request.
    val data = root.obj("data") ?: root
    val items = data.arr("items").orEmpty()
    return DynamicFeedPage(
        entries = items.mapNotNull { (it as? JsonObject)?.let(::dynamicEntryOf) },
        offset = data.str("offset"),
        hasMore = data.bool("has_more"),
    )
}

/**
 * Walks `x/relation/followings`.
 *
 * The documented field is `uname`, but the payload has carried `name` in
 * places, so both are tried and a row without either is dropped.
 */
fun parseFollowings(payload: JsonElement?): List<DynamicRailUser> {
    // Same dual shape as [parseDynamicPage]: the caller passes
    // `BiliResponse.data`, a saved payload starts one level up.
    val root = payload as? JsonObject ?: return emptyList()
    val data = root.obj("data") ?: root
    val list = data.arr("list") ?: return emptyList()
    return list.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val mid = row.long("mid")
        val name = row.str("uname").ifBlank { row.str("name") }
        if (mid <= 0 && name.isBlank()) null else DynamicRailUser(mid, name, row.str("face"))
    }
}

private fun dynamicEntryOf(item: JsonObject): DynamicEntry? {
    val type = item.str("type")
    val modules = item.obj("modules") ?: JsonObject(emptyMap())
    val author = modules.obj("module_author")
    val dynamic = modules.obj("module_dynamic")
    val major = dynamic?.obj("major")
    val majorType = major?.str("type").orEmpty()

    val id = item.str("id_str").ifBlank { item.long("id").takeIf { it > 0 }?.toString().orEmpty() }

    // Opus-style payloads move the body text out of `desc`, so both are tried.
    val text = dynamic?.obj("desc")?.str("text").orEmpty()
        .ifBlank { major?.obj("opus")?.obj("summary")?.str("text").orEmpty() }

    val images = imageUrlsOf(major)
    val video = major?.obj("archive")
    val article = major?.obj("article")
    val live = liveInfoOf(major)

    val origModules = item.obj("orig")?.obj("modules")
    val origDynamic = origModules?.obj("module_dynamic")
    val origMajor = origDynamic?.obj("major")
    val origVideo = origMajor?.obj("archive")

    val kind = when {
        type == "DYNAMIC_TYPE_FORWARD" -> DynamicKind.FORWARD
        video != null || majorType == "MAJOR_TYPE_ARCHIVE" -> DynamicKind.VIDEO
        live != null -> DynamicKind.LIVE
        majorType == "MAJOR_TYPE_DRAW" || images.isNotEmpty() -> DynamicKind.IMAGES
        article != null || majorType == "MAJOR_TYPE_ARTICLE" -> DynamicKind.ARTICLE
        text.isNotBlank() -> DynamicKind.TEXT
        else -> DynamicKind.UNKNOWN
    }

    val videoTitle = video?.str("title").orEmpty()
    // 置顶 arrives either as a tag module or as `is_top` on the author.
    val pinned = author?.bool("is_top") == true ||
        modules.obj("module_tag")?.str("text") == "置顶"

    if (id.isEmpty() && text.isBlank() && videoTitle.isBlank() &&
        images.isEmpty() && article == null && live == null
    ) {
        return null
    }

    val stat = modules.obj("module_stat")
    val articleCovers = article?.arr("covers")
    return DynamicEntry(
        id = id,
        kind = kind,
        authorName = author?.str("name").orEmpty(),
        authorFace = author?.str("face").orEmpty(),
        authorMid = author?.long("mid") ?: 0L,
        pubTimeLabel = author?.str("pub_time").orEmpty(),
        isPinned = pinned,
        text = text,
        imageUrls = images,
        videoBvid = video?.str("bvid").orEmpty(),
        videoTitle = videoTitle,
        videoCover = video?.str("cover").orEmpty(),
        videoDurationLabel = video?.str("duration_text").orEmpty(),
        videoViewText = video?.obj("stat")?.str("play").orEmpty(),
        articleTitle = article?.str("title").orEmpty(),
        articleCover = (articleCovers?.firstOrNull() as? JsonPrimitive)?.contentOrNull.orEmpty(),
        forwardAuthorName = origModules?.obj("module_author")?.str("name").orEmpty(),
        forwardText = origDynamic?.obj("desc")?.str("text").orEmpty(),
        forwardImageUrls = imageUrlsOf(origMajor),
        forwardVideoBvid = origVideo?.str("bvid").orEmpty(),
        forwardVideoTitle = origVideo?.str("title").orEmpty(),
        forwardVideoCover = origVideo?.str("cover").orEmpty(),
        liveTitle = live?.first.orEmpty(),
        liveCover = live?.second.orEmpty(),
        likeCount = stat?.obj("like")?.long("count") ?: 0L,
        commentCount = stat?.obj("comment")?.long("count") ?: 0L,
        forwardCount = stat?.obj("forward")?.long("count") ?: 0L,
    )
}

/**
 * Image URLs of a `MAJOR_TYPE_DRAW` (or opus) major.
 *
 * Two shapes exist because the payload has both generations: `draw.items[].src`
 * and `opus.pics[].url`.
 */
private fun imageUrlsOf(major: JsonObject?): List<String> {
    val draw = major?.obj("draw")?.arr("items")
    if (draw != null) {
        return draw.mapNotNull { (it as? JsonObject)?.str("src")?.takeIf(String::isNotBlank) }
    }
    val pics = major?.obj("opus")?.arr("pics")
    if (pics != null) {
        return pics.mapNotNull { (it as? JsonObject)?.str("url")?.takeIf(String::isNotBlank) }
    }
    return emptyList()
}

/**
 * `major.live_rcmd.content` is a JSON document serialised into a string; the
 * useful part is nested under `live_play_info`.
 *
 * A live dynamic therefore counts as LIVE even when its title cannot be read —
 * the rail's `Live(N)` only needs to know that it *is* one.
 */
private fun liveInfoOf(major: JsonObject?): Pair<String, String>? {
    val content = major?.obj("live_rcmd")?.str("content") ?: return null
    if (content.isBlank()) return "" to ""
    return runCatching {
        val root = Json.parseToJsonElement(content) as? JsonObject
        val info = root?.obj("live_play_info")
        info?.str("title").orEmpty() to info?.str("cover").orEmpty()
    }.getOrElse { "" to "" }
}

// ---- tolerant JSON accessors -------------------------------------------------
//
// Deliberately local rather than shared: `JsonNull` and a quoted "null" appear
// in this payload, and every field has to be optional. See the same approach in
// FollowApi.kt and ui/messages/MessageModels.kt.

private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonObject?.field(key: String): JsonElement? {
    val value = this?.get(key) ?: return null
    if (value is JsonPrimitive && !value.isString && value.content == "null") return null
    return value
}

private fun JsonObject?.obj(key: String): JsonObject? = field(key)?.asObjectOrNull()

private fun JsonObject?.arr(key: String): JsonArray? = field(key) as? JsonArray

private fun JsonObject?.str(key: String): String {
    val primitive = field(key) as? JsonPrimitive ?: return ""
    return primitive.contentOrNull?.trim().orEmpty()
}

private fun JsonObject?.long(key: String): Long {
    val primitive = field(key) as? JsonPrimitive ?: return 0L
    primitive.longOrNull?.let { return it }
    val text = primitive.contentOrNull?.trim() ?: return 0L
    return text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong() ?: 0L
}

private fun JsonObject?.bool(key: String): Boolean {
    val primitive = field(key) as? JsonPrimitive ?: return false
    primitive.booleanOrNull?.let { return it }
    return when (primitive.contentOrNull?.trim()?.lowercase()) {
        "true", "1" -> true
        else -> false
    }
}
