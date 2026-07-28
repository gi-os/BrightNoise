package com.gios.lightnoise.audio

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The synthesis layer has no Android dependency on purpose, so it can be checked here
 * rather than by ear on the device. What these tests are really guarding against is a
 * filter going unstable minutes into a session — audible as a screech at 3 a.m., and
 * not something a five-second listen would catch.
 */
class DspTest {

    /** 90 seconds is long enough for the slowest cycle (ocean, thunder) to come round. */
    private val seconds = 90
    private val block = 1024

    private data class Stats(
        val rms: Float,
        val peak: Float,
        val dc: Float,
        val nonFinite: Int,
    )

    private fun analyse(gen: Generator, secs: Int = seconds): Stats {
        val buf = FloatArray(block)
        var sumSq = 0.0
        var sum = 0.0
        var peak = 0f
        var bad = 0
        var count = 0L
        val blocks = secs * SAMPLE_RATE / block
        repeat(blocks) {
            gen.render(buf, block)
            for (v in buf) {
                if (!v.isFinite()) {
                    bad++
                    continue
                }
                sumSq += v.toDouble() * v
                sum += v
                if (abs(v) > peak) peak = abs(v)
                count++
            }
        }
        return Stats(
            rms = sqrt(sumSq / count).toFloat(),
            peak = peak,
            dc = (sum / count).toFloat(),
            nonFinite = bad,
        )
    }

    @Test
    fun everySoundIsFiniteAndUnclipped() {
        for (id in SoundId.entries) {
            val s = analyse(generatorFor(id, seed = 12345L))
            assertEquals("${id.name} produced non-finite samples", 0, s.nonFinite)
            assertTrue("${id.name} clipped at ${s.peak}", s.peak <= 1.0001f)
        }
    }

    @Test
    fun everySoundIsAudibleAndRoughlyLevelMatched() {
        val rmsById = SoundId.entries.associateWith { analyse(generatorFor(it, 777L)).rms }
        for ((id, rms) in rmsById) {
            assertTrue("${id.name} is inaudibly quiet (rms $rms)", rms > 0.02f)
            assertTrue("${id.name} is far too loud (rms $rms)", rms < 0.40f)
        }
        // No sound should be more than ~12 dB from any other, or swapping presets
        // becomes a volume change rather than a texture change.
        val loudest = rmsById.values.max()
        val quietest = rmsById.values.min()
        assertTrue(
            "Level spread too wide: $quietest..$loudest",
            loudest / quietest < 4.5f,
        )
    }

    @Test
    fun noSoundDriftsIntoDcOffset() {
        // A leaky integrator with a bad coefficient shows up here long before it is
        // audible: DC eats headroom and can thump the speaker on stop.
        for (id in SoundId.entries) {
            val s = analyse(generatorFor(id, 99L), secs = 60)
            assertTrue("${id.name} has DC offset ${s.dc}", abs(s.dc) < 0.05f)
        }
    }

    @Test
    fun sweepingTheFilterHardStaysStable() {
        // Ocean and wind reset the SVF cutoff every sample; make sure the extremes
        // of that sweep cannot blow up.
        val svf = Svf(1000f, 8f)
        val rng = Rng(1)
        var peak = 0f
        for (i in 0 until SAMPLE_RATE * 5) {
            val hz = 20f + 19000f * ((i % 2000) / 2000f)
            svf.set(hz, 8f)
            val y = svf.bandpass(rng.bipolar())
            assertTrue("SVF went non-finite at $hz Hz", y.isFinite())
            if (abs(y) > peak) peak = abs(y)
        }
        assertTrue("SVF resonance ran away (peak $peak)", peak < 20f)
    }

    @Test
    fun eventClockFiresAtTheRequestedRate() {
        val rng = Rng(4242)
        val clock = EventClock(20f, rng)
        var fires = 0
        repeat(SAMPLE_RATE * 30) { if (clock.tick()) fires++ }
        val perSecond = fires / 30f
        assertTrue("Expected ~20/s, saw $perSecond", perSecond > 15f && perSecond < 26f)
    }

    @Test
    fun voicePoolDecaysToSilenceAndReleasesVoices() {
        val pool = VoicePool(4)
        val rng = Rng(7)
        pool.trigger(hz = 1000f, q = 5f, amp = 1f, tauSec = 0.01f)
        // After 20 time constants the voice must be gone, not merely quiet.
        repeat((0.2f * SAMPLE_RATE).toInt()) { pool.next(rng.bipolar()) }
        var residual = 0f
        repeat(1000) { residual += abs(pool.next(0f)) }
        assertTrue("Voice never released (residual $residual)", residual < 1e-3f)
    }

    /** Energy above [hz] as a fraction of the total. */
    private fun highBandFraction(id: SoundId, hz: Float, secs: Int = 60): Double {
        val gen = generatorFor(id, 5150L)
        val split = OnePole(hz)
        val buf = FloatArray(block)
        var high = 0.0
        var total = 0.0
        repeat(secs * SAMPLE_RATE / block) {
            gen.render(buf, block)
            for (v in buf) {
                val lp = split.process(v)
                val hp = v - lp
                high += hp.toDouble() * hp
                total += v.toDouble() * v
            }
        }
        return high / total
    }

    /** Energy between [lo] and [hi] Hz as a fraction of the total. */
    private fun bandFraction(id: SoundId, lo: Float, hi: Float, secs: Int = 60): Double {
        val gen = generatorFor(id, 5150L)
        val below = OnePole(lo)
        val belowHi = OnePole(hi)
        val buf = FloatArray(block)
        var band = 0.0
        var total = 0.0
        repeat(secs * SAMPLE_RATE / block) {
            gen.render(buf, block)
            for (v in buf) {
                val inBand = belowHi.process(v) - below.process(v)
                band += inBand.toDouble() * inBand
                total += v.toDouble() * v
            }
        }
        return band / total
    }

    /** RMS of each [windowSec] slice, in order — the amplitude envelope. */
    private fun envelope(id: SoundId, secs: Int, windowSec: Float): FloatArray {
        val gen = generatorFor(id, 8080L)
        val window = (windowSec * SAMPLE_RATE).toInt()
        val buf = FloatArray(block)
        val out = ArrayList<Float>()
        var sq = 0.0
        var count = 0
        repeat(secs * SAMPLE_RATE / block) {
            gen.render(buf, block)
            for (v in buf) {
                sq += v.toDouble() * v
                if (++count == window) {
                    out.add(sqrt(sq / count).toFloat())
                    sq = 0.0
                    count = 0
                }
            }
        }
        return out.toFloatArray()
    }

    /** Normalised autocorrelation of an envelope at a lag measured in windows. */
    private fun autocorrelation(env: FloatArray, lag: Int): Double {
        val mean = env.average()
        var num = 0.0
        var den = 0.0
        for (v in env) {
            val d = v - mean
            den += d * d
        }
        for (i in 0 until env.size - lag) num += (env[i] - mean) * (env[i + lag] - mean)
        return num / den * env.size / (env.size - lag)
    }

    @Test
    fun campfireCracklesAreWoodyNotHissy() {
        // The first cut put crackles at 1.2–5.2 kHz with 3 ms tails and read as static
        // rather than burning wood. Keep the energy where a log actually resonates.
        val fraction = highBandFraction(SoundId.CAMPFIRE, 2500f)
        assertTrue("Campfire is too bright: ${fraction * 100}% above 2.5 kHz", fraction < 0.02)
    }

    @Test
    fun fanHeadActuallyOscillates() {
        // Testing this by how far the level swings does not work: brown noise wanders
        // about as much over a second as the fan sweeps. Periodicity is what makes the
        // fan different, so measure that — the envelope must correlate with itself one
        // sweep later, and anti-correlate half a sweep later because the sweep is a
        // triangle. Steady sounds sit inside ±0.11 on both.
        val windowSec = 0.25f
        val env = envelope(SoundId.FAN, secs = 60, windowSec = windowSec)
        val full = autocorrelation(env, (7.1f / windowSec).toInt())
        val half = autocorrelation(env, (3.55f / windowSec).toInt())
        assertTrue("Fan envelope is not periodic at 7.1 s (r=$full)", full > 0.35)
        assertTrue("Fan sweep is not a triangle (r at half period=$half)", half < -0.25)

        // Same measure on a steady sound, so the test fails if the metric goes blunt.
        val steady = envelope(SoundId.PINK, secs = 60, windowSec = windowSec)
        val steadyFull = autocorrelation(steady, (7.1f / windowSec).toInt())
        assertTrue("Metric is not discriminating (pink r=$steadyFull)", abs(steadyFull) < 0.2)
    }

    @Test
    fun cafeEnergySitsInTheSpeechBand() {
        // Formant resonators put the babble where voices live. The bandpassed pink
        // noise this replaced spread much wider, which is part of why it read as fake.
        val cafe = bandFraction(SoundId.CAFE, 300f, 3400f)
        val pink = bandFraction(SoundId.PINK, 300f, 3400f)
        assertTrue("Cafe is not concentrated in the speech band ($cafe)", cafe > 0.45)
        assertTrue("Cafe is no more speech-shaped than pink noise ($cafe vs $pink)", cafe > pink * 1.8)
    }

    @Test
    fun cafeDoesNotGrowl() {
        // The second attempt at the cafe sounded, in Gio's words, demonic. Two
        // measurable causes, both guarded here.
        //
        // One: energy down in the chest register. Sharp formants with F1 dipping to
        // 300 Hz resonate like a throat.
        val low = bandFraction(SoundId.CAFE, 20f, 250f)
        assertTrue("Cafe has too much chest-register energy ($low)", low < 0.20)

        // Two: the level lurching. Deep syllable gating on a few loud talkers made
        // individual voices step out of the mix and read as speech from something
        // that cannot speak. A room heard from a table away is smoother than pink
        // noise on this measure, not rougher.
        val cafe = envelope(SoundId.CAFE, secs = 60, windowSec = 0.2f)
        val pink = envelope(SoundId.PINK, secs = 60, windowSec = 0.2f)
        assertTrue("Cafe lurches: ${cv(cafe)} vs pink ${cv(pink)}", cv(cafe) < cv(pink))
    }

    /** Coefficient of variation of an envelope. */
    private fun cv(env: FloatArray): Double {
        val mean = env.average()
        val variance = env.sumOf { val d = it - mean; d * d } / env.size
        return sqrt(variance) / mean
    }

    @Test
    fun limiterHoldsTheCeilingOnTransients() {
        // The point of the look-ahead. A plain feedforward limiter lets a transient's
        // leading edge through, which is what was hard-clipping rain and campfire once
        // the makeup gain went in.
        val limiter = Limiter(threshold = 0.9f)
        val rng = Rng(31337)
        var peak = 0f

        // Quiet bed with a hard spike every 40 ms, well over the ceiling.
        for (i in 0 until SAMPLE_RATE * 4) {
            val bed = rng.bipolar() * 0.2f
            val spike = if (i % (SAMPLE_RATE / 25) == 0) 2.5f else 0f
            val y = limiter.process(bed + spike)
            if (abs(y) > peak) peak = abs(y)
        }
        assertTrue("Limiter let $peak through against a 0.9 ceiling", peak < 1.0f)
    }

    @Test
    fun limiterLeavesQuietSignalAlone() {
        // It must not squash the bed, or every sound loses its level advantage.
        val limiter = Limiter(threshold = 0.9f)
        val rng = Rng(99)
        var sqIn = 0.0
        var sqOut = 0.0
        // Skip the look-ahead delay's worth of leading zeros before measuring.
        repeat(1000) { limiter.process(rng.bipolar() * 0.3f) }
        repeat(SAMPLE_RATE * 2) {
            val x = rng.bipolar() * 0.3f
            val y = limiter.process(x)
            sqIn += x.toDouble() * x
            sqOut += y.toDouble() * y
        }
        val ratio = sqrt(sqOut / sqIn)
        assertTrue("Limiter is squashing a quiet signal (gain $ratio)", ratio > 0.98)
    }

    @Test
    fun limiterRecoversAfterATransient() {
        val limiter = Limiter(threshold = 0.9f)
        repeat(500) { limiter.process(3f) }
        assertTrue("Limiter did not pull down (${limiter.currentGain})", limiter.currentGain < 0.4f)
        // Release is 180 ms, so a second of silence must bring it back.
        repeat(SAMPLE_RATE) { limiter.process(0f) }
        assertTrue("Limiter never released (${limiter.currentGain})", limiter.currentGain > 0.99f)
    }

    @Test
    fun softClipIsMonotonicAndBounded() {
        var prev = -2f
        var x = -3f
        while (x <= 3f) {
            val y = softClip(x)
            assertTrue("softClip($x) = $y out of range", y in -1f..1f)
            assertTrue("softClip is not monotonic at $x", y >= prev - 1e-6f)
            prev = y
            x += 0.01f
        }
    }

    @Test
    fun noiseColoursAreOrderedByLowFrequencyEnergy() {
        // brown should hold more low energy than pink, and pink more than white.
        fun lowEnergy(id: SoundId): Double {
            val gen = generatorFor(id, 31L)
            val lp = OnePole(200f)
            val buf = FloatArray(block)
            var acc = 0.0
            repeat(20 * SAMPLE_RATE / block) {
                gen.render(buf, block)
                for (v in buf) {
                    val y = lp.process(v)
                    acc += y.toDouble() * y
                }
            }
            return acc
        }
        val white = lowEnergy(SoundId.WHITE)
        val pink = lowEnergy(SoundId.PINK)
        val brown = lowEnergy(SoundId.BROWN)
        assertTrue("pink ($pink) should hold more low energy than white ($white)", pink > white)
        assertTrue("brown ($brown) should hold more low energy than pink ($pink)", brown > pink)
    }
}
