package com.ytstyle.bili.data.net

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `msg[receiver_type]`: 1 is a user, 2 would be a 粉丝团 id. */
internal const val DM_RECEIVER_USER = "1"

/** `msg[msg_type]`: `send_msg` accepts only 1, 2 and 5; this client sends text. */
internal const val DM_TYPE_TEXT = "1"

/**
 * The form body of `web_im/v1/web_im/send_msg`.
 *
 * Extracted from the repository so the field names and the encoded `content` can
 * be asserted **without a network round trip**. That matters here more than
 * usual: the endpoint needs a login session, which this machine does not have,
 * so the request shape is the only part of sending that can be verified at all,
 * and a silently wrong field name would surface as a bare "发送失败".
 *
 * The shape comes from `docs/message/private_msg.md`: every key is a `msg[...]`
 * form field, `content` is a **JSON document encoded into a string**, and
 * `dev_id` is a UUID v4.
 */
internal fun directMessageFields(
    senderUid: Long,
    receiverId: Long,
    text: String,
    deviceId: String,
    timestampSeconds: Long,
    csrf: String,
): Map<String, String> = mapOf(
    "msg[sender_uid]" to senderUid.toString(),
    "msg[receiver_id]" to receiverId.toString(),
    "msg[receiver_type]" to DM_RECEIVER_USER,
    "msg[msg_type]" to DM_TYPE_TEXT,
    "msg[msg_status]" to "0",
    "msg[dev_id]" to deviceId,
    "msg[timestamp]" to timestampSeconds.toString(),
    // Built with the serializer rather than string concatenation, so a quote,
    // brace or newline in somebody's message cannot break the JSON the server
    // is expecting.
    "msg[content]" to buildJsonObject { put("content", text) }.toString(),
    "csrf_token" to csrf,
    "csrf" to csrf,
)
