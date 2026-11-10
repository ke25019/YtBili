package com.ytstyle.bili.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

/**
 * The update prompt, in Material 3.
 *
 * Hand-built on [BasicAlertDialog] rather than `AlertDialog` so the four actions
 * can sit stacked at the trailing edge, which is the shape modern clients use and
 * the one asked for: a title, a scrollable changelog, a publication date, a
 * divider, then 关闭自动更新 / 稍后提醒 / 查看详情 / 立即更新 in a trailing column.
 *
 * The changelog is capped at 300dp and scrolls inside that cap: release notes are
 * arbitrary-length Markdown, and an unbounded body would push the actions off the
 * screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateDialog(
    state: UpdateUiState,
    onDismiss: () -> Unit,
    onLater: () -> Unit,
    onDisableAutoUpdate: () -> Unit,
    onOpenDetails: (String) -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    if (!state.visible) return

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = state.release?.let { "发现新版本 v${state.titleVersion}" } ?: "检查更新",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                )

                val release = state.release
                if (release != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = release.body.ifBlank { "该版本没有填写更新说明。" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            // The requested cap: long notes scroll instead of
                            // growing the dialog past the screen.
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "发布时间：${release.publishedDate.ifBlank { "未知" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (state.checking) {
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "正在检查更新…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Outcome line: up to date, timeout, no stable release, download error.
                state.notice?.let { notice ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.noticeIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                if (!state.downloading && state.progress == null) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp,
                    )
                    Spacer(Modifier.height(4.dp))
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (state.downloading || state.progress != null) {
                        // The 立即更新 row becomes the progress bar, per the spec:
                        // the action that was pressed is the one that reports.
                        DownloadProgress(state.progress)
                    }

                    // "关闭自动更新" only makes sense while the silent check is on.
                    if (state.autoUpdate) {
                        TextButton(onClick = onDisableAutoUpdate) {
                            Text("关闭自动更新")
                        }
                    }

                    TextButton(onClick = onLater) {
                        Text(if (state.release != null) "稍后提醒" else "关闭")
                    }

                    if (state.release != null) {
                        TextButton(onClick = { onOpenDetails(state.release.pageUrl) }) {
                            Text("查看详情")
                        }
                        val downloaded = state.downloadedApk != null && !state.downloading
                        if (downloaded) {
                            // The APK is on disk. Say 安装 rather than 立即更新: the
                            // download already happened, and re-downloading it was
                            // exactly the complaint after granting the install
                            // permission (which sends the user out of the app).
                            TextButton(onClick = onInstall) {
                                Text(
                                    text = "安装",
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else if (!state.downloading && state.progress == null) {
                            TextButton(
                                onClick = onDownload,
                                enabled = state.hasMatchingApk,
                            ) {
                                Text(
                                    text = "立即更新",
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The download row: a determinate bar plus the percentage, falling back to an
 * indeterminate bar when the server sent no `Content-Length`.
 */
@Composable
private fun DownloadProgress(progress: Float?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LinearProgressIndicator(
                progress = { progress ?: 0f },
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = progress?.let { "${(it * 100).roundToInt()}%" } ?: "…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "正在下载更新…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
