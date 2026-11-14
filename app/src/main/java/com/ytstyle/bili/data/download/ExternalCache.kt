package com.ytstyle.bili.data.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.ytstyle.bili.data.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds media cached by **another** Bilibili client and turns it into
 * [DownloadItem]s this app can list and play.
 *
 * Layout of the official client's cache (one directory per downloaded video):
 *
 * ```
 * Android/data/tv.danmaku.bili/download/<…>/entry.json
 * Android/data/tv.danmaku.bili/download/<…>/<quality>/video.m4s
 * Android/data/tv.danmaku.bili/download/<…>/<quality>/audio.m4s
 * ```
 *
 * `entry.json` carries the metadata; the quality directory (`64`, `80`, …) is
 * read from its `type_tag`, but the files are also searched for when the tag
 * does not match, because the official client moves them around between
 * versions.
 *
 * ## Why this is behind a switch
 *
 * `Android/data/<other package>` is readable on Android 10 and below. From
 * Android 11, filesystem access to another app's external directory requires the
 * "all files access" special permission ([Environment.isExternalStorageManager]);
 * without it the directory simply lists as empty. So the feature is opt-in, the
 * settings row explains what it needs, and a failed scan is a no-result rather
 * than an error.
 */
@Singleton
class ExternalCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Packages whose cache we know how to read, most likely first. */
    private val packages = listOf(
        "tv.danmaku.bili",
        "com.bilibili.app.in",
    )

    /**
     * Whether the filesystem will actually show another app's directory.
     *
     * Below API 30 this is always true; from 30 it needs the special permission,
     * which only the user can grant in system settings.
     */
    fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    /** Opens the per-app "all files access" switch, for when [hasAccess] is false. */
    fun openAccessSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { AppLog.w("download", "no settings activity for all-files access") }
    }

    /**
     * Scans every known package and returns what it could parse.
     *
     * Entries whose media files are missing are skipped: an `entry.json` on its
     * own is a half-finished download, and listing it would produce a row that
     * cannot play.
     */
    suspend fun scan(): List<DownloadItem> = withContext(Dispatchers.IO) {
        if (!hasAccess()) {
            AppLog.i("download", "external cache skipped: no all-files access")
            return@withContext emptyList()
        }
        val storage = Environment.getExternalStorageDirectory()
        val found = buildList {
            packages.forEach { pkg ->
                val root = File(storage, "Android/data/$pkg/download")
                if (!root.isDirectory) return@forEach
                root.walkTopDown()
                    .maxDepth(MAX_SEARCH_DEPTH)
                    .filter { it.isFile && it.name == ENTRY_NAME }
                    .forEach { entry -> itemOf(entry)?.let(::add) }
            }
        }
        AppLog.i("download", "external cache scan: ${found.size} item(s)")
        found
    }

    private fun itemOf(entryFile: File): DownloadItem? = runCatching {
        val root = json.parseToJsonElement(entryFile.readText()) as? JsonObject ?: return null
        val dir = entryFile.parentFile ?: return null

        val video = findFile(dir, VIDEO_NAME) ?: return null
        val audio = findFile(dir, AUDIO_NAME)

        val pageData = root["page_data"] as? JsonObject
        val title = root.text("title")
            .ifBlank { pageData?.text("part").orEmpty() }
            .ifBlank { root.text("bvid") }

        DownloadItem(
            id = "external-" + dir.absolutePath.hashCode().toUInt().toString(16),
            title = title.ifBlank { "未命名缓存" },
            cover = root.text("cover"),
            bvid = root.text("bvid"),
            cid = root.number("cid") ?: pageData?.number("cid") ?: 0L,
            videoPath = video.absolutePath,
            audioPath = audio?.absolutePath.orEmpty(),
            videoBytes = video.length(),
            audioBytes = audio?.length() ?: 0L,
            state = DownloadState.DONE,
            createdAt = root.number("create_time") ?: dir.lastModified(),
            external = true,
        )
    }.onFailure {
        AppLog.w("download", "cannot read ${entryFile.absolutePath}: ${it.javaClass.simpleName}")
    }.getOrNull()

    /** Depth-limited search: the files sit a couple of levels below `entry.json`. */
    private fun findFile(dir: File, name: String): File? =
        dir.walkTopDown().maxDepth(MAX_FILE_DEPTH).firstOrNull { it.isFile && it.name == name }

    private fun JsonObject.text(key: String): String {
        val primitive = this[key] as? JsonPrimitive ?: return ""
        return primitive.contentOrNull?.trim().orEmpty()
    }

    private fun JsonObject.number(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        primitive.longOrNull?.let { return it }
        return primitive.contentOrNull?.trim()?.toLongOrNull()
    }

    private companion object {
        const val ENTRY_NAME = "entry.json"
        const val VIDEO_NAME = "video.m4s"
        const val AUDIO_NAME = "audio.m4s"

        /** `download/<season>/<episode>/entry.json` is the usual nesting. */
        const val MAX_SEARCH_DEPTH = 4
        const val MAX_FILE_DEPTH = 3
    }
}
