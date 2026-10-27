package com.ytstyle.bili.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytstyle.bili.data.settings.ThemeMode
import com.ytstyle.bili.ui.player.DanmakuMode
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/** Pages inside settings. Kept as local state so no extra routes are needed. */
private enum class SettingsPage(val title: String) {
    Root("设置"),
    Appearance("外观"),
    Playback("播放"),
    Danmaku("弹幕设置"),
    Account("账户"),
    Experimental("实验功能"),
    About("关于"),
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
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var page by remember { mutableStateOf(SettingsPage.Root) }

    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val danmakuEnabled by viewModel.danmakuEnabled.collectAsStateWithLifecycle()
    val autoplay by viewModel.autoplay.collectAsStateWithLifecycle()
    val keepLandscape by viewModel.keepPlayerLandscape.collectAsStateWithLifecycle()
    val scale by viewModel.danmakuScale.collectAsStateWithLifecycle()
    val opacity by viewModel.danmakuOpacity.collectAsStateWithLifecycle()
    val area by viewModel.danmakuArea.collectAsStateWithLifecycle()
    val speed by viewModel.danmakuSpeed.collectAsStateWithLifecycle()
    val types by viewModel.danmakuTypes.collectAsStateWithLifecycle()
    val homeGrid by viewModel.homeGrid.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()

    var showSwitchDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (page == SettingsPage.Root) onBack() else page = SettingsPage.Root }) {
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
                .padding(horizontal = 16.dp),
        ) {
            when (page) {
                SettingsPage.Root -> {
                    NavRow("外观", "主题模式") { page = SettingsPage.Appearance }
                    NavRow("播放", "弹幕、自动播放、横屏") { page = SettingsPage.Playback }
                    NavRow("弹幕设置", "字号、透明度、显示区域、速度") { page = SettingsPage.Danmaku }
                    NavRow("账户", "切换账户与本地数据") { page = SettingsPage.Account }
                    NavRow("实验功能", "尚未稳定的功能") { page = SettingsPage.Experimental }
                    NavRow("关于", "版本与说明") { page = SettingsPage.About }
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
                    SwitchRow("默认开启弹幕", "进入播放页是否显示弹幕", danmakuEnabled, viewModel::setDanmakuEnabled)
                    SwitchRow("自动播放", "进入播放页后自动开始", autoplay, viewModel::setAutoplay)
                    SwitchRow(
                        "保持横屏",
                        "退出全屏后仍锁定横屏，适合横放观看",
                        keepLandscape,
                        viewModel::setKeepPlayerLandscape,
                    )
                }

                SettingsPage.Danmaku -> {
                    SectionTitle("显示")
                    SliderRow(
                        label = "字号",
                        value = scale,
                        range = 0.5f..1.5f,
                        display = { "%.1fx".format(it) },
                        onChange = viewModel::setDanmakuScale,
                    )
                    SliderRow(
                        label = "不透明度",
                        value = opacity,
                        range = 0.3f..1f,
                        display = { "%.0f%%".format(it * 100) },
                        onChange = viewModel::setDanmakuOpacity,
                    )
                    SliderRow(
                        label = "显示区域",
                        value = area,
                        range = 0.25f..1f,
                        display = { "%.0f%%".format(it * 100) },
                        onChange = viewModel::setDanmakuArea,
                    )
                    SliderRow(
                        label = "滚动速度",
                        value = speed,
                        range = 4f..14f,
                        display = { "%.0f 秒/屏".format(it) },
                        onChange = viewModel::setDanmakuSpeed,
                    )

                    Spacer(Modifier.height(16.dp))
                    SectionTitle("类型")
                    SwitchRow(
                        "滚动弹幕",
                        "从右向左滚动",
                        DanmakuMode.SCROLL in types,
                    ) { viewModel.toggleDanmakuType(DanmakuMode.SCROLL) }
                    SwitchRow(
                        "顶部弹幕",
                        "固定在画面上方",
                        DanmakuMode.TOP in types,
                    ) { viewModel.toggleDanmakuType(DanmakuMode.TOP) }
                    SwitchRow(
                        "底部弹幕",
                        "固定在画面下方",
                        DanmakuMode.BOTTOM in types,
                    ) { viewModel.toggleDanmakuType(DanmakuMode.BOTTOM) }
                }

                SettingsPage.Account -> {
                    SectionTitle("账户")
                    InfoRow("当前状态", if (isLoggedIn) "已登录" else "未登录")
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(AppTheme.colors.chipBackground)
                            .clickable(enabled = isLoggedIn) { showSwitchDialog = true }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "切换账户",
                            color = if (isLoggedIn) {
                                YtColors.Red
                            } else {
                                AppTheme.colors.onBackgroundSecondary
                            },
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

                SettingsPage.Experimental -> {
                    SectionTitle("实验功能")
                    SwitchRow(
                        "首页双列排版",
                        "首页改为两列网格，一屏显示更多内容",
                        homeGrid,
                        viewModel::setHomeGrid,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "这些功能尚未充分验证，可能随时调整。",
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 11.sp,
                    )
                }

                SettingsPage.About -> {
                    SectionTitle("关于")
                    InfoRow("应用", "YtBili（哔哩视界）")
                    InfoRow("包名", "com.ytstyle.bili")
                    InfoRow("版本", "1.4.0")
                    Spacer(Modifier.height(12.dp))
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
        Slider(
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
