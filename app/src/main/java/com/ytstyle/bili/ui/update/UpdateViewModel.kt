package com.ytstyle.bili.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.BuildConfig
import com.ytstyle.bili.data.log.AppLog
import com.ytstyle.bili.data.repo.Result
import com.ytstyle.bili.data.settings.SettingsStore
import com.ytstyle.bili.data.update.ApkDownloader
import com.ytstyle.bili.data.update.ApkInstaller
import com.ytstyle.bili.data.update.GitHubRelease
import com.ytstyle.bili.data.update.UpdateChannel
import com.ytstyle.bili.data.update.UpdateChecker
import com.ytstyle.bili.data.update.UpdateResult
import com.ytstyle.bili.data.update.VersionTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the update dialog renders. */
data class UpdateUiState(
    val visible: Boolean = false,
    val currentVersion: String = BuildConfig.VERSION_NAME,
    /** True while the GitHub call is in flight. */
    val checking: Boolean = false,
    /** Non-null once a newer build was found. */
    val release: GitHubRelease? = null,
    val version: VersionTag? = null,
    val downloading: Boolean = false,
    /** 0f..1f while downloading, null when the size is unknown. */
    val progress: Float? = null,
    /** The finished APK, kept so 安装 does not have to download it again. */
    val downloadedApk: java.io.File? = null,
    /** One-line outcome shown inside the dialog (up to date, timeout, …). */
    val notice: String? = null,
    val noticeIsError: Boolean = false,
    val autoUpdate: Boolean = true,
    /** The channel the current check ran on; shown in the dialog title. */
    val channel: UpdateChannel = UpdateChannel.Stable,
) {
    /** The version line in the title: `发现新版本 v1.13.0`. */
    val titleVersion: String
        get() = release?.tagName?.removePrefix("v")?.ifBlank { version?.toString().orEmpty() }.orEmpty()

    /**
     * True when the release carries an APK that matches this build's type.
     *
     * A debug build must take the debug APK and a release build the release one —
     * they are signed differently, so the wrong file is an install that Android
     * refuses. Kept here so the dialog does not need to know about build types.
     */
    val hasMatchingApk: Boolean
        get() = release?.apkUrl(forDebugBuild = BuildConfig.DEBUG) != null
}

/**
 * Drives the in-app update: the GitHub check, the download, and the hand-off to
 * the system installer.
 *
 * Two entry points, deliberately different in how loud they are: [checkOnLaunch]
 * (called once at startup) stays silent unless there is something to offer, while
 * [checkNow] (the settings button) reports every outcome, because the user asked
 * and deserves an answer either way.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val checker: UpdateChecker,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // A DataStore read can fail (corrupt file, no disk); the launch check
            // must then err on the side of checking, not on the side of silence.
            val auto = runCatching { settings.autoUpdate.first() }.getOrDefault(true)
            val channel = runCatching { settings.updateChannel.first() }
                .getOrDefault(UpdateChannel.Stable)
            _state.update { it.copy(autoUpdate = auto, channel = channel) }
        }
    }

    /**
     * The startup check: silent on failure and when up to date.
     *
     * A "你已是最新版本" dialog every launch would be worse than no check at all,
     * so only an actual update opens the dialog.
     */
    fun checkOnLaunch() {
        if (_state.value.checking || _state.value.downloading) return
        viewModelScope.launch {
            val current = _state.value
            if (!current.autoUpdate) return@launch
            // Read the channel from its source of truth rather than from [current]:
            // the init block's read may not have landed yet on a cold start.
            val channel = runCatching { settings.updateChannel.first() }
                .getOrDefault(current.channel)
            _state.update { it.copy(checking = true, notice = null, channel = channel) }
            when (
                val result = checker.check(currentVersion = current.currentVersion, channel = channel)
            ) {
                is UpdateResult.Available -> _state.update {
                    it.copy(
                        checking = false,
                        visible = true,
                        release = result.release,
                        version = result.version,
                        notice = null,
                        noticeIsError = false,
                    )
                }
                // Silent: nothing to tell the user at launch, including "this
                // channel has no release at all" — that would open a dialog on
                // every start for anyone on Stable while only Betas exist.
                is UpdateResult.UpToDate,
                is UpdateResult.NoReleaseInChannel,
                is UpdateResult.Failed,
                -> _state.update { it.copy(checking = false) }
            }
        }
    }

    /** The settings button: always shows the outcome. */
    fun checkNow() {
        if (_state.value.checking || _state.value.downloading) return
        viewModelScope.launch {
            val current = _state.value
            val channel = runCatching { settings.updateChannel.first() }
                .getOrDefault(current.channel)
            _state.update {
                it.copy(
                    visible = true,
                    checking = true,
                    release = null,
                    version = null,
                    notice = null,
                    channel = channel,
                )
            }
            when (
                val result = checker.check(currentVersion = current.currentVersion, channel = channel)
            ) {
                is UpdateResult.Available -> _state.update {
                    it.copy(
                        checking = false,
                        release = result.release,
                        version = result.version,
                        notice = null,
                        noticeIsError = false,
                    )
                }
                is UpdateResult.UpToDate -> _state.update {
                    it.copy(
                        checking = false,
                        release = null,
                        notice = "已是最新版本（${result.newest ?: it.currentVersion}）",
                        noticeIsError = false,
                    )
                }

                is UpdateResult.NoReleaseInChannel -> _state.update {
                    it.copy(
                        checking = false,
                        release = null,
                        notice = if (result.channel == UpdateChannel.Stable) {
                            "稳定版渠道暂无可用版本。当前发布的都是测试版，可在设置里切换渠道。"
                        } else {
                            "测试版渠道暂无可用版本。"
                        },
                        noticeIsError = false,
                    )
                }

                is UpdateResult.Failed -> _state.update {
                    it.copy(checking = false, notice = result.message, noticeIsError = true)
                }
            }
        }
    }

    /**
     * The channel selector: persist the choice, then forget the offer that
     * belonged to the previous channel.
     *
     * Deliberately does **not** download, install or even re-check: switching a
     * preference must never start a network transfer, and silently checking
     * again would pop the dialog while the user is still in Settings. The next
     * explicit 检查更新 (or the next launch) uses the new channel.
     */
    fun setChannel(channel: UpdateChannel) {
        if (_state.value.channel == channel && _state.value.release == null) {
            // Still persist: the setting may have been changed elsewhere.
            viewModelScope.launch { settings.setUpdateChannel(channel) }
            return
        }
        _state.update {
            it.copy(
                channel = channel,
                visible = false,
                checking = false,
                release = null,
                version = null,
                downloading = false,
                progress = null,
                downloadedApk = null,
                notice = null,
                noticeIsError = false,
            )
        }
        viewModelScope.launch { settings.setUpdateChannel(channel) }
    }

    /** The dialog's 关闭自动更新: stop the silent startup check from now on. */
    fun disableAutoUpdate() {
        _state.update { it.copy(autoUpdate = false, visible = false) }
        viewModelScope.launch { settings.setAutoUpdate(false) }
    }

    /** 稍后提醒 and the scrim tap: just close. The next launch checks again. */
    fun dismiss() = _state.update { it.copy(visible = false) }

    /**
     * 安装: hand the APK that is **already on disk** to the installer.
     *
     * This is the path back from the "install unknown apps" switch: granting it
     * sends the user out of the app, and before this existed they came back to a
     * dialog whose only button was 立即更新, i.e. a 3.4 MB re-download for a file
     * that was already there. When the grant is still missing the same guidance
     * runs again, which is what makes the round trip repeatable.
     */
    fun installDownloaded() {
        val apk = _state.value.downloadedApk
        if (apk == null || !apk.exists()) {
            _state.update {
                it.copy(
                    downloadedApk = null,
                    notice = "安装包已不存在，请重新下载",
                    noticeIsError = true,
                )
            }
            return
        }
        if (!installer.canInstall()) {
            _state.update {
                it.copy(notice = "还需要允许「安装未知应用」，已为你打开设置", noticeIsError = false)
            }
            installer.openInstallPermission()
            return
        }
        if (installer.install(apk)) {
            _state.update { it.copy(visible = false) }
        } else {
            _state.update { it.copy(notice = "没有找到系统安装器", noticeIsError = true) }
        }
    }

    /**
     * 立即更新: download, then install.
     *
     * When the "install unknown apps" permission is missing the download is still
     * kept — and remembered in [UpdateUiState.downloadedApk] — so the 安装 button
     * can finish the job once the user comes back from the switch.
     */
    fun downloadAndInstall() {
        val current = _state.value
        val release = current.release ?: return
        if (current.downloading) return
        val url = release.apkUrl(forDebugBuild = BuildConfig.DEBUG)
        if (url.isNullOrBlank()) {
            _state.update { it.copy(notice = "这个版本没有可用的 APK 资产", noticeIsError = true) }
            return
        }

        _state.update { it.copy(downloading = true, progress = null, notice = null) }
        viewModelScope.launch {
            val fileName = "YtBili-${release.tagName.removePrefix("v").ifBlank { "update" }}.apk"
            when (val result = downloader.download(url, fileName) { fraction ->
                _state.update { it.copy(progress = fraction) }
            }) {
                is Result.Ok -> {
                    _state.update {
                        it.copy(downloading = false, progress = 1f, downloadedApk = result.value)
                    }
                    if (installer.canInstall()) {
                        installDownloaded()
                    } else {
                        _state.update {
                            it.copy(
                                notice = "已下载完成。请允许「安装未知应用」，回来后点「安装」即可。",
                                noticeIsError = false,
                            )
                        }
                        installer.openInstallPermission()
                    }
                }

                is Result.Err -> {
                    AppLog.w("update", "download error: ${result.message}")
                    _state.update {
                        it.copy(
                            downloading = false,
                            progress = null,
                            notice = result.message,
                            noticeIsError = true,
                        )
                    }
                }
            }
        }
    }
}
