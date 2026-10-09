package dev.periy.bridge.music

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.periy.bridge.server.EventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One of the famous curves, in dB for each band. */
@Serializable
data class EqPreset(val id: String, val name: String, val gains: List<Float>)

/** The equalizer as the players use it: on or off, the curve chosen ("custom" once a band is moved), its ten gains. */
@Serializable
data class EqState(val on: Boolean = false, val preset: String = "flat", val gains: List<Float> = List(EqMath.BANDS.size) { 0f })

/** What the page is sent: the state, the bands and the curves to choose from. */
@Serializable
data class EqDto(
    val on: Boolean,
    val preset: String,
    val gains: List<Float>,
    val bands: List<Float> = EqMath.BANDS.toList(),
    val q: Float = EqMath.Q,
    val max: Float = EqMath.MAX_DB,
    val presets: List<EqPreset> = EqMath.PRESETS,
)

/**
 * A ten-band graphic equalizer, as a hi-fi has one: bands an octave apart from 32 Hz to 16 kHz.
 * Made the accurate way (Valimaki and Liski's cascade graphic equalizer, 2017): a peaking filter
 * 1.5 octaves wide for each band between, shelves for the two ends (from halfway to the next band,
 * so the lowest and highest settings hold out to 20 Hz and 20 kHz), and each filter's own gain
 * solved so the curve passes exactly through the ten points set, with under half a dB of ripple
 * between them. The curve drawn is what plays, on the phone and in the page's Web Audio alike
 * (the same RBJ filters as Web Audio's), and the level comes down by its highest boost, so a boosted
 * song never clips.
 */
object EqMath {
    val BANDS = floatArrayOf(32f, 64f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    /** 1.5 octaves. */
    const val Q = 0.92f
    const val LOW_SHELF = 45f
    const val HIGH_SHELF = 11_300f
    const val MAX_DB = 12f
    private const val FS = 48_000.0

    /** The famous curves, 32 Hz to 16 kHz. Harman's is its target's bass shelf and gentle treble fall. */
    val PRESETS = listOf(
        EqPreset("flat", "Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("harman", "Harman", listOf(5f, 5f, 3.5f, 1f, 0f, 0f, 0f, 0f, -1f, -2.5f)),
        EqPreset("loudness", "Loudness", listOf(6f, 4.5f, 2f, 0f, -1f, -1f, 0f, 1f, 3f, 4f)),
        EqPreset("bass", "Bass Boost", listOf(6f, 5.5f, 4.5f, 2.5f, 0.5f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("bassless", "Bass Cut", listOf(-6f, -5f, -4f, -2f, -0.5f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("treble", "Treble Boost", listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 2f, 3.5f, 5f, 6f)),
        EqPreset("trebleless", "Treble Cut", listOf(0f, 0f, 0f, 0f, 0f, -0.5f, -2f, -3.5f, -5f, -6f)),
        EqPreset("v", "V-Shape", listOf(5f, 4f, 2f, 0f, -1.5f, -2f, -1f, 1f, 3.5f, 4.5f)),
        EqPreset("vocal", "Vocal", listOf(-2f, -2f, -1f, 1f, 3f, 3.5f, 3f, 1.5f, 0f, -1f)),
        EqPreset("warm", "Warm", listOf(3f, 3f, 2f, 1f, 0f, 0f, -0.5f, -1f, -2f, -3f)),
        EqPreset("bright", "Bright", listOf(-1f, -1f, 0f, 0f, 0f, 0.5f, 1.5f, 3f, 4f, 4.5f)),
        EqPreset("rock", "Rock", listOf(4.5f, 3.5f, 2f, 0f, -1f, -0.5f, 1f, 2.5f, 3.5f, 4f)),
        EqPreset("pop", "Pop", listOf(-1f, -0.5f, 0.5f, 2f, 3f, 3f, 2f, 0.5f, -0.5f, -1f)),
        EqPreset("jazz", "Jazz", listOf(3f, 2f, 1f, 1.5f, -1f, -1f, 0f, 1f, 2f, 3f)),
        EqPreset("classical", "Classical", listOf(3f, 2.5f, 1.5f, 0.5f, 0f, 0f, 0f, 1f, 2f, 2.5f)),
        EqPreset("electronic", "Electronic", listOf(5f, 4.5f, 1.5f, 0f, -1.5f, 1f, 0.5f, 1f, 4f, 5f)),
        EqPreset("hiphop", "Hip-Hop", listOf(5.5f, 4.5f, 1.5f, 3f, -1f, -1f, 1f, -0.5f, 1.5f, 2.5f)),
        EqPreset("acoustic", "Acoustic", listOf(4f, 4f, 3f, 1f, 1.5f, 1.5f, 3f, 3.5f, 3f, 2f)),
    )

    /** The curve the ten points set make: each filter's gain, and the level at any frequency. */
    class Curve(points: List<Float>) {
        val gains: DoubleArray = solve(points)
        fun db(f: Double): Double { var s = 0.0; for (i in gains.indices) s += one(i, gains[i], f); return s }
        /** The highest point from 20 Hz to 20 kHz, in dB (0 when it only cuts). */
        fun peak(): Double { var top = 0.0; for (k in 0..240) top = max(top, db(20.0 * 1000.0.pow(k / 240.0))); return top }
    }

    /** One filter's level at [f]: band [i] at gain [g] dB (Web Audio's lowshelf, peaking, highshelf). */
    private fun one(i: Int, g: Double, f: Double): Double {
        if (g == 0.0) return 0.0
        val last = BANDS.size - 1
        val f0 = when (i) { 0 -> LOW_SHELF.toDouble(); last -> HIGH_SHELF.toDouble(); else -> BANDS[i].toDouble() }
        val a = 10.0.pow(g / 40)
        val w0 = 2 * PI * f0 / FS
        val cw = cos(w0); val sw = sin(w0)
        val c: DoubleArray = if (i == 0 || i == last) {
            val sa = 2 * sqrt(a) * sw / 2 * sqrt(2.0)
            if (i == 0) doubleArrayOf(
                a * ((a + 1) - (a - 1) * cw + sa), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sa),
                (a + 1) + (a - 1) * cw + sa, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sa,
            ) else doubleArrayOf(
                a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa),
                (a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa,
            )
        } else {
            val al = sw / (2 * Q)
            doubleArrayOf(1 + al * a, -2 * cw, 1 - al * a, 1 + al / a, -2 * cw, 1 - al / a)
        }
        val w = 2 * PI * f / FS
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = c[0] + c[1] * c1 + c[2] * c2; val ni = -(c[1] * s1 + c[2] * s2)
        val dr = c[3] + c[4] * c1 + c[5] * c2; val di = -(c[4] * s1 + c[5] * s2)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    /**
     * Each filter's gain for the points: how much each filter moves each point (per dB, at about
     * the gain it will have) is a 10 by 10 table; solving it, three times over as the gains settle,
     * gives gains whose curve meets every point.
     */
    private fun solve(points: List<Float>): DoubleArray {
        val n = BANDS.size
        val t = DoubleArray(n) { points.getOrElse(it) { 0f }.toDouble() }
        if (t.all { it == 0.0 }) return DoubleArray(n)
        var g = t.copyOf()
        repeat(3) {
            val m = Array(n) { r ->
                DoubleArray(n + 1) { c ->
                    if (c == n) t[r] else { val gc = if (abs(g[c]) > 0.5) g[c] else 6.0; one(c, gc, BANDS[r].toDouble()) / gc }
                }
            }
            for (col in 0 until n) {
                var p = col
                for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[p][col])) p = r
                val tmp = m[col]; m[col] = m[p]; m[p] = tmp
                for (r in 0 until n) if (r != col) {
                    val k = m[r][col] / m[col][col]
                    for (c in col..n) m[r][c] -= k * m[col][c]
                }
            }
            g = DoubleArray(n) { (m[it][n] / m[it][it]).coerceIn(-24.0, 24.0) }
        }
        return g
    }
}

/**
 * The equalizer's setting, kept on the phone for its player and every page's alike (the pages hear
 * of a change at once, as with hearts), and the phone's own player run through it.
 */
class EqStore(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("equalizer", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(
        prefs.getString(K_STATE, null)?.let { runCatching { json.decodeFromString(EqState.serializer(), it) }.getOrNull() } ?: EqState(),
    )
    val state: StateFlow<EqState> = _state

    fun set(s: EqState) {
        val clean = s.copy(gains = List(EqMath.BANDS.size) { i -> (s.gains.getOrElse(i) { 0f }).coerceIn(-EqMath.MAX_DB, EqMath.MAX_DB) })
        if (clean == _state.value) return
        _state.value = clean
        prefs.edit().putString(K_STATE, json.encodeToString(EqState.serializer(), clean)).apply()
        EventBus.emit("eq", json.encodeToString(EqState.serializer(), clean))
    }

    fun setOn(on: Boolean) = set(_state.value.copy(on = on))
    fun choose(p: EqPreset) = set(EqState(on = true, preset = p.id, gains = p.gains))
    fun setBand(i: Int, db: Float) {
        val g = _state.value.gains.toMutableList().also { it[i] = db }
        set(EqState(on = true, preset = EqMath.PRESETS.firstOrNull { it.gains == g }?.id ?: "custom", gains = g))
    }

    fun dto(): EqDto = _state.value.let { EqDto(it.on, it.preset, it.gains) }

    private companion object { const val K_STATE = "state" }
}

/**
 * The phone's player through the equalizer: Android's DynamicsProcessing on the players' audio
 * session, its band gains following the curve closely (64 bands from 20 Hz up, each set to the
 * curve at its middle), its input brought down by the curve's peak. Off, it is released, so the
 * sound goes out untouched.
 *
 * When it is made matters. An effect made on a session that has no track yet (the setting is
 * applied as the app opens, long before a song plays) is moved by Android onto the real output
 * when the song starts, and on some phones it comes out of that move misconfigured: static, until
 * the equalizer is switched off and on again, which makes it afresh while a song is playing. So
 * the effect is made only once sound is really flowing (the song's position moving), and again
 * after the phone has been idle for a while or the output changes (headphones, a speaker). A skip
 * from one song to the next keeps it: the session never leaves its output for long.
 */
class EqEngine(private val session: Int, audio: AudioManager) {
    private val main = Handler(Looper.getMainLooper())
    private var dp: DynamicsProcessing? = null
    private var failed = false
    private var want = EqState()

    /** A song has been started on the session and not let go: there is a track for the effect to sit on. */
    private var live = false
    /** The effect may be made: its track is there and sound is flowing through it. */
    private var settled = false
    /** When the last track was let go, so a quick skip is told from the phone having been idle. */
    private var goneAt = 0L
    /** Bumped each time a start or a stop is seen, so a wait for sound that was overtaken does nothing. */
    private var gen = 0

    private val routeMoved = Runnable {
        if (live && settled && dp != null) {
            Log.i(TAG, "The output changed: making the equalizer afresh")
            release()
            refresh()
        }
    }

    init {
        // Headphones or a speaker coming or going moves the sound to another output, and the effect with it.
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = moved()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = moved()
        }, main)
    }

    private fun moved() {
        if (!live || !settled) return
        main.removeCallbacks(routeMoved)
        main.postDelayed(routeMoved, ROUTE_SETTLE_MS)
    }

    /** The curve to play through, now (the equalizer switched, a band moved). */
    fun apply(s: EqState) { main.post { want = s; refresh() } }

    /**
     * A song has been started. [sounding] says when sound is flowing (its position has moved):
     * the effect is made then, not before.
     */
    fun trackStarted(sounding: () -> Boolean) {
        main.post {
            if (live) return@post
            live = true
            failed = false
            val g = ++gen
            if (dp != null && SystemClock.elapsedRealtime() - goneAt < KEEP_MS) {
                // A skip: the session is still on its output and the effect with it.
                settled = true
                return@post
            }
            settled = false
            release()
            waitForSound(sounding, g, 0)
        }
    }

    /** The song was let go (another is about to start, or the queue was cleared). */
    fun trackGone() {
        main.post {
            if (!live) return@post
            live = false
            settled = false
            gen++
            goneAt = SystemClock.elapsedRealtime()
            // The effect stays for a moment: a skip starts the next song on the same session.
            main.postDelayed({ if (!live && SystemClock.elapsedRealtime() - goneAt >= KEEP_MS) release() }, KEEP_MS + 200)
        }
    }

    private fun waitForSound(sounding: () -> Boolean, g: Int, tries: Int) {
        main.postDelayed({
            if (g != gen || !live) return@postDelayed
            if (sounding() || tries >= MAX_TRIES) {
                settled = true
                if (tries >= MAX_TRIES) Log.i(TAG, "No sound seen after ${MAX_TRIES * STEP_MS} ms; making the equalizer anyway")
                refresh()
            } else waitForSound(sounding, g, tries + 1)
        }, STEP_MS)
    }

    private fun refresh() {
        val s = want
        if (!s.on || s.gains.all { it == 0f }) { release(); return }
        // Not yet: it is made when the sound is flowing (see the class's note).
        if (settled) build(s)
    }

    private fun build(s: EqState) {
        if (failed || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val curve = EqMath.Curve(s.gains)
        val eq = DynamicsProcessing.Eq(true, true, N)
        var lo = 20.0
        for (i in 0 until N) {
            val hi = if (i == N - 1) 24_000.0 else 20.0 * 1000.0.pow((i + 1).toDouble() / N)
            val mid = sqrt(lo * minOf(hi, 20_000.0))
            eq.setBand(i, DynamicsProcessing.EqBand(true, hi.toFloat(), curve.db(mid).toFloat()))
            lo = hi
        }
        val preamp = -curve.peak().toFloat()
        try {
            val fresh = dp == null
            val d = dp ?: DynamicsProcessing(
                0, session,
                DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    true, N, false, 0, false, 0, false,
                ).setPreEqAllChannelsTo(eq).build(),
            ).also { dp = it }
            d.setPreEqAllChannelsTo(eq)
            d.setInputGainAllChannelsTo(preamp)
            d.enabled = true
            if (fresh) Log.i(TAG, "The equalizer is on the session (control ${d.hasControl()})")
        } catch (e: Exception) {
            Log.w(TAG, "DynamicsProcessing is not available", e)
            failed = true
            release()
        }
    }

    fun release() {
        runCatching { dp?.release() }
        dp = null
    }

    private companion object {
        const val TAG = "Eq"
        const val N = 64
        /** How often sound is looked for after a song starts, and how long to look (1.2 s) before making the effect anyway. */
        const val STEP_MS = 40L
        const val MAX_TRIES = 30
        /** A song started within this long of the last one ending is a skip: the effect stays. */
        const val KEEP_MS = 8_000L
        /** A new output takes a moment to settle before the effect is made on it. */
        const val ROUTE_SETTLE_MS = 700L
    }
}
