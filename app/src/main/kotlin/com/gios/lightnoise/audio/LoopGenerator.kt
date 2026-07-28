package com.gios.lightnoise.audio

/**
 * Loops a decoded buffer forever, seamlessly.
 *
 * The crossfade is baked in once at construction rather than applied while playing.
 * The playable length is shortened to `size - fade`, and the first `fade` samples are
 * the file's head fading in over its discarded tail fading out — so wrapping from the
 * last sample to the first is continuous in the source material. Fading at the wrap
 * point instead (the obvious first attempt) still steps straight from tail to head and
 * clicks once per pass.
 *
 * No Android imports here, so this is covered by the JVM unit tests.
 */
class LoopGenerator(pcm: FloatArray) : Generator {

    private val buf: FloatArray
    private var pos = 0

    init {
        // 50 ms of crossfade, or a quarter of the file when it is shorter than that.
        val fade = minOf(SAMPLE_RATE / 20, pcm.size / 4)
        if (pcm.size < 8 || fade < 1) {
            buf = pcm.copyOf()
        } else {
            val length = pcm.size - fade
            val out = FloatArray(length)
            pcm.copyInto(out, 0, 0, length)
            for (j in 0 until fade) {
                val t = j.toFloat() / fade
                out[j] = pcm[j] * t + pcm[length + j] * (1f - t)
            }
            buf = out
        }
    }

    override fun render(out: FloatArray, n: Int) {
        if (buf.isEmpty()) {
            java.util.Arrays.fill(out, 0, n, 0f)
            return
        }
        for (i in 0 until n) {
            out[i] = buf[pos]
            if (++pos >= buf.size) pos = 0
        }
    }
}
