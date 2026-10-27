package com.ytstyle.bili.data.danmaku

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * A single danmaku (bullet comment) with its on-screen timing.
 *
 * @param timeMs when it appears, in milliseconds from video start
 * @param mode   1/2/3 = scrolling right-to-left, 4 = bottom, 5 = top
 * @param fontSize 18 = small, 25 = medium, 36 = large
 * @param color  RGB int, e.g. 0xFFFFFF for white
 */
data class DanmakuItem(
    val timeMs: Long,
    val mode: Int,
    val fontSize: Int,
    val color: Int,
    val text: String,
)

/**
 * Parses Bilibili's `seg.so` danmaku payload.
 *
 * The endpoint returns Protobuf despite the `.so` extension, but every field we
 * need is a varint or a length-delimited string, so a focused wire-format
 * reader avoids pulling in a protobuf runtime and a generated schema for one
 * message type.
 *
 * Relevant schema (dm_seg.proto `DmSegMobileReply`):
 * ```
 * message DanmakuElem {
 *   int64  id       = 1;
 *   int32  progress = 2;  // ms
 *   int32  mode     = 3;
 *   int32  fontsize = 4;
 *   uint32 color    = 5;
 *   string midHash  = 6;  // sender hash, NOT the comment text
 *   string content  = 7;  // <- the comment
 * }
 * ```
 *
 * Field 6 is `midHash` and field 7 is `content`. Reading 6 as the text is an
 * easy mistake that renders hashes such as `b9269537` where comments belong;
 * verified against a live segment, where field 6 was uniformly 7-8 ASCII hex
 * characters and field 7 held the UTF-8 comment.
 */
object DanmakuParser {

    /** `DanmakuElem.content`. Field 6 is `midHash`, not the text. */
    private const val CONTENT_FIELD = 7

    fun parse(stream: InputStream): List<DanmakuItem> {
        val bytes = stream.use { it.readBytes() }
        return parse(bytes)
    }

    fun parse(bytes: ByteArray): List<DanmakuItem> {
        val items = ArrayList<DanmakuItem>()
        var offset = 0
        while (offset < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, offset)
            if (afterTag < 0) break
            val field = (tag shr 3).toInt()
            val wireType = (tag and 0x7).toInt()

            when {
                // Field 1 of DmSegMobileReply is `repeated DanmakuElem elems`.
                field == 1 && wireType == 2 -> {
                    val (len, afterLen) = readVarint(bytes, afterTag)
                    val end = afterLen + len.toInt()
                    if (end > bytes.size) break
                    parseElem(bytes, afterLen, end)?.let(items::add)
                    offset = end
                }
                else -> {
                    val next = skipField(bytes, afterTag, wireType) ?: break
                    offset = next
                }
            }
        }
        return items
    }

    /** Reads one DanmakuElem from [start, end). */
    private fun parseElem(bytes: ByteArray, start: Int, end: Int): DanmakuItem? {
        var offset = start
        var timeMs = 0L
        var mode = 1
        var fontSize = 25
        var color = 0xFFFFFF
        var content = ""

        while (offset < end) {
            val (tag, afterTag) = readVarint(bytes, offset)
            if (afterTag < 0) break
            val field = (tag shr 3).toInt()
            val wireType = (tag and 0x7).toInt()

            when {
                wireType == 0 -> {
                    val (value, next) = readVarint(bytes, afterTag)
                    if (next < 0) return null
                    when (field) {
                        2 -> timeMs = value
                        3 -> mode = value.toInt()
                        4 -> fontSize = value.toInt()
                        5 -> color = value.toInt()
                    }
                    offset = next
                }
                wireType == 2 -> {
                    val (len, afterLen) = readVarint(bytes, afterTag)
                    val fieldEnd = afterLen + len.toInt()
                    if (fieldEnd > end) return null
                    // Field 7 is `content`; field 6 is `midHash` and must not
                    // be mistaken for the text.
                    if (field == CONTENT_FIELD) {
                        content = String(bytes, afterLen, len.toInt(), Charsets.UTF_8)
                    }
                    offset = fieldEnd
                }
                else -> offset = skipField(bytes, afterTag, wireType) ?: return null
            }
        }

        if (content.isBlank()) return null
        return DanmakuItem(
            timeMs = timeMs,
            mode = mode,
            fontSize = fontSize,
            color = color,
            text = content,
        )
    }

    /** Reads a base-128 varint; returns value and the offset just past it. */
    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var offset = start
        while (offset < bytes.size) {
            val b = bytes[offset].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            offset++
            if (b and 0x80 == 0) return result to offset
            shift += 7
            if (shift > 63) return 0L to -1
        }
        return 0L to -1
    }

    /** Advances past a field we do not care about. */
    private fun skipField(bytes: ByteArray, offset: Int, wireType: Int): Int? = when (wireType) {
        0 -> readVarint(bytes, offset).let { if (it.second < 0) null else it.second }
        1 -> (offset + 8).takeIf { it <= bytes.size }
        2 -> {
            val (len, afterLen) = readVarint(bytes, offset)
            if (afterLen < 0) null else (afterLen + len.toInt()).takeIf { it <= bytes.size }
        }
        5 -> (offset + 4).takeIf { it <= bytes.size }
        else -> null
    }
}

/** Small helper so callers can concat segments without extra allocations. */
internal fun InputStream.readBytes(): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    while (true) {
        val read = read(chunk)
        if (read <= 0) break
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}
