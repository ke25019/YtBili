package com.ytstyle.bili.ui.search

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ytstyle.bili.data.net.ArticleSearchItem
import com.ytstyle.bili.data.net.LiveRoomSearchItem
import com.ytstyle.bili.data.net.MediaSearchItem
import com.ytstyle.bili.data.net.SearchResultItem
import com.ytstyle.bili.data.net.UserSearchItem
import com.ytstyle.bili.ui.components.formatCount
import com.ytstyle.bili.ui.components.parseDurationString
import com.ytstyle.bili.ui.components.relativeTime
import com.ytstyle.bili.ui.components.ThumbnailDuration
import com.ytstyle.bili.ui.components.toSecureUrl
import com.ytstyle.bili.ui.components.toThumbUrl
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/**
 * Search with Bilibili's own partition model — hot words before typing,
 * typeahead while typing, then per-partition results — rendered in this app's
 * visual language.
 */
@Composable
fun SearchScreen(
    onVideoClick: (String) -> Unit,
    onBack: () -> Unit,
    onUserClick: (Long) -> Unit = {},
    onLiveClick: (Long) -> Unit = {},
    /** 番剧 / 影视: the season id, played by this app's own player. */
    onMediaClick: (Long) -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        SearchBar(
            query = state.query,
            onQueryChange = viewModel::onQueryChange,
            onSubmit = {
                keyboard?.hide()
                viewModel.search()
            },
            onBack = onBack,
            onClear = viewModel::clear,
        )

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = YtColors.Red)
            }

            state.showHotwords -> SearchLanding(
                history = state.history,
                hotwords = state.hotwords.map { it.showName.ifBlank { it.keyword } },
                onSelect = viewModel::onTermSelected,
                onRemoveHistory = viewModel::removeHistory,
                onClearHistory = viewModel::clearHistory,
            )

            state.showSuggestions -> SuggestionList(
                suggestions = state.suggestions.map { it.value.ifBlank { it.term } },
                onSelect = viewModel::onTermSelected,
            )

            state.hasSearched -> {
                PartitionTabs(
                    selected = state.partition,
                    onSelect = viewModel::onPartitionChange,
                )

                val error = state.error
                if (error != null) {
                    Box(
                        Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(error, color = AppTheme.colors.onBackgroundSecondary)
                    }
                } else if (state.isCurrentPartitionEmpty) {
                    Box(
                        Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("没有找到相关内容", color = AppTheme.colors.onBackgroundSecondary)
                    }
                } else {
                    PartitionResults(
                        state = state,
                        onVideoClick = onVideoClick,
                        onUserClick = onUserClick,
                        onLiveClick = onLiveClick,
                        onMediaClick = onMediaClick,
                        onSortChange = viewModel::onSortChange,
                        onDurationChange = viewModel::onDurationChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun PartitionResults(
    state: SearchUiState,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onLiveClick: (Long) -> Unit,
    onMediaClick: (Long) -> Unit,
    onSortChange: (SortOption) -> Unit,
    onDurationChange: (DurationOption) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        if (state.showFilters) {
            item {
                FilterRow(
                    sort = state.sort,
                    duration = state.duration,
                    onSortChange = onSortChange,
                    onDurationChange = onDurationChange,
                )
            }
        }

        when (state.partition) {
            SearchPartition.Video -> items(state.videos, key = { it.bvid }) { item ->
                VideoResultRow(item = item, onClick = { onVideoClick(item.bvid) })
            }

            SearchPartition.Bangumi, SearchPartition.Film ->
                items(state.media, key = { it.mediaId }) { item ->
                    MediaResultRow(item = item, onClick = { onMediaClick(item.seasonId) })
                }

            SearchPartition.User -> items(state.users, key = { it.mid }) { item ->
                UserResultRow(item = item, onClick = { onUserClick(item.mid) })
            }

            SearchPartition.Article -> items(state.articles, key = { it.id }) { item ->
                ArticleResultRow(item = item)
            }

            SearchPartition.Live -> items(state.liveRooms, key = { it.roomid }) { item ->
                LiveResultRow(item = item, onClick = { onLiveClick(item.roomid) })
            }
        }
    }
}

/** Bilibili's partition tabs, as chips rather than a scrollable strip. */
@Composable
private fun PartitionTabs(selected: SearchPartition, onSelect: (SearchPartition) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(SearchPartition.entries.toList()) { partition ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (partition == selected) {
                            AppTheme.colors.chipSelected
                        } else {
                            AppTheme.colors.chipBackground
                        },
                    )
                    .clickable { onSelect(partition) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    text = partition.label,
                    color = if (partition == selected) {
                        AppTheme.colors.chipSelectedContent
                    } else {
                        AppTheme.colors.onBackground
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.background)
            .statusBarsPadding()
            // Kept deliberately tight: the field used to be a full-height
            // TextField with generous padding, which looked oversized.
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = AppTheme.colors.onBackground,
            )
        }

        // A plain `BasicTextField` in a hand-built pill, **not** Material3's
        // `TextField`.
        //
        // `TextField` wraps its input in a decoration box with a 56dp minimum
        // height and 16dp of internal vertical padding. Constraining that to the
        // 44dp this bar needs made the box taller than its container, so the
        // text was drawn with its lower half cut off — typing showed "一 half of
        // each glyph" and the placeholder was clipped horizontally too. Owning
        // the layout removes the failure mode instead of tuning around it.
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(AppTheme.colors.chipBackground),
            textStyle = TextStyle(fontSize = 14.sp, color = AppTheme.colors.onBackground),
            singleLine = true,
            cursorBrush = SolidColor(YtColors.Red),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = AppTheme.colors.onBackgroundSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索视频、番剧、用户、直播",
                                color = AppTheme.colors.onBackgroundSecondary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                // Ellipsised rather than sliced in half when the
                                // pill is narrow.
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                    if (query.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "清除",
                            tint = AppTheme.colors.onBackgroundSecondary,
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onClear),
                        )
                    }
                }
            },
        )

        Text(
            text = "搜索",
            // Dimming the label is the only affordance that an empty query
            // cannot be submitted, since the row is not a disabled button.
            color = if (query.isBlank()) AppTheme.colors.onBackgroundSecondary else YtColors.Red,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            modifier = Modifier
                .clickable(enabled = query.isNotBlank(), onClick = onSubmit)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * The pre-search landing state: remembered terms on top, trending terms below,
 * in one scrollable list so the two do not fight over the viewport.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchLanding(
    history: List<String>,
    hotwords: List<String>,
    onSelect: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    if (history.isEmpty() && hotwords.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("输入关键词开始搜索", color = AppTheme.colors.onBackgroundSecondary)
        }
        return
    }

    // Both dialogs are driven by local state: they are pure confirmations, so
    // nothing needs to survive recomposition or reach the ViewModel.
    var entryPendingDelete by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (history.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "搜索历史",
                        style = MaterialTheme.typography.titleMedium,
                        color = AppTheme.colors.onBackground,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "删除全部",
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { showClearDialog = true },
                    )
                }
            }
            items(history) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onSelect(entry) },
                            onLongClick = { entryPendingDelete = entry },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = null,
                        tint = AppTheme.colors.onBackgroundSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = entry,
                        color = AppTheme.colors.onBackground,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "删除",
                        tint = AppTheme.colors.onBackgroundSecondary,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onRemoveHistory(entry) },
                    )
                }
            }
        }

        hotwordItems(hotwords = hotwords, onSelect = onSelect)
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = AppTheme.colors.surfaceElevated,
            title = { Text("清空搜索历史", color = AppTheme.colors.onBackground) },
            text = {
                Text(
                    text = "将删除本机保存的全部搜索记录。",
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    onClearHistory()
                }) { Text("删除", color = YtColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消", color = AppTheme.colors.onBackgroundSecondary)
                }
            },
        )
    }

    entryPendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { entryPendingDelete = null },
            containerColor = AppTheme.colors.surfaceElevated,
            title = { Text("删除这条搜索记录？", color = AppTheme.colors.onBackground) },
            text = {
                Text(
                    text = target,
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    entryPendingDelete = null
                    onRemoveHistory(target)
                }) { Text("删除", color = YtColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { entryPendingDelete = null }) {
                    Text("取消", color = AppTheme.colors.onBackgroundSecondary)
                }
            },
        )
    }
}

/**
 * Emits the "B 站热搜" header and its rows into the caller's [LazyListScope]:
 * a nested LazyColumn is not allowed, so the shared landing list needs the
 * hot words as items rather than as their own list composable.
 */
private fun LazyListScope.hotwordItems(hotwords: List<String>, onSelect: (String) -> Unit) {
    if (hotwords.isEmpty()) return

    item {
        Text(
            text = "B 站热搜",
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
    itemsIndexed(hotwords) { index, word ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(word) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${index + 1}",
                color = if (index < 3) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
                fontWeight = if (index < 3) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                modifier = Modifier.width(28.dp),
            )
            Text(
                text = word,
                color = AppTheme.colors.onBackground,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SuggestionList(suggestions: List<String>, onSelect: (String) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(suggestions) { suggestion ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(suggestion) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = AppTheme.colors.onBackgroundSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = suggestion,
                    color = AppTheme.colors.onBackground,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FilterRow(
    sort: SortOption,
    duration: DurationOption,
    onSortChange: (SortOption) -> Unit,
    onDurationChange: (DurationOption) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SortOption.entries.toList()) { option ->
                FilterChip(
                    label = option.label,
                    selected = option == sort,
                    onClick = { onSortChange(option) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(DurationOption.entries.toList()) { option ->
                FilterChip(
                    label = option.label,
                    selected = option == duration,
                    onClick = { onDurationChange(option) },
                )
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) AppTheme.colors.chipSelected else AppTheme.colors.chipBackground,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            color = if (selected) {
                AppTheme.colors.chipSelectedContent
            } else {
                AppTheme.colors.onBackground
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Shared thumbnail + text layout for every result type. */
@Composable
private fun ResultRow(
    thumbnail: String,
    durationBadge: String?,
    title: String,
    onClick: (() -> Unit)?,
    trailingBadge: (@Composable () -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(168.dp)
                .height(95.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AppTheme.colors.surfaceElevated),
        ) {
            AsyncImage(
                model = thumbnail.toThumbUrl(),
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
            )
            if (!durationBadge.isNullOrEmpty()) {
                // Single-column result rows show the digits alone; see
                // ThumbnailDuration for why the shadow stays.
                ThumbnailDuration(
                    text = durationBadge,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
            }
            trailingBadge?.let { badge ->
                Box(modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) { badge() }
            }
        }

        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) { body() }
    }
}

@Composable
private fun VideoResultRow(item: SearchResultItem, onClick: () -> Unit) {
    val seconds = parseDurationString(item.duration)
    ResultRow(
        thumbnail = item.pic,
        durationBadge = if (seconds > 0) formatClock(seconds) else null,
        title = item.title,
        onClick = onClick,
    ) {
        Text(
            text = item.title.stripHighlight(),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        AuthorLine(avatar = item.uface, name = item.author)
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append(formatCount(item.play))
                append("次播放 · ")
                append(formatCount(item.danmaku))
                append("弹幕")
                if (item.pubdate > 0) {
                    append(" · ")
                    append(relativeTime(item.pubdate))
                }
            },
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A PGC entry (番剧 / 影视).
 *
 * Tapping plays it in this app's own player. The season id is what the PGC
 * endpoints are keyed by; the browser hand-off is kept only as a fallback for
 * the rare entry that carries no season id, because a result that cannot be
 * played is still better than a dead row.
 */
@Composable
private fun MediaResultRow(item: MediaSearchItem, onClick: () -> Unit) {
    val context = LocalContext.current
    ResultRow(
        thumbnail = item.cover,
        durationBadge = item.indexShow.ifEmpty { null },
        title = item.title.stripHighlight(),
        onClick = {
            if (item.seasonId > 0) {
                onClick()
            } else if (item.gotoUrl.isNotEmpty()) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.gotoUrl)))
                }
            }
        },
        trailingBadge = {
            val score = item.mediaScore?.score ?: 0.0
            if (score > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text(text = "%.1f".format(score), color = Color(0xFFFFC107), fontSize = 10.sp)
                }
            }
        },
    ) {
        Text(
            text = item.title.stripHighlight(),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = listOf(item.seasonTypeName, item.areas, item.styles)
                .filter { it.isNotEmpty() }
                .joinToString(" · "),
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.desc,
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UserResultRow(item: UserSearchItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.upic.toSecureUrl(),
            contentDescription = item.uname,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.surfaceElevated),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.uname.stripHighlight(),
                    color = AppTheme.colors.onBackground,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val verify = item.officialVerify
                if (verify != null && verify.type >= 0) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.Verified,
                        contentDescription = verify.desc,
                        tint = YtColors.Red,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${formatCount(item.fans)}粉丝 · ${formatCount(item.videos)}投稿 · Lv${item.level}",
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
            )
            if (item.usign.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.usign.stripHighlight(),
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ArticleResultRow(item: ArticleSearchItem) {
    ResultRow(
        thumbnail = item.cover,
        durationBadge = null,
        title = item.title.stripHighlight(),
        onClick = null,
    ) {
        Text(
            text = item.title.stripHighlight(),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.desc,
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${item.author} · ${formatCount(item.view)}阅读 · ${formatCount(item.like)}点赞",
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LiveResultRow(item: LiveRoomSearchItem, onClick: () -> Unit) {
    ResultRow(
        thumbnail = item.cover,
        durationBadge = null,
        title = item.title,
        onClick = onClick,
        trailingBadge = {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(YtColors.Red)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.LiveTv,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(10.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = if (item.liveStatus == 1) "直播中" else "未开播",
                    color = Color.White,
                    fontSize = 10.sp,
                )
            }
        },
    ) {
        Text(
            text = item.title.stripHighlight(),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        AuthorLine(avatar = item.uface, name = item.uname)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${formatCount(item.online)}人气 · ${item.cateName.ifEmpty { "直播" }}",
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AuthorLine(avatar: String, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = avatar.toSecureUrl(),
            contentDescription = name,
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.surfaceElevated),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = name.stripHighlight(),
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Strips `<em class="keyword">…</em>` and any other markup Bilibili injects. */
internal fun String.stripHighlight(): String = replace(Regex("<[^>]*>"), "")

/** Search durations can exceed an hour without an hour field, e.g. "266:42". */
private fun formatClock(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
