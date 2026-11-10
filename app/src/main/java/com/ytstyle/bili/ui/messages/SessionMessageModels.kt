package com.ytstyle.bili.ui.messages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What one private message is.
 *
 * `msg_type` decides the shape of `content`, which arrives as a JSON document
 * *serialised into a string* — so every message needs a second parse, and any
 * of them may fail. A message whose content cannot be read still renders as
 * [UNKNOWN] with its raw text rather than disappearing.
 */
enum class SessionMessageKind {
    TEXT,
    IMAGE,
    SHARE,
    NOTICE,
    SYSTEM,
    RECALLED,
    UNKNOWN,
}

/** One message, flattened. `key` is unique and stable, which a list key needs. */
data class SessionMessage(
    val key: Long = 0,
    val seqno: Long = 0,
    val senderUid: Long = 0,
    /** True when this account sent it, which is what decides the bubble side. */
    val outgoing: Boolean = false,
    val kind: SessionMessageKind = SessionMessageKind.UNKNOWN,
    val text: String = "",
    val imageUrl: String = "",
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val title: String = "",
    val desc: String = "",
    val cover: String = "",
    val jumpUrl: String = "",
    val bvid: String = "",
    /** Epoch seconds; the server sends seconds here, not microseconds. */
    val timeSeconds: Long = 0,
)

/**
 * One page of history, **oldest first** so it can be appended straight into a
 * chat list. The endpoint itself answers newest-first.
 *
 * [minSeqno] is what paging older needs: it goes back as `end_seqno`.
 */
data class SessionMessagePage(
    val messages: List<SessionMessage> = emptyList(),
    val hasMore: Boolean = false,
    val minSeqno: Long = 0,
    /** `e_infos`: `[tv_doge]` → image url, for the messages that use them. */
    val emotes: Map<String, String> = emptyMap(),
)

/**
 * Walks `svr_sync/v1/svr_sync/fetch_session_msgs`.
 *
 * [selfUid] decides which side a bubble belongs to; `0` (not yet known) simply
 * renders everything as incoming.
 */
fun parseSessionMessages(payload: JsonElement?, selfUid: Long): SessionMessagePage {
    val root = payload as? JsonObject ?: return SessionMessagePage()
    // Both the full envelope and the bare `data` object are accepted — see the
    // note in `parseDynamicPage`. The repository passes `MsgEnvelope.data`.
    val data = root.obj("data") ?: root
    val emotes = parseEmotes(data.arr("e_infos"))
    val messages = data.arr("messages").orEmpty()
        .mapNotNull { (it as? JsonObject)?.let { row -> sessionMessage(row, selfUid) } }
        // The payload is newest-first; a chat list reads the other way round.
        .sortedBy { it.seqno }
    return SessionMessagePage(
        messages = messages,
        hasMore = data.long("has_more") != 0L,
        minSeqno = data.long("min_seqno"),
        emotes = emotes,
    )
}

private fun parseEmotes(array: JsonArray?): Map<String, String> {
    if (array == null) return emptyMap()
    val out = HashMap<String, String>(array.size)
    array.forEach { element ->
        val row = element as? JsonObject ?: return@forEach
        val token = row.str("text")
        // The documented key is `url`; older payloads have used `uri`.
        val url = row.str("url").ifBlank { row.str("uri") }
        if (token.isNotEmpty() && url.isNotEmpty()) out[token] = url
    }
    return out
}

private fun sessionMessage(row: JsonObject, selfUid: Long): SessionMessage? {
    val seqno = row.long("msg_seqno")
    val sender = row.long("sender_uid")
    val time = row.long("timestamp")
    val key = row.long("msg_key").takeIf { it != 0L }
        ?: (seqno * 1000 + (sender % 1000))
    val outgoing = selfUid > 0 && sender == selfUid

    val base = SessionMessage(
        key = key,
        seqno = seqno,
        senderUid = sender,
        outgoing = outgoing,
        timeSeconds = time,
    )

    // `msg_status = 1` is a message that was recalled; the body is gone.
    if (row.long("msg_status") == 1L) {
        return base.copy(
            kind = SessionMessageKind.RECALLED,
            text = if (outgoing) "你撤回了一条消息" else "对方撤回了一条消息",
        )
    }

    val raw = row.str("content")
    val content = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    return when (row.long("msg_type").toInt()) {
        1 -> base.copy(kind = SessionMessageKind.TEXT, text = content.str("content").ifBlank { raw })

        2, 6 -> base.copy(
            kind = SessionMessageKind.IMAGE,
            imageUrl = content.str("url").ifBlank { content.str("thumb") },
            imageWidth = content.int("width"),
            imageHeight = content.int("height"),
        )

        // A `5` in history is the "recalled" marker itself.
        5 -> base.copy(kind = SessionMessageKind.SYSTEM, text = "撤回了一条消息")

        7 -> base.copy(
            kind = SessionMessageKind.SHARE,
            title = content.str("title").ifBlank { content.str("headline") },
            desc = content.str("author"),
            cover = content.str("thumb"),
            jumpUrl = content.str("url"),
            bvid = content.str("bvid").ifBlank { extractBvid(content.str("url")).orEmpty() },
        )

        10 -> base.copy(
            kind = SessionMessageKind.NOTICE,
            title = content.str("title"),
            text = content.str("text"),
            jumpUrl = content.str("jump_uri"),
        )

        14 -> base.copy(
            kind = SessionMessageKind.SHARE,
            title = content.str("title"),
            desc = content.str("desc").ifBlank { content.str("author") },
            cover = content.str("cover"),
            jumpUrl = content.str("url"),
        )

        // Two nesting levels: the content document is `{"content": "[…]"}`, and
        // that inner string is the serialised hint array.
        18 -> base.copy(
            kind = SessionMessageKind.SYSTEM,
            text = systemHint(content.str("content").ifBlank { raw }),
        )

        // Everything else (小程序卡片, 视频推送, AI 消息 …) still shows its raw
        // body: a wall of JSON is ugly, an empty bubble is worse.
        else -> base.copy(kind = SessionMessageKind.UNKNOWN, text = raw.take(120))
    }
}

/**
 * `msg_type = 18`'s hint list: a **JSON array serialised into a string**, each
 * entry carrying the hint's `text` and its light/dark colours.
 *
 * [serialised] is that string — the caller has already unwrapped the outer
 * `content` object.
 */
private fun systemHint(serialised: String): String {
    if (serialised.isBlank()) return ""
    val array = runCatching { Json.parseToJsonElement(serialised) as? JsonArray }.getOrNull()
        ?: return serialised.take(120)
    return array
        .mapNotNull { (it as? JsonObject)?.str("text")?.takeIf(String::isNotBlank) }
        .joinToString(" ")
        .ifBlank { serialised.take(120) }
}
