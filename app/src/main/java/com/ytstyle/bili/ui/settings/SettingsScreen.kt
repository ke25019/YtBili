package com.ytstyle.bili.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ytstyle.bili.ui.components.toSecureUrl
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import com.ytstyle.bili.BuildConfig
import com.ytstyle.bili.data.log.AppLog
import com.ytstyle.bili.data.net.NetworkStatus
import com.ytstyle.bili.data.settings.AudioPreference
import com.ytstyle.bili.data.settings.AutoplayMode
import com.ytstyle.bili.data.settings.ThemeMode
import com.ytstyle.bili.data.update.UpdateChannel
import com.ytstyle.bili.ui.player.DanmakuMode
import com.ytstyle.bili.ui.player.DanmakuSettingsPanel
import com.ytstyle.bili.ui.player.Quality
import com.ytstyle.bili.ui.components.CompactSlider
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/** Pages inside settings. Kept as local state so no extra routes are needed. */
private enum class SettingsPage(val title: String, val parent: SettingsPage?) {
    Root("设置", null),
    Appearance("外观", Root),

    /**
     * The home feed's own page.
     *
     * The two-column layout used to live under 实验功能, but it is a display
     * preference rather than an unfinished feature, and that page held nothing
     * else. It moved here and 实验功能 went away with it — an empty menu entry
     * is worse than no menu entry.
     */
    HomeFeed("首页推荐设置", Root),
    Playback("播放", Root),

    /**
     * The playback switches, one level in.
     *
     * 播放 used to be a wall of controls; it is now a two-entry menu so the new
     * 音画设置 has somewhere to live and the switches keep their own page.
     */
    PlaybackOptions("播放选项", Playback),
    AudioVideo("音画设置", Playback),
    Danmaku("弹幕设置", Root),
    Account("账户", Root),
    About("关于", Root),
}

/**
 * Settings, grouped into sub-pages rather than one long scroll.
 *
 * Only options wired to real behaviour appear; a screen of switches that do
 * nothing is worse than a short one.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit = {},
    /** Opens the login screen to add another account to the switcher. */
    onAddAccount: () -> Unit = {},
    /** Asks the app shell to run an update check; its dialog opens on top. */
    onCheckUpdate: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var page by remember { mutableStateOf(SettingsPage.Root) }

    /**
     * The system back gesture mirrors the arrow next to the title, one level at
     * a time.
     *
     * It used to jump straight to [SettingsPage.Root] from any depth, so leaving
     * 播放选项 dropped you past 播放 and onto the settings home even though you had
     * only stepped one level in — the reported "按返回会直接回设置主页". Each page
     * now knows its parent, so back unwinds the same way it was walked. At the
     * root there is no handler, and back leaves settings as before.
     */
    BackHandler(enabled = page.parent != null) { page = page.parent ?: SettingsPage.Root }

    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val danmakuEnabled by viewModel.danmakuEnabled.collectAsStateWithLifecycle()
    val autoplayMode by viewModel.autoplayMode.collectAsStateWithLifecycle()
    val scale by viewModel.danmakuScale.collectAsStateWithLifecycle()
    val opacity by viewModel.danmakuOpacity.collectAsStateWithLifecycle()
    val area by viewModel.danmakuArea.collectAsStateWithLifecycle()
    val speed by viewModel.danmakuSpeed.collectAsStateWithLifecycle()
    val types by viewModel.danmakuTypes.collectAsStateWithLifecycle()
    val homeGrid by viewModel.homeGrid.collectAsStateWithLifecycle()
    val defaultQuality by viewModel.defaultQuality.collectAsStateWithLifecycle()
    val audioPreference by viewModel.audioPreference.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val uname by viewModel.uname.collectAsStateWithLifecycle()
    val savedAccounts by viewModel.savedAccounts.collectAsStateWithLifecycle()
    val activeMid by viewModel.activeMid.collectAsStateWithLifecycle()
    val autoUpdate by viewModel.autoUpdate.collectAsStateWithLifecycle()
    val updateChannel by viewModel.updateChannel.collectAsStateWithLifecycle()

    // Sampled when the page opens: the user is here to change a preference, and
    // a live subscription would only add a moving label to a settings screen.
    val context = LocalContext.current
    val networkLabel = remember(context) { NetworkStatus.current(context).label }

    var showSwitchDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    /** True after an export attempt with nothing logged yet. */
    var logEmpty by remember { mutableStateOf(false) }

    // The size is held in state so the number updates the moment the log is
    // cleared, instead of only on the next visit to this page.
    var logSizeKb by remember { mutableStateOf(AppLog.sizeBytes() / 1024f) }
    var showClearLogDialog by remember { mutableStateOf(false) }
    var logCleared by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    // Same rule as the back gesture: one level at a time.
                    val parent = page.parent
                    if (parent == null) onBack() else page = parent
                },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = AppTheme.colors.onBackground,
                )
            }
            Text(
                text = page.title,
                style = MaterialTheme.typography.titleMedium,
                color = AppTheme.colors.onBackground,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            when (page) {
                SettingsPage.Root -> {
                    NavRow("外观", "主题模式") { page = SettingsPage.Appearance }
                    NavRow("首页推荐设置", "首页排版与推荐内容") { page = SettingsPage.HomeFeed }
                    NavRow("播放", "弹幕、自动播放、流量") { page = SettingsPage.Playback }
                    NavRow("弹幕设置", "字号、透明度、显示区域、速度") { page = SettingsPage.Danmaku }
                    NavRow("账户", "切换账户、退出登录与本地数据") { page = SettingsPage.Account }
                    NavRow("关于", "版本与说明") { page = SettingsPage.About }
                }

                SettingsPage.HomeFeed -> {
                    SectionTitle("排版")
                    SwitchRow(
                        "首页双列排版",
                        "首页改为两列网格，一屏显示更多内容",
                        homeGrid,
                        viewModel::setHomeGrid,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "双列会显示更紧凑的卡片（不展示 UP 主头像），" +
                            "两种排版都可以下拉刷新、也会持续加载新的推荐视频。",
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }

                SettingsPage.Appearance -> {
                    SectionTitle("主题")
                    Text(
                        text = "默认浅色，可切换为深色或跟随系统",
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    ThemeMode.entries.forEach { mode ->
                        ThemeOption(
                            mode = mode,
                            selected = mode == themeMode,
                            onClick = { viewModel.setThemeMode(mode) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }

                SettingsPage.Playback -> {
                    SectionTitle("播放")
                    NavRow("播放选项", "自动播放与弹幕默认开关") {
                        page = SettingsPage.PlaybackOptions
                    }
                    NavRow("音画设置", "默认画质与默认音质") {
                        page = SettingsPage.AudioVideo
                    }
                }

                SettingsPage.PlaybackOptions -> {
                    SectionTitle("播放选项")
                    SwitchRow("默认开启弹幕", "进入播放页是否显示弹幕", danmakuEnabled, viewModel::setDanmakuEnabled)

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "自动播放",
                        color = AppTheme.colors.onBackground,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "选择在什么网络下进入播放页就自动开始",
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    AutoplayMode.entries.forEach { option ->
                        AutoplayOption(
                            mode = option,
                            selected = option == autoplayMode,
                            onClick = { viewModel.setAutoplayMode(option) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    // The 流量提示 panel and the 全屏播放 explanation were removed
                    // on request: the first duplicated the choice above it, the
                    // second documented a button that no longer exists.
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "当前网络：$networkLabel",
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }

                SettingsPage.AudioVideo -> {
                    SectionTitle("默认画质")
                    Text(
                        text = "进入播放页时请求的档位。视频没有这一档时会自动降到它实际支持的档位。",
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Quality.entries.forEach { quality ->
                        PickRow(
                            label = quality.label,
                            selected = quality.qn == defaultQuality,
                            onClick = { viewModel.setDefaultQuality(quality.qn) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    Spacer(Modifier.height(18.dp))
                    SectionTitle("默认音质")
                    Text(
                        text = "只决定选哪一条音频轨；视频没有该音质时取最高的一条。",
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    AudioPreference.entries.forEach { option ->
                        PickRow(
                            label = option.label,
                            selected = option == audioPreference,
                            onClick = { viewModel.setAudioPreference(option) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }

                SettingsPage.Danmaku -> {
                    // The same panel the player shows from its own settings
                    // button, so the two can never drift apart.
                    DanmakuSettingsPanel(
                        scale = scale,
                        opacity = opacity,
                        area = area,
                        speed = speed,
                        types = types,
                        onScale = viewModel::setDanmakuScale,
                        onOpacity = viewModel::setDanmakuOpacity,
                        onArea = viewModel::setDanmakuArea,
                        onSpeed = viewModel::setDanmakuSpeed,
                        onToggleType = viewModel::toggleDanmakuType,
                        onReset = viewModel::resetDanmakuSettings,
                    )
                }

                SettingsPage.Account -> {
                    SectionTitle("账户")
                    InfoRow(
                        "当前状态",
                        if (isLoggedIn) {
                            "已登录" + (if (uname.isNotEmpty()) "（$uname）" else "")
                        } else {
                            "未登录"
                        },
                    )
                    Spacer(Modifier.height(12.dp))

                    // Saved accounts, one tap to switch. The cookie is kept, so
                    // this does not re-run the login flow — that is the whole
                    // point of the list.
                    if (savedAccounts.isNotEmpty()) {
                        Text(
                            text = "已保存的账户",
                            color = AppTheme.colors.onBackgroundSecondary,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        savedAccounts.forEach { account ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (account.mid == activeMid) {
                                            YtColors.Red.copy(alpha = 0.12f)
                                        } else {
                                            AppTheme.colors.surfaceElevated
                                        },
                                    )
                                    .clickable(enabled = account.mid != activeMid) {
                                        viewModel.switchToAccount(account.mid)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AsyncImage(
                                    model = account.face.toSecureUrl(),
                                    contentDescription = account.uname,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(AppTheme.colors.chipBackground),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = account.uname.ifEmpty { "UID ${account.mid}" },
                                        color = AppTheme.colors.onBackground,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = if (account.mid == activeMid) {
                                            "当前使用中"
                                        } else {
                                            "点击切换（无需重新登录）"
                                        },
                                        color = AppTheme.colors.onBackgroundSecondary,
                                        fontSize = 11.sp,
                                    )
                                }
                                if (account.mid == activeMid) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "当前账户",
                                        tint = YtColors.Red,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(AppTheme.colors.chipBackground)
                            .clickable { page = SettingsPage.Root; onAddAccount() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "添加另一个账户",
                            color = YtColors.Red,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(AppTheme.colors.chipBackground)
                            .clickable(enabled = isLoggedIn) { showLogoutDialog = true }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "退出登录",
                            color = AppTheme.colors.onBackground,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "切换账户会退出当前账号。你可以选择保留本机的偏好设置，" +
                            "或一并清除。B 站不支持同时登录多个账号，因此需要重新登录。",
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                    )
                }

                SettingsPage.About -> {
                    SectionTitle("关于")
                    InfoRow("应用", "YtBili（哔哩视界）")
                    InfoRow("包名", "com.ytstyle.bili")
                    InfoRow("版本", BuildConfig.VERSION_NAME)
                    InfoRow("构建", if (BuildConfig.DEBUG) "debug" else "release")
                    InfoRow("开源地址", "github.com/ke25019/YtBili")

                    Spacer(Modifier.height(16.dp))
                    SectionTitle("更新")
                    Text(
                        text = "更新渠道",
                        color = AppTheme.colors.onBackground,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "稳定版只收正式版，测试版包含标记为 beta 的版本。" +
                            "切换渠道不会自动下载或安装。",
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    UpdateChannel.entries.forEach { option ->
                        PickRow(
                            label = option.label,
                            selected = option == updateChannel,
                            onClick = { viewModel.setUpdateChannel(option) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    SwitchRow(
                        "自动更新",
                        if (autoUpdate) {
                            "启动时自动检查新版本"
                        } else {
                            "不自动检查，可随时手动检查"
                        },
                        autoUpdate,
                        viewModel::setAutoUpdate,
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(AppTheme.colors.chipBackground)
                            .clickable { onCheckUpdate() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "检查更新",
                            color = YtColors.Red,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    SectionTitle("日志")
                    InfoRow("当前大小", "%.1f KB".format(logSizeKb))
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(AppTheme.colors.chipBackground)
                            .clickable {
                                // Say so when there is nothing to send rather
                                // than opening an empty share sheet.
                                val intent = AppLog.buildShareIntent(context)
                                if (intent == null) {
                                    logEmpty = true
                                } else {
                                    runCatching {
                                        context.startActivity(
                                            Intent.createChooser(intent, "导出日志"),
                                        )
                                    }
                                }
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "导出日志",
                            color = YtColors.Red,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(AppTheme.colors.chipBackground)
                            .clickable { showClearLogDialog = true }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "清除日志",
                            color = AppTheme.colors.onBackground,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (logEmpty) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "还没有日志内容：先用一会儿再导出。",
                            color = AppTheme.colors.onBackgroundSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    if (logCleared) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "日志已清除。",
                            color = AppTheme.colors.onBackgroundSecondary,
                            fontSize = 11.sp,
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "第三方 B 站客户端，与官方客户端共存，不含任何广告或统计 SDK。",
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "本项目为个人学习用途，与哔哩哔哩官方无关。" +
                            "所有内容版权归原作者及哔哩哔哩所有。",
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showSwitchDialog) {
        AlertDialog(
            onDismissRequest = { showSwitchDialog = false },
            containerColor = AppTheme.colors.surfaceElevated,
            title = { Text("切换账户", color = AppTheme.colors.onBackground) },
            text = {
                Text(
                    text = "是否保留本机已有的设置（主题、弹幕偏好等）？" +
                        "收藏、历史、稍后再看都存在 B 站服务器上，不受影响。",
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSwitchDialog = false
                    viewModel.switchAccount(keepLocalData = true)
                    onLoggedOut()
                }) { Text("保留设置", color = YtColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSwitchDialog = false
                    viewModel.switchAccount(keepLocalData = false)
                    onLoggedOut()
                }) { Text("一并清除", color = AppTheme.colors.onBackgroundSecondary) }
            },
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            containerColor = AppTheme.colors.surfaceElevated,
            title = { Text("退出登录", color = AppTheme.colors.onBackground) },
            text = {
                Text(
                    text = "退出后将无法使用点赞、投币、收藏与评论，收藏和历史仍保存在 B 站服务器上。",
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.logout()
                    onLoggedOut()
                    showLogoutDialog = false
                }) { Text("退出", color = YtColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消", color = AppTheme.colors.onBackgroundSecondary)
                }
            },
        )
    }

    if (showClearLogDialog) {
        AlertDialog(
            onDismissRequest = { showClearLogDialog = false },
            containerColor = AppTheme.colors.surfaceElevated,
            title = { Text("清除日志", color = AppTheme.colors.onBackground) },
            text = {
                Text(
                    text = "将删除全部运行日志。遇到问题时请先导出，再清除。",
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppLog.clear()
                    logSizeKb = AppLog.sizeBytes() / 1024f
                    logCleared = true
                    logEmpty = false
                    showClearLogDialog = false
                }) { Text("清除", color = YtColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showClearLogDialog = false }) {
                    Text("取消", color = AppTheme.colors.onBackgroundSecondary)
                }
            },
        )
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = AppTheme.colors.onBackground, fontSize = 15.sp)
            Text(
                text = subtitle,
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = AppTheme.colors.onBackgroundSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = YtColors.Red,
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
    )
}

/**
 * One autoplay policy.
 *
 * Shows the consequence of each choice rather than only its name, because
 * "Wi-Fi/移动网络" and "仅 Wi-Fi" differ solely in what they cost.
 */
@Composable
private fun AutoplayOption(mode: AutoplayMode, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) AppTheme.colors.chipSelected else AppTheme.colors.surfaceElevated,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mode.label,
                color = if (selected) {
                    AppTheme.colors.chipSelectedContent
                } else {
                    AppTheme.colors.onBackground
                },
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
            Text(
                text = mode.detail,
                color = if (selected) {
                    AppTheme.colors.chipSelectedContent.copy(alpha = 0.7f)
                } else {
                    AppTheme.colors.onBackgroundSecondary
                },
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = AppTheme.colors.chipSelectedContent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** A single-select row: the label, plus a tick when it is the current choice. */
@Composable
private fun PickRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) AppTheme.colors.chipSelected else AppTheme.colors.surfaceElevated,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected) {
                AppTheme.colors.chipSelectedContent
            } else {
                AppTheme.colors.onBackground
            },
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = AppTheme.colors.chipSelectedContent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ThemeOption(mode: ThemeMode, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) AppTheme.colors.chipSelected else AppTheme.colors.surfaceElevated,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = mode.label,
            color = if (selected) {
                AppTheme.colors.chipSelectedContent
            } else {
                AppTheme.colors.onBackground
            },
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = AppTheme.colors.chipSelectedContent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}


@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = AppTheme.colors.onBackground, fontSize = 14.sp)
            Text(
                text = subtitle,
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = YtColors.Red,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = AppTheme.colors.divider,
            ),
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, color = AppTheme.colors.onBackground, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(
                text = display(value),
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
            )
        }
        CompactSlider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = YtColors.Red,
                activeTrackColor = YtColors.Red,
                inactiveTrackColor = AppTheme.colors.divider,
            ),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = AppTheme.colors.onBackgroundSecondary, fontSize = 13.sp)
        Spacer(Modifier.width(16.dp))
        Text(text = value, color = AppTheme.colors.onBackground, fontSize = 13.sp)
    }
}
