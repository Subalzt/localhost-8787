package dev.periy.bridge.music

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import dev.periy.bridge.server.MusicLibrary
import dev.periy.bridge.server.TrackInfoDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Songs made smaller for a thin link (the internet), the way music apps step down: Tidal's and
 * Qobuz's CD quality (16-bit FLAC, lossless at that depth) when the link carries it, else AAC at
 * 256 kbps (Apple Music's). Each is made once, kept in the app's cache, and served like any file,
 * byte ranges and all; the song's own file is never touched.
 */
class Transcoder(ctx: Context, private val music: MusicLibrary) {

    /** CD quality, then AAC at three bitrates (Apple Music's 256, and lower for thinner links, as Spotify's). */
    enum class Mode(val ext: String, val mime: String, val kbps: Int) {
        // AAC as ADTS (frames each with its own small header), which a browser plays while it is
        // still arriving; an MP4 can be played only once its index, written last, is there.
        CD("flac", "audio/flac", 0), AAC("aac", "audio/aac", 256), AAC128("aac", "audio/aac", 128), AAC64("aac", "audio/aac", 64);

        companion object {
            /** From the page's ?q=: cd, aac, aac128 or aac64. */
            fun of(q: String?): Mode? = entries.firstOrNull { it.name.lowercase() == q }
        }
    }

    private val app = ctx.applicationContext
    private val dir = File(app.cacheDir, "smaller")
    private val busy = ConcurrentHashMap<String, CompletableDeferred<File?>>()

    /** Two at a time: the song pressed is made while the next one is prepared, not after it. */
    private val worker = Executors.newFixedThreadPool(2) { r -> Thread(r, "Transcoder").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** The finished copy if there is one, else null. */
    fun ready(id: Long, mode: Mode): File? = file(id, mode).takeIf { it.isFile }?.also { it.setLastModified(System.currentTimeMillis()) }

    /** The copy as it is being written: what a page can be sent before it is finished. */
    fun part(id: Long, mode: Mode): File = File(dir, file(id, mode).name + ".part")

    /** Starts making the copy, if it is not made or being made; the result when it is done. */
    fun start(id: Long, mode: Mode): kotlinx.coroutines.Deferred<File?> =
        scope.async { runCatching { get(id, mode) }.getOrNull() }

    /**
     * What to send over a link of [linkKbps] (measured by the page): the song's own file when it
     * fits with room to spare, CD quality when that would, else AAC. Null means the song's own file.
     */
    fun plan(info: TrackInfoDto, linkKbps: Int): Mode? {
        if (linkKbps <= 0 || info.kbps <= 0) return null
        if (linkKbps >= info.kbps * HEADROOM) return null
        val cd = cdEstimate(info)
        if (lossless(info) && cd < info.kbps && linkKbps >= cd * HEADROOM) return Mode.CD
        // The best AAC the link carries; the smallest when it carries none. Never one hardly
        // smaller than the file itself (an MP3, an AAC already).
        val worth = listOf(Mode.AAC, Mode.AAC128, Mode.AAC64).filter { it.kbps * 1.25 < info.kbps }
        return worth.firstOrNull { linkKbps >= it.kbps * HEADROOM } ?: worth.lastOrNull()
    }

    private fun lossless(info: TrackInfoDto) = info.format.uppercase() in setOf("FLAC", "WAV", "AIFF", "ALAC", "APE", "WV")

    /** About how big the CD-quality copy runs: 16 of the bits, at half or a quarter of the rate. */
    fun cdEstimate(info: TrackInfoDto): Int {
        val bits = if (info.bits > 0) info.bits else 16
        return (info.kbps * (16.0 / bits).coerceAtMost(1.0) / decimation(info.sampleRate)).roundToInt()
    }

    /** What the smaller copy is, for the player's line: its real bitrate once it has been made. */
    fun infoOf(id: Long, info: TrackInfoDto, mode: Mode): TrackInfoDto {
        val rate = info.sampleRate / decimation(info.sampleRate)
        val made = file(id, mode).takeIf { it.isFile }
        val durMs = music.find(id)?.durationMs ?: 0
        val kbps = if (made != null && durMs > 0) (made.length() * 8 / durMs).toInt()
        else if (mode == Mode.CD) cdEstimate(info) else mode.kbps
        return when (mode) {
            Mode.CD -> TrackInfoDto("FLAC", kbps, rate, info.channels, 16)
            else -> TrackInfoDto("AAC", kbps, rate, info.channels, 0)
        }
    }

    private fun file(id: Long, mode: Mode) = File(dir, "$id.$VERSION.${mode.name.lowercase()}.${mode.ext}")

    /** The smaller copy, made now if it never was. Null when this song cannot be made smaller. */
    suspend fun get(id: Long, mode: Mode): File? {
        val f = file(id, mode)
        if (f.isFile) { f.setLastModified(System.currentTimeMillis()); return f }
        val mine = CompletableDeferred<File?>()
        busy.putIfAbsent(f.name, mine)?.let { return it.await() }
        try {
            val made = withContext(worker) { if (f.isFile) f else make(id, mode, f) }
            mine.complete(made)
            return made
        } catch (e: Throwable) {
            mine.complete(null)
            throw e
        } finally {
            busy.remove(f.name)
        }
    }

    private fun make(id: Long, mode: Mode, out: File): File? {
        dir.mkdirs()
        trim()
        val tmp = File(dir, out.name + ".part")
        val t0 = System.currentTimeMillis()
        val ok = runCatching { if (mode == Mode.CD) flac(id, tmp) else aac(id, tmp, mode.kbps) }
            .onFailure { Log.w(TAG, "Cannot make $id smaller ($mode)", it) }.getOrDefault(false)
        if (!ok || !tmp.isFile || tmp.length() == 0L) { tmp.delete(); return null }
        if (!tmp.renameTo(out)) { tmp.delete(); return null }
        Log.i(TAG, "Made $id $mode: ${out.length() / 1024} KB in ${System.currentTimeMillis() - t0} ms")
        return out
    }

    /** Keeps the cache under [CACHE_BYTES], the least recently played going first. */
    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= CACHE_BYTES) break
            total -= f.length(); f.delete()
        }
    }

    // ------------------------------------------------------------------ CD quality: 16-bit FLAC

    private fun flac(id: Long, out: File): Boolean {
        out.delete()
        RandomAccessFile(out, "rw").use { raf ->
            var enc: Feeder? = null
            var info = -1L        // where STREAMINFO's body starts, to put the length in at the end
            var header = false
            var rate = 0
            var ch = 0
            decode(id, start = { r, c ->
                rate = r; ch = c
                val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, r, c)
                f.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
                f.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                enc = Feeder(codec, r, c, onFormat = {}) { buf, bi ->
                    val bytes = ByteArray(bi.size).also { buf.position(bi.offset); buf.get(it) }
                    if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        if (header) return@Feeder
                        header = true
                        if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "fLaC") {
                            info = raf.filePointer + 8
                            raf.write(bytes)
                        } else if (bytes.size == 34) {
                            raf.write("fLaC".toByteArray(Charsets.US_ASCII)); raf.write(byteArrayOf(0x80.toByte(), 0, 0, 34))
                            info = raf.filePointer
                            raf.write(bytes)
                        }
                        return@Feeder
                    }
                    if (!header) {
                        header = true
                        raf.write("fLaC".toByteArray(Charsets.US_ASCII)); raf.write(byteArrayOf(0x80.toByte(), 0, 0, 34))
                        info = raf.filePointer
                        raf.write(streamInfo(rate, ch, 0))
                    }
                    raf.write(bytes)
                }
            }, take = { s, n -> enc!!.feed(s, n) })
            val e = enc ?: return false
            e.finish()
            e.release()
            // The length, so players know how long it runs (the encoder writes it as unknown).
            if (info >= 0 && e.frames > 0) {
                raf.seek(info + 13)
                val hi = raf.read()
                raf.seek(info + 13)
                raf.write((hi and 0xF0) or ((e.frames ushr 32).toInt() and 0x0F))
                raf.writeInt((e.frames and 0xFFFFFFFFL).toInt())
            }
            return header && raf.length() > 42
        }
    }

    /** A STREAMINFO body for [rate] Hz, [ch] channels, 16 bits, [frames] long (0: unknown). */
    private fun streamInfo(rate: Int, ch: Int, frames: Long): ByteArray {
        val b = ByteBuffer.allocate(34).order(ByteOrder.BIG_ENDIAN)
        b.putShort(16); b.putShort(4608.toShort())
        b.put(ByteArray(6))
        val packed = (rate.toLong() shl 44) or ((ch - 1).toLong() shl 41) or (15L shl 36) or (frames and 0xFFFFFFFFFL)
        b.putLong(packed)
        b.put(ByteArray(16))
        return b.array()
    }

    // ------------------------------------------------------------------ AAC, 256 kbps

    private fun aac(id: Long, out: File, kbps: Int): Boolean {
        out.delete()
        java.io.FileOutputStream(out).use { o ->
            var enc: Feeder? = null
            var frames = 0
            var head = ByteArray(0)
            decode(id, start = { r, c ->
                val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, r, c)
                f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                f.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                head = adtsHead(r, c)
                enc = Feeder(codec, r, c, onFormat = {}) { buf, bi ->
                    if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || bi.size == 0) return@Feeder
                    val body = ByteArray(bi.size).also { buf.position(bi.offset); buf.get(it) }
                    // Each frame with its own header, written at once, so it can go to a page straight away.
                    o.write(adts(head, body.size))
                    o.write(body)
                    frames++
                }
            }, take = { s, n -> enc!!.feed(s, n) })
            val e = enc ?: return false
            e.finish()
            e.release()
            return frames > 0
        }
    }

    /** The parts of an ADTS header that stay the same: AAC LC, the sample rate, the channels. */
    private fun adtsHead(rate: Int, channels: Int): ByteArray {
        val idx = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
            .indexOf(rate).let { if (it < 0) 4 else it }
        return byteArrayOf(idx.toByte(), channels.toByte())
    }

    /** One frame's 7-byte ADTS header, for [len] bytes of AAC after it. */
    private fun adts(head: ByteArray, len: Int): ByteArray {
        val idx = head[0].toInt(); val ch = head[1].toInt(); val full = len + 7
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            ((1 shl 6) or (idx shl 2) or (ch shr 2)).toByte(),
            (((ch and 3) shl 6) or (full shr 11)).toByte(),
            ((full shr 3) and 0xFF).toByte(),
            (((full and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    // ------------------------------------------------------------------ decoding

    /**
     * Decodes the song and hands on interleaved 16-bit samples as they come: brought down to at
     * most 48 kHz (a steep filter, then every second or fourth sample) and to 16 bits with a
     * little triangular dither, so the quiet parts keep their detail instead of stepping.
     */
    private fun decode(id: Long, start: (rate: Int, channels: Int) -> Unit, take: (ShortArray, Int) -> Unit) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(app, music.uri(id), null)
            val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            ex.selectTrack(t)
            val fmt = ex.getTrackFormat(t)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            // No float asked for: this phone's FLAC decoder says "float" and hands out 32-bit whole
            // numbers. What it says it gives is read below, and checked.
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val bi = MediaCodec.BufferInfo()
            var inputDone = false
            var enc = AudioFormat.ENCODING_PCM_16BIT
            var dec: Decimator? = null
            var shorts = ShortArray(0)
            var floats = FloatArray(0)
            val rnd = java.util.Random(1)
            while (true) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(bi, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) enc = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    Log.i(TAG, "Decoding $id as $of")
                } else if (o >= 0) {
                    if (dec == null) {
                        Log.i(TAG, "First output of $id: ${bi.size} bytes, encoding $enc, $rate Hz, $ch ch")
                        require(ch <= 2) { "$ch channels" }
                        val d = decimation(rate)
                        dec = Decimator(d, ch)
                        start(rate / d, ch)
                    }
                    val ob = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    ob.position(bi.offset); ob.limit(bi.offset + bi.size)
                    val n = read(ob, enc, bi.size) { size -> if (floats.size < size) floats = FloatArray(size); floats }
                    c.releaseOutputBuffer(o, false)
                    // "Float" that is really 32-bit numbers shows itself as NaNs or wild values.
                    if (enc == AudioFormat.ENCODING_PCM_FLOAT && (0 until n).any { k -> val v = floats[k]; v.isNaN() || v > 8f || v < -8f }) {
                        Log.w(TAG, "Float output of $id is 32-bit numbers: read as that")
                        enc = AudioFormat.ENCODING_PCM_32BIT
                        ob.position(bi.offset); ob.limit(bi.offset + bi.size)
                        read(ob, enc, bi.size) { floats }
                    }
                    val got = dec.push(floats, n)
                    if (shorts.size < got.size) shorts = ShortArray(got.size)
                    for (k in got.indices) {
                        val s = got[k]
                        val v = (if (s.isNaN()) 0f else s) * 32767f + (rnd.nextFloat() - rnd.nextFloat())
                        shorts[k] = v.roundToInt().coerceIn(-32768, 32767).toShort()
                    }
                    if (got.isNotEmpty()) take(shorts, got.size)
                    if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    /** A decoder's samples as floats from -1 to 1, in whatever form [enc] says it hands out; how many. */
    private fun read(ob: ByteBuffer, enc: Int, size: Int, into: (Int) -> FloatArray): Int = when (enc) {
        AudioFormat.ENCODING_PCM_FLOAT -> { val n = size / 4; ob.asFloatBuffer().get(into(n), 0, n); n }
        AudioFormat.ENCODING_PCM_32BIT -> {
            val n = size / 4; val a = into(n); val ib = ob.asIntBuffer()
            for (k in 0 until n) a[k] = ib.get() / 2147483648f
            n
        }
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val n = size / 3; val a = into(n)
            for (k in 0 until n) {
                val b0 = ob.get().toInt() and 0xFF; val b1 = ob.get().toInt() and 0xFF; val b2 = ob.get().toInt()
                a[k] = ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
            }
            n
        }
        else -> { val n = size / 2; val a = into(n); val sb = ob.asShortBuffer(); for (k in 0 until n) a[k] = sb.get() / 32768f; n }
    }

    /** Low-pass then keep every [f]th sample: a windowed-sinc filter, the history carried between pieces. */
    private class Decimator(val f: Int, val ch: Int) {
        private val taps: FloatArray = if (f == 1) FloatArray(0) else {
            val n = 48 * f + 1
            // Halving: a half-band filter, every other tap of which is zero (and skipped below);
            // what folds back lands above 22 kHz. A quarter: a plain low-pass under the new top.
            val fc = if (f == 2) 0.25 else 0.5 / f * 0.92
            val m = (n - 1) / 2.0
            val h = DoubleArray(n) { k ->
                val x = k - m
                val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                // Blackman-Harris: about 92 dB down past the cut, under 16 bits' own noise floor.
                val w = 0.35875 - 0.48829 * cos(2 * PI * k / (n - 1)) + 0.14128 * cos(4 * PI * k / (n - 1)) - 0.01168 * cos(6 * PI * k / (n - 1))
                sinc * w
            }
            val sum = h.sum()
            FloatArray(n) { (h[it] / sum).toFloat() }
        }
        /** The taps that are not zero, and where they sit. */
        private val at = taps.indices.filter { kotlin.math.abs(taps[it]) > 1e-9f }.toIntArray()
        private val tv = FloatArray(at.size) { taps[at[it]] }
        private val hist = Array(ch) { FloatArray(maxOf(0, taps.size - 1)) }
        private var next = maxOf(0, taps.size - 1)

        fun push(inter: FloatArray, count: Int): FloatArray {
            val frames = count / ch
            if (f == 1) return inter.copyOf(frames * ch)
            val n = taps.size
            val len = hist[0].size + frames
            val outs = if (next >= len) 0 else (len - 1 - next) / f + 1
            val out = FloatArray(outs * ch)
            for (c in 0 until ch) {
                val comb = FloatArray(len)
                System.arraycopy(hist[c], 0, comb, 0, hist[c].size)
                var j = hist[c].size
                for (fr in 0 until frames) comb[j++] = inter[fr * ch + c]
                var p = next
                var o = 0
                while (p < len) {
                    var acc = 0f
                    var k = 0
                    while (k < tv.size) { acc += tv[k] * comb[p - at[k]]; k++ }
                    out[o * ch + c] = acc
                    o++; p += f
                }
                System.arraycopy(comb, len - (n - 1), hist[c], 0, n - 1)
            }
            next = next + outs * f - (len - (n - 1))
            return out
        }
    }

    /** Feeds an encoder interleaved 16-bit samples and drains what it makes. */
    private class Feeder(
        val c: MediaCodec, val rate: Int, val ch: Int,
        val onFormat: (MediaFormat) -> Unit,
        val onOut: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    ) {
        var frames = 0L
        private val bi = MediaCodec.BufferInfo()
        private var ended = false

        fun feed(s: ShortArray, count: Int) {
            var off = 0
            while (off < count) {
                val i = c.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val b = c.getInputBuffer(i)!!
                    b.clear()
                    val n = minOf(count - off, b.remaining() / 2 / ch * ch)
                    b.order(ByteOrder.nativeOrder()).asShortBuffer().put(s, off, n)
                    c.queueInputBuffer(i, 0, n * 2, frames * 1_000_000 / rate, 0)
                    frames += n / ch
                    off += n
                }
                drain(false)
            }
        }

        fun finish() {
            var tries = 0
            while (tries++ < 500) {
                val i = c.dequeueInputBuffer(10_000)
                if (i >= 0) { c.queueInputBuffer(i, 0, 0, frames * 1_000_000 / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
                drain(false)
            }
            tries = 0
            while (!ended && tries++ < 1000) drain(true)
        }

        private fun drain(wait: Boolean) {
            while (true) {
                val o = c.dequeueOutputBuffer(bi, if (wait) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(c.outputFormat)
                    o >= 0 -> {
                        val buf = c.getOutputBuffer(o)!!
                        if (bi.size > 0) { buf.position(bi.offset); buf.limit(bi.offset + bi.size); onOut(buf, bi) }
                        c.releaseOutputBuffer(o, false)
                        if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { ended = true; return }
                    }
                    else -> return
                }
            }
        }

        fun release() {
            runCatching { c.stop() }
            runCatching { c.release() }
        }
    }

    companion object {
        private const val TAG = "Transcoder"
        /** t2: t1's copies were made from misread samples. */
        private const val VERSION = "t3"
        /** The link has to carry this much more than the song needs: room for the rest and for dips. */
        const val HEADROOM = 1.25
        private const val CACHE_BYTES = 1L shl 30

        /** 88.2/96 kHz halve, 176.4/192 kHz quarter; up to 48 kHz stays. */
        fun decimation(rate: Int): Int = when {
            rate > 96_000 -> 4
            rate > 48_000 -> 2
            else -> 1
        }
    }
}
