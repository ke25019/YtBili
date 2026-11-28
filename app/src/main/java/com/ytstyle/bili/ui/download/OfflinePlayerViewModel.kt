package com.ytstyle.bili.ui.download

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.download.DownloadItem
import com.ytstyle.bili.data.download.DownloadStore
import com.ytstyle.bili.data.extcache.ShizukuCopy
import com.ytstyle.bili.data.extcache.ShizukuShell
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class OfflinePlayerUiState(
    val item: DownloadItem? = null,
    /** Every other cached video, newest first: the picker's source. */
    val others: List<DownloadItem> = emptyList(),
    /** False until the index has been read, so the screen does not flash "not found". */
    val loaded: Boolean = false,
)

/**
 * Looks up one cached item by route argument `id`, and can switch to another
 * without leaving the screen.
 *
 * [select] exists because the offline screen lists the other cached videos: a
 * viewer picking one should not have to navigate back to the download list and
 * start over. The player is re-created per id by the screen's `key`, so the old
 * media source is released rather than resumed.
 */
@HiltViewModel
class OfflinePlayerViewModel @Inject constructor(
    private val store: DownloadStore,
    /**
     * Needed only for `externalShell` items: their paths belong to another app
     * and are read through Binder-opened descriptors, not `File`.
     */
    val shizuku: ShizukuShell,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialId: String = savedStateHandle.get<String>("id").orEmpty()
    private val currentId = MutableStateFlow(initialId)

    private val _copying = MutableStateFlow(false)
    val copying: StateFlow<Boolean> = _copying.asStateFlow()

    private val _copyError = MutableStateFlow<String?>(null)
    val copyError: StateFlow<String?> = _copyError.asStateFlow()

    val state: StateFlow<OfflinePlayerUiState> =
        combine(store.items, currentId) { items, id ->
            OfflinePlayerUiState(
                item = items.firstOrNull { it.id == id },
                others = items.filterNot { it.id == id },
                loaded = true,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = OfflinePlayerUiState(),
        )

    /** Switches the playing item; the store keeps its newest-first order. */
    fun select(id: String) {
        currentId.value = id
    }

    /**
     * Copies a Shizuku-backed item into the app's private storage.
     *
     * The live descriptor path is the intended way to watch these files; this is
     * the escape hatch for devices where it does not work. The copied item
     * replaces the original row (`externalShell = false`), so later plays are
     * plain local files and nothing is copied twice.
     */
    fun copyToLocal(item: DownloadItem) {
        if (_copying.value) return
        viewModelScope.launch {
            _copying.value = true
            _copyError.value = null
            try {
                val service = shizuku.awaitService()
                    ?: error("Shizuku 未连接，无法复制")
                val directory = File(context.filesDir, "extcache/${item.id}")
                val video = File(directory, "video.m4s")
                val videoBytes = withContext(Dispatchers.IO) {
                    ShizukuCopy.copy(service, item.videoPath, video)
                }
                val audio = if (item.audioPath.isNotBlank()) {
                    val file = File(directory, "audio.m4s")
                    withContext(Dispatchers.IO) {
                        ShizukuCopy.copy(service, item.audioPath, file)
                    }
                    file
                } else {
                    null
                }
                store.upsert(
                    item.copy(
                        videoPath = video.absolutePath,
                        audioPath = audio?.absolutePath.orEmpty(),
                        videoBytes = videoBytes,
                        audioBytes = audio?.length() ?: 0L,
                        externalShell = false,
                    ),
                )
            } catch (error: Throwable) {
                _copyError.value = error.message ?: error.javaClass.simpleName
            } finally {
                _copying.value = false
            }
        }
    }

    fun dismissCopyError() {
        _copyError.value = null
    }
}
