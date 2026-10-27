package com.ytstyle.bili.ui.upload

import com.ytstyle.bili.ui.theme.AppTheme

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.ytstyle.bili.ui.history.SimpleTopBar
import com.ytstyle.bili.ui.theme.YtColors

/**
 * Upload entry point.
 *
 * Scope note: this screen selects a file, collects metadata and validates it.
 * It does **not** transmit anything. Bilibili's upload API
 * (`x/vu/web/add/v3` + `x/vu/web/cover/up` + the resumable `preupload` flow) is
 * undocumented, requires WBI signing over a multipart body, and returns
 * `-403` for web sessions that have not passed additional risk checks. Wiring
 * it up without a live account to verify each step would produce code that
 * looks complete but cannot be trusted, so the transfer is left unwired and
 * stated plainly in the UI.
 */
@Composable
fun UploadScreen(
    onBack: () -> Unit,
    viewModel: UploadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let(viewModel::onFileSelected)
    }

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        SimpleTopBar(title = "投稿管理", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            NoticeCard()

            Spacer(Modifier.height(20.dp))

            Text(
                text = "视频文件",
                color = AppTheme.colors.onBackground,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppTheme.colors.surfaceElevated)
                    .clickable { picker.launch("video/*") },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.CloudUpload,
                        contentDescription = null,
                        tint = AppTheme.colors.onBackgroundSecondary,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.fileName.ifEmpty { "选择视频文件" },
                        color = AppTheme.colors.onBackground,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.fileSizeLabel.isNotEmpty()) {
                        Text(
                            text = state.fileSizeLabel,
                            color = AppTheme.colors.onBackgroundSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = "标题",
                color = AppTheme.colors.onBackground,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                placeholder = {
                    Text("填写视频标题", color = AppTheme.colors.onBackgroundSecondary, fontSize = 13.sp)
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = AppTheme.colors.chipBackground,
                    unfocusedContainerColor = AppTheme.colors.chipBackground,
                    focusedTextColor = AppTheme.colors.onBackground,
                    unfocusedTextColor = AppTheme.colors.onBackground,
                    cursorColor = YtColors.Red,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "简介",
                color = AppTheme.colors.onBackground,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = state.description,
                onValueChange = viewModel::onDescriptionChange,
                modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)),
                placeholder = {
                    Text("介绍一下你的视频", color = AppTheme.colors.onBackgroundSecondary, fontSize = 13.sp)
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = AppTheme.colors.chipBackground,
                    unfocusedContainerColor = AppTheme.colors.chipBackground,
                    focusedTextColor = AppTheme.colors.onBackground,
                    unfocusedTextColor = AppTheme.colors.onBackground,
                    cursorColor = YtColors.Red,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )

            Spacer(Modifier.height(24.dp))

            // The submit button is present but permanently disabled, because
            // no upload transport exists behind it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppTheme.colors.chipBackground)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(
                        color = YtColors.Red,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = "投稿功能尚未接入",
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            state.error?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(text = message, color = YtColors.Red, fontSize = 12.sp)
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun NoticeCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.colors.surfaceElevated)
            .padding(14.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = AppTheme.colors.onBackgroundSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = "当前版本可以填写投稿信息，但不会真正上传到 B 站。" +
                "B 站投稿接口未公开且需要额外风控校验，接入前无法可靠工作。",
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}
