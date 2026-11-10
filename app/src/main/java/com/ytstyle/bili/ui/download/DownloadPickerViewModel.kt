package com.ytstyle.bili.ui.download

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.download.DownloadItem
import com.ytstyle.bili.data.download.DownloadManager
import com.ytstyle.bili.data.download.DownloadState
import com.ytstyle.bili.data.download.DownloadStore
import com.ytstyle.bili.data.net.DashTrack
import com.ytstyle.bili.data.net.PlayUrlData
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A selectable video quality, already collapsed to one track per resolution. */
data class VideoTier(
    val id: Int,
    val label: String,
    /** Pixel height; also the de-duplication key. */
    val height: Int,
    val url: String,
)

/** A selectable audio quality. */
data class AudioTier(
    val id: Int,
    val label: String,
    val bandwidth: Long,
    val url: String,
)

data class DownloadPickerUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    val cover: String = "",
    val bvid: String = "",
    val cid: Long = 0,
    val videoTiers: List<VideoTier> = emptyList(),
    val audioTiers: List<AudioTier> = emptyList(),
    val selectedVideo: VideoTier? = null,
    val selectedAudio: AudioTier? = null,
    /** An index entry for this bvid, if one exists already. */
    val existing: DownloadItem? = null,
    /** True while this bvid's job is executing. */
    val running: Boolean = false,
    /** One-shot confirmation shown under the button. */
    val message: String? = null,
)

/**
 * Offers the tracks the video actually serves.
 *
 * `viewDetail` supplies title/cover/cid, then one `playUrl` call supplies the
 * DASH manifest. Nothing here is cached on disk — the picker is re-resolved
 * every time it opens, which is what keeps expiring stream URLs from leaking
 * into the index (except for the copy kept on the 继续 path).
 */
@HiltViewModel
class DownloadPickerViewModel @Inject constructor(
    private val repository: BiliRepository,
    private val store: DownloadStore,
    private val manager: DownloadManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Route argument; the owner wires `bvid` into the back-stack entry. */
    private val bvid: String = savedStateHandle.get<String>("bvid").orEmpty()

    private val _state = MutableStateFlow(DownloadPickerUiState(bvid = bvid))
    val state: StateFlow<DownloadPickerUiState> = _state.asStateFlow()

    init {
        load()
        observeIndex()
        observeRunning()
    }

    private fun load() {
        if (bvid.isBlank()) {
            _state.update { it.copy(isLoading = false, error = "缺少视频 id") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val detail = repository.viewDetail(bvid)) {
                is Result.Err -> _state.update { it.copy(isLoading = false, error = detail.message) }
                is Result.Ok -> {
                    val view = detail.value
                    // `cid` on the detail payload is the first page; older
                    // archives leave it empty, so fall back to the page list.
                    val cid = if (view.cid > 0) view.cid else view.pages.firstOrNull()?.cid ?: 0L
                    if (cid <= 0) {
                        _state.update { it.copy(isLoading = false, error = "无法解析视频分P") }
                    } else {
                        _state.update {
                            it.copy(
                                title = view.title,
                                cover = view.pic,
                                cid = cid,
                            )
                        }
                        when (val play = resolvePlay(cid)) {
                            is Result.Err ->
                                _state.update { it.copy(isLoading = false, error = play.message) }
                            is Result.Ok -> applyPlay(play.value)
                        }
                    }
                }
            }
        }
    }

    /**
     * Asks for the highest tier the app supports so the manifest carries the
     * whole quality ladder, and retries with the player's own default when the
     * server refuses that request.
     */
    private suspend fun resolvePlay(cid: Long): Result<PlayUrlData> {
        val highest = repository.playUrl(bvid, cid, quality = 116)
        if (highest is Result.Ok) return highest
        return repository.playUrl(bvid, cid)
    }

    private fun applyPlay(data: PlayUrlData) {
        val videos = videoTiers(data.dash?.video.orEmpty())
        val audios = audioTiers(data.dash?.audio.orEmpty())
        _state.update {
            it.copy(
                isLoading = false,
                error = when {
                    videos.isEmpty() -> "该视频没有可缓存的视频流"
                    audios.isEmpty() -> "该视频没有可缓存的音频流"
                    else -> null
                },
                videoTiers = videos,
                audioTiers = audios,
                selectedVideo = videos.firstOrNull(),
                selectedAudio = audios.firstOrNull(),
            )
        }
    }

    fun selectVideo(tier: VideoTier) {
        _state.update { it.copy(selectedVideo = tier, message = null) }
    }

    fun selectAudio(tier: AudioTier) {
        _state.update { it.copy(selectedAudio = tier, message = null) }
    }

    /** Builds the index entry and hands the pair of URLs to the manager. */
    fun start() {
        val current = _state.value
        val video = current.selectedVideo ?: return
        val audio = current.selectedAudio ?: return
        if (current.cid <= 0 || current.bvid.isBlank()) return

        val item = DownloadItem(
            id = current.bvid,
            title = current.title,
            cover = current.cover,
            bvid = current.bvid,
            cid = current.cid,
            videoId = video.id,
            videoLabel = video.label,
            audioId = audio.id,
            audioLabel = audio.label,
            videoPath = store.videoFile(current.bvid).absolutePath,
            audioPath = store.audioFile(current.bvid).absolutePath,
            state = DownloadState.DOWNLOADING,
            progress = 0f,
            createdAt = System.currentTimeMillis(),
            videoUrl = video.url,
            audioUrl = audio.url,
        )
        manager.start(item, video.url, audio.url)
        _state.update { it.copy(message = "已开始缓存，可以离开本页") }
    }

    private fun observeIndex() {
        viewModelScope.launch {
            store.items.collect { items ->
                val existing = items.firstOrNull { it.id == bvid }
                _state.update { it.copy(existing = existing) }
            }
        }
    }

    private fun observeRunning() {
        viewModelScope.launch {
            manager.running.collect { running ->
                _state.update { it.copy(running = running.contains(bvid)) }
            }
        }
    }

    // ---- track shaping ----

    /**
     * One option per resolution, highest bandwidth wins.
     *
     * Bilibili returns several codecs per resolution (AVC/HEVC/AV1); the user
     * should see `1080P` once, not three times.
     */
    private fun videoTiers(tracks: List<DashTrack>): List<VideoTier> =
        tracks.asSequence()
            .filter { it.baseUrl.isNotBlank() }
            .groupBy { heightOf(it) }
            .mapNotNull { (height, group) ->
                val best = group.maxByOrNull { it.bandwidth } ?: return@mapNotNull null
                VideoTier(id = best.id, label = videoLabel(height), height = height, url = best.baseUrl)
            }
            .sortedByDescending { it.height }

    /** One option per label, highest bandwidth wins. */
    private fun audioTiers(tracks: List<DashTrack>): List<AudioTier> =
        tracks.asSequence()
            .filter { it.baseUrl.isNotBlank() }
            .groupBy { audioLabel(it) }
            .mapNotNull { (label, group) ->
                val best = group.maxByOrNull { it.bandwidth } ?: return@mapNotNull null
                AudioTier(id = best.id, label = label, bandwidth = best.bandwidth, url = best.baseUrl)
            }
            .sortedByDescending { it.bandwidth }

    /**
     * Pixel height, falling back to Bilibili's quality id when the track omits
     * `height` — a missing field must not collapse every tier into "360P".
     */
    private fun heightOf(track: DashTrack): Int {
        if (track.height > 0) return track.height
        return when (track.id) {
            127, 120 -> 2160
            126, 125 -> 1440
            116, 80 -> 1080
            74, 64 -> 720
            32 -> 480
            16 -> 360
            else -> 360
        }
    }

    private fun videoLabel(height: Int): String = when {
        height >= 2160 -> "4K"
        height >= 1440 -> "2K"
        height >= 1080 -> "1080P"
        height >= 720 -> "720P"
        height >= 480 -> "480P"
        else -> "360P"
    }

    /**
     * Bilibili's three DASH audio tiers are identified by id (30280 / 30232 /
     * 30216). `bandwidth` is the documented mapping the task asks for, so id is
     * checked first only because it is exact, and bandwidth thresholds act as
     * the fallback for ids this app has not seen.
     */
    private fun audioLabel(track: DashTrack): String = when {
        track.id == 30280 -> "192K"
        track.id == 30232 -> "132K"
        track.id == 30216 -> "64K"
        track.bandwidth >= 190_000 -> "192K"
        track.bandwidth >= 120_000 -> "132K"
        else -> "64K"
    }
}
