package com.ytstyle.bili.ui.cast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytstyle.bili.data.cast.DlnaRenderer
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/**
 * The cast sheet: pick a device, then watch the connection state.
 *
 * [sourceUrl] and [headers] come from the player — the progressive stream and
 * the media headers the CDN requires. Discovery starts as soon as the dialog
 * opens, because a cast sheet that needs a second tap to look for devices feels
 * broken.
 *
 * [isResolvingSource] is true while the player is still asking for that
 * progressive URL. Picking a device with nothing to send would only produce
 * "没有可投屏的单流地址", so the rows wait instead.
 */
@Composable
fun CastDialog(
    sourceUrl: String,
    title: String,
    headers: Map<String, String>,
    onDismiss: () -> Unit,
    isResolvingSource: Boolean = false,
    viewModel: CastViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (state.devices.isEmpty() && !state.isCasting) viewModel.discover()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.colors.surfaceElevated,
        title = {
            Text(
                text = "投屏",
                style = MaterialTheme.typography.titleMedium,
                color = AppTheme.colors.onBackground,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when {
                    state.isCasting -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Cast,
                                contentDescription = null,
                                tint = YtColors.Red,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "正在投屏到",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppTheme.colors.onBackgroundSecondary,
                                )
                                Text(
                                    text = state.activeDeviceName.ifBlank { "电视" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppTheme.colors.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "由手机中转播放，请保持本应用在前台或后台运行，并让手机留在同一 Wi-Fi。",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.onBackgroundSecondary,
                        )
                    }

                    state.devices.isNotEmpty() -> {
                        if (sourceUrl.isBlank() && isResolvingSource) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    color = YtColors.Red,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "正在解析可投屏的地址…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppTheme.colors.onBackgroundSecondary,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        state.devices.forEach { renderer ->
                            DeviceRow(
                                renderer = renderer,
                                enabled = sourceUrl.isNotBlank(),
                                onClick = {
                                    viewModel.cast(
                                        renderer = renderer,
                                        sourceUrl = sourceUrl,
                                        title = title,
                                        headers = headers,
                                    )
                                },
                            )
                        }
                    }

                    state.isDiscovering -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = YtColors.Red,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "正在搜索同一 Wi-Fi 下的设备…",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.onBackgroundSecondary,
                        )
                    }

                    else -> Text(
                        text = state.error ?: "没有找到可投屏的设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.onBackgroundSecondary,
                    )
                }

                // An error after a list is shown belongs under the list, not
                // instead of it.
                if (state.error != null && state.devices.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = state.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = YtColors.Red,
                    )
                }
            }
        },
        confirmButton = {
            if (state.isCasting) {
                TextButton(onClick = { viewModel.stop() }) {
                    Text(text = "停止投屏", color = YtColors.Red)
                }
            } else {
                TextButton(onClick = { viewModel.discover() }) {
                    Text(text = "重新搜索", color = YtColors.Red)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "关闭", color = AppTheme.colors.onBackgroundSecondary)
            }
        },
    )
}

@Composable
private fun DeviceRow(
    renderer: DlnaRenderer,
    onClick: () -> Unit,
    /** False while the stream URL is still being resolved. */
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Cast,
                contentDescription = null,
                tint = if (enabled) {
                    AppTheme.colors.onBackgroundSecondary
                } else {
                    AppTheme.colors.onBackgroundSecondary.copy(alpha = 0.4f)
                },
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = renderer.name.ifBlank { "未命名设备" },
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                AppTheme.colors.onBackground
            } else {
                AppTheme.colors.onBackgroundSecondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
