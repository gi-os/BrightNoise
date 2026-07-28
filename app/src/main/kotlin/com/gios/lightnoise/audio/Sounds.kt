package com.gios.lightnoise.audio

import kotlin.math.abs
import kotlin.math.pow

/**
 * The twelve synthesised sounds. Every one is an endless generator rather than a loop,
 * so there is no seam to hear at 3 a.m. and no megabytes of assets in the APK.
 *
 * Gain staging: each generator aims for an RMS near 0.12 so swapping sounds does not
 * change perceived loudness. DspTest asserts that band, and that nothing clips.
 */
enum class SoundId(val label: String, val blurb: String) {
    WHITE("White noise", "Flat, bright hiss"),
    PINK("Pink noise", "Softer, balanced hiss"),
    BROWN("Brown noise", "Deep low rumble"),
    RAIN("Rain", "Steady rain on a roof"),
    STORM("Thunderstorm", "Heavy rain, distant thunder"),
    OCEAN("Ocean waves", "Slow swell and foam"),
    STREAM("Stream", "Water over stones"),
    FAN("Fan", "Oscillating fan, motor hum"),
    TRAIN("Train", "Rail rumble and clack"),
    CAMPFIRE("Campfire", "Embers and woody crackle"),
    CAFE("Cafe", "A room of voices and cutlery"),
    WIND("Wind", "Gusts through trees");
}

fun generatorFor(id: SoundId, seed: Long = System.nanoTime()): Generator = when (id) {
    SoundId.WHITE -> WhiteGen(seed)
    SoundId.PINK -> PinkGen(seed)
    SoundId.BROWN -> BrownGen(seed)
    SoundId.RAIN -> RainGen(seed)
    SoundId.STORM -> StormGen(seed)
    SoundId.OCEAN -> OceanGen(seed)
    SoundId.STREAM -> StreamGen(seed)
    SoundId.FAN -> FanGen(seed)
    SoundId.TRAIN -> TrainGen(seed)
    SoundId.CAMPFIRE -> CampfireGen(seed)
    SoundId.CAFE -> CafeGen(seed)
    SoundId.WIND -> WindGen(seed)
}

// ---------------------------------------------------------------- plain noise colours

private class WhiteGen(seed: Long) : Generator {
    private val rng = Rng(seed)

    // Rolled off above 14 kHz: on a phone speaker the very top octave is all rattle.
    private val tilt = Svf(14000f, 0.7f)

    override fun render(out: FloatArray, n: Int) {
        for (i in 0 until n) out[i] = tilt.lowpass(rng.bipolar()) * 0.264f
    }
}

private class PinkGen(seed: Long) : Generator {
    private val rng = Rng(seed)
    private val pink = Pink(rng)

    override fun render(out: FloatArray, n: Int) {
        for (i in 0 until n) out[i] = pink.next() * 0.428f
    }
}

private class BrownGen(seed: Long) : Generator {
    private val rng = Rng(seed)
    private val brown = Brown(rng)

    // A touch of pink keeps it from sounding like a fault rather than a sound.
    private val pink = Pink(rng)

    override fun render(out: FloatArray, n: Int) {
        for (i in 0 until n) out[i] = brown.next() * 0.345f + pink.next() * 0.038f
    }
}

// ------------------------------------------------------------------------------- rain

/**
 * Rain is three things at once: a broadband hiss (spray), a mid body (water on a
 * surface) and discrete drops. The drops are what stop it reading as plain noise.
 */
private open class RainGen(
    seed: Long,
    private val heavy: Boolean = false,
    private val gain: Float = 0.83f,
) : Generator {
    protected val rng = Rng(seed)
    private val pink = Pink(rng)
    private val hiss = Svf(if (heavy) 900f else 1400f, 0.7f)
    private val body = Svf(if (heavy) 700f else 1000f, 0.8f)
    private val drops = VoicePool(12)
    private val dropClock = EventClock(if (heavy) 90f else 55f, rng)
    private val gust = SlowNoise(0.07f, rng)

    override fun render(out: FloatArray, n: Int) {
        for (i in 0 until n) {
            val p = pink.next()
            var s = hiss.highpass(p) * (if (heavy) 0.5f else 0.62f)
            s += body.lowpass(p) * (if (heavy) 0.85f else 0.5f)

            if (dropClock.tick()) {
                // Bright, very short: a drop is mostly its attack.
                drops.trigger(
                    hz = rng.range(1600f, 6500f),
                    q = rng.range(2f, 6f),
                    amp = rng.nextFloat().pow(2) * (if (heavy) 0.7f else 0.5f),
                    tauSec = rng.range(0.002f, 0.012f),
                )
            }
            s += drops.next(rng.bipolar()) * 0.5f

            // Slow swell, so it never sits perfectly still.
            out[i] = s * gain * (0.85f + 0.3f * (gust.next() * 0.5f + 0.5f).coerceIn(0f, 1f))
        }
    }
}

/** Heavier rain plus thunder that arrives every 20–70 seconds. */
private class StormGen(seed: Long) : Generator {
    private val rain = RainGen(seed, heavy = true, gain = 0.56f)
    private val rng = Rng(seed xor 0x5DEECE66DL)
    private val rumbleNoise = Brown(rng)
    private val rumbleFilter = Svf(120f, 0.9f)
    private val rumbleMod = SlowNoise(2.5f, rng)
    private val crack = VoicePool(6)

    private var thunderTimer = (SAMPLE_RATE * 6).toInt()
    private var thunderPos = 0
    private var thunderLen = 0
    private var thunderGain = 0f
    private var sweepFrom = 0f
    private var sweepTo = 0f

    override fun render(out: FloatArray, n: Int) {
        rain.render(out, n)
        for (i in 0 until n) {
            var s = out[i]

            if (thunderLen == 0) {
                if (--thunderTimer <= 0) {
                    thunderLen = (SAMPLE_RATE * rng.range(3.5f, 9f)).toInt()
                    thunderPos = 0
                    thunderGain = rng.range(0.5f, 1f)
                    sweepFrom = rng.range(200f, 420f)
                    sweepTo = rng.range(45f, 90f)
                    // A close strike gets an audible crack ahead of the rumble.
                    if (rng.nextFloat() > 0.55f) {
                        crack.trigger(rng.range(900f, 2600f), 1.2f, thunderGain * 0.9f, 0.06f)
                    }
                    thunderTimer = (SAMPLE_RATE * rng.range(20f, 70f)).toInt()
                }
            } else {
                val t = thunderPos.toFloat() / thunderLen
                // Fast swell, long tail — the shape of a rumble rolling away.
                val env = if (t < 0.06f) t / 0.06f else ((1f - t) / 0.94f).pow(1.6f)
                rumbleFilter.set(sweepFrom + (sweepTo - sweepFrom) * t, 0.9f)
                val wobble = 0.65f + 0.35f * (rumbleMod.next() * 0.5f + 0.5f).coerceIn(0f, 1f)
                s += rumbleFilter.lowpass(rumbleNoise.next()) * env * thunderGain * wobble * 0.9f
                if (++thunderPos >= thunderLen) thunderLen = 0
            }

            s += crack.next(rng.bipolar()) * 0.25f
            out[i] = softClip(s)
        }
    }
}

// ------------------------------------------------------------------------------ water

/**
 * Ocean: an 7–13 s wave cycle. The swell opens a lowpass while the crest adds foam,
 * which is what makes a wave sound like it is coming toward you rather than fading up.
 */
private class OceanGen(seed: Long) : Generator {
    private val rng = Rng(seed)
    private val brown = Brown(rng)
    private val pink = Pink(rng)
    private val bodyFilter = Svf(300f, 0.8f)
    private val foamFilter = Svf(2200f, 0.6f)
    private var period = (SAMPLE_RATE * rng.range(7f, 13f)).toInt()
    private var pos = 0

    override fun render(out: FloatArray, n: Int) {
        for (i in 0 until n) {
            val t = pos.toFloat() / period
            // Rise over the first 55 %, break, then draw back.
            val env = if (t < 0.55f) {
                val u = t / 0.55f
                u * u * (3f - 2f * u)
            } else {
                val u = (t - 0.55f) / 0.45f
                (1f - u).pow(1.4f)
            }
            bodyFilter.set(180f + 900f * env, 0.8f)
            var s = bodyFilter.lowpass(brown.next()) * (0.35f + 0.75f * env)
            s += foamFilter.highpass(pink.next()) * env.pow(3) * 0.85f
            out[i] = s * 0.47f

            if (++pos >= period) {
                pos = 0
                period = (SAMPLE_RATE * rng.range(7f, 13f)).toInt()
            }
        }
    }
}

/**
 * Stream: two noise bands plus resonant gurgles that drift in pitch.
 *
 * Pitched down about a third from the first cut — a bigger body of water over bigger
 * stones. The bands were at 2.4 kHz and 900 Hz, which is a thin trickle.
 */
private class StreamGen(seed: Long) : Generator {
    private val rng = Rng(seed)
    private val high = Svf(1650f, 0.9f)
    private val mid = Svf(620f, 0.8f)
    private val gurgles = VoicePool(10)
    private val clock = EventClock(14f, rng)
    private val tremolo = SlowNoise(7f, rng)
    private val drift = SlowNoise(0.15f, rng)

    override fun render(out: FloatArray, n: Int) {
        for (i in 0 until n) {
            val w = rng.bipolar()
            var s = high.bandpass(w) * 0.85f + mid.bandpass(w) * 0.7f
            if (clock.tick()) {
                val centre = 240f + 480f * (drift.next() * 0.5f + 0.5f).coerceIn(0f, 1f)
                gurgles.trigger(
                    hz = centre * rng.range(0.7f, 2.4f),
                    q = rng.range(6f, 16f),
                    amp = rng.nextFloat().pow(1.5f) * 0.55f,
                    tauSec = rng.range(0.025f, 0.11f),
                )
            }
            s += gurgles.next(rng.bipolar()) * 0.9f
            out[i] = s * 0.575f * (0.85f + 0.25f * (tremolo.next() * 0.5f + 0.5f).coerceIn(0f, 1f))
        }
    }
}

// ------------------------------------------------------------------------ machine hum

/** Box fan: broadband bed, a mains-ish hum, and slow blade beating. */
private class FanGen(seed: Long) : Generator {
    private val rng = Rng(seed)
    private val brown = Brown(rng)
    private val air = Svf(1600f, 0.7f)
    private val hum1 = Sine(101f)
    private val hum2 = Sine(202f)
    private val blade = Sine(23.5f)
    private val wobble = SlowNoise(0.4f, rng)

    /**
     * An oscillating fan sweeps its head side to side over about 7 seconds. What you
     * hear when it turns away is not just less level: the air noise loses its top end
     * first, because the high frequencies are the directional ones. Moving brightness
     * and level together is what makes it read as rotation rather than a tremolo.
     */
    private val sweepPeriod = (SAMPLE_RATE * 7.1f).toInt()
    private var sweepPos = 0

    override fun render(out: FloatArray, n: Int) {
        for (i in 0 until n) {
            // Triangle, not sine: the head turns at a constant rate and reverses at
            // the ends of its travel.
            val phase = sweepPos.toFloat() / sweepPeriod
            val tri = if (phase < 0.5f) phase * 2f else 2f - phase * 2f
            // Facing you at the middle of the sweep, angled away at either end.
            val facing = 0.35f + 0.65f * tri

            air.set(650f + 1500f * facing, 0.7f)

            var s = brown.next() * 0.5f
            s += air.lowpass(rng.bipolar()) * 0.30f * facing
            // The motor hum is the one part that does not swing with the head.
            s += hum1.next() * 0.05f + hum2.next() * 0.025f
            val mod = (0.62f + 0.38f * facing) *
                (1f + 0.05f * blade.next() + 0.06f * wobble.next())
            out[i] = s * 0.75f * mod

            if (++sweepPos >= sweepPeriod) sweepPos = 0
        }
    }
}

/** Train cabin: heavy rumble under a repeating pair of rail joints. */
private class TrainGen(seed: Long) : Generator {
    private val rng = Rng(seed)
    private val brown = Brown(rng)
    private val rumble = Svf(110f, 0.9f)
    private val air = Svf(600f, 0.7f)
    private val clacks = VoicePool(8)
    private val sway = SlowNoise(0.3f, rng)

    private var period = (SAMPLE_RATE * 1.45f).toInt()
    private var pos = 0
    private var secondThumpAt = -1

    override fun render(out: FloatArray, n: Int) {
        for (i in 0 until n) {
            var s = rumble.lowpass(brown.next()) * 1.5f
            s += air.lowpass(rng.bipolar()) * 0.12f

            if (pos == 0) {
                thump(1f)
                secondThumpAt = (SAMPLE_RATE * rng.range(0.13f, 0.19f)).toInt()
            }
            if (pos == secondThumpAt) {
                thump(0.8f)
                secondThumpAt = -1
            }
            s += clacks.next(rng.bipolar()) * 0.55f

            out[i] = softClip(s * 0.25f * (1f + 0.1f * sway.next()))

            if (++pos >= period) {
                pos = 0
                // Track speed drifts a little, so the rhythm is not metronomic.
                period = (SAMPLE_RATE * rng.range(1.35f, 1.6f)).toInt()
            }
        }
    }

    private fun thump(gain: Float) {
        clacks.trigger(rng.range(70f, 110f), 2.2f, gain * 1.5f, 0.09f)
        clacks.trigger(rng.range(1800f, 3200f), 3f, gain * 0.35f, 0.006f)
    }
}

// ------------------------------------------------------------------------------- fire

/**
 * Campfire: a quiet ember bed with crackles that arrive in bursts. The clustering is
 * the whole trick — evenly spaced pops sound like a fault, not a fire.
 *
 * Crackles live at 220–1500 Hz with tails long enough to ring. The first cut put them
 * at 1.2–5.2 kHz with 3 ms tails, which is the sound of static, not burning wood: what
 * you actually hear from a fire is the resonance of the log the steam pocket burst in,
 * and a log is a big object. Occasional lower thumps stand in for one settling.
 */
private class CampfireGen(seed: Long) : Generator {
    private val rng = Rng(seed)
    private val brown = Brown(rng)
    private val bed = Svf(260f, 0.8f)
    private val airy = Svf(900f, 0.7f)
    private val pops = VoicePool(14)
    private val clock = EventClock(9f, rng)
    private val settle = EventClock(0.12f, rng)
    private var burstFrames = 0

    override fun render(out: FloatArray, n: Int) {
        for (i in 0 until n) {
            var s = bed.lowpass(brown.next()) * 0.8f
            s += airy.lowpass(rng.bipolar()) * 0.06f

            if (burstFrames > 0) {
                burstFrames--
                if (burstFrames == 0) clock.rate = 9f
            } else if (rng.nextFloat() < 0.000012f) {
                burstFrames = (SAMPLE_RATE * rng.range(0.1f, 0.35f)).toInt()
                clock.rate = rng.range(45f, 90f)
            }

            if (clock.tick()) {
                pops.trigger(
                    hz = rng.range(220f, 1500f),
                    // Lower Q than before: a woody snap is a broad thock, not a ping.
                    q = rng.range(2.5f, 7f),
                    amp = rng.nextFloat().pow(2.2f) * 1.5f,
                    tauSec = rng.range(0.008f, 0.045f),
                )
            }
            if (settle.tick()) {
                // A log shifting: low, soft, and slow to die away.
                pops.trigger(rng.range(70f, 150f), 2f, rng.range(0.5f, 1.1f), 0.18f)
            }
            s += pops.next(rng.bipolar()) * 0.6f
            out[i] = softClip(s * 0.441f)
        }
    }
}

// ------------------------------------------------------------------------------- room

/**
 * One indistinct talker: noise through two soft formants, gated at syllable rate.
 *
 * Every knob here is set away from "realistic single voice" on purpose. Sharp formants
 * on unpitched noise, gated deeply and slowly, is the recipe for a growl — the ear
 * hears vowel resonances with no pitch behind them and reads it as whispering or worse.
 * So: low Q, a floor under the envelope so syllables never fully close, fast glides,
 * and F1 kept off the chest register. One of these alone still sounds odd. A dozen of
 * them at low level do not, which is the point — a cafe heard from a table away is a
 * blur, and no individual voice should ever step out of it.
 */
private class Talker(private val rng: Rng, private val level: Float) {

    private val pink = Pink(rng)
    // Q around 3 colours the noise without singing. The first cut used 7–11.
    private val f1 = Svf(650f, 3.0f)
    private val f2 = Svf(1600f, 3.5f)

    private val tract = rng.range(0.85f, 1.18f)
    // Faster than real speech; overlapping talkers then blur into a wash.
    private val syllablesPerSec = rng.range(3.6f, 6.2f)

    private var target1 = 650f
    private var target2 = 1600f
    // 30 Hz, not 11: slow glides drag the formants into a moan.
    private val glide1 = OnePole(30f)
    private val glide2 = OnePole(30f)

    private var envelope = FLOOR
    private var envelopeStep = 0f
    private var framesLeft = 0
    private var phraseLeft = (SAMPLE_RATE * rng.range(1f, 4f)).toInt()
    private var resting = false

    fun next(): Float {
        if (--framesLeft <= 0) advance()
        envelope = (envelope + envelopeStep).coerceIn(FLOOR, 1f)

        val p = pink.next()
        // No cubing or squaring of the envelope: that sharpened the gate into a gasp.
        var v = f1.bandpass(p)
        v += f2.bandpass(p) * 0.6f

        f1.set(glide1.process(target1), 3.0f)
        f2.set(glide2.process(target2), 3.5f)

        return v * envelope * level
    }

    private fun advance() {
        if (resting) {
            resting = false
            framesLeft = (SAMPLE_RATE / syllablesPerSec * rng.range(0.5f, 0.9f)).toInt()
            envelopeStep = (1f - FLOOR) / (0.04f * SAMPLE_RATE)
            // F1 stays above 430 Hz. Below that it resonates in the chest and growls.
            target1 = tract * rng.range(430f, 900f)
            target2 = tract * rng.range(1100f, 2200f)
        } else {
            resting = true
            envelopeStep = -(1f - FLOOR) / (0.04f * SAMPLE_RATE)
            framesLeft = (SAMPLE_RATE / syllablesPerSec * rng.range(0.15f, 0.4f)).toInt()
        }
        if (--phraseLeft <= 0) {
            phraseLeft = (SAMPLE_RATE * rng.range(2f, 6f)).toInt()
            if (rng.nextFloat() < 0.5f) {
                resting = true
                envelopeStep = -(1f - FLOOR) / (0.06f * SAMPLE_RATE)
                framesLeft = (SAMPLE_RATE * rng.range(0.5f, 1.6f)).toInt()
            }
        }
    }

    private companion object {
        /** Syllables duck to this, never to silence. Full gating is what stutters. */
        const val FLOOR = 0.45f
    }
}

/**
 * Cafe: fourteen quiet talkers heard through a room, plus cutlery.
 *
 * Nobody is close. Fourteen talkers all at roughly the same low level means no single
 * one is ever resolvable, and a 2.2 kHz lowpass over the whole babble bus stands in for
 * the distance and takes the edge off. The earlier version had two talkers up at 0.7–1.0
 * against five quiet ones, and those two were exactly what you heard.
 */
private class CafeGen(seed: Long) : Generator {
    private val rng = Rng(seed)
    private val brown = Brown(rng)
    private val roomFilter = Svf(180f, 0.8f)
    private val airFilter = Svf(3000f, 0.6f)

    // Distance. Also kills the sibilant edge that made the babble sound close and dry.
    private val distance = Svf(2200f, 0.7f)
    private val chestCut = Svf(200f, 0.7f)

    private val talkers = Array(14) { Talker(rng, level = rng.range(0.16f, 0.34f)) }

    private val clinks = VoicePool(8)
    private val clinkClock = EventClock(0.55f, rng)

    override fun render(out: FloatArray, n: Int) {
        for (i in 0 until n) {
            var babble = 0f
            for (t in talkers) babble += t.next()
            // Roll off both ends of the babble: no chest, no sibilance.
            babble = distance.lowpass(babble)
            babble = chestCut.highpass(babble)

            var s = babble * 0.85f

            // Room tone: HVAC and the low end of everything else in the building.
            // Kept well down. Brown noise carries so much low energy that at any
            // real level it buries the babble and the whole thing turns to rumble.
            s += roomFilter.lowpass(brown.next()) * 0.12f
            s += airFilter.highpass(rng.bipolar()) * 0.012f

            if (clinkClock.tick()) {
                // Two partials, inharmonic, short: a spoon on porcelain.
                val f = rng.range(1800f, 3800f)
                val amp = rng.range(0.12f, 0.4f)
                clinks.trigger(f, 22f, amp, rng.range(0.03f, 0.11f))
                clinks.trigger(f * rng.range(1.9f, 2.7f), 18f, amp * 0.45f, 0.04f)
            }
            s += clinks.next(rng.bipolar()) * 0.5f

            out[i] = softClip(s * 0.781f)
        }
    }
}

// ------------------------------------------------------------------------------- wind

/**
 * Wind: one gust envelope drives amplitude, filter cutoff and leaf hiss together.
 * Coupling them is what separates wind from a volume knob on brown noise.
 */
private class WindGen(seed: Long) : Generator {
    private val rng = Rng(seed)
    private val brown = Brown(rng)
    private val body = Svf(400f, 0.8f)
    private val leaves = Svf(2800f, 0.6f)
    private val whistle = Svf(900f, 7f)
    private val gustSlow = SlowNoise(0.05f, rng)
    private val gustFast = SlowNoise(0.35f, rng)
    private val whistleDrift = SlowNoise(0.09f, rng)

    override fun render(out: FloatArray, n: Int) {
        for (i in 0 until n) {
            val g = (0.55f * (gustSlow.next() * 0.5f + 0.5f) +
                0.45f * (gustFast.next() * 0.5f + 0.5f)).coerceIn(0f, 1f)
            val gust = 0.3f + 0.7f * g

            body.set(220f + 950f * gust, 0.8f)
            var s = body.lowpass(brown.next()) * gust * 1.3f
            s += leaves.highpass(rng.bipolar()) * gust.pow(2.4f) * 0.22f

            whistle.set(600f + 900f * (whistleDrift.next() * 0.5f + 0.5f).coerceIn(0f, 1f), 7f)
            s += whistle.bandpass(rng.bipolar()) * gust.pow(3.5f) * 0.35f

            out[i] = softClip(s * 0.41f)
        }
    }
}

/** Guards against a generator quietly returning NaN and blanking the output. */
internal fun FloatArray.hasNonFinite(n: Int): Boolean {
    for (i in 0 until n) if (!this[i].isFinite() || abs(this[i]) > 4f) return true
    return false
}
