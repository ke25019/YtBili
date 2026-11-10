package com.ytstyle.bili.data.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 私信 request body, pinned to what `docs/message/private_msg.md` specifies.
 *
 * This is the only part of sending a message that can be verified here: the
 * endpoint requires a login session, so the success path cannot be exercised.
 * The field names are exact strings the server matches on, and getting one wrong
 * would come back as a bare `请求错误` with nothing to point at the cause.
 */
class DirectMessagePayloadTest {

    private fun fields(text: String = "你好") = directMessageFields(
        senderUid = 12345L,
        receiverId = 67890L,
        text = text,
        deviceId = "0f8fad5b-d9cb-469f-a165-70867728950e",
        timestampSeconds = 1_700_000_000L,
        csrf = "abc123",
    )

    @Test
    fun `every documented field is present`() {
        val payload = fields()
        assertEquals(
            setOf(
                "msg[sender_uid]",
                "msg[receiver_id]",
                "msg[receiver_type]",
                "msg[msg_type]",
                "msg[msg_status]",
                "msg[dev_id]",
                "msg[timestamp]",
                "msg[content]",
                "csrf_token",
                "csrf",
            ),
            payload.keys,
        )
    }

    @Test
    fun `identity and status fields carry the documented values`() {
        val payload = fields()
        assertEquals("12345", payload["msg[sender_uid]"])
        assertEquals("67890", payload["msg[receiver_id]"])
        // 1 = a user; 2 would mean a 粉丝团 id.
        assertEquals("1", payload["msg[receiver_type]"])
        // Only 1, 2 and 5 are accepted by this endpoint.
        assertEquals("1", payload["msg[msg_type]"])
        assertEquals("0", payload["msg[msg_status]"])
        assertEquals("1700000000", payload["msg[timestamp]"])
        assertEquals("abc123", payload["csrf"])
        assertEquals("abc123", payload["csrf_token"])
    }

    @Test
    fun `dev_id is carried verbatim`() {
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", fields()["msg[dev_id]"])
    }

    @Test
    fun `content is a json document with the text inside`() {
        val content = fields("你好").getValue("msg[content]")
        val parsed = Json.parseToJsonElement(content).jsonObject
        assertEquals("你好", parsed.getValue("content").jsonPrimitive.content)
    }

    /**
     * Building it with the serializer rather than concatenation is the point:
     * a quote, a brace or a newline in somebody's message must not produce
     * invalid JSON, because the server answers that with `请求错误`.
     */
    @Test
    fun `content survives characters that would break naive concatenation`() {
        val nasty = "他说\"你好\"\n然后 {笑了}"
        val content = fields(nasty).getValue("msg[content]")
        val parsed = Json.parseToJsonElement(content).jsonObject
        assertEquals(nasty, parsed.getValue("content").jsonPrimitive.content)
    }

    /**
     * Retrofit's `@FieldMap` takes `Map<String, String>`, so a non-string would
     * not even compile — but an *empty* value would be sent as an empty field,
     * and the docs are explicit that an empty `content` is answered with
     * `请求错误`. Every field must carry something.
     */
    @Test
    fun `no field is sent empty`() {
        fields().forEach { (key, value) ->
            assertTrue("blank key", key.isNotBlank())
            assertTrue("empty value for $key", value.isNotEmpty())
        }
    }
}
