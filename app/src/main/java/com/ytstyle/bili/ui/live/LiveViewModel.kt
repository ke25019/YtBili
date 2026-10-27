package com.ytstyle.bili.ui.live

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.net.LivePlayData
import com.ytstyle.bili.data.net.LiveRoomInfo
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveUiState(
    val room: LiveRoomInfo? = null,
    val roomId: Long = 0,
    val playUrl: String = "",
    val isHls: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class LiveViewModel @Inject constructor(
    private val repository: BiliRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialRoomId: Long =
        savedStateHandle.get<String>("roomId")?.toLongOrNull() ?: 0L

    private val _state = MutableStateFlow(LiveUiState(roomId = initialRoomId))
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val roomId = _state.value.roomId
        if (roomId <= 0) {
            _state.update { it.copy(isLoading = false, error = "无效的直播间") }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            val infoDeferred = async { repository.liveRoomInfo(roomId) }
            val playDeferred = async { repository.livePlayInfo(roomId) }

            when (val info = infoDeferred.await()) {
                is Result.Ok -> _state.update { it.copy(room = info.value) }
                is Result.Err -> _state.update { it.copy(error = info.message) }
            }

            when (val play = playDeferred.await()) {
                is Result.Ok -> {
                    val picked = pickLiveStream(play.value)
                    _state.update {
                        it.copy(
                            playUrl = picked?.url.orEmpty(),
                            isHls = picked?.isHls ?: false,
                            isLoading = false,
                            error = if (picked == null) "当前没有可用的直播流" else it.error,
                        )
                    }
                }
                is Result.Err -> _state.update {
                    it.copy(isLoading = false, error = it.error ?: play.message)
                }
            }
        }
    }
}

/** A resolved live stream URL plus whether it is HLS. */
data class LiveStreamChoice(val url: String, val isHls: Boolean)

/**
 * Picks a playable URL out of the live play payload.
 *
 * HLS is preferred over FLV: Media3's HLS source handles a continuous live
 * playlist properly, whereas an unbounded FLV stream is far more fragile. `avc`
 * is preferred over `hevc` because software decoding of HEVC is unreliable on
 * the range of devices this app targets.
 *
 * The final URL is `host + base_url + extra`; `extra` carries the signature and
 * must be appended verbatim.
 */
fun pickLiveStream(data: LivePlayData): LiveStreamChoice? {
    val streams = data.playurlInfo?.playurl?.stream.orEmpty()

    fun firstUrl(protocol: String, codec: String): String? {
        val stream = streams.firstOrNull { it.protocolName == protocol } ?: return null
        // Prefer TS for HLS (broadest decoder support), then fMP4.
        val formats = stream.format.sortedBy { if (it.formatName == "ts") 0 else 1 }
        for (format in formats) {
            val match = format.codec.firstOrNull { it.codecName == codec } ?: continue
            val info = match.urlInfo.firstOrNull() ?: continue
            if (info.host.isEmpty() || match.baseUrl.isEmpty()) continue
            return info.host + match.baseUrl + info.extra
        }
        return null
    }

    for (codec in listOf("avc", "hevc")) {
        firstUrl("http_hls", codec)?.let { return LiveStreamChoice(it, isHls = true) }
    }
    for (codec in listOf("avc", "hevc")) {
        firstUrl("http_stream", codec)?.let { return LiveStreamChoice(it, isHls = false) }
    }
    return null
}
