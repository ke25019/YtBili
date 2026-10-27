package com.ytstyle.bili.data.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Tests for the hand-written protobuf danmaku reader.
 *
 * The payloads here are real protobuf wire bytes assembled by the helpers at
 * the bottom of this file, so these assertions exercise the actual varint and
 * length-delimited decoding rather than a mock.
 *
 * Schema being encoded (`DmSegMobileReply`):
 * ```
 * message DanmakuElem {
 *   int64  id       = 1;
 *   int32  progress = 2;   // ms
 *   int32  mode     = 3;
 *   int32  fontsize = 4;
 *   uint32 color    = 5;
 *   string content  = 6;
 * }
 * ```
 */
class DanmakuParserTest {

    @Test
    fun `parses a single element with all fields`() {
        val payload = reply(
            elem(id = 111L, progress = 1_500L, mode = 1, fontSize = 25, color = 0xFFFFFF, content = "hello"),
        )

        val items = DanmakuParser.parse(payload)

        assertEquals(1, items.size)
        with(items.first()) {
            assertEquals(1_500L, timeMs)
            assertEquals(1, mode)
            assertEquals(25, fontSize)
            assertEquals(0xFFFFFF, color)
            assertEquals("hello", text)
        }
    }

    @Test
    fun `parses multiple elements in order`() {
        val payload = reply(
            elem(progress = 100L, content = "first"),
            elem(progress = 200L, content = "second"),
            elem(progress = 300L, content = "third"),
        )

        val items = DanmakuParser.parse(payload)

        assertEquals(listOf("first", "second", "third"), items.map { it.text })
        assertEquals(listOf(100L, 200L, 300L), items.map { it.timeMs })
    }

    /**
     * Timestamps exceed one varint byte almost immediately (128 ms), and exceed
     * two bytes past ~16 s. A reader that mishandles continuation bits passes
     * the small cases and corrupts every real video.
     */
    @Test
    fun `handles multi-byte varints`() {
        val payload = reply(
            elem(progress = 127L, content = "one-byte-max"),
            elem(progress = 128L, content = "two-byte-min"),
            elem(progress = 16_383L, content = "two-byte-max"),
            elem(progress = 16_384L, content = "three-byte-min"),
            elem(progress = 3_600_000L, content = "one-hour"),
        )

        val items = DanmakuParser.parse(payload)

        assertEquals(
            listOf(127L, 128L, 16_383L, 16_384L, 3_600_000L),
            items.map { it.timeMs },
        )
    }

    /** Colors are 24-bit RGB values, which always need multiple varint bytes. */
    @Test
    fun `preserves rgb colour values`() {
        val payload = reply(
            elem(progress = 1L, color = 0xFF0000, content = "red"),
            elem(progress = 2L, color = 0x00FF00, content = "green"),
            elem(progress = 3L, color = 0x0000FF, content = "blue"),
            elem(progress = 4L, color = 0x000000, content = "black"),
        )

        val items = DanmakuParser.parse(payload)

        assertEquals(
            listOf(0xFF0000, 0x00FF00, 0x0000FF, 0x000000),
            items.map { it.color },
        )
    }

    /** Modes 4 and 5 are the fixed top/bottom comments; they must survive. */
    @Test
    fun `preserves placement modes`() {
        val payload = reply(
            elem(progress = 1L, mode = 1, content = "scroll"),
            elem(progress = 2L, mode = 4, content = "bottom"),
            elem(progress = 3L, mode = 5, content = "top"),
        )

        assertEquals(listOf(1, 4, 5), DanmakuParser.parse(payload).map { it.mode })
    }

    /** Bilibili's three font sizes are 18 / 25 / 36. */
    @Test
    fun `preserves font sizes`() {
        val payload = reply(
            elem(progress = 1L, fontSize = 18, content = "small"),
            elem(progress = 2L, fontSize = 25, content = "medium"),
            elem(progress = 3L, fontSize = 36, content = "large"),
        )

        assertEquals(listOf(18, 25, 36), DanmakuParser.parse(payload).map { it.fontSize })
    }

    /** Non-ASCII comments are the common case on Bilibili and are UTF-8 encoded. */
    @Test
    fun `decodes utf8 content`() {
        val payload = reply(
            elem(progress = 1L, content = "前方高能"),
            elem(progress = 2L, content = "🎉🎊"),
        )

        val items = DanmakuParser.parse(payload)

        assertEquals("前方高能", items[0].text)
        assertEquals("🎉🎊", items[1].text)
    }

    /** An empty body must yield no comments rather than throwing. */
    @Test
    fun `empty payload returns empty list`() {
        assertTrue(DanmakuParser.parse(ByteArray(0)).isEmpty())
    }

    /** Comments with blank text are dropped, since they cannot be rendered. */
    @Test
    fun `skips elements with blank content`() {
        val payload = reply(
            elem(progress = 1L, content = ""),
            elem(progress = 2L, content = "kept"),
        )

        val items = DanmakuParser.parse(payload)

        assertEquals(1, items.size)
        assertEquals("kept", items.first().text)
    }

    /**
     * The real payload carries fields this reader does not model (id, weight,
     * pool, ...). They must be skipped without desynchronising the stream.
     */
    @Test
    fun `skips unknown varint and length-delimited fields`() {
        val payload = reply(
            elem(progress = 1L, content = "before"),
            elemWithExtraFields(progress = 2L, content = "with-extras"),
            elem(progress = 3L, content = "after"),
        )

        val items = DanmakuParser.parse(payload)

        assertEquals(listOf("before", "with-extras", "after"), items.map { it.text })
        assertEquals(2L, items[1].timeMs)
    }

    /** Unknown 32-bit fixed fields (wire type 5) must also be skipped. */
    @Test
    fun `skips fixed32 fields`() {
        // Field 30 is not modelled; wire type 5 is a 4-byte fixed32.
        val extra = tag(30, 5) + byteArrayOf(1, 2, 3, 4)
        val body = varintField(2, 42L) + extra + stringField(7, "fixed32-skipped")
        val payload = bytesField(1, body)

        val items = DanmakuParser.parse(payload)

        assertEquals(1, items.size)
        assertEquals("fixed32-skipped", items.first().text)
        assertEquals(42L, items.first().timeMs)
    }

    /**
     * A truncated final element should not throw; the reader returns what it
     * could decode. Real segments are fetched over the network and can be cut
     * short.
     */
    @Test
    fun `truncated payload does not throw`() {
        val full = reply(elem(progress = 1L, content = "complete"))
        val truncated = full.copyOf(full.size - 3)

        // Must not throw; contents are unspecified but decoding is bounded.
        DanmakuParser.parse(truncated)
    }

    /** Defaults apply when optional numeric fields are absent. */
    @Test
    fun `applies defaults for absent fields`() {
        val body = stringField(7, "only-content")
        val items = DanmakuParser.parse(bytesField(1, body))

        assertEquals(1, items.size)
        with(items.first()) {
            assertEquals(0L, timeMs)
            assertEquals(1, mode)
            assertEquals(25, fontSize)
            assertEquals(0xFFFFFF, color)
        }
    }

    // ---- against a real captured segment ----

    private fun realSegment(): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/danmaku_seg.bin")) {
            "missing fixture: danmaku_seg.bin"
        }.use { it.readBytes() }

    /**
     * Decodes an actual `seg.so` payload from Bilibili.
     *
     * This is the regression test for the field-mapping bug: the reader used to
     * take field 6 (`midHash`) as the comment, which rendered strings like
     * `b9269537` on screen instead of the comments themselves.
     */
    @Test
    fun `parses a real segment into readable comments`() {
        val items = DanmakuParser.parse(realSegment())

        assertTrue("expected a substantial batch, got ${items.size}", items.size > 100)

        // Real danmaku are overwhelmingly Chinese; the buggy mapping produced
        // pure ASCII hex, so any Chinese at all proves the right field is read.
        val withChinese = items.count { item -> item.text.any { it in '\u4e00'..'\u9fff' } }
        assertTrue(
            "only $withChinese/${items.size} comments contained Chinese - field 6 (midHash) is probably being read again",
            withChinese > items.size / 2,
        )

        // Hash-like comments must be a rarity, not the norm. Reading field 6
        // (midHash) makes essentially *every* comment a 7-8 character hex
        // string, so a small fraction is the signature of correct decoding.
        // A few viewers really do comment "100000", so zero is the wrong bar.
        val hexLike = items.filter { it.text.matches(Regex("[0-9a-f]{5,12}")) }
        assertTrue(
            "found ${hexLike.size}/${items.size} hash-like comments; field 6 (midHash) is probably being read again",
            hexLike.size < items.size / 20,
        )
    }

    /** The real segment must yield sane timing and colour values. */
    @Test
    fun `real segment has usable timing and colour`() {
        val items = DanmakuParser.parse(realSegment())

        // Progress is milliseconds from video start and must be ordered-ish.
        assertTrue("no timestamps decoded", items.any { it.timeMs > 0 })
        assertTrue("timestamps look wrong", items.all { it.timeMs in 0..(6 * 60 * 60 * 1000L) })

        // Colours come back as 24-bit RGB; white (0xFFFFFF) dominates.
        assertTrue("no white comments", items.any { it.color == 0xFFFFFF })
        assertTrue("colours outside RGB range", items.all { it.color in 0..0xFFFFFF })

        // Modes are 1 (scroll), 4 (bottom), 5 (top).
        assertTrue("unexpected modes: ${items.map { it.mode }.toSet()}", items.all { it.mode in 1..5 })
    }

    /** A larger realistic batch keeps ordering and count intact. */
    @Test
    fun `parses a realistic batch`() {
        val payload = reply(
            *Array(50) { i ->
                elem(progress = i * 250L, mode = if (i % 10 == 0) 4 else 1, content = "弹幕$i")
            },
        )

        val items = DanmakuParser.parse(payload)

        assertEquals(50, items.size)
        assertEquals("弹幕0", items.first().text)
        assertEquals("弹幕49", items.last().text)
        // First and last timestamps: 0 ms and 49 * 250 ms.
        assertEquals(0L, items.first().timeMs)
        assertEquals(12_250L, items.last().timeMs)
    }
}

// ---------------------------------------------------------------------------
// Minimal protobuf wire-format writers, used to build the fixtures above.
// ---------------------------------------------------------------------------

private fun varint(value: Long): ByteArray {
    val out = ByteArrayOutputStream()
    var v = value
    while (true) {
        val b = (v and 0x7F).toInt()
        v = v ushr 7
        if (v == 0L) {
            out.write(b)
            return out.toByteArray()
        }
        out.write(b or 0x80)
    }
}

private fun tag(field: Int, wireType: Int): ByteArray = varint(((field shl 3) or wireType).toLong())

private fun varintField(field: Int, value: Long): ByteArray =
    tag(field, 0) + varint(value)

private fun bytesField(field: Int, payload: ByteArray): ByteArray =
    tag(field, 2) + varint(payload.size.toLong()) + payload

private fun stringField(field: Int, value: String): ByteArray =
    bytesField(field, value.toByteArray(Charsets.UTF_8))

/**
 * Encodes one `DanmakuElem`.
 *
 * `midHash` is written to field 6 and the text to field 7, matching the real
 * wire format; writing the text to field 6 is exactly the bug these fixtures
 * exist to catch.
 */
private fun elem(
    id: Long = 0L,
    progress: Long,
    mode: Int = 1,
    fontSize: Int = 25,
    color: Int = 0xFFFFFF,
    content: String,
    midHash: String = "deadbeef",
): ByteArray {
    val out = ByteArrayOutputStream()
    if (id != 0L) out.write(varintField(1, id))
    out.write(varintField(2, progress))
    out.write(varintField(3, mode.toLong()))
    out.write(varintField(4, fontSize.toLong()))
    out.write(varintField(5, color.toLong() and 0xFFFFFFFFL))
    out.write(stringField(6, midHash))   // midHash, NOT the comment
    out.write(stringField(7, content))   // content
    return out.toByteArray()
}

/** Adds fields the reader does not model, to exercise skipping. */
private fun elemWithExtraFields(progress: Long, content: String): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(varintField(1, 999_999L))          // id
    out.write(varintField(2, progress))
    out.write(varintField(3, 1))                 // mode
    out.write(varintField(4, 25))                // fontsize
    out.write(varintField(5, 0xFFFFFF))          // color
    out.write(stringField(6, "cafebabe"))        // midHash
    out.write(stringField(7, content))           // content
    out.write(stringField(8, "extra-string"))    // ctime-ish, unknown
    out.write(varintField(9, 5L))                // weight, unknown
    return out.toByteArray()
}

/** Wraps elements in `DmSegMobileReply.elems` (field 1, length-delimited). */
private fun reply(vararg elems: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    for (e in elems) out.write(bytesField(1, e))
    return out.toByteArray()
}
