package com.gios.lightnoise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightnoise.ui.theme.Dim
import com.gios.lightnoise.ui.theme.Faint
import com.gios.lightnoise.ui.theme.RuleGrey

@Composable
fun Rule(modifier: Modifier = Modifier) =
    HorizontalDivider(modifier = modifier, color = RuleGrey, thickness = 1.dp)

@Composable
fun ScreenTitle(text: String) {
    Column {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = Dim,
            modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 10.dp),
        )
        Rule()
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Full-width tappable row. Selection inverts the whole row rather than tinting it —
 * on a greyscale matte panel an inversion is the only state change that reads at
 * arm's length in a dark room.
 */
@Composable
fun SoundRow(
    label: String,
    sub: String? = null,
    selected: Boolean = false,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    val fg = if (selected) Color.Black else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Color.White else Color.Black)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) Color(0xFF444444) else Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelSmall, color = fg)
        }
    }
}

/**
 * Segmented level control. A Material Slider's thumb is a small target on a 3.92"
 * panel and its track is nearly invisible in greyscale; discrete blocks are legible
 * across the room and can be hit anywhere along their length.
 */
@Composable
fun LevelBar(
    label: String,
    value: Float,
    enabled: Boolean = true,
    segments: Int = 16,
    onChange: (Float) -> Unit,
) {
    val filled = (value * segments).toInt().coerceIn(0, segments)

    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) Dim else Faint,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(value * 100).toInt()}",
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) Color.White else Faint,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .padding(top = 8.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    detectTapGestures { offset -> onChange((offset.x / w).coerceIn(0f, 1f)) }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    detectHorizontalDragGestures { change, _ ->
                        onChange((change.position.x / w).coerceIn(0f, 1f))
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(segments) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            when {
                                !enabled -> RuleGrey
                                i < filled -> Color.White
                                else -> Color(0xFF303030)
                            },
                        ),
                )
            }
        }
    }
}

/** Bottom tab bar in the LightOS action-bar idiom: the active tab is bracketed. */
@Composable
fun TabBar(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    Column {
        Rule()
        Row(
            Modifier.fillMaxWidth().height(60.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { i, label ->
                val active = i == selected
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (active) "[ $label ]" else label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) Color.White else Faint,
                    )
                }
            }
        }
    }
}

/** Inverting chip, used for the timer presets. */
@Composable
fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(if (selected) Color.White else Color.Black)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color.Black else Color.White,
            maxLines = 1,
        )
    }
}
