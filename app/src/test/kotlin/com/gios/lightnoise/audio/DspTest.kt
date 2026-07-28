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

    @Test
    fun loopGeneratorWrapsWithoutAClick() {
        // A ramp is the worst case: raw looping would jump from +1 back to -1.
        val n = SAMPLE_RATE / 2
        val pcm = FloatArray(n) { -1f + 2f * it / n }
        val gen = LoopGenerator(pcm)
        // Size the buffer to a whole number of blocks: a partly-filled tail leaves
        // zeros behind and the step into them looks exactly like the click we are
        // hunting for. That false positive cost a debugging round the first time.
        val blocks = n * 3 / 512
        val out = FloatArray(blocks * 512)
        val buf = FloatArray(512)
        var p = 0
        while (p < out.size) {
            gen.render(buf, 512)
            buf.copyInto(out, p)
            p += 512
        }
        var maxStep = 0f
        for (i in 1 until out.size) {
            val step = abs(out[i] - out[i - 1])
            if (step > maxStep) maxStep = step
        }
        // The ramp's own slope is 2/n per sample; a click would be near 2.0.
        assertTrue("Loop wrap produced a discontinuity of $maxStep", maxStep < 0.05f)
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
