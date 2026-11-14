package com.ytstyle.bili.ui.dynamic

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ytstyle.bili.data.net.DynamicEntry
import com.ytstyle.bili.data.net.DynamicKind
import com.ytstyle.bili.data.net.Reply
import com.ytstyle.bili.ui.components.ImageViewer
import com.ytstyle.bili.ui.components.formatCount
import com.ytstyle.bili.ui.components.toSecureUrl
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The 图文动态 reading page.
 *
 * Deliberately its own screen rather than an expanded card: the feed is a
 * scanning surface, and reading a long post with several pictures needs the
 * whole window. The card the user tapped is painted immediately and the network
 * refines it afterwards, so opening never shows a blank spinner over content the
 * app already had.
 */
@Composable
fun DynamicDetailScreen(
    onBack: () -> Unit,
    onOpenSpace: (Long) -> Unit,
    viewModel: DynamicDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entry = state.entry
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var viewerAt by remember { mutableStateOf<Int?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    // Where the comment section starts, so the bottom bar's 评论 button has a
    // real destination instead of a decorative one.
    val commentAnchor = entry?.let { shown ->
        val hasText = shown.text.isNotBlank()
        val hasForward = shown.kind == DynamicKind.FORWARD && shown.forwardText.isNotBlank()
        val hasImages = shown.imageUrls.isNotEmpty()
        1 + (if (hasText) 1 else 0) + (if (hasForward) 1 else 0) +
            (if (hasImages) 1 else 0) + 2
    } ?: 0

    Box(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = AppTheme.colors.onBackground,
                    )
                }
                Text(
                    text = "动态",
                    color = AppTheme.colors.onBackground,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "更多",
                            tint = AppTheme.colors.onBackground,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("复制链接") },
                            onClick = {
                                menuOpen = false
                                entry?.let { copyLink(context, it) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("在浏览器打开") },
                            onClick = {
                                menuOpen = false
                                entry?.let { openInBrowser(context, it) }
                            },
                        )
                    }
                }
            }

            if (entry == null) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = YtColors.Red, strokeWidth = 2.dp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    // The action bar floats over the end of the list, and it now
                    // includes the navigation-bar inset, so the padding has to
                    // cover both.
                    contentPadding = PaddingValues(bottom = 104.dp),
                ) {
                    item(key = "author") {
                        AuthorRow(entry = entry, onOpenSpace = onOpenSpace)
                    }
                    if (entry.text.isNotBlank()) {
                        item(key = "text") { BodyText(entry.text) }
                    }
                    if (entry.kind == DynamicKind.FORWARD && entry.forwardText.isNotBlank()) {
                        item(key = "forward") { ForwardBlock(entry) }
                    }
                    if (entry.imageUrls.isNotEmpty()) {
                        item(key = "images") {
                            DynamicImageGrid(
                                urls = entry.imageUrls,
                                onImageClick = { index -> viewerAt = index },
                            )
                        }
                    }
                    item(key = "stats") {
                        StatsRow(entry = entry, likeCount = state.likeCount, liked = state.liked)
                    }
                    item(key = "divider") {
                        HorizontalDivider(
                            color = AppTheme.colors.divider,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    item(key = "comments-header") {
                        Text(
                            text = if (entry.commentCount > 0) {
                                "评论 ${formatCount(entry.commentCount)}"
                            } else {
                                "评论"
                            },
                            color = AppTheme.colors.onBackground,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    if (state.commentsLoading) {
                        item(key = "comments-loading") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = YtColors.Red,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    } else if (state.commentsError != null) {
                        item(key = "comments-error") {
                            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(
                                    text = state.commentsError.orEmpty(),
                                    color = AppTheme.colors.onBackgroundSecondary,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    text = "重试",
                                    color = YtColors.Red,
                                    fontSize = 13.sp,
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .clickable { viewModel.retryComments() },
                                )
                            }
                        }
                    } else if (state.comments.isEmpty()) {
                        item(key = "comments-empty") {
                            Text(
                                text = "还没有评论",
                                color = AppTheme.colors.onBackgroundSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                            )
                        }
                    } else {
                        items(items = state.comments, key = { it.rpid }) { comment ->
                            CommentRow(comment)
                        }
                    }
                }
            }
        }

        if (entry != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(AppTheme.colors.surfaceElevated)
                    // The gesture bar and the three-button navigation bar draw
                    // over this row otherwise, so the bottom tap targets sat
                    // underneath the system's own buttons — the "图文 page fights
                    // the navigation bar" report.
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(
                    icon = if (state.liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    label = formatCount(state.likeCount).ifBlank { "点赞" },
                    tint = if (state.liked) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
                    onClick = viewModel::toggleLike,
                )
                ActionButton(
                    icon = Icons.AutoMirrored.Filled.Comment,
                    label = "评论",
                    tint = AppTheme.colors.onBackgroundSecondary,
                    onClick = { scope.launch { listState.animateScrollToItem(commentAnchor) } },
                )
                ActionButton(
                    icon = Icons.Filled.Share,
                    label = "分享",
                    tint = AppTheme.colors.onBackgroundSecondary,
                    onClick = { shareEntry(context, entry) },
                )
                Spacer(Modifier.weight(1f))
            }
        }

        // A toast-like notice from a failed like, inside the root Box so it can
        // be anchored above the action bar.
        state.notice?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp),
            ) {
                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .clickable { viewModel.dismissNotice() },
                )
            }
        }
    }

    viewerAt?.let { start ->
        ImageViewer(
            urls = entry?.imageUrls.orEmpty().map { it.toSecureUrl() },
            startIndex = start,
            onDismiss = { viewerAt = null },
        )
    }
}

@Composable
private fun AuthorRow(entry: DynamicEntry, onOpenSpace: (Long) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.authorMid > 0) { onOpenSpace(entry.authorMid) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.chipBackground),
            contentAlignment = Alignment.Center,
        ) {
            val face = entry.authorFace.toSecureUrl()
            if (face.isNotBlank()) {
                AsyncImage(
                    model = face,
                    contentDescription = entry.authorName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(text = entry.authorName.take(1).ifBlank { "U" }, color = AppTheme.colors.onBackground)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = entry.authorName.ifBlank { "未知作者" },
                color = AppTheme.colors.onBackground,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            if (entry.pubTimeLabel.isNotBlank()) {
                Text(
                    text = entry.pubTimeLabel,
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        color = AppTheme.colors.onBackground,
        fontSize = 15.sp,
        // The line height is what keeps a long post readable; the feed's card is
        // deliberately tighter because it is a summary, not the article.
        lineHeight = 23.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun ForwardBlock(entry: DynamicEntry) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppTheme.colors.surfaceElevated)
            .padding(12.dp),
    ) {
        if (entry.forwardAuthorName.isNotBlank()) {
            Text(
                text = "@${entry.forwardAuthorName}",
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = entry.forwardText.ifBlank { "原动态内容不可用" },
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One picture keeps its aspect ratio and the full width; several pictures are
 * laid out on a grid of *fit* cells.
 *
 * Fit rather than Crop on purpose: cropping a 9:16 photo into a square cuts the
 * subject out, and the request was explicitly to preserve the original
 * proportions. The letterbox is the app's own surface colour, so a tall picture
 * reads as a picture rather than a defect.
 */
@Composable
private fun DynamicImageGrid(urls: List<String>, onImageClick: (Int) -> Unit) {
    if (urls.size == 1) {
        AsyncImage(
            model = urls.first().toSecureUrl(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black)
                .clickable { onImageClick(0) },
        )
        return
    }

    val columns = if (urls.size == 2 || urls.size == 4) 2 else 3
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        urls.chunked(columns).forEachIndexed { rowIndex, rowUrls ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowUrls.forEachIndexed { columnIndex, url ->
                    val index = rowIndex * columns + columnIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(110.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(AppTheme.colors.surfaceElevated)
                            .clickable { onImageClick(index) },
                    ) {
                        AsyncImage(
                            model = url.toSecureUrl(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                // Keep the last row aligned with the grid instead of stretching
                // two cells across three columns.
                repeat(columns - rowUrls.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatsRow(entry: DynamicEntry, likeCount: Long, liked: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${formatCount(likeCount)} 点赞",
            color = if (liked) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = "${formatCount(entry.commentCount)} 评论",
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = "${formatCount(entry.forwardCount)} 转发",
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun CommentRow(comment: Reply) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val avatar = comment.member?.avatar.orEmpty().toSecureUrl()
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.chipBackground),
            contentAlignment = Alignment.Center,
        ) {
            if (avatar.isNotBlank()) {
                AsyncImage(
                    model = avatar,
                    contentDescription = comment.member?.uname,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = comment.member?.uname.orEmpty().take(1).ifBlank { "U" },
                    color = AppTheme.colors.onBackground,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.member?.uname.orEmpty().ifBlank { "匿名用户" },
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatCommentTime(comment.ctime),
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = comment.content?.message.orEmpty(),
                color = AppTheme.colors.onBackground,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            if (comment.like > 0) {
                Text(
                    text = "${formatCount(comment.like.toLong())} 赞",
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = label, color = tint, fontSize = 12.sp)
    }
}

private fun formatCommentTime(seconds: Long): String {
    if (seconds <= 0) return ""
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(seconds * 1000))
}

private fun dynamicUrl(entry: DynamicEntry): String = "https://t.bilibili.com/${entry.id}"

private fun copyLink(context: Context, entry: DynamicEntry) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("动态链接", dynamicUrl(entry)))
}

private fun openInBrowser(context: Context, entry: DynamicEntry) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(dynamicUrl(entry))))
    }
}

private fun shareEntry(context: Context, entry: DynamicEntry) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "${entry.text.take(60)}\n${dynamicUrl(entry)}".trim())
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "分享动态")) }
}
