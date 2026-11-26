package com.ytstyle.bili.data.download

import com.ytstyle.bili.data.extcache.ShizukuShell
import com.ytstyle.bili.data.log.AppLog
import com.ytstyle.bili.data.settings.ExternalCacheMode
import com.ytstyle.bili.data.settings.SettingsStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the download index in step with another client's cache.
 *
 * Shared by the 离线缓存 screen and the experimental switch in settings: both
 * used to need their own copy of this logic, and turning the switch on should
 * import immediately rather than only when the cache screen is opened.
 *
 * Which reader runs is decided here:
 *  - [ExternalCacheMode.Shizuku] reads the official paths through the Shizuku
 *    user service (file descriptors, no copy);
 *  - [ExternalCacheMode.Copy] scans the filesystem, which only works on
 *    Android 10 and below, or for a folder the user copied somewhere public;
 *  - [ExternalCacheMode.Auto] uses Shizuku when it is installed/authorised and
 *    falls back to the copy scan otherwise.
 *
 * Imported rows are marked [DownloadItem.external] and carry no source URLs.
 * They are replaced wholesale on each sync, and removed when the switch is
 * turned off — the files themselves belong to the other client and are never
 * touched.
 */
@Singleton
class ExternalCacheSync @Inject constructor(
    private val externalCache: ExternalCache,
    private val store: DownloadStore,
    private val settings: SettingsStore,
    private val shizuku: ShizukuShell,
) {

    /** Scans with the configured mode, drops vanished entries, and upserts. */
    suspend fun sync(): ExternalScan {
        val mode = settings.externalCacheMode.first()
        val scan = if (shouldUseShizuku(mode)) {
            externalCache.scanViaShizuku()
        } else {
            externalCache.scan()
        }

        if (!scan.hasAccess) {
            // Without storage access the files cannot be read, so keeping rows
            // that point at them would only produce unplayable entries.
            drop()
            return scan
        }

        val foundIds = scan.items.mapTo(HashSet()) { it.id }
        val stale = store.items.first().filter { it.external && it.id !in foundIds }
        stale.forEach { store.remove(it.id) }
        scan.items.forEach { store.upsert(it) }

        if (stale.isNotEmpty()) {
            AppLog.i("download", "external cache: dropped ${stale.size} stale entr(ies)")
        }
        return scan
    }

    /** Removes every imported row; called when the switch is turned off. */
    suspend fun drop() {
        store.items.first().filter { it.external }.forEach { store.remove(it.id) }
    }

    private suspend fun shouldUseShizuku(mode: ExternalCacheMode): Boolean = when (mode) {
        ExternalCacheMode.Shizuku -> true
        ExternalCacheMode.Copy -> false
        ExternalCacheMode.Auto -> shizuku.awaitService(timeoutMs = 5_000) != null
    }
}
