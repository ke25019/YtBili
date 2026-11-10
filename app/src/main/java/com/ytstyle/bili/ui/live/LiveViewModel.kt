package com.ytstyle.bili.ui.live

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.live.LiveDanmakuClient
import com.ytstyle.bili.data.live.LiveDanmakuProtocol
import com.ytstyle.bili.data.log.AppLog
import com.ytstyle.bili.data.net.LiveDanmuHost
import com.ytstyle.bili.data.net.LivePlayData
import com.ytstyle.bili.data.net.LiveRoomInfo
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import com.ytstyle.bili.data.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveUiState(
    val room: LiveRoomInfo? = null,
    val roomId: Long = 0,
    val playUrl: String = "",
    val isHls: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    /** Recent chat, oldest first; capped so a busy room cannot grow forever. */
    val danmaku: List<LiveDanmakuProtocol.LiveChatMessage> = emptyList(),
    /** 人气值, refreshed with every heartbeat reply. */
    val popularity: Int = 0,
    val danmakuConnected: Boolean = false,
    val danmakuEnabled: Boolean = true,
    /** Why the danmaku socket is not up, when it is not. */
    val danmakuNotice: String? = null,
)

@HiltViewModel
class LiveViewModel @Inject constructor(
    private val repository: BiliRepository,
    private val session: SessionStore,
    private val danmakuClient: LiveDanmakuClient,
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
                is Result.Ok -> {
                    _state.update { it.copy(room = info.value) }
                    // The queue is keyed by the *real* room id; the id in the
                    // route may be a short id.
                    connectDanmaku(info.value.roomId)
                }
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

    fun toggleDanmaku() = _state.update { it.copy(danmakuEnabled = !it.danmakuEnabled) }

    /** Retry for the "弹幕未连接" note. */
    fun reconnectDanmaku() {
        val roomId = _state.value.room?.roomId ?: return
        connectDanmaku(roomId)
    }

    /**
     * Opens the danmaku socket for [realRoomId].
     *
     * Failure is not an error state for the screen: the video keeps playing, so
     * the reason is surfaced as a one-line [LiveUiState.danmakuNotice] instead.
     * The connection is cancelled with the view model.
     */
    private fun connectDanmaku(realRoomId: Long) {
        if (realRoomId <= 0) return
        danmakuJob?.cancel()
        danmakuJob = viewModelScope.launch {
            var attempt = 0
            // Remembered across reconnects so a host that worked keeps being
            // tried first instead of re-walking the whole list every drop.
            var preferredHost: Pair<String, Int>? = null
            while (isActive) {
                // Re-fetched per attempt: the token is tied to the room and
                // expires, so reusing one after a drop is how a reconnect ends
                // up refused. The repository tries the legacy endpoint first —
                // the modern one is risk-controlled into -352 — and reports
                // which one answered.
                val info = when (val result = repository.liveDanmuCredentials(realRoomId)) {
                    is Result.Ok -> result.value
                    is Result.Err -> {
                        // Nothing to retry against: the credentials call itself failed.
                        AppLog.w("live", "danmu credentials failed code=${result.code}")
                        _state.update {
                            it.copy(
                                danmakuConnected = false,
                                danmakuNotice = result.message,
                            )
                        }
                        return@launch
                    }
                }

                val uid = runCatching { session.midFlow.first() }.getOrDefault(0L)
                // The device fingerprint was never sent before v1.12.1.
                // Bilibili's live side applies the same risk control as the web
                // API, and a guest auth without `buvid` is the cheapest thing to
                // rule out.
                val buvid = runCatching { session.currentBuvid() }.getOrDefault("")

                // Every (host, port) the server offered, the last working one
                // first. Only the first entry used to be tried — and the endpoint
                // returns a *list* precisely because hosts differ in
                // reachability, so a network that blocked the advertised port
                // left the reconnect loop retrying one unreachable pair forever.
                val candidates = danmakuCandidates(info.hostList, preferredHost)
                if (candidates.isEmpty()) {
                    AppLog.w("live", "danmu info carried no host (hosts=${info.hostList.size})")
                    _state.update {
                        it.copy(danmakuConnected = false, danmakuNotice = "没有可用的弹幕服务器")
                    }
                    return@launch
                }

                var closedReason: String? = null
                var authenticated = false

                for ((hostName, port) in candidates) {
                    AppLog.d(
                        "live",
                        "danmaku connect attempt=${attempt + 1} host=$hostName port=$port " +
                            "source=${info.source} hosts=${info.hostList.size} " +
                            "uid=${if (uid > 0) "yes" else "guest"} " +
                            "token=${info.token.isNotEmpty()} buvid=${buvid.isNotEmpty()}",
                    )

                    danmakuClient.connect(
                        host = hostName,
                        port = port,
                        roomId = realRoomId,
                        uid = uid,
                        token = info.token,
                        buvid = buvid,
                    ).collect { event ->
                        when (event) {
                            is LiveDanmakuClient.Event.Connected -> {
                                AppLog.d("live", "danmaku socket open")
                                _state.update { it.copy(danmakuNotice = null) }
                            }

                            is LiveDanmakuClient.Event.Authenticated -> {
                                authenticated = true
                                attempt = 0
                                preferredHost = hostName to port
                                AppLog.d("live", "danmaku authenticated on $hostName:$port")
                                _state.update {
                                    it.copy(danmakuConnected = true, danmakuNotice = null)
                                }
                            }

                            is LiveDanmakuClient.Event.Chat -> _state.update { current ->
                                current.copy(
                                    danmaku = (current.danmaku + event.message).takeLast(MAX_DANMAKU),
                                )
                            }

                            is LiveDanmakuClient.Event.Popularity -> _state.update {
                                it.copy(popularity = event.value)
                            }

                            is LiveDanmakuClient.Event.Closed -> {
                                closedReason = event.reason
                            }
                        }
                    }

                    if (!isActive) return@launch

                    // A host that *authenticated* and then dropped is a healthy
                    // connection that ended; walking on to the next host would
                    // restart the handshake for nothing, and the reconnect delay
                    // below is the right response. Only an unauthenticated end
                    // means this particular host is the problem.
                    if (authenticated) break

                    AppLog.w(
                        "live",
                        "danmaku host $hostName:$port did not authenticate: " +
                            (closedReason ?: "unknown"),
                    )
                }

                // The flow ended, which only happens when the socket closed.
                if (!isActive) return@launch
                if (authenticated) {
                    AppLog.w("live", "danmaku socket ended: ${closedReason ?: "unknown"}")
                } else {
                    AppLog.w("live", "no danmaku host authenticated (${candidates.size} tried)")
                }
                attempt++
                if (attempt > MAX_DANMAKU_RECONNECTS) {
                    _state.update {
                        it.copy(
                            danmakuConnected = false,
                            danmakuNotice = closedReason?.takeIf { it.isNotBlank() }
                                ?: "弹幕连接已断开",
                        )
                    }
                    return@launch
                }

                // A live socket drops routinely; retrying is what turns that into
                // a blip instead of "直播间消息不加载" until the user taps 重试.
                val waitMs = DANMAKU_RECONNECT_MS * attempt
                _state.update {
                    it.copy(
                        danmakuConnected = false,
                        danmakuNotice = "弹幕断开，${waitMs / 1000} 秒后重连（第 $attempt 次）",
                    )
                }
                delay(waitMs)
            }
        }
    }

    /**
     * The connection candidates for one attempt.
     *
     * Each host is offered on the port the server advertised, plus 443 — the one
     * a restrictive network is most likely to allow. [preferred] (the pair that
     * authenticated last time) is moved to the front so a reconnect does not
     * re-walk a list of hosts already known to be unreachable.
     */
    private fun danmakuCandidates(
        hosts: List<LiveDanmuHost>,
        preferred: Pair<String, Int>?,
    ): List<Pair<String, Int>> {
        val ordered = LinkedHashSet<Pair<String, Int>>()
        preferred?.let { ordered += it }
        hosts.forEach { host ->
            val name = host.host.trim()
            if (name.isEmpty()) return@forEach
            val port = host.wssPort.takeIf { it > 0 } ?: DEFAULT_WSS_PORT
            ordered += name to port
            if (port != DEFAULT_WSS_PORT) ordered += name to DEFAULT_WSS_PORT
        }
        return ordered.toList()
    }

    private var danmakuJob: Job? = null

    private companion object {
        /** Ten screens' worth of history; anything more is never read. */
        const val MAX_DANMAKU = 60

        /** How many times a dropped danmaku socket is retried before giving up. */
        const val MAX_DANMAKU_RECONNECTS = 5

        /** Base backoff; multiplied by the attempt number, so 3s, 6s, 9s… */
        const val DANMAKU_RECONNECT_MS = 3_000L

        /** WSS default, and the fallback port for a network that blocks others. */
        const val DEFAULT_WSS_PORT = 443
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
