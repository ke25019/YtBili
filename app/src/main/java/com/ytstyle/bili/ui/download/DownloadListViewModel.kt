package com.ytstyle.bili.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.download.DownloadItem
import com.ytstyle.bili.data.download.DownloadManager
import com.ytstyle.bili.data.download.DownloadStore
import com.ytstyle.bili.data.download.ExternalCache
import com.ytstyle.bili.data.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row of the manager screen: persisted metadata plus live runtime state. */
data class DownloadEntry(
    val item: DownloadItem,
    /** Live fraction while running, otherwise the last persisted value. */
    val progress: Float,
    val running: Boolean,
)

data class DownloadListUiState(
    val entries: List<DownloadEntry> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * Joins the persisted index with the manager's in-memory progress and running
 * set. Painting resumes on return to the screen because both flows are hot
 * singletons, not per-screen state.
 *
 * Also owns the experimental "other clients' cache" import: the toggle is the
 * single source of truth, so turning it on scans and indexes, and turning it off
 * removes those entries again.
 */
@HiltViewModel
class DownloadListViewModel @Inject constructor(
    private val store: DownloadStore,
    private val manager: DownloadManager,
    private val externalCache: ExternalCache,
    private val settings: SettingsStore,
) : ViewModel() {

    val state: StateFlow<DownloadListUiState> =
        combine(store.items, manager.progress, manager.running) { items, progress, running ->
            DownloadListUiState(
                entries = items.map { item ->
                    DownloadEntry(
                        item = item,
                        progress = progress[item.id] ?: item.progress,
                        running = running.contains(item.id),
                    )
                },
                isLoading = false,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DownloadListUiState(),
        )

    init {
        viewModelScope.launch {
            settings.externalCache.collect { enabled ->
                if (enabled) importExternal() else dropExternal()
            }
        }
    }

    /**
     * Scans the other clients' caches and indexes what it found.
     *
     * Stale entries are dropped first: the source files can be deleted by the
     * other app at any time, and a row that points at a missing file is worse
     * than no row.
     */
    private suspend fun importExternal() {
        val found = externalCache.scan()
        val existing = store.items.first()
        val foundIds = found.mapTo(HashSet()) { it.id }
        existing.filter { it.external && it.id !in foundIds }.forEach { store.remove(it.id) }
        found.forEach { store.upsert(it) }
    }

    private suspend fun dropExternal() {
        store.items.first().filter { it.external }.forEach { store.remove(it.id) }
    }

    /** Called by the settings row to ask for the special permission. */
    fun hasExternalAccess(): Boolean = externalCache.hasAccess()

    fun openExternalAccessSettings() = externalCache.openAccessSettings()

    /**
     * 暂停 is just a cancel: the entry lands in 失败 and 继续 restarts from zero.
     * That is the documented trade-off of not implementing HTTP range requests.
     */
    fun pause(id: String) {
        manager.cancel(id)
    }

    /** Restarts a failed/cancelled item using the URLs stored with it. */
    fun resume(item: DownloadItem) {
        // Imported entries have no source URLs to resume from.
        if (item.external) return
        if (item.videoUrl.isBlank() || item.audioUrl.isBlank()) return
        manager.start(item, item.videoUrl, item.audioUrl)
    }

    /** Deletes the files and the index entry, cancelling first if needed. */
    fun delete(id: String) {
        viewModelScope.launch { manager.remove(id) }
    }
}
