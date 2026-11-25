package com.ytstyle.bili.data.download

import com.ytstyle.bili.data.log.AppLog
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
 * Imported rows are marked [DownloadItem.external] and carry no source URLs.
 * They are replaced wholesale on each sync, and removed when the switch is
 * turned off — the files themselves belong to the other client and are never
 * touched.
 */
@Singleton
class ExternalCacheSync @Inject constructor(
    private val externalCache: ExternalCache,
    private val store: DownloadStore,
) {

    /** Scans, drops entries whose files disappeared, and upserts what was found. */
    suspend fun sync(): ExternalScan {
        val scan = externalCache.scan()
        if (!scan.hasAccess) {
            // Without the permission the files cannot be read, so keeping rows
            // that point at them would only produce unplayable entries. They
            // come back on the next scan once access is granted.
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
}
