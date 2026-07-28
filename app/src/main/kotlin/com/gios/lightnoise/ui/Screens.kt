package com.gios.lightnoise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.lightnoise.audio.SoundId
import com.gios.lightnoise.service.NoiseController
import com.gios.lightnoise.service.NoiseState
import com.gios.lightnoise.service.Pick
import com.gios.lightnoise.service.Slot
import com.gios.lightnoise.ui.theme.Dim

/** Tab 1 — the twelve synthesised sounds. */
@Composable
fun SoundsScreen(state: NoiseState, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize()) {
        item { ScreenTitle("SOUNDS") }
        items(SoundId.entries.toList(), key = { it.name }) { id ->
            SoundRow(
                label = id.label,
                sub = id.blurb,
                selected = state.pickA == Pick.Synth(id),
                trailing = if (state.pickB == Pick.Synth(id)) "B" else null,
                onClick = { NoiseController.playNow(Pick.Synth(id)) },
            )
        }
        item { Box(Modifier.height(24.dp)) }
    }
}

/** Tab 2 — two layers with independent levels, plus master volume. */
@Composable
fun MixScreen(state: NoiseState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("LAYER A")
        PickerRow(Slot.A, state.pickA)
        LevelBar(
            label = "LEVEL",
            value = state.levelA,
            enabled = state.pickA != Pick.None,
        ) { NoiseController.setLevel(Slot.A, it) }

        ScreenTitle("LAYER B")
        PickerRow(Slot.B, state.pickB)
        LevelBar(
            label = "LEVEL",
            value = state.levelB,
            enabled = state.pickB != Pick.None,
        ) { NoiseController.setLevel(Slot.B, it) }

        ScreenTitle("OUTPUT")
        LevelBar(label = "VOLUME", value = state.master) { NoiseController.setMaster(it) }
        Box(Modifier.height(24.dp))
    }
}

/** Every choice for one slot, wrapped across as many rows as it needs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerRow(slot: Slot, current: Pick) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Chip("NONE", current == Pick.None) { NoiseController.setPick(slot, Pick.None) }
        SoundId.entries.forEach { id ->
            Chip(id.label.uppercase(), current == Pick.Synth(id)) {
                NoiseController.setPick(slot, Pick.Synth(id))
            }
        }
    }
}

/** Tab 3 — sleep timer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimerScreen(state: NoiseState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        ScreenTitle("SLEEP TIMER")

        Box(Modifier.fillMaxWidth().padding(vertical = 26.dp), Alignment.Center) {
            val text = when {
                state.timerEndsAt > 0L -> {
                    val m = state.remainingSeconds / 60
                    val s = state.remainingSeconds % 60
                    "%d:%02d".format(m, s)
                }
                state.timerMinutes > 0 -> "${state.timerMinutes} min"
                else -> "∞"
            }
            Text(text, style = MaterialTheme.typography.displaySmall, color = Color.White)
        }

        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NoiseController.timerPresets.forEach { minutes ->
                Chip(
                    label = if (minutes == 0) "OFF" else "$minutes MIN",
                    selected = state.timerMinutes == minutes,
                ) { NoiseController.setTimer(minutes) }
            }
        }
    }
}

/**
 * Always-visible transport. Big target, inverted while playing, because on the LPIII
 * you are often reaching for it in the dark.
 */
@Composable
fun TransportBar(state: NoiseState) {
    val playing = state.playing
    Column {
        Rule()
        Row(
            Modifier.fillMaxWidth().height(72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f).padding(horizontal = 16.dp),
            ) {
                Text(
                    NoiseController.nowPlayingLabel(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    maxLines = 1,
                )
                Text(
                    if (playing) {
                        if (state.timerEndsAt > 0L) {
                            "Playing · ${state.remainingSeconds / 60}m left"
                        } else {
                            "Playing"
                        }
                    } else {
                        "Stopped"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                    maxLines = 1,
                )
            }
            Box(
                Modifier
                    .height(72.dp)
                    .background(if (playing) Color.White else Color.Black)
                    .clickable { NoiseController.toggle() }
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (playing) "STOP" else "PLAY",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (playing) Color.Black else Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
