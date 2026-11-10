package com.ytstyle.bili.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ytstyle.bili.ui.components.CompactSlider
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors
import kotlin.math.roundToInt

/**
 * 弹幕设置, shared by the player and the settings page.
 *
 * One component rather than two: the same four sliders and the same mode filter
 * used to exist only under 设置 → 弹幕设置, while the player had no way to reach
 * them at all — which is exactly what "在播放器里新增弹幕设置" asks for. Both
 * callers therefore read and write the same stored values, and a change made
 * mid-playback takes effect immediately.
 *
 * The bounds are the ones the store documents: 显示区域 25–100 % of the picture,
 * 不透明度 30–100 %, 字体大小 50–150 %, 弹幕速度 4–14 s to cross the screen.
 *
 * [onDark] switches the palette for the landscape panel, which floats over the
 * video on a translucent dark card.
 */
@Composable
fun DanmakuSettingsPanel(
    scale: Float,
    opacity: Float,
    area: Float,
    speed: Float,
    types: Set<Int>,
    onScale: (Float) -> Unit,
    onOpacity: (Float) -> Unit,
    onArea: (Float) -> Unit,
    onSpeed: (Float) -> Unit,
    onToggleType: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
) {
    val labelColor = if (onDark) Color.White else AppTheme.colors.onBackground
    val valueColor = if (onDark) Color(0xCCFFFFFF) else AppTheme.colors.onBackgroundSecondary
    val dividerColor = if (onDark) Color(0x33FFFFFF) else AppTheme.colors.divider

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "弹幕设置",
                color = labelColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "重置弹幕设置",
                color = if (onDark) Color(0xCCFFFFFF) else YtColors.Red,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onReset)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        SettingSlider(
            label = "显示区域",
            valueText = "${(area * 100).roundToInt()}%",
            value = area,
            range = 0.25f..1f,
            onValueChange = onArea,
            labelColor = labelColor,
            valueColor = valueColor,
        )
        SettingSlider(
            label = "不透明度",
            valueText = "${(opacity * 100).roundToInt()}%",
            value = opacity,
            range = 0.3f..1f,
            onValueChange = onOpacity,
            labelColor = labelColor,
            valueColor = valueColor,
        )
        SettingSlider(
            label = "字体大小",
            valueText = "${(scale * 100).roundToInt()}%",
            value = scale,
            range = 0.5f..1.5f,
            onValueChange = onScale,
            labelColor = labelColor,
            valueColor = valueColor,
        )
        SettingSlider(
            label = "弹幕速度",
            // Larger traverse time is a slower comment, which is how the
            // official wording reads.
            valueText = when {
                speed <= 6f -> "快"
                speed >= 11f -> "慢"
                else -> "适中"
            },
            value = speed,
            range = 4f..14f,
            onValueChange = onSpeed,
            labelColor = labelColor,
            valueColor = valueColor,
        )

        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(dividerColor))
        Spacer(Modifier.height(10.dp))

        Text(
            text = "按弹幕类型屏蔽",
            color = labelColor,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TypeChip(
                label = "固定",
                selected = DanmakuMode.TOP in types,
                onDark = onDark,
                onClick = { onToggleType(DanmakuMode.TOP) },
            )
            TypeChip(
                label = "滚动",
                selected = DanmakuMode.SCROLL in types,
                onDark = onDark,
                onClick = { onToggleType(DanmakuMode.SCROLL) },
            )
            TypeChip(
                label = "底端",
                selected = DanmakuMode.BOTTOM in types,
                onDark = onDark,
                onClick = { onToggleType(DanmakuMode.BOTTOM) },
            )
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    labelColor: Color,
    valueColor: Color,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, color = labelColor, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(text = valueText, color = valueColor, fontSize = 12.sp)
        }
        CompactSlider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = YtColors.Red,
                activeTrackColor = YtColors.Red,
                inactiveTrackColor = labelColor.copy(alpha = 0.25f),
            ),
        )
    }
}

@Composable
private fun TypeChip(
    label: String,
    selected: Boolean,
    onDark: Boolean,
    onClick: () -> Unit,
) {
    val background = when {
        selected -> YtColors.Red
        onDark -> Color(0x33FFFFFF)
        else -> AppTheme.colors.chipBackground
    }
    val content = when {
        selected -> Color.White
        onDark -> Color.White
        else -> AppTheme.colors.onBackground
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = content,
            fontSize = 13.sp,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
