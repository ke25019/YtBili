package com.ytstyle.bili.ui.cast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.cast.DlnaController
import com.ytstyle.bili.data.cast.DlnaRenderer
import com.ytstyle.bili.data.cast.LocalMediaProxy
import com.ytstyle.bili.data.cast.ProxyHttp
import com.ytstyle.bili.data.cast.SsdpDiscovery
import com.ytstyle.bili.data.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CastUiState(
    val devices: List<DlnaRenderer> = emptyList(),
    val isDiscovering: Boolean = false,
    /** Friendly name of the renderer being driven, empty when idle. */
    val activeDeviceName: String = "",
    val isCasting: Boolean = false,
    val error: String? = null,
)

/**
 * Drives casting: find a renderer, then hand it a URL served by this phone.
 *
 * The stream is never given to the television directly. Bilibili's CDN URLs are
 * signed and short-lived and several edges require a `Referer`, so the phone
 * re-serves the bytes through [LocalMediaProxy] with the headers the CDN
 * expects. That is also why casting needs a single progressive stream: DLNA
 * plays one URL, and DASH separates video from audio.
 */
@HiltViewModel
class CastViewModel @Inject constructor(
    private val discovery: SsdpDiscovery,
    private val controller: DlnaController,
    private val proxy: LocalMediaProxy,
) : ViewModel() {

    private val _state = MutableStateFlow(CastUiState())
    val state: StateFlow<CastUiState> = _state.asStateFlow()

    private var activeRenderer: DlnaRenderer? = null

    fun discover() {
        if (_state.value.isDiscovering) return
        _state.update { it.copy(isDiscovering = true, error = null) }
        viewModelScope.launch {
            val devices = runCatching { discovery.discover() }.getOrElse {
                AppLog.w("cast", "discovery failed ${it.javaClass.simpleName}")
                emptyList()
            }
            _state.update { current ->
                current.copy(
                    devices = devices,
                    isDiscovering = false,
                    error = if (devices.isEmpty()) "没有找到可投屏的设备，请确认电视与手机在同一 Wi-Fi" else null,
                )
            }
        }
    }

    /**
     * Starts casting [sourceUrl].
     *
     * [headers] are the media request headers the CDN wants (`Referer`,
     * `User-Agent`, `Cookie`); they are attached by the proxy, never sent to
     * the television.
     */
    fun cast(
        renderer: DlnaRenderer,
        sourceUrl: String,
        title: String,
        headers: Map<String, String>,
    ) {
        if (sourceUrl.isBlank()) {
            _state.update { it.copy(error = "这个视频没有可投屏的单流地址") }
            return
        }
        _state.update { it.copy(isCasting = true, error = null, activeDeviceName = renderer.name) }
        viewModelScope.launch {
            val localHost = ProxyHttp.findLanAddress()
            if (localHost == null) {
                _state.update {
                    it.copy(isCasting = false, activeDeviceName = "", error = "请先连接 Wi-Fi：投屏需要手机与电视在同一网络")
                }
                return@launch
            }
            val port = proxy.start(headers)
            if (port == null) {
                _state.update {
                    it.copy(isCasting = false, activeDeviceName = "", error = "本机代理启动失败")
                }
                return@launch
            }

            val localUrl = proxy.urlFor(sourceUrl, localHost)
            controller.play(renderer, localUrl, title).fold(
                onSuccess = {
                    activeRenderer = renderer
                    _state.update { it.copy(isCasting = true, activeDeviceName = renderer.name, error = null) }
                },
                onFailure = { error ->
                    AppLog.w("cast", "play rejected ${error.javaClass.simpleName}")
                    proxy.stop()
                    activeRenderer = null
                    _state.update {
                        it.copy(
                            isCasting = false,
                            activeDeviceName = "",
                            error = error.message ?: "电视拒绝了播放请求",
                        )
                    }
                },
            )
        }
    }

    /** Stops the television and shuts the proxy down. */
    fun stop() {
        val renderer = activeRenderer ?: return
        viewModelScope.launch {
            // Best effort: the proxy must stop either way, or it keeps a
            // listening socket for the rest of the session.
            runCatching { controller.stop(renderer) }
            proxy.stop()
            activeRenderer = null
            _state.update { it.copy(isCasting = false, activeDeviceName = "") }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        super.onCleared()
        // Leaving the player must not leave a proxy listening.
        proxy.stop()
    }
}
