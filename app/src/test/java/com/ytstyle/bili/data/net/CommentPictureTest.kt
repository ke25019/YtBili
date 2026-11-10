package com.ytstyle.bili.data.net

import com.ytstyle.bili.ui.components.toThumbUrl
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comment images, pinned to a **real** response.
 *
 * `pictures` was not guessable: `docs/comment/list.md` documents no picture
 * field, and the existing `replies.json` fixture has none either, because
 * Bilibili omits the key entirely on comments without an image. The shape came
 * from signing `x/v2/reply/wbi/main` and dumping every key inside `content`
 * across several hundred comments (`diag_comment_pic_field2.py`), and
 * `reply_picture.json` is one of those comments saved verbatim
 * (`diag_capture_reply_picture.py`).
 */
class CommentPictureTest {

    /** The exact configuration used at runtime, not a test-local copy. */
    private val json: Json = NetworkModule.provideJson()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing fixture: $name"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun capturedReply(): Reply = json.decodeFromString(fixture("reply_picture.json"))

    @Test
    fun `a real reply with an image parses`() {
        val reply = capturedReply()
        val content = reply.content
        assertTrue("fixture lost its content", content != null)
        assertEquals(1, content!!.pictures.size)
        assertTrue("message should survive", content.message.isNotEmpty())
    }

    @Test
    fun `the picture carries the url and its pixel size`() {
        val picture = capturedReply().content!!.pictures.first()
        assertTrue("blank img_src", picture.imgSrc.isNotEmpty())
        assertEquals(1452, picture.imgWidth)
        assertEquals(1083, picture.imgHeight)
    }

    /**
     * The URL arrives over plain `http://`, like every other Bilibili image, so
     * the upgrade has to happen before Coil sees it — the app forbids cleartext.
     */
    @Test
    fun `the picture url is normalised, not used raw`() {
        val picture = capturedReply().content!!.pictures.first()
        assertTrue("fixture no longer exercises the http case", picture.imgSrc.startsWith("http://"))
        val usable = picture.imgSrc.toThumbUrl()
        assertTrue(usable.startsWith("https://"))
        assertFalse(usable.contains("http://"))
    }

    @Test
    fun `aspect ratio follows the reported size and stays sane`() {
        val picture = capturedReply().content!!.pictures.first()
        // 1452 x 1083 is ~1.34, inside the clamp.
        assertTrue(picture.aspectRatio in 1.3f..1.4f)
    }

    /** A missing or nonsensical size must not produce a wild box. */
    @Test
    fun `aspect ratio falls back when dimensions are absent`() {
        assertEquals(1f, ReplyPicture().aspectRatio, 0.001f)
        assertEquals(1f, ReplyPicture(imgWidth = 0, imgHeight = 900).aspectRatio, 0.001f)
        // Extremes are clamped rather than honoured.
        assertEquals(2.5f, ReplyPicture(imgWidth = 4000, imgHeight = 1).aspectRatio, 0.001f)
        assertEquals(0.4f, ReplyPicture(imgWidth = 1, imgHeight = 4000).aspectRatio, 0.001f)
    }

    /**
     * The reason the first probe found nothing: comments without images do not
     * carry an empty `pictures` array, they carry no `pictures` key at all. If
     * this ever regressed to a required field, every ordinary comment would fail
     * to parse.
     */
    @Test
    fun `comments without images simply have none`() {
        val response = json.decodeFromString<BiliResponse<ReplyListData>>(fixture("replies.json"))
        val replies = response.data!!.replies
        assertTrue("fixture has no replies", replies.isNotEmpty())
        val withPictures = replies.count { it.content?.pictures.orEmpty().isNotEmpty() }
        assertEquals(0, withPictures)
        // And the key really is absent from the raw bytes, not merely null.
        assertFalse(fixture("replies.json").contains("\"pictures\""))
    }
}
