package com.gios.lightnoise.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Environment
import android.util.Log
import java.io.File
import java.nio.ByteOrder

/**
 * User-supplied loops. Drop .ogg / .mp3 / .m4a / .wav files into a "LightNoise" folder
 * on the phone and they appear alongside the synthesised sounds.
 *
 * Files are decoded once to a mono float array and then looped from memory, so the loop
 * point is sample-exact — a MediaPlayer restart leaves an audible gap, which is exactly
 * the artefact you notice when you are trying to fall asleep.
 */
object LoopLibrary {

    private const val TAG = "LightNoise"
    private const val MAX_SECONDS = 150

    /** Every folder we are willing to look in, best first. */
    fun searchPaths(): List<File> {
        val ext = Environment.getExternalStorageDirectory()
        return listOf(
            File(ext, "LightNoise"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "LightNoise"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LightNoise"),
        )
    }

    private val extensions = setOf("ogg", "oga", "mp3", "m4a", "aac", "wav", "flac", "opus")

    /** Audio files found in any search path, sorted by name. */
    fun scan(): List<File> = searchPaths()
        .filter { it.isDirectory }
        .flatMap { it.listFiles()?.toList() ?: emptyList() }
        .filter { it.isFile && it.extension.lowercase() in extensions }
        .sortedBy { it.name.lowercase() }

    /** The folder to tell the user about, whether or not it exists yet. */
    fun primaryPathLabel(): String = "/sdcard/LightNoise"

    /**
     * Decode [file] to mono PCM. Returns null on anything unsupported rather than
     * throwing, because this runs off a user tapping a filename.
     */
    fun decodeMono(file: File): FloatArray? = try {
        decodeInternal(file)
    } catch (t: Throwable) {
        Log.w(TAG, "Could not decode ${file.name}", t)
        null
    }

    private fun decodeInternal(file: File): FloatArray? {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i
                format = f
                break
            }
        }
        if (track < 0 || format == null) {
            extractor.release()
            return null
        }
        extractor.selectTrack(track)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val out = ArrayList<FloatArray>()
        var totalFrames = 0
        val limit = MAX_SECONDS * srcRate
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        while (!sawOutputEos && totalFrames < limit) {
            if (!sawInputEos) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val shorts = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = shorts.remaining() / channels
                    val chunk = FloatArray(frames)
                    for (f in 0 until frames) {
                        var acc = 0f
                        for (c in 0 until channels) acc += shorts.get() / 32768f
                        chunk[f] = acc / channels
                    }
                    out.add(chunk)
                    totalFrames += frames
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
            }
        }

        codec.stop()
        codec.release()
        extractor.release()
        if (totalFrames == 0) return null

        val flat = FloatArray(totalFrames)
        var p = 0
        for (c in out) {
            val take = minOf(c.size, totalFrames - p)
            System.arraycopy(c, 0, flat, p, take)
            p += take
            if (p >= totalFrames) break
        }
        return if (srcRate == SAMPLE_RATE) flat else resample(flat, srcRate, SAMPLE_RATE)
    }

    /** Linear resample. Good enough for a background loop; avoids shipping a library. */
    private fun resample(src: FloatArray, from: Int, to: Int): FloatArray {
        val ratio = to.toDouble() / from
        val n = (src.size * ratio).toInt().coerceAtLeast(1)
        val dst = FloatArray(n)
        for (i in 0 until n) {
            val x = i / ratio
            val i0 = x.toInt()
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val f = (x - i0).toFloat()
            dst[i] = src[i0] * (1f - f) + src[i1] * f
        }
        return dst
    }
}
