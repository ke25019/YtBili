package com.ytstyle.bili.ui.messages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The four message categories, in the order Bilibili shows their shortcuts.
 */
enum class MessageCategory(val label: String) {
    REPLY("回复我的"),
    AT("@我"),
    LIKE("收到的赞"),
    SYSTEM("系统通知"),
}

/** Unread badges for the shortcut row. */
data class MsgUnread(
    val reply: Int = 0,
    val at: Int = 0,
    val like: Int = 0,
    val sysMsg: Int = 0,
) {
    fun countFor(category: MessageCategory): Int = when (category) {
        MessageCategory.REPLY -> reply
        MessageCategory.AT -> at
        MessageCategory.LIKE -> like
        MessageCategory.SYSTEM -> sysMsg
    }

    companion object {
        val Zero = MsgUnread()
    }
}

/**
 * One row in the feed, flattened from the four server item shapes.
 *
 * Every field has a safe default: a payload that drops or retypes a field yields
 * an empty string or zero instead of a parse failure, which is what keeps a
 * server-side schema tweak from blanking the whole page.
 */
data class MessageEntry(
    val id: Long = 0,
    val category: MessageCategory = MessageCategory.REPLY,
    val senderName: String = "",
    val avatar: String = "",
    val summary: String = "",
    val timeSeconds: Long = 0,
    val timeLabel: String = "",
    val bvid: String? = null,
)

/** Server paging cursor; `isEnd` stops the "加载更多" button. */
data class MessageCursor(
    val id: Long = 0,
    val time: Long = 0,
    val isEnd: Boolean = true,
)

/** One page of a single category. */
data class MessagePage(
    val entries: List<MessageEntry> = emptyList(),
    val cursor: MessageCursor = MessageCursor(),
)

/** Outcome of a message request: the server message and code are kept for display. */
sealed interface MsgResult<out T> {
    data class Ok<T>(val value: T) : MsgResult<T>
    data class Err(val message: String, val code: Int = -1) : MsgResult<Nothing>
}

/** Bilibili's "账号未登录" status returned when the cookie is missing or stale. */
const val MSG_CODE_NOT_LOGGED_IN = -101

/**
 * Bilibili's "请求过于频繁" status.
 *
 * Seen once from `get_sessions`; it is transient, so the repository retries the
 * call once instead of treating the session list as broken.
 */
const val MSG_CODE_RATE_LIMITED = -509

/**
 * Turns the raw `data` objects of the message endpoints into UI models.
 *
 * Deliberately hand-rolled rather than `@Serializable` item classes: the feeds
 * disagree on where the text lives (`reply.content.message`, `title`, `text`,
 * `item.subject`) and on which timestamp field they carry. Every lookup here is
 * optional and defaults to empty/zero.
 */
internal object MessageParser {

    fun unread(data: JsonElement?): MsgUnread {
        val root = data as? JsonObject
        val counts = root.obj("unread") ?: root
        return MsgUnread(
            reply = counts.int("reply"),
            at = counts.int("at"),
            like = counts.int("like"),
            sysMsg = counts.int("sys_msg"),
        )
    }

    fun page(category: MessageCategory, data: JsonElement?): MessagePage {
        val root = data as? JsonObject ?: return MessagePage()
        val items = root.arr("items")?.toList().orEmpty()
        val entries = items.mapNotNull { element ->
            (element as? JsonObject)?.let { entry(category, it) }
        }
        val cursor = root.obj("cursor")
        return MessagePage(
            entries = entries,
            cursor = MessageCursor(
                id = cursor.long("id"),
                time = cursor.long("time"),
                isEnd = cursor.bool("is_end") ?: entries.isEmpty(),
            ),
        )
    }

    private fun entry(category: MessageCategory, item: JsonObject): MessageEntry {
        val target = item.obj("item")
        val user = item.obj("user")
        val firstUser = (item.arr("users")?.firstOrNull() as? JsonObject) ?: user
        val replyContent = item.obj("reply").obj("content")
        val (seconds, label) = timeOf(item, category)

        val senderName = when (category) {
            MessageCategory.REPLY, MessageCategory.AT -> firstUser.str("nickname")
            MessageCategory.LIKE -> likeSender(firstUser.str("nickname"), item.long("count"))
            MessageCategory.SYSTEM -> ""
        }

        val summary = when (category) {
            MessageCategory.REPLY -> firstText(
                target.str("source_content"),
                target.str("root_reply_content"),
                replyContent.str("message"),
                target.firstText("title", "subject", "description"),
                item.firstText("title", "content", "text"),
            )
            MessageCategory.AT -> firstText(
                target.str("source_content"),
                target.str("target_reply_content"),
                item.firstText("text", "content"),
                target.firstText("title", "subject"),
                replyContent.str("message"),
            )
            MessageCategory.LIKE -> firstText(
                target.firstText("title", "subject"),
                item.firstText("title", "content"),
            )
            MessageCategory.SYSTEM -> systemSummary(item)
        }

        val bvid = extractBvid(
            target.str("bvid"),
            target.str("target"),
            target.str("uri"),
            target.str("title"),
            item.str("bvid"),
            item.str("target"),
            item.str("uri"),
            item.str("web_url"),
            item.str("url"),
        )

        val rawId = item.long("id")
        val id = if (rawId != 0L) rawId else seconds * 1000 + category.ordinal

        return MessageEntry(
            id = id,
            category = category,
            senderName = senderName,
            avatar = firstUser.str("avatar"),
            summary = summary,
            timeSeconds = seconds,
            timeLabel = label,
            bvid = bvid,
        )
    }

    /**
     * Picks the first timestamp the item carries, in category-specific order.
     *
     * A reply keeps `ctime` inside `reply`, likes use `like_time`, mentions use
     * `at_time`, and system notices may return a formatted string instead of an
     * epoch, so the label is carried along as a display fallback.
     */
    private fun timeOf(item: JsonObject, category: MessageCategory): Pair<Long, String> {
        val keys = when (category) {
            MessageCategory.REPLY -> listOf("reply_time", "ctime", "time", "pub_time", "timestamp")
            MessageCategory.AT -> listOf("at_time", "ctime", "time", "timestamp")
            MessageCategory.LIKE -> listOf("like_time", "ctime", "time", "timestamp")
            MessageCategory.SYSTEM -> listOf("time", "ctime", "pub_time", "timestamp")
        }
        val sources = mutableListOf<JsonObject?>(item)
        item.obj("reply")?.let { sources.add(it) }
        item.obj("item")?.let { sources.add(it) }
        for (source in sources) {
            for (key in keys) {
                val parsed = parseMoment(source.field(key)) ?: continue
                return parsed
            }
        }
        return 0L to ""
    }

    private fun systemSummary(item: JsonObject): String {
        val title = item.str("title")
        val text = item.str("text")
        return when {
            title.isBlank() -> text
            text.isBlank() -> title
            title == text -> title
            else -> "$title：$text"
        }
    }

    private fun likeSender(name: String, count: Long): String {
        val who = name.ifBlank { "有人" }
        return if (count > 1) "$who 等${count}人" else who
    }

    private fun firstText(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

/**
 * Normalises a timestamp field.
 *
 * Accepts epoch seconds, epoch milliseconds, digit strings and the
 * `yyyy-MM-dd HH:mm:ss` strings the system feed sometimes uses. Returns the
 * display label only when the value could not be turned into an epoch.
 */
internal fun parseMoment(element: JsonElement?): Pair<Long, String>? {
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) {
        val number = primitive.longOrNull ?: primitive.content.trim().toDoubleOrNull()?.toLong()
        return if (number != null && number > 0) normaliseSeconds(number) to "" else null
    }
    val text = primitive.content.trim()
    if (text.isEmpty()) return null
    text.toLongOrNull()?.let { return if (it > 0) normaliseSeconds(it) to "" else null }
    text.toDoubleOrNull()?.toLong()?.let { return if (it > 0) normaliseSeconds(it) to "" else null }
    for (pattern in DATE_PATTERNS) {
        val seconds = parseDate(text, pattern) ?: continue
        return seconds to text
    }
    return null
}

private fun normaliseSeconds(value: Long): Long =
    if (value > 100_000_000_000L) value / 1000 else value

private val DATE_PATTERNS = listOf(
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd HH:mm",
    "yyyy-MM-dd",
)

private fun parseDate(text: String, pattern: String): Long? = try {
    val format = SimpleDateFormat(pattern, Locale.CHINA)
    format.isLenient = false
    format.parse(text)?.time?.let { it / 1000 }
} catch (e: Exception) {
    null
}

private val BV_PATTERN = Regex("BV[0-9A-Za-z]{10}")

/**
 * Finds a BV id anywhere in the payload.
 *
 * The feeds mostly expose the target as a URL or a `bilibili://video/av…` uri
 * rather than a `bvid` field, so every candidate string is scanned.
 */
internal fun extractBvid(vararg candidates: String?): String? {
    for (candidate in candidates) {
        val text = candidate?.trim().orEmpty()
        if (text.length < 12) continue
        val match = BV_PATTERN.find(text) ?: continue
        return match.value
    }
    return null
}

// ---- tolerant JSON accessors -------------------------------------------------

internal fun JsonObject?.field(key: String): JsonElement? {
    val value = this?.get(key) ?: return null
    return if (value is JsonPrimitive && !value.isString && value.content == "null") null else value
}

internal fun JsonObject?.str(key: String): String {
    val primitive = field(key) as? JsonPrimitive ?: return ""
    return primitive.content.trim()
}

internal fun JsonObject?.long(key: String): Long {
    val primitive = field(key) as? JsonPrimitive ?: return 0
    primitive.longOrNull?.let { return it }
    val text = primitive.content.trim()
    text.toLongOrNull()?.let { return it }
    return text.toDoubleOrNull()?.toLong() ?: 0
}

internal fun JsonObject?.int(key: String): Int = long(key).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

internal fun JsonObject?.bool(key: String): Boolean? {
    val primitive = field(key) as? JsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return it }
    return when (primitive.content.trim().lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }
}

internal fun JsonObject?.obj(key: String): JsonObject? = field(key) as? JsonObject

internal fun JsonObject?.arr(key: String): JsonArray? = field(key) as? JsonArray

internal fun JsonObject?.firstText(vararg keys: String): String {
    for (key in keys) {
        val text = str(key)
        if (text.isNotBlank()) return text
    }
    return ""
}

// ---- direct-message sessions (api.vc.bilibili.com) ---------------------------

/**
 * One 私信会话 row from `session_svr/v1/session_svr/get_sessions`.
 *
 * [id] is a synthetic key for `LazyColumn`, not a server field. `talker_id`
 * alone is not unique — the response's own `session_type` splits the id space
 * into 用户 and 粉丝团 — and a duplicate key crashes the list, so uniqueness is
 * enforced by [parseSessions] rather than assumed here.
 *
 * [bvid] is not part of the documented session shape either: it is scanned out
 * of the last message when a shared-video message happens to carry one, which
 * is what lets such a row open the player instead of a profile page.
 *
 * Every field defaults, so a row the server retypes still renders.
 */
data class DirectMessageSession(
    val id: Long = 0,
    val talkerId: Long = 0,
    val isGroup: Boolean = false,
    val name: String = "",
    val face: String = "",
    val preview: String = "",
    val timeSeconds: Long = 0,
    val unreadCount: Int = 0,
    val isSystem: Boolean = false,
    val isLive: Boolean = false,
    val bvid: String? = null,
)

/** `session_type` in the *response*: 2 means 粉丝团 (1 means 用户). */
private const val SESSION_TYPE_GROUP = 2L

/** Nothing below this is a plausible epoch reading (2009-01-01). */
private const val MIN_PLAUSIBLE_SECONDS = 1_230_768_000L

private const val MICROS = 1_000_000L
private const val MILLIS = 1_000L

/**
 * Parses `data` of a `get_sessions` response.
 *
 * Hand-rolled and fully optional like the rest of this file: a null, absent or
 * mistyped `session_list` yields an empty list, a non-object entry is skipped,
 * and one odd field can never blank the page. Never throws.
 */
fun parseSessions(payload: JsonElement?): List<DirectMessageSession> {
    val entries = sessionList(payload) ?: return emptyList()
    val seen = HashSet<Long>()
    val sessions = ArrayList<DirectMessageSession>(entries.size)
    entries.forEachIndexed { index, element ->
        val item = element as? JsonObject ?: return@forEachIndexed
        sessions.add(session(item, index, seen))
    }
    return sessions
}

/**
 * `session_list` from the payload, tolerating the `{ data: { session_list } }`
 * wrapper some of the message endpoints use.
 */
private fun sessionList(payload: JsonElement?): JsonArray? {
    val root = payload as? JsonObject ?: return null
    root.arr("session_list")?.let { return it }
    return root.obj("data")?.arr("session_list")
}

private fun session(item: JsonObject, index: Int, seen: MutableSet<Long>): DirectMessageSession {
    val talkerId = item.long("talker_id")
    val groupName = item.str("group_name")
    val groupCover = item.str("group_cover")
    val account = item.obj("account_info")
    val isGroup = item.long("session_type") == SESSION_TYPE_GROUP || groupName.isNotBlank()
    val lastMsg = item.obj("last_msg")
    val content = lastMsg.str("content")

    return DirectMessageSession(
        id = uniqueId(talkerId, isGroup, index, seen),
        talkerId = talkerId,
        isGroup = isGroup,
        // account_info is how the official/system rows (创作助手, 社区中心,
        // 未关注人消息 in the reference screenshot) carry their identity;
        // group_name/group_cover is the 粉丝团 path. A user row has neither and
        // is filled in later from `account/v1/user/cards`.
        name = firstNonBlank(account.firstText("name", "nickname", "uname"), groupName),
        face = firstNonBlank(account.firstText("pic_url", "face", "avatar", "cover"), groupCover),
        preview = previewOf(content),
        timeSeconds = sessionSeconds(item.long("session_ts"), lastMsg),
        unreadCount = item.int("unread_count"),
        isSystem = item.int("system_msg_type") != 0 || account != null,
        isLive = item.int("live_status") > 0,
        bvid = extractBvid(
            content,
            lastMsg.field("content")?.toString(),
            lastMsg.str("bvid"),
            lastMsg.str("url"),
            lastMsg.str("jump_url"),
            item.str("bvid"),
        ),
    )
}

/**
 * A `LazyColumn` key that cannot repeat within one page.
 *
 * The session kind is folded into the id because a 粉丝团 row and a user row can
 * share a `talker_id`, and `talker_id = 0` is answered by the server for some
 * official rows. Any remaining collision (two id-less rows) falls back to a
 * negative, position-derived value, which is deterministic for a given payload.
 */
private fun uniqueId(talkerId: Long, isGroup: Boolean, index: Int, seen: MutableSet<Long>): Long {
    val kind = if (isGroup) 2L else 1L
    val composed = if (talkerId > 0) talkerId * 10L + kind else 0L
    if (composed > 0L && seen.add(composed)) return composed
    var fallback = -(index + 1L)
    while (!seen.add(fallback)) fallback -= 1_000_000L
    return fallback
}

private fun firstNonBlank(vararg values: String): String =
    values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

/**
 * `session_ts` is documented as **microseconds**, but the API has been seen
 * sending milliseconds and seconds elsewhere, so the interpretation that lands
 * on a plausible epoch wins. `last_msg.timestamp` (seconds) is the fallback and
 * 0 the last resort, which the screen renders as an empty time.
 */
private fun sessionSeconds(raw: Long, lastMsg: JsonObject?): Long {
    plausibleSeconds(raw)?.let { return it }
    return parseMoment(lastMsg.field("timestamp"))?.first ?: 0L
}

private fun plausibleSeconds(raw: Long): Long? {
    if (raw <= 0L) return null
    val upper = System.currentTimeMillis() / 1000 + 86_400
    return listOf(raw / MICROS, raw / MILLIS, raw)
        .firstOrNull { it in MIN_PLAUSIBLE_SECONDS..upper }
}

/**
 * Fills blank names/faces in [sessions] from an `account/v1/user/cards` payload.
 *
 * The documented shape is `data` keyed by uid-as-string with `{mid, name,
 * face}` values, but that has never been observed with a login session, so
 * anything else — an empty `data`, an array, a primitive value, a missing key —
 * leaves the affected session exactly as it was. Never throws.
 */
fun mergeUserCards(
    sessions: List<DirectMessageSession>,
    cards: JsonElement?,
): List<DirectMessageSession> {
    if (sessions.isEmpty()) return sessions
    val root = cards as? JsonObject ?: return sessions
    // Accept both `{ data: { uid: card } }` and the bare uid-keyed object.
    val data = root.obj("data") ?: root
    if (data.isEmpty()) return sessions
    return sessions.map { session ->
        if (session.name.isNotBlank() && session.face.isNotBlank()) return@map session
        val card = data.obj(session.talkerId.toString()) ?: return@map session
        session.copy(
            name = session.name.ifBlank { card.firstText("name", "nickname", "uname") },
            face = session.face.ifBlank { card.firstText("face", "avatar", "pic_url") },
        )
    }
}

/**
 * `name` and `face` for one uid out of an `account/v1/user/cards` payload.
 *
 * The session list resolves every uid in one call, but a conversation opened
 * directly only needs its own peer — that is what this reads. Same dual-shape
 * tolerance as [mergeUserCards]; null when the payload does not mention [uid].
 */
fun parseUserCard(payload: JsonElement?, uid: Long): Pair<String, String>? {
    if (uid <= 0) return null
    val root = payload as? JsonObject ?: return null
    val data = root.obj("data") ?: root
    val card = data.obj(uid.toString()) ?: return null
    val name = card.firstText("name", "nickname", "uname")
    val face = card.firstText("face", "avatar", "pic_url")
    if (name.isBlank() && face.isBlank()) return null
    return name to face
}

/** The shared lenient configuration is only reachable through Hilt; parsing a
 *  preview is pure, so the parser owns a private instance instead. */
private val PreviewJson = Json { isLenient = true; ignoreUnknownKeys = true }

private val PREVIEW_WHITESPACE = Regex("\\s+")
private const val PREVIEW_MAX_CHARS = 80

/**
 * One display line for `last_msg.content`.
 *
 * `content` is documented as a JSON document serialized into a string, but in
 * practice it is also a plain sentence, an array (system/recall notices) and an
 * image descriptor, so every branch is optional:
 *
 *  - `{ text }`                    -> the text
 *  - `{ title, text }`             -> `title：text` (matching 系统通知's style)
 *  - `{ url | origin_image | thumb }` with no text -> `[图片]`
 *  - a JSON array                  -> its texts, or `[消息]`
 *  - not JSON at all               -> the raw text
 *
 * Whitespace is collapsed and the result capped at [PREVIEW_MAX_CHARS]. Emoji
 * tokens like `[tv_doge]` are left alone: the notification screen does not
 * render emoji images either.
 */
fun previewOf(content: String): String {
    val raw = content.trim()
    if (raw.isEmpty()) return ""
    val document = if (raw.startsWith("{") || raw.startsWith("[")) {
        runCatching { PreviewJson.parseToJsonElement(raw) }.getOrNull()
    } else {
        null
    }
    val decoded = when (document) {
        null -> raw
        is JsonObject -> objectPreview(document)
        is JsonArray -> arrayPreview(document)
        is JsonPrimitive -> if (document.isString) document.content else raw
        else -> raw
    }
    return capPreview(decoded)
}

private fun objectPreview(obj: JsonObject): String {
    val text = obj.str("text")
    val title = obj.str("title")
    val body = when {
        title.isBlank() -> text
        text.isBlank() -> title
        title == text -> title
        else -> "$title：$text"
    }
    if (body.isNotBlank()) return body
    return if (obj.hasImage()) "[图片]" else "[消息]"
}

private fun JsonObject.hasImage(): Boolean =
    field("url") != null ||
        field("origin_image") != null ||
        field("thumb") != null ||
        field("image") != null ||
        field("image_url") != null

private fun arrayPreview(array: JsonArray): String {
    val parts = array.mapNotNull { element ->
        when (element) {
            is JsonObject -> element.str("text").ifBlank { element.str("title") }.ifBlank { null }
            is JsonPrimitive -> {
                val text = element.content
                if (element.isString && text.isNotBlank()) text else null
            }
            else -> null
        }
    }
    return parts.joinToString(" ").ifBlank { "[消息]" }
}

private fun capPreview(raw: String): String {
    val collapsed = raw.replace(PREVIEW_WHITESPACE, " ").trim()
    if (collapsed.length <= PREVIEW_MAX_CHARS) return collapsed
    return collapsed.take(PREVIEW_MAX_CHARS - 1).trimEnd() + "…"
}
