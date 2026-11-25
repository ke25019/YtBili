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
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One directory that was searched for another client's cache.
 *
 * Reported to the settings page so "打开开关后什么都没发生" has an explanation:
 * the two reasons that matter are the wrong path and the Android 11 storage
 * rule, and both are invisible without this.
 */
data class ExternalRootReport(
    /** Path as shown in the UI, with the storage root shortened to `/sdcard`. */
    val path: String,
    val exists: Boolean,
    val readable: Boolean,
    /** `entry.json` files seen below this root. */
    val entries: Int,
    /** Entries that became a playable [DownloadItem]. */
    val parsed: Int,
    val note: String = "",
)

/** Result of one scan; carries the items *and* why the other roots were empty. */
data class ExternalScan(
    val hasAccess: Boolean = true,
    val items: List<DownloadItem> = emptyList(),
    val roots: List<ExternalRootReport> = emptyList(),
) {
    val entries: Int get() = roots.sumOf { it.entries }

    /** Paths that were readable but contained nothing we could use. */
    val complete: Boolean get() = items.isNotEmpty()
}

/**
 * Finds media cached by **another** Bilibili client and turns it into
 * [DownloadItem]s this app can list and play.
 *
 * ## Where it looks
 *
 * 1. The official clients' own directories, `Android/data/tv.danmaku.bili/download`
 *    and `Android/data/com.bilibili.app.in/download`.
 * 2. A copy the user made in public storage: `Download/BiliCache` or
 *    `/sdcard/BiliCache`.
 *
 * ## Why a copy exists at all
 *
 * From Android 11, an app *cannot* read another app's `Android/data` directory,
 * even with the "all files access" special permission:
 *
 * > Apps that are granted this permission still can't access the app-specific
 * > directories that belong to other apps.
 * > — developer.android.com/training/data-storage/manage-all-files
 *
 * That is a system rule, not a missing permission, and it is why the first
 * release of this feature (which only scanned `Android/data`) found nothing on
 * a modern phone. The public copy is the only route that works without root or
 * Shizuku; `tools/import-phone-cache.ps1` makes the copy with adb in one step.
 * The scan still *tries* the direct path first, because it works on Android 10
 * and below (and on a rooted device).
 */
@Singleton
class ExternalCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Packages whose cache we know how to read, most likely first. */
    private val packages = listOf(
        "tv.danmaku.bili",
        "com.bilibili.app.in",
    )

    /**
     * Whether the filesystem will show public storage at all.
     *
     * Below API 30 this is always true; from 30 it needs the special permission,
     * which only the user can grant in system settings. It does **not** unlock
     * other apps' `Android/data` — see the class comment.
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
     * Scans every known location and returns what it could parse, plus a report
     * per location. Entries whose media files are missing are skipped: an
     * `entry.json` on its own is a half-finished download, and listing it would
     * produce a row that cannot play.
     */
    suspend fun scan(): ExternalScan = withContext(Dispatchers.IO) {
        if (!hasAccess()) {
            AppLog.i("download", "external cache skipped: no all-files access")
            return@withContext ExternalScan(hasAccess = false)
        }

        val reports = mutableListOf<ExternalRootReport>()
        val found = mutableListOf<DownloadItem>()

        roots().forEach { root ->
            val (report, items) = scanRoot(root)
            reports += report
            found += items
            if (report.entries > report.parsed) {
                AppLog.w(
                    "download",
                    "external cache: ${report.entries - report.parsed} entr(ies) unreadable in ${report.path}",
                )
            }
        }

        // The same video may sit in both the direct path and the public copy,
        // and the same episode may exist in two qualities. Keep the largest
        // video track, which is the quality the user chose to download.
        val unique = LinkedHashMap<String, DownloadItem>()
        found.forEach { item ->
            val previous = unique[item.id]
            if (previous == null || item.videoBytes > previous.videoBytes) unique[item.id] = item
        }

        AppLog.i(
            "download",
            "external cache scan: entries=${reports.sumOf { it.entries }}, " +
                "items=${unique.size}, roots=${reports.size}",
        )
        ExternalScan(hasAccess = true, items = unique.values.toList(), roots = reports)
    }

    /** Direct paths first (Android ≤10 or root), then the public copies. */
    private fun roots(): List<File> {
        val storage = Environment.getExternalStorageDirectory()
        return buildList {
            packages.forEach { pkg -> add(File(storage, "Android/data/$pkg/download")) }
            add(File(storage, "Download/BiliCache"))
            add(File(storage, "BiliCache"))
        }.distinctBy { it.absolutePath }
    }

    private fun scanRoot(root: File): Pair<ExternalRootReport, List<DownloadItem>> {
        val path = displayPath(root)
        // On Android 11+ this directory is hidden from us entirely, so `exists()`
        // can be false for a place that really is there. Say why, not "missing".
        val hiddenBySystem = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            root.absolutePath.contains("/Android/data/")
        if (!root.exists()) {
            return ExternalRootReport(
                path = path,
                exists = false,
                readable = false,
                entries = 0,
                parsed = 0,
                note = if (hiddenBySystem) {
                    "不可见（Android 11 起禁止第三方应用访问其他应用的目录）"
                } else {
                    "不存在"
                },
            ) to emptyList()
        }

        // A directory another app owns shows up as existing but refuses to list;
        // that is the Android 11 rule, and it must be reported as such rather
        // than as "empty".
        val children = runCatching { root.listFiles() }.getOrNull()
        if (children == null) {
            return ExternalRootReport(
                path = path,
                exists = true,
                readable = false,
                entries = 0,
                parsed = 0,
                note = "无法读取（Android 11 起禁止第三方应用读取其他应用的目录）",
            ) to emptyList()
        }

        val entryFiles = runCatching {
            root.walkTopDown()
                .maxDepth(MAX_SEARCH_DEPTH)
                .filter { it.isFile && it.name == ENTRY_NAME }
                .toList()
        }.getOrElse {
            AppLog.w("download", "external cache: cannot walk $path (${it.javaClass.simpleName})")
            emptyList()
        }

        var parsed = 0
        val items = entryFiles.mapNotNull { entry ->
            ExternalCacheParser.parse(entry)?.also { parsed++ }
        }

        val note = when {
            entryFiles.isEmpty() -> "没有找到 entry.json"
            parsed < entryFiles.size -> "${entryFiles.size - parsed} 个缓存不完整或无法解析"
            else -> ""
        }
        return ExternalRootReport(
            path = path,
            exists = true,
            readable = true,
            entries = entryFiles.size,
            parsed = parsed,
            note = note,
        ) to items
    }

    /** `/storage/emulated/0/Download/BiliCache` reads better as `/sdcard/…`. */
    private fun displayPath(file: File): String {
        val root = Environment.getExternalStorageDirectory().absolutePath
        return file.absolutePath.removePrefix(root).let {
            if (it.startsWith("/")) "/sdcard$it" else file.absolutePath
        }
    }

    private companion object {
        const val ENTRY_NAME = "entry.json"

        /**
         * `download/<avid>/c_<cid>/entry.json` is the current nesting; a copied
         * folder may add one more level (`…/download/<avid>/…`), hence 5.
         */
        const val MAX_SEARCH_DEPTH = 5
    }
}
