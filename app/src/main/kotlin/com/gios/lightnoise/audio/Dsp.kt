package com.gios.lightnoise.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.tan

/**
 * Filter and noise primitives. Deliberately free of any Android import so the whole
 * synthesis layer can be exercised by JVM unit tests (see DspTest).
 */

const val SAMPLE_RATE = 44100

/** Everything a mixer layer can be: fills [out] with mono samples in roughly -1..1. */
interface Generator {
    fun render(out: FloatArray, n: Int)
}

/**
 * xorshift64*. java.util.Random synchronises and boxes its way through a nextFloat;
 * this runs in a tight audio loop pulling several samples per frame, so it gets its own.
 */
class Rng(seed: Long) {
    private var s: Long = if (seed == 0L) 0x2545F4914F6CDD1DL else seed

    fun nextLong(): Long {
        var x = s
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        s = x
        return x * -0x61c8864680b583ebL
    }

    /** Uniform in 0..1. */
    fun nextFloat(): Float = ((nextLong() ushr 40).toInt()) / 16777216f

    /** Uniform in -1..1 — the white-noise source for everything below. */
    fun bipolar(): Float = nextFloat() * 2f - 1f

    fun range(lo: Float, hi: Float): Float = lo + (hi - lo) * nextFloat()
}

/** One-pole lowpass. Cheap, no resonance, good for beds and control signals. */
class OnePole(hz: Float) {
    private var a = coeff(hz)
    private var y = 0f

    fun setHz(hz: Float) {
        a = coeff(hz)
    }

    fun process(x: Float): Float {
        y += a * (x - y)
        return y
    }

    val value: Float get() = y

    private fun coeff(hz: Float): Float {
        val f = hz.coerceIn(0.001f, SAMPLE_RATE * 0.45f)
        return (1.0 - exp(-2.0 * PI * f / SAMPLE_RATE)).toFloat()
    }
}

/**
 * Andy Simper's topology-preserving-transform state variable filter. Picked over a
 * naive biquad because the cutoff is swept every sample in several of the sounds
 * (ocean, wind, thunder) and this stays stable while it moves.
 */
class Svf(hz: Float, q: Float) {
    private var g = 0f
    private var k = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var a3 = 0f
    private var ic1 = 0f
    private var ic2 = 0f

    var low = 0f; private set
    var band = 0f; private set
    var high = 0f; private set

    init {
        set(hz, q)
    }

    fun set(hz: Float, q: Float) {
        val f = hz.coerceIn(10f, SAMPLE_RATE * 0.45f)
        g = tan(PI * f / SAMPLE_RATE).toFloat()
        k = 1f / q.coerceAtLeast(0.05f)
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    fun process(x: Float) {
        val v3 = x - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2f * v1 - ic1
        ic2 = 2f * v2 - ic2
        band = v1
        low = v2
        high = x - k * v1 - v2
    }

    fun lowpass(x: Float): Float {
        process(x); return low
    }

    fun bandpass(x: Float): Float {
        process(x); return band
    }

    fun highpass(x: Float): Float {
        process(x); return high
    }
}

/** Pink noise via Paul Kellet's economy filter — flat-ish -3 dB/oct across the band. */
class Pink(private val rng: Rng) {
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var b3 = 0f
    private var b4 = 0f
    private var b5 = 0f
    private var b6 = 0f

    fun next(): Float {
        val w = rng.bipolar()
        b0 = 0.99886f * b0 + w * 0.0555179f
        b1 = 0.99332f * b1 + w * 0.0750759f
        b2 = 0.96900f * b2 + w * 0.1538520f
        b3 = 0.86650f * b3 + w * 0.3104856f
        b4 = 0.55000f * b4 + w * 0.5329522f
        b5 = -0.7616f * b5 - w * 0.0168980f
        val out = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362f
        b6 = w * 0.115926f
        return out * 0.16f
    }
}

/**
 * Brown noise: leaky integration of white, so -6 dB/oct. The leak stops DC wander.
 *
 * Output RMS lands near 0.35 by construction: a uniform source has variance 1/3, the
 * 0.06 input scaler takes that to 1.2e-3, and the integrator's 1/(1 - leak²) gain
 * brings it to ~0.12, i.e. 0.35 RMS. Getting this wrong is how the first cut peaked
 * at 3.5 and clipped everything downstream of it.
 */
class Brown(private val rng: Rng, private val leak: Float = 0.995f) {
    private var y = 0f

    fun next(): Float {
        y = leak * y + rng.bipolar() * 0.06f
        return y
    }
}

/** Slow band-limited random control signal, for gusts, wobble and murmur. */
class SlowNoise(hz: Float, private val rng: Rng) {
    private val lp1 = OnePole(hz)
    private val lp2 = OnePole(hz)

    /** Roughly -1..1, though two poles of smoothing keep it well inside that. */
    fun next(): Float = lp2.process(lp1.process(rng.bipolar())) * 3.2f

    /** Same signal remapped into [lo]..[hi]. */
    fun unipolar(lo: Float, hi: Float): Float =
        lo + (hi - lo) * ((next() * 0.5f + 0.5f).coerceIn(0f, 1f))
}

/**
 * A pool of band-passed, exponentially-decaying voices. Rain drops, fire crackles,
 * stream gurgles and rail clacks are all the same shape: excite a resonator, let it
 * ring out. Fixed size so the audio thread never allocates.
 */
class VoicePool(size: Int) {
    private class Voice {
        val svf = Svf(1000f, 4f)
        var amp = 0f
        var decay = 0f
        var active = false
    }

    private val voices = Array(size) { Voice() }
    private var next = 0

    /**
     * @param hz resonator centre frequency
     * @param q  resonator sharpness
     * @param amp initial gain
     * @param tauSec time to decay by 1/e
     */
    fun trigger(hz: Float, q: Float, amp: Float, tauSec: Float) {
        // Round-robin steal. A stolen tail is inaudible under the noise bed.
        val v = voices[next]
        next = (next + 1) % voices.size
        v.svf.set(hz, q)
        v.amp = amp
        v.decay = exp(-1.0 / (tauSec.coerceAtLeast(0.0005f) * SAMPLE_RATE)).toFloat()
        v.active = true
    }

    /** Sum of every ringing voice for this frame, driven by white noise [excite]. */
    fun next(excite: Float): Float {
        var sum = 0f
        for (v in voices) {
            if (!v.active) continue
            sum += v.svf.bandpass(excite) * v.amp
            v.amp *= v.decay
            if (v.amp < 1e-4f) v.active = false
        }
        return sum
    }
}

/** Poisson-ish event clock: returns true on frames where an event should fire. */
class EventClock(ratePerSec: Float, private val rng: Rng) {
    private var countdown = 0
    var rate = ratePerSec

    fun tick(): Boolean {
        if (countdown > 0) {
            countdown--
            return false
        }
        // Exponential inter-arrival, approximated well enough by -ln(u)/rate.
        val u = rng.nextFloat().coerceAtLeast(1e-6f)
        val gap = -kotlin.math.ln(u.toDouble()) / rate.coerceAtLeast(0.0001f)
        countdown = (gap * SAMPLE_RATE).toInt().coerceAtLeast(1)
        return true
    }
}

/** Cheap sine oscillator for hums and clinks. */
class Sine(hz: Float) {
    private var phase = 0f
    private var inc = hz / SAMPLE_RATE

    fun setHz(hz: Float) {
        inc = hz / SAMPLE_RATE
    }

    fun next(): Float {
        phase += inc
        if (phase >= 1f) phase -= 1f
        // Parabolic sine approximation: within 0.06 % and no trig call per sample.
        val t = phase * 2f - 1f
        val a = if (t < 0f) -t else t
        return 4f * t * (1f - a)
    }
}

/** Soft clip. Guards the mix bus when two layers and a transient land together. */
fun softClip(x: Float): Float = when {
    x > 1.6f -> 1f
    x < -1.6f -> -1f
    else -> x - x * x * x * 0.13f
}.coerceIn(-1f, 1f)
