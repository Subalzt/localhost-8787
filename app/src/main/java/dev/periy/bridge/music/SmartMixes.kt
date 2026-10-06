package dev.periy.bridge.music

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import dev.periy.bridge.server.MusicLibrary
import dev.periy.bridge.server.TrackDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/** How often and how lately a song was played: counted once it has played half way, or 30 s. */
@Serializable
data class PlayStat(val count: Int = 0, val last: Long = 0)

/**
 * Plays, kept on the phone: the phone's own player counts a song when it has played half of it or
 * 30 s (whichever is first); a page playing from the phone says so the same way (/api/music/played).
 */
class PlayStats(ctx: Context) {
    private val file = File(ctx.applicationContext.filesDir, "plays.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = MapSerializer(Long.serializer(), PlayStat.serializer())
    private val _all = MutableStateFlow(runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyMap()))
    val all: StateFlow<Map<Long, PlayStat>> = _all

    @Synchronized
    fun played(id: Long, at: Long = System.currentTimeMillis()) {
        val old = _all.value[id] ?: PlayStat()
        _all.value = _all.value + (id to PlayStat(old.count + 1, at))
        runCatching { file.writeText(json.encodeToString(ser, _all.value)) }
    }

    companion object {
        /** Counted as played: half the song, or this much of it. */
        const val ENOUGH_MS = 30_000L
    }
}

/**
 * What a song sounds like, measured on the phone from a minute of it (from a fifth of the way in):
 * how loud ([db], the mean level in dBFS, and [peakDb], its loud moments), how fast ([bpm], from
 * the beat of its onsets), how busy ([onset], how much the sound jumps), and how bright ([hz],
 * the mean frequency of its sound, from how fast the wave moves against how big it is).
 */
@Serializable
data class SoundProfile(val db: Float, val peakDb: Float, val bpm: Float, val onset: Float, val hz: Float)

/**
 * The library's sound, worked out once per song in the background (one at a time, at the lowest
 * priority, a moment between songs), kept in the app's files; and the mixes made from it and
 * from the plays: most played, not heard in a while, loudest, quietest, and by mood.
 */
class SmartMixes(ctx: Context, private val music: MusicLibrary, val plays: PlayStats) {
    private val app = ctx.applicationContext
    private val file = File(app.filesDir, "sound-profiles.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = MapSerializer(Long.serializer(), SoundProfile.serializer())
    private val _profiles = MutableStateFlow(runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyMap()))
    val profiles: StateFlow<Map<Long, SoundProfile>> = _profiles

    /** Songs measured so far and in all, while it is working through the library; null when done. */
    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress: StateFlow<Pair<Int, Int>?> = _progress

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST); r.run() }, "SoundProfile").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    /** Measures every song not yet measured (and forgets songs gone from the library). */
    fun scan() {
        if (job?.isActive == true) return
        job = scope.launch {
            val tracks = withContext(Dispatchers.IO) { music.tracks() }
            if (tracks.isEmpty()) return@launch
            val ids = tracks.map { it.id }.toSet()
            var have = _profiles.value.filterKeys { it in ids }
            val todo = tracks.filter { it.id !in have }
            if (todo.isEmpty()) { _progress.value = null; return@launch }
            Log.i(TAG, "measuring ${todo.size} songs")
            val t0 = System.currentTimeMillis()
            todo.forEachIndexed { i, t ->
                if (!isActive) return@launch
                _progress.value = have.size to tracks.size
                val p = withContext(worker) { runCatching { measure(t) }.onFailure { Log.w(TAG, "could not measure ${t.title}: ${it.message}") }.getOrNull() }
                if (p != null) have = have + (t.id to p)
                // Saved every so often, so a stop part way keeps what was done.
                if (i % 20 == 19 || i == todo.lastIndex) {
                    _profiles.value = have
                    withContext(Dispatchers.IO) { runCatching { file.writeText(json.encodeToString(ser, have)) } }
                }
                delay(150)
            }
            _profiles.value = have
            _progress.value = null
            Log.i(TAG, "measured ${todo.size} songs in ${(System.currentTimeMillis() - t0) / 1000} s")
        }
    }

    // ------------------------------------------------------------------ measuring

    private fun measure(t: TrackDto): SoundProfile? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(app, music.uri(t.id), null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else t.durationMs * 1000
            val startUs = (dur / 5).coerceAtMost(90_000_000L)
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var enc = android.media.AudioFormat.ENCODING_PCM_16BIT
            val a = Analysis()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            val wantSamples = { rate.toLong() * SECONDS }
            var got = 0L
            try {
                while (got < wantSamples()) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) enc = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        got += a.feed(buf, channels, enc)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                runCatching { codec.stop() }; codec.release()
            }
            return a.result(rate)
        } finally {
            ex.release()
        }
    }

    /** Frames of [FRAME] samples (mono), each: its energy, and its first difference's (for brightness). */
    private class Analysis {
        val energy = ArrayList<Float>(6000)
        val diff = ArrayList<Float>(6000)
        private var e = 0.0; private var d = 0.0; private var n = 0; private var prev = 0f

        fun feed(b: java.nio.ByteBuffer, ch: Int, enc: Int): Long {
            var frames = 0L
            val float = enc == android.media.AudioFormat.ENCODING_PCM_FLOAT
            val step = if (float) 4 else 2
            while (b.remaining() >= ch * step) {
                var s = 0f
                for (c in 0 until ch) s += if (float) b.float else b.short / 32768f
                s /= ch
                e += s * s; val dd = s - prev; d += dd * dd; prev = s
                if (++n == FRAME) { energy += (e / n).toFloat(); diff += (d / n).toFloat(); e = 0.0; d = 0.0; n = 0 }
                frames++
            }
            return frames
        }

        fun result(rate: Int): SoundProfile? {
            if (energy.size < 200) return null
            val db = energy.map { 10 * log10(it.toDouble() + 1e-10) }
            val sorted = db.sorted()
            // Silence (fades, gaps) does not count towards how loud a song is.
            val loud = db.filter { it > sorted[sorted.size / 10] }
            val mean = 10 * log10(loud.map { Math.pow(10.0, it / 10) }.average())
            val peak = sorted[(sorted.size * 0.95).toInt()]
            // Brightness: for a tone of frequency f, diff²/x² = 2 - 2cos(2πf/rate); its inverse gives the mean frequency.
            val r = diff.sum() / energy.sum().coerceAtLeast(1e-9f)
            val hz = (rate / (2 * PI) * acos((1 - r / 2).coerceIn(-1f, 1f).toDouble())).toFloat()
            // Onsets: how much louder each frame is than the last (in log terms), only rises.
            val on = FloatArray(db.size)
            for (i in 1 until db.size) on[i] = (db[i] - db[i - 1]).toFloat().coerceAtLeast(0f)
            val onsetMean = on.average().toFloat()
            return SoundProfile(mean.toFloat(), peak.toFloat(), tempo(on, rate.toFloat() / FRAME), onsetMean, hz)
        }

        /** The beat: the lag (60 to 200 a minute) at which the onsets line up best, nudged towards 120. */
        private fun tempo(on: FloatArray, fps: Float): Float {
            val m = on.average().toFloat()
            val x = FloatArray(on.size) { on[it] - m }
            var best = 0f; var bestBpm = 0f
            val minLag = (fps * 60 / 200).toInt().coerceAtLeast(1)
            val maxLag = (fps * 60 / 60).toInt()
            for (lag in minLag..maxLag) {
                var s = 0f
                for (i in lag until x.size) s += x[i] * x[i - lag]
                val bpm = 60 * fps / lag
                val prior = exp(-0.5 * (ln(bpm / 120.0) / 0.5).let { it * it }).toFloat()
                val score = s / (x.size - lag) * prior
                if (score > best) { best = score; bestBpm = bpm }
            }
            return bestBpm
        }
    }

    // ------------------------------------------------------------------ the mixes

    /** One mix: what it is called, a line about it, and its songs in order. */
    data class Mix(val key: String, val title: String, val line: String, val tracks: List<TrackDto>)

    fun mixes(tracks: List<TrackDto>, now: Long = System.currentTimeMillis()): List<Mix> {
        val out = ArrayList<Mix>()
        val stats = plays.all.value
        val byId = tracks.associateBy { it.id }
        val most = stats.entries.filter { it.value.count > 0 && it.key in byId }.sortedWith(compareByDescending<Map.Entry<Long, PlayStat>> { it.value.count }.thenByDescending { it.value.last })
            .take(MIX_MAX).mapNotNull { byId[it.key] }
        if (most.size >= MIX_MIN) out += Mix("most", "Most played", "Your top ${most.size}", most)
        // Not heard in a while: a month or more since it last played, or never; the longest ago first, the never-played among them shuffled.
        val stale = tracks.filter { (stats[it.id]?.last ?: 0) < now - STALE_MS }
        val (heard, never) = stale.partition { (stats[it.id]?.last ?: 0) > 0 }
        val forgotten = (heard.sortedBy { stats[it.id]!!.last } + never.shuffled(java.util.Random(now / 86_400_000L))).take(MIX_MAX)
        if (forgotten.size >= MIX_MIN) out += Mix("forgotten", "Not heard in a while", if (heard.isEmpty()) "Never played on this phone" else "A month or more since", forgotten)
        val prof = _profiles.value
        val measured = tracks.filter { it.id in prof }
        if (measured.size >= MIX_MIN * 2) {
            out += Mix("loudest", "Loudest", "Mastered loud", measured.sortedByDescending { prof[it.id]!!.db }.take(MIX_MAX / 2))
            out += Mix("quietest", "Quietest", "Soft and roomy", measured.sortedBy { prof[it.id]!!.db }.take(MIX_MAX / 2))
            val moods = moods(measured, prof)
            for ((mood, line) in MOODS) {
                val l = moods[mood].orEmpty()
                if (l.size >= MIX_MIN) out += Mix("mood-$mood", mood, line, l.shuffled(java.util.Random(now / 86_400_000L)).take(MIX_MAX))
            }
        }
        return out
    }

    /**
     * Each song's mood, from where it sits in this library (so a library of loud rock still has its
     * calm ones): energy from its level and how much it jumps, brightness, and its beat.
     */
    private fun moods(tracks: List<TrackDto>, prof: Map<Long, SoundProfile>): Map<String, List<TrackDto>> {
        fun rank(sel: (SoundProfile) -> Float): Map<Long, Float> {
            val s = tracks.sortedBy { sel(prof[it.id]!!) }
            return s.withIndex().associate { (i, t) -> t.id to i.toFloat() / (s.size - 1).coerceAtLeast(1) }
        }
        val level = rank { it.db }
        val jumps = rank { it.onset }
        val bright = rank { it.hz }
        return tracks.groupBy { t ->
            val p = prof[t.id]!!
            val energy = 0.55f * level[t.id]!! + 0.45f * jumps[t.id]!!
            val b = bright[t.id]!!
            when {
                energy >= 0.62f && p.bpm >= 112 -> "Energetic"
                energy >= 0.55f && b < 0.4f -> "Dark and heavy"
                b >= 0.55f && p.bpm >= 96 && energy >= 0.35f -> "Upbeat"
                energy < 0.42f && b < 0.45f -> "Melancholy"
                energy < 0.5f -> "Chill"
                else -> "Mixed"
            }
        }
    }

    companion object {
        private const val TAG = "SmartMixes"
        private const val FRAME = 1024
        private const val SECONDS = 60
        const val MIX_MAX = 100
        const val MIX_MIN = 5
        const val STALE_MS = 30L * 86_400_000L
        val MOODS = listOf(
            "Energetic" to "Fast and full on",
            "Upbeat" to "Bright, with a lift",
            "Chill" to "Easy and unhurried",
            "Melancholy" to "Slow and dark",
            "Dark and heavy" to "Loud and low",
        )
    }
}
