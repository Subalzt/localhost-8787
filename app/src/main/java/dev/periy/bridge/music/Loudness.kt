package dev.periy.bridge.music

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import android.util.LruCache
import dev.periy.bridge.server.MusicLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * How loud a song is, moment by moment: one value (0 to 255) for every [STEP_MS] of it.
 *
 * The player draws its seek bar as these bars, and the cover swells a little on the loud
 * moments. The song is decoded once, from where it lives, at low priority and one at a time;
 * the result is a few kilobytes, kept in the app's files so it is never worked out twice.
 */
class Loudness(ctx: Context, private val music: MusicLibrary) {

    private val app = ctx.applicationContext
    private val dir = File(app.filesDir, "loudness")
    private val mem = LruCache<Long, ByteArray>(48)

    /** One decode at a time, behind whatever is playing. */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "Loudness").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /** Already worked out: from memory, without waiting. */
    fun cached(id: Long): ByteArray? = mem.get(id)

    /** The song's loudness, worked out now if it never was. Null for a file that cannot be read. */
    suspend fun of(id: Long): ByteArray? {
        mem.get(id)?.let { return it }
        val file = File(dir, "$id.$VERSION")
        withContext(Dispatchers.IO) { file.takeIf { it.isFile }?.readBytes() }?.let { mem.put(id, it); return it }
        val made = withContext(worker) { mem.get(id) ?: decode(id) } ?: return null
        mem.put(id, made)
        withContext(Dispatchers.IO) { runCatching { dir.mkdirs(); file.writeBytes(made) } }
        return made
    }

    /**
     * Decodes the song and keeps, for each step, how much sound there is in it (the root mean
     * square of the samples, both channels together). Scaled so the loud parts of this song
     * reach the top: a quiet recording draws as tall as a loud one.
     */
    private suspend fun decode(id: Long): ByteArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(app, music.uri(id), null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val steps = FloatArray(((durUs / 1000 / STEP_MS) + 2).toInt().coerceIn(16, MAX_STEPS))
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var float = false
            var perStep = rate * STEP_MS / 1000 * channels
            var step = 0
            var inStep = 0
            var sum = 0.0
            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    perStep = rate * STEP_MS / 1000 * channels
                    float = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    val ob = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    if (float) {
                        val fb = ob.asFloatBuffer()
                        while (fb.hasRemaining() && step < steps.size) {
                            val v = fb.get().toDouble(); sum += v * v
                            if (++inStep >= perStep) { steps[step++] = sqrt(sum / inStep).toFloat(); sum = 0.0; inStep = 0 }
                        }
                    } else {
                        val sb = ob.asShortBuffer()
                        while (sb.hasRemaining() && step < steps.size) {
                            val v = sb.get() / 32768.0; sum += v * v
                            if (++inStep >= perStep) { steps[step++] = sqrt(sum / inStep).toFloat(); sum = 0.0; inStep = 0 }
                        }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || step >= steps.size) break
                }
            }
            if (inStep > 0 && step < steps.size) steps[step++] = sqrt(sum / inStep).toFloat()
            if (step == 0) return null
            // The loud end of this song, ignoring the odd peak, is the top of the scale.
            val top = steps.copyOf(step).sortedArray()[(step * 0.97).toInt().coerceAtMost(step - 1)].coerceAtLeast(1e-4f)
            return ByteArray(step) { i -> ((steps[i] / top).coerceIn(0f, 1f) * 255f).toInt().toByte() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Cannot read $id", e)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    companion object {
        /** One value per this many milliseconds of the song. */
        const val STEP_MS = 50
        private const val VERSION = "l1"
        /** An hour and a half, at [STEP_MS]: longer recordings are drawn to that point. */
        private const val MAX_STEPS = 108_000
        private const val TAG = "Loudness"

        /** The value at [positionMs], from 0 to 1, between the steps either side. */
        fun at(env: ByteArray?, positionMs: Long): Float {
            if (env == null || env.isEmpty()) return 0f
            val f = positionMs.toFloat() / STEP_MS
            val i = f.toInt().coerceIn(0, env.lastIndex)
            val j = (i + 1).coerceAtMost(env.lastIndex)
            val t = (f - i).coerceIn(0f, 1f)
            return ((env[i].toInt() and 0xFF) * (1 - t) + (env[j].toInt() and 0xFF) * t) / 255f
        }

        /** [count] bars for a seek bar: each the loudest moment in its stretch of the song. */
        fun bars(env: ByteArray?, count: Int): FloatArray {
            val out = FloatArray(count)
            if (env == null || env.isEmpty() || count <= 0) return out
            for (b in 0 until count) {
                val from = (b.toLong() * env.size / count).toInt()
                val to = ((b + 1L) * env.size / count).toInt().coerceAtLeast(from + 1).coerceAtMost(env.size)
                var m = 0
                for (k in from until to) m = maxOf(m, env[k].toInt() and 0xFF)
                out[b] = m / 255f
            }
            return out
        }
    }
}
