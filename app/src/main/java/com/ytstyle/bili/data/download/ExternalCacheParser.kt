package com.ytstyle.bili.data.download

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Parses the metadata half of one `entry.json` from the official client's cache.
 *
 * Kept separate from [parse] because the Shizuku reader has the JSON as a
 * string (read by a shell process) and locates the media through Binder instead
 * of `File`; both paths need the same title / BV id / quality label.
 */
internal data class ExternalEntryMeta(
    val id: String,
    val title: String,
    val cover: String,
    val bvid: String,
    val cid: Long,
    val videoLabel: String,
    val createdAt: Long,
    /** Quality directory name (`112`, `80`, …) used to find the right track. */
    val typeTag: String,
)

/**
 * Parses one `entry.json` from the official client's offline cache.
 *
 * ## What the directory actually looks like
 *
 * A captured example from the official client (8.x, Android 16), one P per
 * `c_<cid>` directory:
 *
 * ```
 * Android/data/tv.danmaku.bili/download/114160683719836/
 *   c_28862581315/
 *     entry.json
 *     danmaku.xml
 *     112/              <- quality id, 112 = 1080P+
 *       index.json
 *       video.m4s
 *       audio.m4s
 * ```
 *
 * The video and audio tracks are plain (unencrypted) `m4s` files. Single-P
 * downloads from older versions keep `entry.json` one level up, directly under
 * `download/<avid>/`, so the media is located by searching below the entry file
 * rather than by assuming a fixed nesting.
 *
 * `entry.json` names the video by **`avid`**, not `bvid`; [bvidOf] converts it,
 * because everything else in this app addresses videos as `BV…`. When several
 * quality directories exist for the same episode, the largest `video.m4s` wins:
 * that is the resolution the user picked for offline viewing.
 */
internal object ExternalCacheParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Depth below `entry.json` that still belongs to this download. */
    private const val MAX_MEDIA_DEPTH = 3

    /**
     * Parses [entryFile], or returns null when this is not a finished download
     * we can play (no media, zero-byte track, or `is_completed == false`).
     */
    fun parse(entryFile: File): DownloadItem? =
        runCatching { parseEntry(entryFile) }.getOrNull()

    private fun parseEntry(entryFile: File): DownloadItem? {
        val dir = entryFile.parentFile ?: return null
        val meta = parseMeta(entryFile.readText(), dir.absolutePath, dir.lastModified())
            ?: return null
        val (video, audio) = findMedia(dir, meta.typeTag) ?: return null
        return meta.toItem(
            videoPath = video.absolutePath,
            audioPath = audio?.absolutePath.orEmpty(),
            videoBytes = video.length(),
            audioBytes = audio?.length() ?: 0L,
            shell = false,
        )
    }

    /**
     * Reads everything except the media paths.
     *
     * Returns null for a half-finished download (`is_completed == false`) or a
     * document that is not JSON — both are normal states while the official
     * client is writing.
     */
    fun parseMeta(
        text: String,
        dirPath: String,
        fallbackModified: Long = 0L,
    ): ExternalEntryMeta? = runCatching {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return null

        // A half-finished download has metadata but no usable track yet.
        if (root.boolean("is_completed") == false) return null

        val pageData = root["page_data"] as? JsonObject
        val episode = root["ep"] as? JsonObject
        val avid = root.number("avid") ?: root.number("aid") ?: episode?.number("avid") ?: 0L
        val cid = pageData?.number("cid") ?: root.number("cid") ?: 0L
        val page = pageData?.number("page") ?: 0L
        val part = pageData?.text("part").orEmpty()

        val title = root.text("title")
            .ifBlank { episode?.text("title").orEmpty() }
            .ifBlank { part }
            .ifBlank { if (avid > 0) "av$avid" else "未命名缓存" }

        val quality = qualityLabel(
            root.text("type_tag"),
            pageData?.number("width") ?: 0L,
            pageData?.number("height") ?: 0L,
        )
        val pageLabel = if (page > 1) "P$page" else ""

        ExternalEntryMeta(
            id = itemId(avid, cid, dirPath),
            title = title,
            cover = root.text("cover").ifBlank { episode?.text("cover").orEmpty() },
            bvid = root.text("bvid").ifBlank { bvidOf(avid) },
            cid = cid,
            videoLabel = listOf(quality, pageLabel).filter { it.isNotEmpty() }.joinToString(" · "),
            createdAt = createdAt(root, fallbackModified),
            typeTag = root.text("type_tag"),
        )
    }.getOrNull()

    private fun ExternalEntryMeta.toItem(
        videoPath: String,
        audioPath: String,
        videoBytes: Long,
        audioBytes: Long,
        shell: Boolean,
    ): DownloadItem = DownloadItem(
        id = id,
        title = title,
        cover = cover,
        bvid = bvid,
        cid = cid,
        videoLabel = videoLabel,
        videoPath = videoPath,
        audioPath = audioPath,
        videoBytes = videoBytes,
        audioBytes = audioBytes,
        state = DownloadState.DONE,
        createdAt = createdAt,
        external = true,
        externalShell = shell,
    )

    /**
     * Builds an item whose media paths were resolved through the Shizuku shell
     * (no `File` access is possible on those paths).
     */
    fun shellItem(
        meta: ExternalEntryMeta,
        videoPath: String,
        videoBytes: Long,
        audioPath: String,
        audioBytes: Long,
    ): DownloadItem = meta.toItem(
        videoPath = videoPath,
        audioPath = audioPath,
        videoBytes = videoBytes,
        audioBytes = audioBytes,
        shell = true,
    )

    /**
     * Locates the pair of tracks below [dir].
     *
     * The quality directory named by `type_tag` wins when it exists: that is
     * the resolution the metadata says was downloaded. Only when the tag is
     * absent or its directory is gone does the largest track win — a directory
     * with a higher bitrate is a better guess than the first one found.
     *
     * `audio.m4s` normally sits next to `video.m4s`. When it does not (some
     * versions keep the audio in the parent directory), the search widens to
     * the whole download directory.
     */
    private fun findMedia(dir: File, typeTag: String): Pair<File, File?>? {
        val tagged = typeTag.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { tag -> File(File(dir, tag), VIDEO_NAME) }
            ?.takeIf { it.isFile && it.length() > 0 }

        val video = tagged ?: dir.walkTopDown()
            .maxDepth(MAX_MEDIA_DEPTH)
            .filter { it.isFile && it.name == VIDEO_NAME && it.length() > 0 }
            .maxByOrNull { it.length() }
            ?: return null

        val audio = File(video.parentFile, AUDIO_NAME).takeIf { it.isFile && it.length() > 0 }
            ?: dir.walkTopDown()
                .maxDepth(MAX_MEDIA_DEPTH)
                .firstOrNull { it.isFile && it.name == AUDIO_NAME && it.length() > 0 }

        return video to audio
    }

    /** `external-<avid>-<cid>`, with a path hash when the ids are missing. */
    private fun itemId(avid: Long, cid: Long, dirPath: String): String {
        if (avid > 0 && cid > 0) return "external-$avid-$cid"
        if (avid > 0) return "external-$avid"
        return "external-" + dirPath.hashCode().toUInt().toString(16)
    }

    /** `time_create_stamp` is milliseconds in the official client. */
    private fun createdAt(root: JsonObject, fallbackModified: Long): Long {
        val raw = root.number("time_create_stamp") ?: return fallbackModified
        // Tolerate a seconds-resolution value from an older client.
        return if (raw in 1..9_999_999_999L) raw * 1000 else raw
    }

    /**
     * Bilibili's quality ids, as they appear in `type_tag`.
     *
     * Falls back to the pixel height when the tag is a value we do not know, and
     * to an empty string when there is nothing to say — a wrong label is worse
     * than no label.
     */
    private fun qualityLabel(typeTag: String, width: Long, height: Long): String {
        val byTag = when (typeTag.trim()) {
            "6" -> "240P"
            "16" -> "360P"
            "32" -> "480P"
            "64" -> "720P"
            "74" -> "720P60"
            "80" -> "1080P"
            "112" -> "1080P+"
            "116" -> "1080P60"
            "120" -> "4K"
            "125" -> "HDR"
            "126" -> "杜比视界"
            "127" -> "8K"
            else -> ""
        }
        if (byTag.isNotEmpty()) return byTag
        // `height` alone would call a 1440p upload "4K" wrongly, so use the
        // same coarse tiers the download picker uses.
        return when {
            height >= 2160 -> "4K"
            height >= 1440 -> "2K"
            height >= 1080 -> "1080P"
            height >= 720 -> "720P"
            height >= 480 -> "480P"
            height >= 360 -> "360P"
            height in 1..359 -> "240P"
            width in 1..359 -> "240P"
            else -> ""
        }
    }

    /**
     * Converts an `avid` to the `BV…` form every other part of the app speaks.
     *
     * This is Bilibili's own published encoding (base-58 over a masked id, with
     * two fixed character swaps), not a heuristic; `av170001` must come out as
     * `BV17x411w7KC`. An unknown or zero id yields an empty string so the caller
     * can fall back to the path.
     */
    fun bvidOf(avid: Long): String {
        if (avid <= 0) return ""
        val chars = "BV1000000000".toCharArray()
        var index = chars.lastIndex
        var tmp = (MAX_AID or avid) xor XOR_CODE
        while (tmp > 0) {
            chars[index--] = ALPHABET[(tmp % BASE).toInt()]
            tmp /= BASE
        }
        // The two swaps are part of the published encoding, not decoration.
        var swap = chars[3]
        chars[3] = chars[9]
        chars[9] = swap
        swap = chars[4]
        chars[4] = chars[7]
        chars[7] = swap
        return String(chars)
    }

    private fun JsonObject.text(key: String): String {
        val primitive = this[key] as? JsonPrimitive ?: return ""
        return primitive.contentOrNull?.trim().orEmpty()
    }

    private fun JsonObject.number(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        primitive.longOrNull?.let { return it }
        return primitive.contentOrNull?.trim()?.toLongOrNull()
    }

    private fun JsonObject.boolean(key: String): Boolean? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return when (primitive.contentOrNull?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private const val VIDEO_NAME = "video.m4s"
    private const val AUDIO_NAME = "audio.m4s"

    // Bilibili's av↔BV constants (see bilibili-API-collect, `bvid.md`).
    private const val XOR_CODE = 23442827791579L
    private const val MAX_AID = 1L shl 51
    private const val BASE = 58L
    private const val ALPHABET = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"
}
