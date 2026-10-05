package dev.periy.bridge.server

import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The laptop's voice on its way into the call, while its page has it: the page's microphone as it
 * arrives (a sink on that track), kept a moment, and written over what this phone's microphone
 * recorded ([into]), so every connection in the call sends it. WebRTC on Android sends what it
 * records on every connection and takes no other voice on one (see Calls.pageJoin).
 */
class LaptopVoice : AudioTrackSink {
    private val lock = Any()
    /** Mono, at the rate it comes in ([rate]): a second at most. */
    private val ring = ShortArray(96_000)
    private var head = 0
    private var size = 0
    @Volatile private var rate = 48_000
    private var started = false
    private var frac = 0.0

    override fun onData(data: ByteBuffer, bits: Int, sampleRate: Int, channels: Int, frames: Int, at: Long) {
        if (bits != 16 || frames <= 0 || channels <= 0 || sampleRate <= 0) return
        rate = sampleRate
        val b = data.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        synchronized(lock) {
            for (f in 0 until frames) {
                var sum = 0
                for (ch in 0 until channels) sum += b.get(f * channels + ch).toInt()
                if (size == ring.size) { head = (head + 1) % ring.size; size-- }
                ring[(head + size) % ring.size] = (sum / channels).toShort()
                size++
            }
            // No more than 200 ms behind the laptop: past that, back to 50 ms.
            if (size > sampleRate / 5) {
                val drop = size - sampleRate / 20
                head = (head + drop) % ring.size
                size -= drop
            }
        }
    }

    /** Over what the microphone recorded: [bytes] of 16-bit sound in [channels], at [outRate]. */
    fun into(buf: ByteBuffer, channels: Int, outRate: Int, bytes: Int) {
        val out = buf.duplicate().order(ByteOrder.nativeOrder())
        val ch = channels.coerceAtLeast(1)
        val frames = bytes / 2 / ch
        val step = rate.toDouble() / outRate.coerceAtLeast(1)
        synchronized(lock) {
            // 40 ms kept before it starts (and again after it ran dry), so a late packet is not a gap.
            if (!started && size < rate / 25) {
                for (i in 0 until frames * ch) out.putShort(i * 2, 0)
                return
            }
            started = true
            for (f in 0 until frames) {
                val v: Short = if (size > 1) {
                    val a = ring[head].toInt()
                    val b = ring[(head + 1) % ring.size].toInt()
                    val x = (a + (b - a) * frac).toInt().toShort()
                    frac += step
                    while (frac >= 1.0 && size > 1) { frac -= 1.0; head = (head + 1) % ring.size; size-- }
                    x
                } else { started = false; 0 }
                for (c in 0 until ch) out.putShort((f * ch + c) * 2, v)
            }
        }
    }
}

/**
 * Everyone else's voices on their way to the laptop: a sink on each, the frames that come in each
 * 10 ms (one after another, on the thread that plays the call) mixed into one, and sent over the
 * page's data channel as 16-bit mono sound at 48 kHz, a message each 10 ms; dropped rather than
 * queued when the way is slow, as a call does.
 */
class ToLaptop(private val channel: () -> DataChannel?) {
    private val lock = Any()
    private val sinks = HashMap<AudioTrack, AudioTrackSink>()
    private var mix = IntArray(480)
    private var mixLen = 0
    private val seen = HashSet<AudioTrack>()

    /** The voices to mix now. */
    fun hear(tracks: List<AudioTrack>) {
        synchronized(lock) {
            (sinks.keys - tracks.toSet()).forEach { t -> sinks.remove(t)?.let { s -> runCatching { t.removeSink(s) } } }
            for (t in tracks) if (t !in sinks) {
                val s = AudioTrackSink { d, bits, rate, ch, frames, _ -> frame(t, d, bits, rate, ch, frames) }
                sinks[t] = s
                runCatching { t.addSink(s) }
            }
        }
    }

    private fun frame(t: AudioTrack, d: ByteBuffer, bits: Int, rate: Int, ch: Int, frames: Int) {
        if (bits != 16 || rate <= 0 || ch <= 0 || frames <= 0) return
        synchronized(lock) {
            // A voice heard twice: the last 10 ms are all in, and go.
            if (t in seen) flush()
            seen += t
            val n = (frames.toLong() * OUT_RATE / rate).toInt()
            if (mix.size < n) mix = mix.copyOf(n)
            val b = d.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
            for (i in 0 until n) {
                val src = (i.toLong() * rate / OUT_RATE).toInt().coerceAtMost(frames - 1)
                var sum = 0
                for (c in 0 until ch) sum += b.get(src * ch + c).toInt()
                mix[i] += sum / ch
            }
            mixLen = maxOf(mixLen, n)
        }
    }

    private fun flush() {
        val dc = channel()
        if (mixLen > 0 && dc != null && dc.state() == DataChannel.State.OPEN && dc.bufferedAmount() < 48_000) {
            val bb = ByteBuffer.allocateDirect(mixLen * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until mixLen) bb.putShort(mix[i].coerceIn(-32768, 32767).toShort())
            bb.flip()
            runCatching { dc.send(DataChannel.Buffer(bb, true)) }
        }
        java.util.Arrays.fill(mix, 0, mixLen, 0)
        mixLen = 0
        seen.clear()
    }

    fun release() {
        synchronized(lock) {
            sinks.forEach { (t, s) -> runCatching { t.removeSink(s) } }
            sinks.clear()
        }
    }

    private companion object {
        const val OUT_RATE = 48_000
    }
}
