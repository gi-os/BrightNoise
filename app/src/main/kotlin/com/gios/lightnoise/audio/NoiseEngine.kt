package com.gios.lightnoise.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlin.math.abs

/**
 * Two-layer mixer feeding one AudioTrack from a dedicated thread.
 *
 * Everything the UI can change is a @Volatile scalar that the render loop reads once per
 * block and ramps toward, so there are no locks on the audio path and no zipper noise
 * when a slider moves.
 */
class NoiseEngine {

    companion object {
        private const val BLOCK = 1024

        /** Slider moves are smoothed over roughly this long. */
        private const val RAMP_SECONDS = 0.08f

        /**
         * Makeup gain applied after the layer mix.
         *
         * Generators are individually normalised to 0.12 RMS, which at unity put the
         * app about 10 dB under music and podcasts — you had to turn the phone all the
         * way up. 1.7x lands a single layer near -14 dBFS RMS at full master, which is
         * roughly where mastered music sits. The transients this creates are the
         * [Limiter]'s job; without it this would just clip.
         */
        private const val MAKEUP = 1.7f
    }

    @Volatile private var genA: Generator? = null
    @Volatile private var genB: Generator? = null

    /** 0..1 layer levels, and 0..1 master. */
    @Volatile var levelA: Float = 1f
    @Volatile var levelB: Float = 0f
    @Volatile var master: Float = 0.7f

    /** Timer fade multiplier, 1 while playing and driven to 0 to end a session. */
    @Volatile var fade: Float = 1f

    private var thread: Thread? = null
    @Volatile private var running = false

    private var curA = 0f
    private var curB = 0f
    private var curMaster = 0f

    val isRunning: Boolean get() = running

    fun setLayerA(g: Generator?) {
        genA = g
    }

    fun setLayerB(g: Generator?) {
        genB = g
    }

    fun start() {
        if (running) return
        running = true
        // Start from silence and ramp up, so tapping play is not a thud in the speaker.
        curA = 0f; curB = 0f; curMaster = 0f
        thread = Thread({ renderLoop() }, "LightNoise-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun renderLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(BLOCK * 4)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    // A long buffer costs latency we do not care about and saves wakeups.
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
            .build()

        val bufA = FloatArray(BLOCK)
        val bufB = FloatArray(BLOCK)
        val mix = FloatArray(BLOCK)
        val step = 1f / (RAMP_SECONDS * SAMPLE_RATE / BLOCK)
        val limiter = Limiter()

        track.play()
        try {
            while (running) {
                val a = genA
                val b = genB
                if (a != null) a.render(bufA, BLOCK) else java.util.Arrays.fill(bufA, 0f)
                if (b != null) b.render(bufB, BLOCK) else java.util.Arrays.fill(bufB, 0f)

                // Targets are re-read each block; ramping happens across the block.
                val tA = if (a == null) 0f else levelA.coerceIn(0f, 1f)
                val tB = if (b == null) 0f else levelB.coerceIn(0f, 1f)
                val tM = (master.coerceIn(0f, 1f) * fade.coerceIn(0f, 1f))

                val dA = approach(curA, tA, step)
                val dB = approach(curB, tB, step)
                val dM = approach(curMaster, tM, step)

                var ga = curA
                var gb = curB
                var gm = curMaster
                val incA = (dA - curA) / BLOCK
                val incB = (dB - curB) / BLOCK
                val incM = (dM - curMaster) / BLOCK

                for (i in 0 until BLOCK) {
                    ga += incA; gb += incB; gm += incM
                    // Perceptual taper: a linear slider on raw amplitude feels
                    // like it does nothing until the last quarter of its travel.
                    val dry = (bufA[i] * ga * ga + bufB[i] * gb * gb) * gm * gm * MAKEUP
                    // Limit first, then soft clip whatever the limiter's attack let by.
                    mix[i] = softClip(limiter.process(dry))
                }
                curA = dA; curB = dB; curMaster = dM

                var written = 0
                while (written < BLOCK && running) {
                    val r = track.write(mix, written, BLOCK - written, AudioTrack.WRITE_BLOCKING)
                    if (r <= 0) break
                    written += r
                }
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    /** Move [cur] toward [target] by at most [step]. */
    private fun approach(cur: Float, target: Float, step: Float): Float {
        val d = target - cur
        return if (abs(d) <= step) target else cur + if (d > 0) step else -step
    }
}
