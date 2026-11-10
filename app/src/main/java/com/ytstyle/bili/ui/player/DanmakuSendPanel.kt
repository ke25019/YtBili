package com.ytstyle.bili.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/**
 * Danmaku type. The numbers are the ones `x/v2/dm/post` expects for `mode`.
 */
internal object DanmakuSendMode {
    const val SCROLL = 1
    const val BOTTOM = 4
    const val TOP = 5

    val entries: List<Pair<Int, String>> = listOf(
        SCROLL to "滚动",
        TOP to "置顶",
        BOTTOM to "置底",
    )
}

/** Font sizes `x/v2/dm/post` accepts: 25 is 标准, 18 is 小. */
internal object DanmakuSendSize {
    const val NORMAL = 25
    const val SMALL = 18

    val entries: List<Pair<Int, String>> = listOf(
        NORMAL to "默认",
        SMALL to "较小",
    )
}

/**
 * One selectable danmaku colour.
 *
 * [vipGradient] marks 大会员 专属渐变彩色. That is not a colour value at all: it is
 * requested with `colorful=60001` (see `x/v2/dm/post`), and the ordinary `color`
 * travels along as white.
 */
internal data class DanmakuColorOption(
    val color: Long,
    val label: String,
    val vipGradient: Boolean = false,
)

/**
 * The palette the send panel offers, in the order the reference client shows it:
 * white, then the 会员 gradient, then the solid colours.
 *
 * The solids are deliberately **not** gated on 大会员. Bilibili's own 权益与等级
 * table unlocks 彩色弹幕 at Lv2, so hiding them from non-paying accounts — which
 * is what this app did — refused a colour the server would have accepted.
 */
internal val DanmakuColorOptions: List<DanmakuColorOption> = listOf(
    DanmakuColorOption(0xFFFFFF, "白色"),
    DanmakuColorOption(0xFFFFFF, "彩色", vipGradient = true),
    DanmakuColorOption(0xFF0000, "红色"),
    DanmakuColorOption(0xFFFF00, "黄色"),
    DanmakuColorOption(0x00FF00, "绿色"),
    DanmakuColorOption(0x00A0FF, "蓝色"),
    DanmakuColorOption(0xFF6699, "粉色"),
    DanmakuColorOption(0x66CC00, "浅绿"),
    DanmakuColorOption(0x0000FF, "深蓝"),
    DanmakuColorOption(0xFF9900, "橙色"),
    DanmakuColorOption(0x9B30FF, "紫色"),
    DanmakuColorOption(0x66CCFF, "浅蓝"),
    DanmakuColorOption(0x999900, "橄榄"),
)

/**
 * The send options that sit under the portrait composer: 字体大小, 弹幕位置 and
 * 弹幕颜色, laid out the way the reference client lays them out.
 *
 * Every control here changes the actual request — [mode] is sent as `mode`,
 * [fontSize] as `fontsize`, and the colour as `color` (plus `colorful=60001`
 * when the gradient is chosen) — rather than only decorating the composer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DanmakuSendPanel(
    mode: Int,
    fontSize: Int,
    color: Long,
    vipGradient: Boolean,
    /** Solid colours need Lv2; without it only white is offered. */
    canSendColor: Boolean,
    /** The gradient is the 大会员 tier of the same setting. */
    canSendVipGradient: Boolean,
    onMode: (Int) -> Unit,
    onFontSize: (Int) -> Unit,
    onColor: (DanmakuColorOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppTheme.colors.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        LabelledRow(label = "字体大小") {
            DanmakuSendSize.entries.forEach { (value, text) ->
                ChoiceChip(
                    text = text,
                    selected = fontSize == value,
                    onClick = { onFontSize(value) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        LabelledRow(label = "弹幕位置") {
            DanmakuSendMode.entries.forEach { (value, text) ->
                ChoiceChip(
                    text = text,
                    selected = mode == value,
                    onClick = { onMode(value) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        LabelledRow(label = "弹幕颜色") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DanmakuColorOptions.forEach { option ->
                    val allowed = if (option.vipGradient) {
                        canSendVipGradient
                    } else if (option.color == 0xFFFFFFL) {
                        // White is the default and needs no privilege at all.
                        true
                    } else {
                        canSendColor
                    }
                    Swatch(
                        option = option,
                        selected = if (option.vipGradient) {
                            vipGradient
                        } else {
                            !vipGradient && color == option.color
                        },
                        enabled = allowed,
                        onClick = { onColor(option) },
                    )
                }
            }
        }

        if (!canSendColor) {
            Spacer(Modifier.height(10.dp))
            Text(
                // The rule comes from Bilibili's 权益与等级 table, not from a guess.
                text = "彩色弹幕需要账号达到 Lv2，大会员还可使用专属渐变彩色。",
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun LabelledRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.width(64.dp),
        )
        content()
    }
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) {
        YtColors.Red.copy(alpha = 0.14f)
    } else {
        AppTheme.colors.chipBackground
    }
    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(
                width = 1.dp,
                color = if (selected) YtColors.Red else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            color = if (selected) YtColors.Red else AppTheme.colors.onBackground,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

/**
 * One colour bubble. The 会员 gradient is drawn as a real gradient so it reads as
 * a different *kind* of option rather than just another colour.
 */
@Composable
private fun Swatch(
    option: DanmakuColorOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .then(
                    if (option.vipGradient) {
                        Modifier.background(
                            Brush.sweepGradient(
                                listOf(
                                    Color(0xFFFF5C8A),
                                    Color(0xFFFFD166),
                                    Color(0xFF7CE38B),
                                    Color(0xFF66CCFF),
                                    Color(0xFF9B30FF),
                                    Color(0xFFFF5C8A),
                                ),
                            ),
                        )
                    } else {
                        Modifier.background(Color(option.color.toInt() or 0xFF000000.toInt()))
                    },
                )
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        YtColors.Red
                    } else {
                        AppTheme.colors.divider
                    },
                    shape = CircleShape,
                )
                .clickable(enabled = enabled, onClick = onClick),
        )

        if (option.vipGradient) {
            // The badge the reference client puts on this one swatch.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (enabled) YtColors.Red else AppTheme.colors.divider)
                    .padding(horizontal = 3.dp),
            ) {
                Text(
                    text = "会员",
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}
