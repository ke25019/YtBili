package com.ytstyle.bili.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The external-cache parser, pinned to the layout the official client actually
 * writes on Android 16:
 *
 * ```
 * download/114160683719836/c_28862581315/entry.json
 * download/114160683719836/c_28862581315/112/{video,audio}.m4s
 * ```
 *
 * The previous implementation assumed `bvid` was in `entry.json` and that the
 * quality directory could be ignored; the official file has neither `bvid` (it
 * has `avid`) nor a fixed name for the media directory (`112` here is the
 * quality id), which is why the feature found nothing on a real phone.
 */
class ExternalCacheParserTest {

    private fun tempRoot(): File =
        Files.createTempDirectory("ytbili-extcache").toFile().also { it.deleteOnExit() }

    /**
     * Writes one `entry.json` plus its two tracks.
     *
     * @param media name → byte count for the quality directories; the parser
     *   must keep the largest `video.m4s`.
     */
    private fun writeDownload(
        root: File,
        json: String,
        media: Map<String, Int> = mapOf("112" to 4096),
        audioBytes: Int = 512,
        entryParent: File = File(root, "114160683719836/c_28862581315"),
    ): File {
        entryParent.mkdirs()
        media.forEach { (quality, bytes) ->
            val dir = File(entryParent, quality)
            dir.mkdirs()
            File(dir, "video.m4s").writeBytes(ByteArray(bytes))
            File(dir, "audio.m4s").writeBytes(ByteArray(audioBytes))
        }
        val entry = File(entryParent, "entry.json")
        entry.writeText(json)
        return entry
    }

    private fun entryJson(
        title: String = "【4K Hi-Res】葬送的芙莉莲：ヨルシカ「晴る」动画MV【中日歌词 完整版】",
        part: String = "P1- 【4K Hi-Res】葬送的芙莉莲",
        avid: Long = 114160683719836L,
        cid: Long = 28862581315L,
        typeTag: String = "112",
        width: Int = 3840,
        height: Int = 2160,
        completed: Boolean = true,
    ): String = """
        {
          "media_type": 2,
          "has_dash_audio": true,
          "is_completed": $completed,
          "title": "$title",
          "type_tag": "$typeTag",
          "cover": "http://i0.hdslb.com/bfs/archive/cover.jpg",
          "prefered_video_quality": 112,
          "avid": $avid,
          "time_create_stamp": 1730000000000,
          "page_data": {
            "cid": $cid,
            "page": 1,
            "from": "vupload",
            "part": "$part",
            "width": $width,
            "height": $height
          }
        }
    """.trimIndent()

    @Test
    fun `parses the layout captured from the official client`() {
        val entry = writeDownload(tempRoot(), entryJson())
        val parsed = ExternalCacheParser.parse(entry)
        assertNotNull(parsed)
        val item = parsed!!

        assertEquals("external-114160683719836-28862581315", item.id)
        assertTrue(item.title.startsWith("【4K Hi-Res】"))
        assertEquals(28862581315L, item.cid)
        assertEquals("http://i0.hdslb.com/bfs/archive/cover.jpg", item.cover)
        assertEquals("1080P+", item.videoLabel)
        assertEquals(DownloadState.DONE, item.state)
        assertTrue(item.external)
        assertTrue("video path lost", item.videoPath.endsWith("112${File.separator}video.m4s"))
        assertTrue("audio path lost", item.audioPath.endsWith("112${File.separator}audio.m4s"))
        assertEquals(4096L, item.videoBytes)
        assertEquals(512L, item.audioBytes)
        assertEquals(1730000000000L, item.createdAt)
    }

    /**
     * `entry.json` carries `avid` only. Every other screen speaks `BV…`, so the
     * id has to be derived; these are the published reference pairs.
     */
    @Test
    fun `avid converts to the published bvid`() {
        assertEquals("BV17x411w7KC", ExternalCacheParser.bvidOf(170001))
        assertEquals("BV1xx411c7mD", ExternalCacheParser.bvidOf(2))
        assertEquals("", ExternalCacheParser.bvidOf(0))
        assertEquals("", ExternalCacheParser.bvidOf(-1))
        // And a parsed item carries it, not the raw number.
        val item = ExternalCacheParser.parse(writeDownload(tempRoot(), entryJson()))!!
        assertTrue(item.bvid, item.bvid.startsWith("BV1"))
    }

    @Test
    fun `the tagged quality directory wins when several exist`() {
        val entry = writeDownload(
            tempRoot(),
            entryJson(typeTag = "112"),
            media = mapOf("64" to 1000, "112" to 9000),
        )
        val item = ExternalCacheParser.parse(entry)!!
        assertEquals(9000L, item.videoBytes)
        assertTrue(item.videoPath.contains("112"))
    }

    /** No usable `type_tag`: the largest track is the best available guess. */
    @Test
    fun `without a known tag the largest track wins`() {
        val entry = writeDownload(
            tempRoot(),
            entryJson(typeTag = "999", width = 1920, height = 1080),
            media = mapOf("64" to 1000, "112" to 9000),
        )
        val item = ExternalCacheParser.parse(entry)!!
        assertEquals(9000L, item.videoBytes)
        assertEquals("1080P", item.videoLabel)
    }

    @Test
    fun `an unfinished download is not listed`() {
        val entry = writeDownload(tempRoot(), entryJson(completed = false))
        assertNull(ExternalCacheParser.parse(entry))
    }

    @Test
    fun `an entry without any media is not listed`() {
        val dir = File(tempRoot(), "114160683719836/c_28862581315").apply { mkdirs() }
        val entry = File(dir, "entry.json").apply { writeText(entryJson()) }
        assertNull(ExternalCacheParser.parse(entry))
    }

    /** Older single-P downloads keep `entry.json` one level up. */
    @Test
    fun `the flat single-P layout still parses`() {
        val root = tempRoot()
        val entry = writeDownload(
            root = root,
            json = entryJson(typeTag = "80"),
            media = mapOf("80" to 2048),
            entryParent = File(root, "114160683719836"),
        )
        val item = ExternalCacheParser.parse(entry)!!
        assertEquals(2048L, item.videoBytes)
        assertEquals("1080P", item.videoLabel)
    }

    @Test
    fun `a blank title falls back to the part name`() {
        val entry = writeDownload(tempRoot(), entryJson(title = "", part = "P1- 片段名"))
        assertEquals("P1- 片段名", ExternalCacheParser.parse(entry)!!.title)
    }

    /** Unknown or absent `type_tag`: use the pixel height, not a guessed id. */
    @Test
    fun `an unknown quality tag falls back to the resolution`() {
        val entry = writeDownload(tempRoot(), entryJson(typeTag = "999", width = 1920, height = 1080))
        assertEquals("1080P", ExternalCacheParser.parse(entry)!!.videoLabel)
    }

    /**
     * `is_completed` is sometimes absent (older clients). Missing must mean
     * "finished", otherwise every cache from those versions would disappear.
     */
    @Test
    fun `a missing is_completed flag is treated as finished`() {
        val json = entryJson().replace("\"is_completed\": true,", "")
        val item = ExternalCacheParser.parse(writeDownload(tempRoot(), json))!!
        assertEquals(4096L, item.videoBytes)
    }
}
