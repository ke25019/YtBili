package com.ytstyle.bili.ui.home

import com.ytstyle.bili.ui.theme.AppTheme

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytstyle.bili.data.settings.HomePartition
import com.ytstyle.bili.ui.components.VideoCard
import com.ytstyle.bili.ui.theme.YtColors

/**
 * The home feed.
 *
 * Three behaviours were added on top of the previous implementation:
 *
 *  - **Pull to refresh.** Dragging from the top replaces the feed instead of
 *    appending, which is what "在首页上滑触发刷新" needs: at the top of the
 *    list an upward drag has nowhere to scroll, so the gesture is free. The
 *    endless "load more" side keeps working underneath.
 *  - **A continuous feed.** While this screen is in the foreground the view
 *    model appends a page a minute, so the feed keeps filling without the user
 *    having to reach the bottom.
 *  - **An editable chip row.** The chips are a list the user owns: the row has
 *    an edit mode for removing, reordering and adding partitions, so the home
 *    page can be narrowed to the regions someone actually watches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onVideoClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    /**
     * Called with `true` when the feed scrolls down and `false` when it scrolls
     * back up. The shell fades its own top and bottom bars on it, in landscape
     * only; the default no-op keeps the screen usable on its own.
     */
    onChromeHiddenChange: (Boolean) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // One scroll position per partition.
    //
    // These used to be single instances shared by every chip, so scrolling 全部
    // carried every other partition along with it — the reported symptom — and
    // switching chips dropped you wherever the previous one had left the shared
    // state. Holding one state per rid (null is 全部) gives each partition its own
    // place; the maps keep those states alive for as long as this screen is in the
    // back stack, which is when the user comes back to a partition.
    val listStates = remember { mutableMapOf<Int?, LazyListState>() }
    val gridStates = remember { mutableMapOf<Int?, LazyGridState>() }
    val listState = listStates.getOrPut(state.selectedRid) { LazyListState() }
    val gridState = gridStates.getOrPut(state.selectedRid) { LazyGridState() }

    // Scroll direction for the shell's landscape chrome.
    //
    // The first visible item (index plus offset) is enough to tell down from up,
    // and both layouts report it, so no custom NestedScrollConnection is needed.
    // Re-keyed on the selected partition: each one owns a separate list state, and
    // watching the old one would report the previous partition's scrolls.
    LaunchedEffect(listState, state.selectedRid) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            if (index != previousIndex || offset != previousOffset) {
                val down = index > previousIndex ||
                    (index == previousIndex && offset > previousOffset)
                // The very top always keeps the bars: there is nowhere further
                // up to scroll to bring them back.
                onChromeHiddenChange(down && index > 0)
            }
            previousIndex = index
            previousOffset = offset
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Two columns only where they fit. A 320dp screen leaves each cell about
    // 148dp wide, which is narrower than the title and metadata the card draws
    // and makes the 16:9 thumbnail smaller than its own duration badge, so the
    // grid drops to a single column instead of squeezing two cards in.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val gridColumns = if (screenWidthDp < NARROW_SCREEN_DP) {
        GridCells.Fixed(1)
    } else {
        GridCells.Fixed(2)
    }

    // YouTube prefetches the next page well before the user reaches the end.
    //
    // The feed scrolls in whichever layout the experimental toggle picks, so the
    // prefetch must read the state of the layout that is actually on screen.
    // Watching only `listState` meant the two-column grid never asked for more
    // videos: the grid has its own state, and the list's stayed at index 0
    // forever, so `shouldLoadMore` could never become true.
    val shouldLoadMore by remember {
        derivedStateOf {
            // The two layout-info types share no common supertype, so the branch
            // has to select the numbers rather than the info object — otherwise
            // the `if` widens to `Any` and neither property resolves.
            val last: Int
            val total: Int
            if (state.useGrid) {
                last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                total = gridState.layoutInfo.totalItemsCount
            } else {
                last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                total = listState.layoutInfo.totalItemsCount
            }
            total > 0 && last >= total - 3
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && state.items.isNotEmpty() && !state.reachedEnd) viewModel.load()
    }

    // The auto-feed only runs while the home page is actually on screen.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setAutoFeedActive(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.setAutoFeedActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // The observer is registered *after* the lifecycle has already reached
        // RESUMED, so the first ON_RESUME has been and gone. Without this the
        // continuous feed would only arm on the second visit to the tab.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.setAutoFeedActive(true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setAutoFeedActive(false)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        // The row is the user's configured partition list now: each chip fetches
        // its own region instead of refetching the generic feed, and the same row
        // edits that list in place. The editor writes straight through to the
        // partition store, so the order survives a restart without this screen
        // holding any of it.
        ChipRow(
            partitions = state.partitions,
            selectedRid = state.selectedRid,
            editing = state.editing,
            addable = state.addablePartitions,
            onSelect = viewModel::selectChip,
            onEditingChange = viewModel::setEditing,
            onRemove = viewModel::removePartition,
            onMove = viewModel::movePartition,
            onAdd = viewModel::addPartition,
            onReset = viewModel::resetPartitions,
        )

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.items.isEmpty() && state.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = YtColors.Red)
                    }
                }

                state.items.isEmpty() && state.error != null -> {
                    ErrorState(message = state.error!!, onRetry = viewModel::refresh)
                }

                // The request succeeded and the partition simply has nothing:
                // an empty LazyColumn would look like a broken screen.
                state.items.isEmpty() && state.emptyFeedMessage != null -> {
                    EmptyFeedState(message = state.emptyFeedMessage.orEmpty())
                }

                state.useGrid -> {
                    // Experimental grid layout, two columns when the screen can
                    // hold them (see `gridColumns` above).
                    LazyVerticalGrid(
                        state = gridState,
                        columns = gridColumns,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = state.items,
                            key = { it.bvid.ifEmpty { it.id.toString() } },
                        ) { item ->
                            VideoCard(
                                title = item.title,
                                thumbnailUrl = item.pic,
                                uploaderName = item.owner?.name.orEmpty(),
                                uploaderAvatar = item.owner?.face.orEmpty(),
                                viewCount = item.stat?.view ?: 0,
                                danmakuCount = item.stat?.danmaku ?: 0,
                                durationSeconds = item.duration,
                                publishedAt = item.pubdate,
                                compact = true,
                                onClick = { onVideoClick(item.bvid) },
                            )
                        }

                        // Same footers as the list layout. Without them the grid
                        // gave no sign that another page was on its way, which
                        // made a working load look like a frozen screen.
                        if (state.isLoading) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        color = YtColors.Red,
                                        modifier = Modifier.height(24.dp),
                                    )
                                }
                            }
                        }

                        if (state.reachedEnd) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    Modifier.fillMaxWidth().padding(20.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "已经到底了，下拉可以刷新",
                                        color = AppTheme.colors.onBackgroundSecondary,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(
                            items = state.items,
                            key = { it.bvid.ifEmpty { it.id.toString() } },
                        ) { item ->
                            VideoCard(
                                title = item.title,
                                thumbnailUrl = item.pic,
                                uploaderName = item.owner?.name.orEmpty(),
                                uploaderAvatar = item.owner?.face.orEmpty(),
                                viewCount = item.stat?.view ?: 0,
                                danmakuCount = item.stat?.danmaku ?: 0,
                                durationSeconds = item.duration,
                                publishedAt = item.pubdate,
                                onClick = { onVideoClick(item.bvid) },
                            )
                        }

                        if (state.isLoading) {
                            item {
                                Box(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        color = YtColors.Red,
                                        modifier = Modifier.height(24.dp),
                                    )
                                }
                            }
                        }

                        if (state.reachedEnd) {
                            item {
                                Box(
                                    Modifier.fillMaxWidth().padding(20.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "已经到底了，下拉可以刷新",
                                        color = AppTheme.colors.onBackgroundSecondary,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The partition chip row, in its two shapes: read-only chips, and the editor.
 *
 * Why two shapes rather than one row of chips with extra buttons always on:
 * the read-only row is redrawn on every feed switch, while the editor exists
 * only for the seconds the user spends rearranging it. Hiding the controls in
 * the normal row keeps that row a plain list of taps, and it makes "tapping a
 * chip in edit mode must not change the feed" true by construction — the editor
 * chip deliberately has no tap handler of its own.
 *
 * Both shapes are a [LazyRow] with no fixed chip width, so a 320dp screen
 * scrolls the row sideways instead of pushing the trailing chip past the right
 * edge. The editor's candidate row is a [LazyRow] for the same reason: with the
 * default partitions configured, every "+ 名称" candidate does not fit on one
 * line of any phone.
 *
 * The look is unchanged from the read-only version: filled `chipSelected` when
 * selected, `chipBackground` otherwise, `RoundedCornerShape(8.dp)` for both.
 */
@Composable
private fun ChipRow(
    partitions: List<HomePartition>,
    selectedRid: Int?,
    editing: Boolean,
    addable: List<HomePartition>,
    onSelect: (Int?) -> Unit,
    onEditingChange: (Boolean) -> Unit,
    onRemove: (Int?) -> Unit,
    onMove: (Int?, Int) -> Unit,
    onAdd: (HomePartition) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (editing) {
                // The store refuses to empty the row, so the last chip keeps a
                // greyed ✕ rather than losing it: a control that vanishes
                // teaches nothing, a disabled one explains the rule.
                val canRemove = partitions.size > 1
                itemsIndexed(
                    items = partitions,
                    key = { _, partition -> partitionKey(partition) },
                ) { index, partition ->
                    EditorChip(
                        label = partition.label,
                        selected = partition.rid == selectedRid,
                        canRemove = canRemove,
                        canMoveEarlier = index > 0,
                        canMoveLater = index < partitions.lastIndex,
                        onMoveEarlier = { onMove(partition.rid, -1) },
                        onMoveLater = { onMove(partition.rid, 1) },
                        onRemove = { onRemove(partition.rid) },
                    )
                }
                item(key = "chip-done") {
                    ChipAction(
                        label = "完成",
                        selected = true,
                        onClick = { onEditingChange(false) },
                    )
                }
            } else {
                items(
                    items = partitions,
                    key = { partition -> partitionKey(partition) },
                ) { partition ->
                    Chip(
                        label = partition.label,
                        selected = partition.rid == selectedRid,
                        onClick = { onSelect(partition.rid) },
                    )
                }
                // The way into the editor has to sit inside the row: the chips
                // are the only place the partition list is visible, so a menu
                // elsewhere would hide the feature.
                item(key = "chip-edit") {
                    ChipAction(
                        label = "✎ 编辑",
                        selected = false,
                        onClick = { onEditingChange(true) },
                    )
                }
            }
        }

        if (editing) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(
                    items = addable,
                    key = { partition -> "add-" + partitionKey(partition) },
                ) { partition ->
                    Chip(
                        label = "+ " + partition.label,
                        selected = false,
                        onClick = { onAdd(partition) },
                    )
                }
                // 恢复默认 rides in the scrolling row instead of sitting beside
                // it: a fixed button next to a LazyRow would be the one element
                // that cannot give way when the candidates are wide.
                item(key = "chip-reset") {
                    TextAction(label = "恢复默认", onClick = onReset)
                }
            }
        }
    }
}

/**
 * A stable key for a partition inside a [LazyRow].
 *
 * `rid` is nullable because 全部 has no region, and a null key makes Compose
 * fall back to the item index — which would re-create every chip on each
 * reorder instead of moving them.
 */
private fun partitionKey(partition: HomePartition): String =
    "rid-" + (partition.rid?.toString() ?: "all")

/** One read-only chip: filled when selected, as on YouTube. */
@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
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
            color = if (selected) AppTheme.colors.chipSelectedContent else AppTheme.colors.onBackground,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A chip that is a verb rather than a feed: 编辑 to the right of the normal row,
 * 完成 to the right of the editor.
 *
 * [selected] only decides the fill, so 完成 reads as the primary action of the
 * mode it closes without a third colour entering the palette.
 */
@Composable
private fun ChipAction(label: String, selected: Boolean, onClick: () -> Unit) {
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
                AppTheme.colors.onBackgroundSecondary
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 恢复默认: text-like, so it reads as a different kind of action than the chips. */
@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = AppTheme.colors.onBackgroundSecondary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * One chip while editing: the same fill and shape as [Chip], with reorder and
 * remove controls grown into it.
 *
 * The chip itself has no click handler on purpose. In this mode a tap is meant
 * to be harmless, and reloading the feed underneath a row that is being
 * rearranged is exactly what the user did not ask for.
 */
@Composable
private fun EditorChip(
    label: String,
    selected: Boolean,
    canRemove: Boolean,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onRemove: () -> Unit,
) {
    val content = if (selected) AppTheme.colors.chipSelectedContent else AppTheme.colors.onBackground
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) AppTheme.colors.chipSelected else AppTheme.colors.chipBackground,
            )
            .padding(start = 2.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChipGlyph(
            glyph = "‹",
            enabled = canMoveEarlier,
            tint = content,
            onClick = onMoveEarlier,
        )
        Text(
            text = label,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        ChipGlyph(
            glyph = "›",
            enabled = canMoveLater,
            tint = content,
            onClick = onMoveLater,
        )
        ChipGlyph(
            glyph = "✕",
            enabled = canRemove,
            tint = content,
            onClick = onRemove,
        )
    }
}

/**
 * One of the small ‹ › ✕ controls inside an editing chip.
 *
 * Sized by padding instead of a fixed `size`, so a glyph is exactly as wide as
 * its font plus the tap slop it needs: nothing here can widen a chip past the
 * label it belongs to. A disabled glyph keeps its slot (dimmed, not gone) so a
 * chip does not change width as it moves between an end of the row and the
 * middle.
 */
@Composable
private fun ChipGlyph(
    glyph: String,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 5.dp, vertical = 6.dp)
            .alpha(if (enabled) 1f else 0.32f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = tint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "加载失败",
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.onBackgroundSecondary,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(AppTheme.colors.surfaceElevated)
                .clickable(onClick = onRetry)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text("重试", color = AppTheme.colors.onBackground)
        }
    }
}

/**
 * The selected partition answered, but with nothing.
 *
 * Kept apart from [ErrorState] because nothing failed: there is no retry to
 * offer, and pull-to-refresh is the gesture that asks again. Saying that is
 * more useful than an empty list that looks like a frozen screen.
 */
@Composable
private fun EmptyFeedState(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "下拉可以刷新",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.onBackgroundSecondary,
        )
    }
}

/**
 * Below this width the two-column grid stops being readable, so it falls back
 * to a single column. 340dp is where a cell stops being narrower than the
 * metadata line the card draws at the default font scale.
 */
private const val NARROW_SCREEN_DP = 340
