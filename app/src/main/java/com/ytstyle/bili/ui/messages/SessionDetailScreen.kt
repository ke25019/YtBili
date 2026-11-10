package com.ytstyle.bili.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ytstyle.bili.ui.components.relativeTime
import com.ytstyle.bili.ui.components.toSecureUrl
import com.ytstyle.bili.ui.components.toThumbUrl
import com.ytstyle.bili.ui.history.SimpleTopBar
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/**
 * One 私信会话: the message history, oldest at the top, with a composer.
 *
 * Sending goes through `web_im/v1/web_im/send_msg`, which is WBI-signed and
 * takes a nested `msg[...]` body. Only text is offered — the endpoint also
 * accepts an image and a card (types 2 and 5), and neither has any UI here.
 */
@Composable
fun SessionDetailScreen(
    onBack: () -> Unit,
    onOpenVideo: (String) -> Unit = {},
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Jump to the newest message once the first page has landed. Prepending
    // older pages must not move the view, so this only fires while the size
    // grows at the *end*.
    LaunchedEffect(state.isLoading, state.messages.lastOrNull()?.key) {
        if (!state.isLoading && state.messages.isNotEmpty()) {
            val header = if (state.hasMore) 1 else 0
            listState.scrollToItem(state.messages.size - 1 + header)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        SimpleTopBar(title = state.peerName.ifBlank { "私信" }, onBack = onBack)

        // The conversation takes everything above the composer. Weighted rather
        // than `fillMaxSize()` so the composer is not pushed off the bottom —
        // the branches below keep their own `fillMaxSize()` and now fill this box.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        when {
            state.loggedOut -> Centered("未登录", "登录后即可查看聊天记录")

            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = YtColors.Red)
            }

            state.error != null && state.messages.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Centered("加载失败", state.error.orEmpty())
                    TextButton(onClick = viewModel::retry) {
                        Text(text = "重试", color = YtColors.Red)
                    }
                }
            }

            state.messages.isEmpty() -> Centered("还没有聊天记录", "这里会显示你们之间的私信")

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (state.hasMore) {
                    item(key = "load-older") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TextButton(onClick = viewModel::loadMore, enabled = !state.isLoadingMore) {
                                if (state.isLoadingMore) {
                                    CircularProgressIndicator(
                                        color = YtColors.Red,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(text = "加载中…", color = YtColors.Red)
                                } else {
                                    Text(text = "加载更早的消息", color = YtColors.Red)
                                }
                            }
                        }
                    }
                }

                items(items = state.messages, key = { it.key }) { message ->
                    MessageBubble(
                        message = message,
                        emotes = state.emotes,
                        peerName = state.peerName,
                        peerFace = state.peerFace,
                        onOpenVideo = onOpenVideo,
                    )
                }
            }
        }
        }

        // Composer. Hidden while logged out, and while the very first page is
        // still loading — but a post-send refresh is silent (`isLoading` stays
        // false), so sending does not make the box vanish under the user's
        // fingers while the new message is being fetched.
        if (!state.loggedOut && !state.isLoading) {
            Composer(
                text = state.input,
                isSending = state.isSending,
                feedback = state.sendFeedback,
                feedbackIsError = state.sendFeedbackIsError,
                onTextChange = viewModel::onInputChange,
                onSend = viewModel::send,
            )
        }
    }
}

/**
 * The 私信 composer.
 *
 * The send button is replaced by a spinner while a message is in flight, rather
 * than sitting next to it, so the row does not shift at the moment of sending.
 * Failure feedback appears right under the field instead of as a floating strip,
 * for the same reason the comment composer does it: a strip at the bottom of the
 * screen is exactly what the keyboard covers.
 */
@Composable
private fun Composer(
    text: String,
    isSending: Boolean,
    feedback: String?,
    feedbackIsError: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.surface)
            // The app draws edge to edge, so the composer would otherwise sit
            // under the navigation bar — which is exactly how it looked on a
            // gesture-nav phone: the send button half behind the pill. `imePadding`
            // is the same fix for the keyboard, and the two do not stack because
            // they both consume the same window inset.
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp)),
                placeholder = {
                    Text(
                        text = "发消息",
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 13.sp,
                    )
                },
                singleLine = true,
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
            Spacer(Modifier.width(8.dp))
            if (isSending) {
                CircularProgressIndicator(
                    color = YtColors.Red,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Text(
                    text = "发送",
                    color = if (text.isBlank()) {
                        AppTheme.colors.onBackgroundSecondary
                    } else {
                        YtColors.Red
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = text.isNotBlank(), onClick = onSend)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        feedback?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                color = if (feedbackIsError) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun Centered(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = AppTheme.colors.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.onBackgroundSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One message.
 *
 * System lines (a recall, the "对方主动回复前最多发送1条" hint) are centred and
 * grey; everything else is a bubble on the sender's side. Images are drawn
 * without the bubble, since a coloured border around a photo adds nothing.
 */
@Composable
private fun MessageBubble(
    message: SessionMessage,
    emotes: Map<String, String>,
    peerName: String,
    peerFace: String,
    onOpenVideo: (String) -> Unit,
) {
    if (message.kind == SessionMessageKind.SYSTEM || message.kind == SessionMessageKind.RECALLED) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.colors.onBackgroundSecondary,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start,
    ) {
        val contentColor = if (message.outgoing) Color.White else AppTheme.colors.onBackground

        // The peer's avatar beside incoming bubbles. A 1:1 conversation has one
        // other participant, so this comes from the session card rather than
        // from each message — the message payload carries no face.
        if (!message.outgoing) {
            MessageAvatar(
                name = peerName,
                url = peerFace,
                size = 34.dp,
                fallback = "私",
            )
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .let { base ->
                    if (message.kind == SessionMessageKind.IMAGE) {
                        base
                    } else {
                        base
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (message.outgoing) YtColors.Red else AppTheme.colors.surfaceElevated,
                            )
                            .padding(10.dp)
                    }
                },
            horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start,
        ) {
            when (message.kind) {
                SessionMessageKind.IMAGE -> AsyncImage(
                    model = message.imageUrl.toThumbUrl(),
                    contentDescription = "图片消息",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppTheme.colors.surfaceElevated),
                )

                SessionMessageKind.SHARE -> ShareCard(
                    message = message,
                    contentColor = contentColor,
                    onOpenVideo = onOpenVideo,
                )

                SessionMessageKind.NOTICE -> Column {
                    if (message.title.isNotBlank()) {
                        Text(
                            text = message.title,
                            color = contentColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(3.dp))
                    }
                    if (message.text.isNotBlank()) {
                        Text(text = message.text, color = contentColor, fontSize = 13.sp)
                    }
                    if (message.jumpUrl.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "查看详情",
                            color = if (message.outgoing) Color.White else YtColors.Red,
                            fontSize = 12.sp,
                        )
                    }
                }

                else -> SessionText(
                    text = message.text.ifBlank { "[无法显示的消息]" },
                    emotes = emotes,
                    color = contentColor,
                )
            }

            if (message.timeSeconds > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = relativeTime(message.timeSeconds),
                    color = contentColor.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/** A shared video / 专栏 / 直播: cover, title, and a tap that opens a video. */
@Composable
private fun ShareCard(
    message: SessionMessage,
    contentColor: Color,
    onOpenVideo: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { base ->
                if (message.bvid.isNotEmpty()) {
                    base.clickable { onOpenVideo(message.bvid) }
                } else {
                    base
                }
            },
    ) {
        if (message.cover.isNotBlank()) {
            AsyncImage(
                model = message.cover.toThumbUrl(),
                contentDescription = message.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppTheme.colors.surface),
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = message.title.ifBlank { "分享的内容" },
            color = contentColor,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (message.desc.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = message.desc,
                color = contentColor.copy(alpha = 0.75f),
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Message text with `[token]` emotes replaced by their images.
 *
 * Same approach as the comment renderer: a `FlowRow` of text runs and images,
 * because `appendInlineContent` does not exist in the Compose UI Text version
 * this project builds against. Unknown tokens stay as literal text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionText(text: String, emotes: Map<String, String>, color: Color) {
    val segments = remember(text, emotes) { splitIntoSessionSegments(text, emotes) }

    if (segments.all { it is SessionSegment.Text }) {
        Text(text = text, color = color, fontSize = 14.sp, lineHeight = 20.sp)
        return
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            when (segment) {
                is SessionSegment.Text -> Text(
                    text = segment.value,
                    color = color,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )

                is SessionSegment.Emote -> AsyncImage(
                    model = segment.url.toSecureUrl(),
                    contentDescription = segment.token,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** A run of either plain text or one emote image. Internal so it can be tested. */
internal sealed interface SessionSegment {
    data class Text(val value: String) : SessionSegment
    data class Emote(val token: String, val url: String) : SessionSegment
}

/**
 * Splits [message] on the known emote tokens.
 *
 * A single alternation regex is used rather than scanning for `[` by hand. The
 * alternatives are ordered **longest first**: a regex alternation takes the
 * first branch that matches at a position, so with `[tv]` listed before
 * `[tv_doge]` the text `[tv_doge]` would come out as an emote plus the stray
 * text `_doge]`. The map's own order is not stable enough to rely on.
 */
internal fun splitIntoSessionSegments(
    message: String,
    emotes: Map<String, String>,
): List<SessionSegment> {
    if (message.isEmpty() || emotes.isEmpty()) {
        return listOf(SessionSegment.Text(message))
    }
    val pattern = Regex(
        emotes.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) },
    )
    val out = ArrayList<SessionSegment>()
    var cursor = 0
    for (match in pattern.findAll(message)) {
        if (match.range.first > cursor) {
            out.add(SessionSegment.Text(message.substring(cursor, match.range.first)))
        }
        out.add(SessionSegment.Emote(match.value, emotes.getValue(match.value)))
        cursor = match.range.last + 1
    }
    if (cursor < message.length) out.add(SessionSegment.Text(message.substring(cursor)))
    return out.ifEmpty { listOf(SessionSegment.Text(message)) }
}
