package com.gios.lightnoise.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.gios.lightnoise.audio.Generator
import com.gios.lightnoise.audio.NoiseEngine
import com.gios.lightnoise.audio.SoundId
import com.gios.lightnoise.audio.generatorFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a mixer slot is currently set to. */
sealed interface Pick {
    data object None : Pick
    data class Synth(val id: SoundId) : Pick

    val label: String
        get() = when (this) {
            None -> "None"
            is Synth -> id.label
        }
}

enum class Slot { A, B }

data class NoiseState(
    val playing: Boolean = false,
    val pickA: Pick = Pick.Synth(SoundId.RAIN),
    val pickB: Pick = Pick.None,
    val levelA: Float = 1f,
    val levelB: Float = 0.45f,
    val master: Float = 0.7f,
    /** Minutes, or 0 for no timer. */
    val timerMinutes: Int = 0,
    /** Wall clock ms when playback should end, 0 when no timer is armed. */
    val timerEndsAt: Long = 0L,
    val remainingSeconds: Int = 0,
)

/**
 * Single owner of playback. The UI and the foreground service both talk to this rather
 * than to each other, which keeps the Compose layer free of any binder plumbing.
 */
object NoiseController {

    /** Volume is ramped to zero over this long before a sleep timer cuts playback. */
    const val FADE_SECONDS = 25

    val timerPresets = listOf(0, 15, 30, 45, 60, 90, 120)

    private val engine = NoiseEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timerJob: Job? = null

    private val _state = MutableStateFlow(NoiseState())
    val state: StateFlow<NoiseState> = _state.asStateFlow()

    private var appContext: Context? = null

    fun attach(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            Prefs.load(context)?.let { saved ->
                _state.value = _state.value.copy(
                    pickA = saved.pickA,
                    pickB = saved.pickB,
                    levelA = saved.levelA,
                    levelB = saved.levelB,
                    master = saved.master,
                    timerMinutes = saved.timerMinutes,
                )
            }
            pushLevels()
        }
    }

    // ------------------------------------------------------------------ transport

    fun play() {
        val s = _state.value
        if (s.playing) return
        rebuild(Slot.A, s.pickA)
        rebuild(Slot.B, s.pickB)
        pushLevels()
        engine.fade = 1f
        engine.start()
        _state.value = s.copy(playing = true)
        startService()
        armTimer(s.timerMinutes)
    }

    fun stop() {
        timerJob?.cancel()
        timerJob = null
        engine.stop()
        _state.value = _state.value.copy(
            playing = false,
            timerEndsAt = 0L,
            remainingSeconds = 0,
        )
        stopService()
    }

    fun toggle() = if (_state.value.playing) stop() else play()

    /** Tapping a sound in the list plays it right away in slot A. */
    fun playNow(pick: Pick) {
        setPick(Slot.A, pick)
        if (!_state.value.playing) play()
    }

    // -------------------------------------------------------------------- mixing

    fun setPick(slot: Slot, pick: Pick) {
        _state.value = when (slot) {
            Slot.A -> _state.value.copy(pickA = pick)
            Slot.B -> _state.value.copy(pickB = pick)
        }
        if (_state.value.playing) rebuild(slot, pick)
        persist()
    }

    fun setLevel(slot: Slot, v: Float) {
        val c = v.coerceIn(0f, 1f)
        _state.value = when (slot) {
            Slot.A -> _state.value.copy(levelA = c)
            Slot.B -> _state.value.copy(levelB = c)
        }
        pushLevels()
        persist()
    }

    fun setMaster(v: Float) {
        _state.value = _state.value.copy(master = v.coerceIn(0f, 1f))
        pushLevels()
        persist()
    }

    private fun pushLevels() {
        val s = _state.value
        engine.levelA = s.levelA
        engine.levelB = s.levelB
        engine.master = s.master
    }

    private fun rebuild(slot: Slot, pick: Pick) {
        val gen = buildGenerator(pick)
        when (slot) {
            Slot.A -> engine.setLayerA(gen)
            Slot.B -> engine.setLayerB(gen)
        }
    }

    private fun buildGenerator(pick: Pick): Generator? = when (pick) {
        Pick.None -> null
        is Pick.Synth -> generatorFor(pick.id)
    }

    // -------------------------------------------------------------- sleep timer

    fun setTimer(minutes: Int) {
        _state.value = _state.value.copy(timerMinutes = minutes)
        persist()
        if (_state.value.playing) {
            armTimer(minutes)
        } else {
            _state.value = _state.value.copy(timerEndsAt = 0, remainingSeconds = 0)
        }
    }

    private fun armTimer(minutes: Int) {
        timerJob?.cancel()
        engine.fade = 1f
        if (minutes <= 0) {
            _state.value = _state.value.copy(timerEndsAt = 0L, remainingSeconds = 0)
            return
        }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _state.value = _state.value.copy(timerEndsAt = endsAt)
        timerJob = scope.launch {
            while (true) {
                val leftMs = endsAt - System.currentTimeMillis()
                if (leftMs <= 0) break
                val leftSec = ((leftMs + 999) / 1000).toInt()
                _state.value = _state.value.copy(remainingSeconds = leftSec)
                // Ramp the master fade across the final seconds rather than cutting.
                engine.fade = if (leftSec > FADE_SECONDS) {
                    1f
                } else {
                    (leftMs / (FADE_SECONDS * 1000f)).coerceIn(0f, 1f)
                }
                delay(if (leftSec <= FADE_SECONDS) 200 else 1000)
            }
            stop()
        }
    }

    // ------------------------------------------------------------------- service

    private fun startService() {
        val ctx = appContext ?: return
        val intent = Intent(ctx, NoiseService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    private fun stopService() {
        val ctx = appContext ?: return
        ctx.stopService(Intent(ctx, NoiseService::class.java))
    }

    private fun persist() {
        appContext?.let { Prefs.save(it, _state.value) }
    }

    /** Human-readable summary for the notification and the transport strip. */
    fun nowPlayingLabel(): String {
        val s = _state.value
        val a = s.pickA.takeIf { it != Pick.None }?.label
        val b = s.pickB.takeIf { it != Pick.None }?.label
        return when {
            a != null && b != null -> "$a + $b"
            a != null -> a
            b != null -> b
            else -> "Silence"
        }
    }
}
