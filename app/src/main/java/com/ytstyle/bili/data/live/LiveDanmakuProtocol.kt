package com.ytstyle.bili.data.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * The live message-queue protocol, as a set of pure functions.
 *
 * Kept free of Android and network types on purpose: the framing, the
 * decompression and the JSON shape are exactly the parts that break when the
 * server changes something, and this way they are covered by unit tests that
 * need neither a device nor a live room.
 *
 * Wire format (all integers big-endian):
 * ```
 * 0  uint32  total length (header + body)
 * 4  uint16  header length (16)
 * 6  uint16  protocol version: 0 raw, 1 raw (auth/heartbeat), 2 zlib, 3 brotli
 * 8  uint32  operation
 * 12 uint32  sequence
 * 16 ...     body
 * ```
 *
 * **Brotli is never requested.** The auth packet asks for `protover = 2`, which
 * makes the server compress with zlib and lets `java.util.zip.Inflater` do the
 * work; brotli would mean shipping another native dependency for one feature.
 */
object LiveDanmakuProtocol {

    const val HEADER_SIZE = 16

    /** The protocol version this client asks for in its auth packet. */
    const val REQUESTED_PROTOCOL = 2

    private const val PROTOCOL_ZLIB = 2
    private const val MAX_NESTING = 3

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- framing ----

    /**
     * The auth packet's body.
     *
     * `protover` here is *not* the header's protocol version: it asks the server
     * which compression to use from then on. `uid = 0` is a guest — allowed, but
     * Bilibili then masks other users' names and mids.
     */
    fun authBody(
        roomId: Long,
        uid: Long,
        token: String,
        buvid: String = "",
    ): ByteArray = buildString {
        append("{\"uid\":").append(uid)
        append(",\"roomid\":").append(roomId)
        append(",\"protover\":").append(REQUESTED_PROTOCOL)
        append(",\"platform\":\"web\"")
        append(",\"type\":2")
        if (token.isNotEmpty()) append(",\"key\":\"").append(token).append('"')
        if (buvid.isNotEmpty()) append(",\"buvid\":\"").append(buvid).append('"')
        append('}')
    }.toByteArray(Charsets.UTF_8)

    /** Wraps [body] in a header. */
    fun encode(
        operation: Int,
        body: ByteArray = ByteArray(0),
        protocolVersion: Int = 1,
        sequence: Int = 1,
    ): ByteArray = ByteBuffer.allocate(HEADER_SIZE + body.size)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(HEADER_SIZE + body.size)
        .putShort(HEADER_SIZE.toShort())
        .putShort(protocolVersion.toShort())
        .putInt(operation)
        .putInt(sequence)
        .put(body)
        .array()

    /**
     * Splits a received frame into the packets it holds.
     *
     * A decompressed body is itself a sequence of complete packets, each with
     * its own header — that is what [inflate] plus a second [decode] handles in
     * [messages]. Truncated or nonsensical lengths stop the walk rather than
     * throwing: a partial read must not kill a live connection.
     */
    fun decode(payload: ByteArray): List<LiveFrame> {
        val frames = ArrayList<LiveFrame>()
        var offset = 0
        while (offset + HEADER_SIZE <= payload.size) {
            val buffer = ByteBuffer.wrap(payload, offset, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            val total = buffer.int
            val headerSize = buffer.short.toInt()
            val protocolVersion = buffer.short.toInt()
            val operation = buffer.int
            buffer.int // sequence: not used
            if (total < headerSize || headerSize < HEADER_SIZE || offset + total > payload.size) break
            val bodyStart = offset + headerSize
            val body = payload.copyOfRange(bodyStart, offset + total)
            frames.add(LiveFrame(operation, protocolVersion, body))
            offset += total
        }
        return frames
    }

    /** zlib (`Inflater`) decompression of a protocol-2 body. */
    fun inflate(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4)
            val buffer = ByteArray(8 * 1024)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0) {
                    // Needs more input (truncated) or a dictionary we do not have.
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buffer, 0, read)
                }
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    // ---- messages ----

    /** One DANMU_MSG / Super Chat / gift, flattened for the overlay. */
    data class LiveChatMessage(
        val uid: Long = 0,
        val userName: String = "",
        val face: String = "",
        val text: String = "",
        /** 0xRRGGBB; white when the payload did not say. */
        val colorRgb: Int = 0xFFFFFF,
        val timeSeconds: Long = 0,
        val kind: LiveChatKind = LiveChatKind.DANMAKU,
        val price: Int = 0,
    )

    enum class LiveChatKind { DANMAKU, SUPER_CHAT, GIFT }

    /**
     * Every chat message in [payload], in arrival order.
     *
     * Never throws: an unreadable frame contributes nothing.
     */
    fun messages(payload: ByteArray, depth: Int = 0): List<LiveChatMessage> {
        if (depth > MAX_NESTING) return emptyList()
        val out = ArrayList<LiveChatMessage>()
        for (frame in decode(payload)) {
            if (frame.operation != LiveOp.MESSAGE) continue
            if (frame.protocolVersion == PROTOCOL_ZLIB) {
                // The inflated body is itself a sequence of complete packets,
                // each with its own header — not a chat payload.
                out.addAll(messages(inflate(frame.body), depth + 1))
            } else {
                // Versions 0 and 1 are plain JSON text. A version-3 body would
                // be brotli, which this client never asks for, so it is simply
                // not parseable here and contributes nothing.
                parseChat(String(frame.body, Charsets.UTF_8))?.let(out::add)
            }
        }
        return out
    }

    /** Popularity (人气值) from a heartbeat reply, or null for any other frame. */
    fun popularity(frame: LiveFrame): Int? {
        if (frame.operation != LiveOp.HEARTBEAT_REPLY) return null
        if (frame.body.size < 4) return null
        return ByteBuffer.wrap(frame.body, 0, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    // ---- JSON shapes ----

    private fun parseChat(text: String): LiveChatMessage? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return null
        val cmd = root.str("cmd")
        return when {
            cmd.startsWith("DANMU_MSG") -> danmaku(root)
            cmd == "SUPER_CHAT_MESSAGE" -> superChat(root)
            cmd == "SEND_GIFT" -> gift(root)
            else -> null
        }
    }

    /**
     * `DANMU_MSG.info` is a positional array, and the useful per-user fields sit
     * at `info[0][15].user` in current payloads while older ones only carry
     * `info[2]`. Both are read, in that order.
     */
    private fun danmaku(root: JsonObject): LiveChatMessage? {
        val info = root.arr("info") ?: return null
        val text = info.stringAt(1)
        val meta = info.arrayAt(0)
        val extra = meta?.objectAt(15)
        val user = extra?.obj("user")

        val legacy = info.arrayAt(2)
        val uid = user?.long("uid") ?: legacy?.longAt(0) ?: 0L
        // `str` already takes a nullable receiver, so the chain is written with
        // plain calls; the final `orEmpty()` keeps the result a String rather
        // than a String?.
        val name = user.str("uname")
            .ifBlank { user?.obj("base").str("name") }
            .ifBlank { legacy?.stringAt(1).orEmpty() }
        val face = user.str("face").ifBlank { user?.obj("base").str("face") }

        // info[0][3] is the colour as a decimal RGB integer; info[0][4] is the
        // send time in milliseconds, and info[9].ts the same moment in seconds.
        val color = meta?.intAt(3)?.takeIf { it in 0..0xFFFFFF } ?: 0xFFFFFF
        val millis = meta?.longAt(4) ?: 0L
        val seconds = (info.objectAt(9)?.long("ts") ?: 0L).takeIf { it > 0 }
            ?: (millis / 1000).takeIf { millis > 0 }
            ?: 0L

        if (text.isEmpty() && name.isEmpty()) return null
        return LiveChatMessage(
            uid = uid,
            userName = name,
            face = face,
            text = text,
            colorRgb = color,
            timeSeconds = seconds,
            kind = LiveChatKind.DANMAKU,
        )
    }

    private fun superChat(root: JsonObject): LiveChatMessage? {
        val data = root.obj("data") ?: return null
        val user = data.obj("user_info")
        return LiveChatMessage(
            uid = data.long("uid"),
            userName = user?.str("uname").orEmpty(),
            face = user?.str("face").orEmpty(),
            text = data.str("message"),
            colorRgb = 0xFFD54F,
            timeSeconds = data.long("ts").takeIf { it > 0 } ?: data.long("start_time"),
            kind = LiveChatKind.SUPER_CHAT,
            price = data.long("price").toInt(),
        )
    }

    private fun gift(root: JsonObject): LiveChatMessage? {
        val data = root.obj("data") ?: return null
        val name = data.str("uname")
        val giftName = data.str("giftName")
        val count = data.long("num")
        if (name.isEmpty() || giftName.isEmpty()) return null
        return LiveChatMessage(
            uid = data.long("uid"),
            userName = name,
            face = data.str("face"),
            text = if (count > 1) "投喂 $giftName ×$count" else "投喂 $giftName",
            colorRgb = 0xFF8A80,
            timeSeconds = data.long("timestamp"),
            kind = LiveChatKind.GIFT,
        )
    }

    // ---- tolerant accessors ----

    private fun JsonObject?.str(key: String): String {
        val primitive = this?.get(key) as? JsonPrimitive ?: return ""
        return primitive.contentOrNull?.trim().orEmpty()
    }

    private fun JsonObject?.long(key: String): Long {
        val primitive = this?.get(key) as? JsonPrimitive ?: return 0L
        primitive.longOrNull?.let { return it }
        return primitive.contentOrNull?.trim()?.toDoubleOrNull()?.toLong() ?: 0L
    }

    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

    private fun JsonObject?.arr(key: String): JsonArray? = this?.get(key) as? JsonArray

    private fun JsonArray?.elementAt(index: Int): JsonElement? =
        if (this != null && index in 0 until size) this[index] else null

    private fun JsonArray?.stringAt(index: Int): String =
        (elementAt(index) as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonArray?.longAt(index: Int): Long? =
        (elementAt(index) as? JsonPrimitive)?.longOrNull

    private fun JsonArray?.intAt(index: Int): Int? =
        (elementAt(index) as? JsonPrimitive)?.intOrNull

    private fun JsonArray?.objectAt(index: Int): JsonObject? = elementAt(index) as? JsonObject

    private fun JsonArray?.arrayAt(index: Int): JsonArray? = elementAt(index) as? JsonArray
}

/** Operation codes of the protocol. */
object LiveOp {
    const val HEARTBEAT = 2
    const val HEARTBEAT_REPLY = 3
    const val MESSAGE = 5
    const val AUTH = 7
    const val AUTH_REPLY = 8
}

/** One decoded packet: its operation, the version it claims, and its body. */
data class LiveFrame(
    val operation: Int,
    val protocolVersion: Int,
    val body: ByteArray,
) {
    // ByteArray in a data class: equals/hashCode are identity by default, which
    // is fine here (frames are never compared) but would be a trap if they were.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
