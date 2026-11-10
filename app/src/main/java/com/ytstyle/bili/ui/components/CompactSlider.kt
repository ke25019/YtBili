package com.ytstyle.bili.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** Visual size of every draggable bar in the app. */
private val COMPACT_THUMB_SIZE = 14.dp

/** Room the slider is given; Material3's default is noticeably taller. */
private val COMPACT_SLIDER_HEIGHT = 28.dp

/**
 * Every draggable bar in the app, one size smaller.
 *
 * Material3 draws a 20dp thumb inside a 44dp-tall touch area, which reads as
 * oversized next to this app's type scale. Only what is *drawn* shrinks; the
 * touch target keeps its size, so the control is no harder to grab.
 *
 * The `thumb` slot is the only way to resize it — the size is a fixed token that
 * the incoming constraints cannot influence. Checked against the artifact this
 * module actually resolves (material3 1.3.2) with `javap`:
 * `SliderDefaults.Thumb(interactionSource, modifier, colors, enabled, thumbSize)`
 * and `SliderDefaults.Track(sliderState, modifier, enabled, colors, …)`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    thumbSize: Dp = COMPACT_THUMB_SIZE,
) {
    // The thumb slot takes a non-null source, and passing the same one the
    // slider uses keeps drag/focus state consistent between the two.
    val interaction = remember { MutableInteractionSource() }

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(COMPACT_SLIDER_HEIGHT),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interaction,
        thumb = {
            SliderDefaults.Thumb(
                interaction,
                Modifier,
                colors,
                enabled,
                DpSize(thumbSize, thumbSize),
            )
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier,
                enabled = enabled,
                colors = colors,
            )
        },
    )
}
