package dev.periy.bridge.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import dev.periy.bridge.music.Loudness
import dev.periy.bridge.music.PhonePlayer
import dev.periy.bridge.container
import dev.periy.bridge.server.TrackDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * The player, after Namida's (github.com/namidaco/namida), in Localhost 8787's own look.
 *
 * One number says where it is: 0 is the mini player floating over the tabs, 1 is the player
 * full screen, 2 is the queue. A finger moves it continuously and everything follows the
 * number at once: the panel grows from the mini player to the screen, the cover flies from
 * the mini player's corner to the middle and grows, the title moves under it and grows, the
 * play button swells and moves to the centre, and the waveform, the times and the row of
 * buttons come in last. Going on to the queue, the same pieces shrink back into a mini player
 * at the top of the screen while the queue rises under it. Let go, and it settles where the
 * speed and distance of the finger say, the mini player with a little bounce.
 *
 * Sideways, the cover and the title slide to the song before or after, the title a little
 * faster than the cover, and the next one slides in behind them. The cover swells with the
 * loud moments of the song, the seek bar is the song's own waveform, the whole player takes
 * its colour from the cover, and specks drift behind it, quicker when the music is loud.
 *
 * Written anew for Compose from how Namida's player moves; none of its code is used.
 */

// ---------------------------------------------------------------------------- motion

/**
 * Namida's own curves and times, as it settles: every move up or down over 300 ms, into full
 * screen on Flutter's fastEaseInToSlowEaseOut (two cubics meeting at 0.198, 0.541: quick to leave,
 * long and slow to arrive, never past), into the mini player with a small bounce, into the queue
 * with a softer one; a song sideways over 600 ms, quick then gliding to a stop.
 */
private val ToFullIn = CubicBezierEasing(0.056f / 0.198f, 0.024f / 0.541f, 0.108f / 0.198f, 0.3085f / 0.541f)
private val ToFullOut = CubicBezierEasing((0.3655f - 0.198f) / 0.802f, (1f - 0.541f) / 0.459f, (0.5465f - 0.198f) / 0.802f, (0.989f - 0.541f) / 0.459f)
private val ToFull = Easing { t ->
    if (t < 0.198f) ToFullIn.transform(t / 0.198f) * 0.541f
    else 0.541f + ToFullOut.transform((t - 0.198f) / 0.802f) * 0.459f
}
private val ToMini = CubicBezierEasing(0.175f, 0.885f, 0.32f, 1.125f)
private val ToQueue = CubicBezierEasing(0.15f, 0.96f, 0.28f, 1.04f)
private val ToSong = CubicBezierEasing(0.18f, 1f, 0.04f, 1f)
private const val SNAP_MS = 300
private const val SONG_MS = 600

/** Where the player is and how it moves: [p] up and down, [s] sideways. */
@Stable
/** How far a back swipe carries the player before it is let go. */
private const val BACK_REACH = 0.3f

class PlayerMotion(private val scope: CoroutineScope) {
    /** 0 mini, 1 full screen, 2 the queue; a little past either end while it bounces. */
    var p by mutableFloatStateOf(0f)
        private set
    /** The song before (-1), this one (0), the next (1); in between while swiping. */
    var s by mutableFloatStateOf(0f)
        private set
    /** Springing up from the mini player: things grow past full rather than head for the queue. */
    var bounceUp by mutableStateOf(false)
        private set
    /**
     * The songs shown while a swipe settles, before and after: frozen, as songs rather than places
     * in the queue, so nothing jumps under it (not even when the queue is dealt again for a new round).
     */
    var frozen by mutableStateOf<Triple<TrackDto?, TrackDto?, TrackDto?>?>(null)
        private set

    internal var height = 1f
    internal var width = 1f
    internal var density = 1f
    internal var haptic: () -> Unit = {}
    internal var onQueueOpening: () -> Unit = {}

    private var vJob: Job? = null
    private var sJob: Job? = null
    private var startP = 0f
    private var sStart = 0f
    private var sOffset = 0f

    val expanded: Boolean get() = p >= 0.95f
    val open: Boolean get() = p > 0.5f

    // ---- up and down

    fun dragStart() {
        vJob?.cancel()
        startP = p
        bounceUp = false
    }

    /** The finger moved up by [dy] pixels (down, negative). */
    fun dragBy(dy: Float) {
        val was = p
        p = (p + dy / height).coerceIn(-HEADROOM, 2f)
        // Dragged up past the full player, the queue starts to show: at the song playing, as Namida's does.
        if (was <= 1f && p > 1f) onQueueOpening()
    }

    /**
     * Let go, moving at [vy] pixels a second (down positive). Where it started and how far and
     * fast it went say where it settles, as Namida decides: a flick counts as much as a drag.
     */
    fun release(vy: Float, onDismiss: () -> Unit) {
        val speed = vy / density
        val moved = (startP - p) * height
        val far = ACTUATION_DP * density
        when {
            startP < 0.5f && p <= -HEADROOM * 0.8f -> dismiss(onDismiss)
            startP > 1f -> if (speed > FLICK_DP || moved > far) expand() else toQueue()
            startP > 0.5f -> when {
                speed > FLICK_DP || moved > far -> collapse()
                -speed > FLICK_DP || -moved > far -> toQueue()
                else -> expand()
            }
            else -> if (-speed > FLICK_DP || -moved > far) expand() else collapse()
        }
    }

    fun expand() {
        bounceUp = p < 1f
        go(1f, tween(SNAP_MS, easing = ToFull))
    }

    fun collapse() {
        bounceUp = false
        go(0f, tween(SNAP_MS, easing = ToMini))
    }

    /**
     * A back swipe [f] of the way (0 to 1), from [from] towards [to]: the player follows it down a
     * third of the way there at most, the rest played out (collapse, expand) once it is let go.
     */
    fun backPreview(from: Float, to: Float, f: Float) {
        vJob?.cancel()
        bounceUp = false
        p = from + (to - from) * BACK_REACH * f
    }

    /** Slides the mini player up into place, as music starts. */
    fun enter() {
        if (vJob?.isActive == true) return
        p = -HEADROOM * 1.6f
        startP = p
        go(0f, tween(460, easing = ToMini))
    }

    /** Lets the player fall away under the tabs, then [done] (the queue is cleared). */
    fun dismiss(done: () -> Unit) {
        bounceUp = false
        val from = p
        vJob?.cancel()
        haptic()
        vJob = scope.launch {
            animate(from, -HEADROOM * 1.8f, animationSpec = tween(if (from > 0.5f) 420 else 220, easing = ToFull)) { v, _ -> p = v }
            done()
        }
        startP = -HEADROOM * 1.8f
    }

    fun toQueue() {
        bounceUp = false
        if (p < 1.6f) onQueueOpening()
        go(2f, tween(SNAP_MS, easing = ToQueue))
    }

    private fun go(target: Float, spec: AnimationSpec<Float>) {
        val from = p
        vJob?.cancel()
        vJob = scope.launch {
            animate(from, target, animationSpec = spec) { v, _ -> p = v }
            bounceUp = false
        }
        // A tick under the finger when it settles somewhere other than where it started.
        if (abs(target - startP) * height > ACTUATION_DP * density) haptic()
        startP = target
    }

    // ---- sideways

    fun swipeStart() {
        if (sJob?.isActive == true || frozen != null) {
            sJob?.cancel()
            settleNow()
        }
        sStart = sOffset
    }

    /** The finger moved right by [dx] pixels: towards the song before. */
    fun swipeBy(dx: Float) {
        sOffset = (sOffset - dx).coerceIn(-width, width)
        s = (sOffset / width * OVERSHOOT).coerceIn(-1f, 1f)
    }

    fun swipeRelease(vx: Float, player: PhonePlayer, around: (Int) -> Triple<Int, Int, Int>) {
        val speed = vx / density
        val moved = sOffset - sStart
        val far = ACTUATION_DP * 1.5f * density
        when {
            speed > SWIPE_FLICK_DP || -moved > far -> toSong(-1, player, around)
            -speed > SWIPE_FLICK_DP || moved > far -> toSong(1, player, around)
            else -> settle(0f)
        }
    }

    /**
     * Slides to the song before or after: the player changes song at once, while the old and
     * new covers finish sliding; then the frozen songs let go, in the same frame as the slide
     * resets, so nothing flickers.
     */
    fun toSong(dir: Int, player: PhonePlayer, around: (Int) -> Triple<Int, Int, Int>) {
        val st = player.state.value
        if (dir < 0 && !player.previousChangesSong()) { settle(0f); player.previous(); return }
        if (dir > 0 && !st.hasNext) { settle(0f); return }
        if (dir < 0 && st.queue.size < 2) { settle(0f); return }
        sJob?.cancel()
        if (frozen != null) settleNow()
        frozen = around(st.index).let { (a, b, c) -> Triple(st.queue.getOrNull(a), st.queue.getOrNull(b), st.queue.getOrNull(c)) }
        if (dir > 0) player.next() else player.previous()
        haptic()
        val from = s
        sJob = scope.launch {
            animate(from, dir.toFloat(), animationSpec = tween(SONG_MS, easing = ToSong)) { v, _ -> s = v }
            settleNow()
        }
    }

    private fun settle(target: Float) {
        val from = s
        sJob?.cancel()
        sJob = scope.launch {
            animate(from, target, animationSpec = tween(SONG_MS, easing = ToSong)) { v, _ -> s = v }
            sOffset = 0f
        }
    }

    private fun settleNow() {
        Snapshot.withMutableSnapshot { frozen = null; s = 0f }
        sOffset = 0f
    }

    companion object {
        /** How far below the mini player it can be pulled, to put it away. */
        const val HEADROOM = 0.12f
        const val ACTUATION_DP = 100f
        const val FLICK_DP = 500f
        const val SWIPE_FLICK_DP = 1000f
        /** A full swipe gets there before the finger has crossed the whole screen. */
        const val OVERSHOOT = 1.25f
    }
}

@Composable
fun rememberPlayerMotion(): PlayerMotion {
    val scope = rememberCoroutineScope()
    return remember { PlayerMotion(scope) }
}

// ---------------------------------------------------------------------------- geometry

/** The fixed measures, in pixels, from which every position is worked out as the player moves. */
private class Geo(
    val w: Float,
    val h: Float,
    d: Density,
    statusTop: Float,
    navBottom: Float,
    /** How far above the bottom the mini player sits: over the tabs. */
    miniLift: Float,
) {
    private val px = d.density
    fun dp(v: Float) = v * px

    // Namida's mini player: 82 high, 6 in from the sides, the cover 58 with 12 around it, corners 20.
    val miniH = dp(82f)
    val side = dp(6f)
    val miniBottom = h - miniLift
    val miniTop = miniBottom - miniH
    val miniArt = dp(58f)
    val miniPad = dp(12f)
    val miniRadius = dp(20f)

    /** In the queue, the mini row sits at the top (Namida's 100 under the status bar), and the queue 12 under it. */
    val qTop = statusTop + dp(9f)
    val listTop = statusTop + dp(112f)

    // Namida's rows along the top and bottom: 1.25 x (32 + 8 + 8).
    val topRowTop = statusTop
    val topRowH = dp(60f)
    val bottomRowH = dp(60f)
    val bottomRowTop = h - navBottom - dp(8f) - bottomRowH
    // Its play disc: a 40 icon with 14 around it, 68 across; 22 with 11 around it in the mini player.
    val playBig = dp(68f)
    val playMini = dp(44f)
    val ctrlY = bottomRowTop - dp(18f) - playBig / 2
    val waveH = dp(60f)
    val waveBottom = ctrlY - playBig / 2 - dp(18f)
    val waveTop = waveBottom - waveH
    // Namida's two lines, both in its medium style, the song over its artist: the song at 20, the artist at 15 (14.5 and 12.5 small).
    val titleBig = with(d) { 20.nsp.toPx() }
    val titleMini = with(d) { 14.5.nsp.toPx() }
    val artistBig = with(d) { 15.nsp.toPx() }
    val artistMini = with(d) { 12.5.nsp.toPx() }
    /** The heart beside the two lines, in the full player: 32 across. */
    val heart = dp(48f)
    val textBigH = titleBig * 1.3f + artistBig * 1.35f + dp(4f)
    val textBigTop = waveTop - dp(18f) - textBigH
    private val artAreaTop = topRowTop + topRowH + dp(12f)
    private val artAreaBottom = textBigTop - dp(22f)
    val artBig = min(w - dp(56f), artAreaBottom - artAreaTop).coerceAtLeast(dp(120f))
    val artBigX = (w - artBig) / 2
    val artBigY = artAreaTop + ((artAreaBottom - artAreaTop) - artBig) / 2

    /** The mini row's measures, laid along [top] from [left] to [right]. */
    inner class Row(val top: Float, val left: Float, val right: Float) {
        val artX = left + miniPad
        val artY = top + miniPad
        val nextX = right - dp(6f) - dp(40f)
        val playX = nextX - playMini
        val prevX = playX - dp(40f)
        val cy = top + miniH / 2
        val textX = left + miniH
        val textW = prevX - dp(6f) - textX
        val titleTop = cy - (titleMini * 1.3f + artistMini * 1.3f) / 2
        val artistTop = titleTop + titleMini * 1.32f
        /** Where the songs slide sideways in the mini row: up to its buttons, never under them. */
        val slideRight = prevX - dp(2f)
    }

    val miniRow = Row(miniTop, side, w - side)
    val queueRow = Row(qTop, dp(4f), w - dp(4f))
}

/** The number [Geo] works from, as Namida splits it: towards full (c), towards the queue (q). */
private class Terms(p: Float, bounceUp: Boolean) {
    val cp = p.coerceIn(0f, 1f)
    val qp = if (bounceUp) 0f else (p.coerceIn(1f, 3f) - 1f)
    val qcp = qp.coerceIn(0f, 1f)
    /** Rises to full, falls again to the queue: how far the pieces are from the mini row. */
    val bcp = if (bounceUp) 1f.coerceAtMost(p.coerceAtLeast(0f)) else (if (p > 1f) 2f - p else p).coerceIn(0f, 1f)
    /** Past full while springing up: the pieces carry on up a little and come back. */
    val over = if (bounceUp) (p - 1f).coerceAtLeast(0f) else 0f
    /** Pulled under the mini player: it follows down, and fades on the way to going. */
    val under = (-p).coerceAtLeast(0f)
    val slow = (bcp * 4 - 3).coerceIn(0f, 1f)
    val mid = (bcp * 5 - 4).coerceIn(0f, 1f)
    val fast = (bcp * 10 - 9).coerceIn(0f, 1f)
}

/** Places a child at a box worked out as the player moves, scaled from the size it is laid out at. */
private fun Modifier.placed(
    laidW: Float,
    laidH: Float,
    box: () -> FloatArray,
): Modifier = placed({ laidW }, laidH, box)

/** As [placed], with a width that follows the motion too (the mini row's title stops short of its buttons). */
private fun Modifier.placed(
    laidW: () -> Float,
    laidH: Float,
    box: () -> FloatArray,
): Modifier = this
    .layout { m, _ ->
        val pl = m.measure(Constraints.fixed(laidW().roundToInt().coerceAtLeast(1), laidH.roundToInt().coerceAtLeast(1)))
        layout(pl.width, pl.height) { pl.placeRelative(0, 0) }
    }
    .graphicsLayer {
        val b = box()
        transformOrigin = TransformOrigin(0f, 0f)
        translationX = b[0]
        translationY = b[1]
        scaleX = b[2]
        scaleY = b[2]
        alpha = b[3]
    }

// ---------------------------------------------------------------------------- the player

/**
 * The player over the app: the mini player over the tabs, full screen, or the queue. [lift]
 * is how far above the bottom the mini player sits. [onOpenAlbum] shows the song's album in
 * the Music tab.
 */
@Composable
fun NowPlaying(
    player: PhonePlayer,
    state: PhonePlayer.State,
    motion: PlayerMotion,
    loudness: dev.periy.bridge.music.Loudness,
    statusTop: Dp,
    navBottom: Dp,
    lift: Dp,
    onOpenAlbum: (TrackDto) -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val dark = Bridge.Dark
    motion.density = density.density
    motion.haptic = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

    val cur = state.current ?: return
    val shown = motion.frozen ?: aroundIndex(state, state.index).let { (a, b, c) -> Triple(state.queue.getOrNull(a), state.queue.getOrNull(b), state.queue.getOrNull(c)) }
    val prevT = shown.first
    val curT = shown.second ?: cur
    val nextT = shown.third

    // The cover's colour, as the page takes it; the album's made-up colour until it loads.
    val bigCover = rememberCover(cur.albumId, big = true)
    val tintTarget = (if (bigCover != null) Covers.tint(cur.albumId) else null) ?: Covers.madeUp(cur.album)
    val tint by animateColorAsState(tintTarget, tween(600), label = "tint")
    // The whole player in the cover's colour, worked out as Namida works out its theme (NamidaStyle.kt).
    val basePalette = LocalPalette.current
    val nc = remember(tint, basePalette.dark) { namidaColors(tint, basePalette.dark) }
    val palette = remember(nc, basePalette) { nc.asPalette(basePalette) }
    val favourites = androidx.compose.ui.platform.LocalContext.current.container.favourites
    val hearts by favourites.ids.collectAsState()

    // How loud each moment is: the waveform and the cover's swell.
    val env by produceState(loudness.cached(cur.id), cur.id) { value = loudness.of(cur.id) }
    // What comes next is worked out ahead, so its waveform is there when it starts; the covers
    // either side are decoded ahead too, so a swipe never shows one arriving.
    nextT?.let { n -> LaunchedEffect(n.id) { loudness.of(n.id) } }
    val library = androidx.compose.ui.platform.LocalContext.current.container.music
    LaunchedEffect(prevT?.albumId, nextT?.albumId) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            listOfNotNull(prevT, nextT).forEach { runCatching { Covers.load(library, it.albumId, big = true) } }
        }
    }

    // Every frame while playing: the swell, the waveform's progress and the drifting specks.
    val live = rememberUpdatedState(state)
    val pulse = remember { mutableFloatStateOf(0f) }
    val loud = remember { mutableFloatStateOf(0f) }
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.playing, env) {
        var last = 0L
        while (true) {
            val now = withFrameMillis { it }
            val dt = if (last == 0L) 16f else (now - last).toFloat().coerceIn(1f, 64f)
            last = now
            val s = live.value
            val v = if (s.playing) Loudness.at(env, s.positionNow()) else 0f
            val k = 1f - exp(-dt / 70f)
            loud.floatValue += (v - loud.floatValue) * k
            pulse.floatValue += (v.pow(2.4f) - pulse.floatValue) * k
            tick.longValue = now
            if (!s.playing && pulse.floatValue < 0.002f) { pulse.floatValue = 0f; loud.floatValue = 0f; break }
        }
    }

    LaunchedEffect(Unit) { if (motion.p <= 0f) motion.enter() }

    // A seek being dragged on the waveform: where it would go. Read only by the label, so a
    // scrub redraws the label and nothing else.
    val seek = remember { mutableStateOf<Long?>(null) }
    // The sound controls, opened from the bottom row.
    var sound by remember { mutableStateOf(false) }
    // How far the sound dialog is in, for the player under it to blur by.
    val soundFade = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    // What the file is, for the chip: FLAC, 1,411 kbps, 44.1 kHz.
    val info by produceState<dev.periy.bridge.server.TrackInfoDto?>(null, cur.id) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { library.info(cur.id) }.getOrNull() }
    }
    // The lyrics, over the cover while Namida's lyrics button is on: kept on the phone, else
    // looked up (LyricsFinder), and read again when a page leaves new ones.
    val container = androidx.compose.ui.platform.LocalContext.current.container
    var lyricsOn by remember { mutableStateOf(container.prefs.lyricsShown) }
    val lyricsVer by container.lyrics.changed.collectAsState()
    val lyrics by produceState(container.lyrics.cached(cur), cur.id, lyricsVer, lyricsOn) {
        value = container.lyrics.cached(cur)
        if (lyricsOn) value = runCatching { container.lyrics.forTrack(cur) }.getOrNull()
    }
    val lyricsShowing = lyricsOn && lyrics?.let { it.timed || it.kind == dev.periy.bridge.server.ShownLyrics.Kind.PLAIN } == true
    val lyricsVis by animateFloatAsState(if (lyricsShowing) 1f else 0f, tween(400), label = "lyrics")
    // Only while the player is open on them do they take taps and scrolling.
    val lyricsLive by remember { derivedStateOf { Terms(motion.p, motion.bounceUp).let { it.bcp > 0.95f && it.qcp < 0.05f } && abs(motion.s) < 0.05f } }
    // Namida's full-page lyrics, opened by a tap on those over the cover. They go with the lyrics,
    // and when the player folds away; Back closes them first.
    var lyricsFull by remember { mutableStateOf(false) }
    val playerOpen by remember { derivedStateOf { motion.p > 0.9f } }
    // Kept open from song to song (the next one's lyrics come in under it); only turning the
    // lyrics off, or the player folding away, closes it.
    if (lyricsFull && (!lyricsOn || !playerOpen)) lyricsFull = false
    // Back follows the finger: the lyrics slide aside and fade, back to the player under them.
    val lyricsSwipe = rememberBackSwipe(enabled = lyricsFull) { lyricsFull = false }

    CompositionLocalProvider(LocalPalette provides palette, LocalNamida provides nc) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val g = remember(constraints.maxWidth, constraints.maxHeight, statusTop, navBottom, lift, density) {
            with(density) {
                Geo(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), density, statusTop.toPx(), navBottom.toPx(), lift.toPx())
            }
        }
        motion.width = g.w
        motion.height = g.h

        // Under Namida's dialogs, the player blurs (Android 12 on) as the dialog fades in.
        val behindDialog = Modifier.graphicsLayer {
            val f = soundFade.floatValue
            renderEffect = if (f > 0.01f) androidx.compose.ui.graphics.BlurEffect(8.7.dp.toPx() * f, 8.7.dp.toPx() * f, androidx.compose.ui.graphics.TileMode.Clamp) else null
        }
        val onDismiss = rememberUpdatedState { player.clear() }
        val around = rememberUpdatedState { i: Int -> aroundIndex(player.state.value, i) }

        // ---- the panel: from the mini player's card to the whole screen
        Box(
            Modifier
                .fillMaxSize()
                .then(behindDialog)
                .graphicsLayer {
                    val t = Terms(motion.p, motion.bounceUp)
                    val r = panelRect(g, t)
                    val radius = lerp(g.miniRadius, 0f, t.cp)
                    shape = RectShape(r, radius)
                    clip = true
                    // Namida's panel shadow: soft, a little deeper as it opens.
                    shadowElevation = g.dp(10f) * (1f - t.under * 4f).coerceIn(0f, 1f) * (1f - t.cp)
                    alpha = (1f + (t.under - 0.04f).coerceAtLeast(0f) * -9f).coerceIn(0f, 1f)
                    ambientShadowColor = nc.shadow.copy(alpha = 0.2f + 0.1f * t.cp)
                    spotShadowColor = nc.shadow.copy(alpha = 0.2f + 0.1f * t.cp)
                }
                .drawBehind {
                    val t = Terms(motion.p, motion.bounceUp)
                    val r = panelRect(g, t)
                    drawRect(nc.bg, Offset(r.left, r.top), Size(r.width, r.height))
                    // Namida's wash of the song's colour: lifted towards the text colour at the top,
                    // less so at the foot; stronger at the top once open, at the foot in the mini player.
                    val icp = 1f - t.cp
                    drawRect(
                        Brush.verticalGradient(
                            0f to nc.onSurface.copy(alpha = 100 / 255f).compositeOver(nc.main).copy(alpha = lerp(0.38f, 0.28f, icp)),
                            1f to nc.onSurface.copy(alpha = 40 / 255f).compositeOver(nc.main).copy(alpha = lerp(0.10f, 0.22f, icp)),
                            startY = r.top, endY = r.bottom,
                        ),
                        Offset(r.left, r.top), Size(r.width, r.height),
                    )
                }
                .pointerInput(Unit) { playerGestures(g, motion, player, { around.value(it) }, { onDismiss.value() }) },
        ) {
            // Specks drifting behind the full player, quicker when the music is loud.
            Particles(motion, tint, loud, tick, state.playing)

            // The covers and titles slide within the mini row's own corner at first (short of
            // its buttons), and across the whole screen once the player is open.
            Box(Modifier.fillMaxSize().graphicsLayer {
                shape = RectShape(slideClip(g, Terms(motion.p, motion.bounceUp)), 0f)
                clip = true
            }) {
            // ---- the titles, sliding a little faster than the covers; drawn under them, as Namida's are,
            // so on the way to the queue the title slides out from behind the cover
            listOf(-1 to prevT, 0 to curT, 1 to nextT).forEach { (slot, t) ->
                if (t != null) key("t", slot, t.id) {
                    TitleLine(t.title, g.titleBig, Modifier.placed({ textWidth(g, Terms(motion.p, motion.bounceUp), title = true) }, g.titleBig * 1.3f) {
                        textBox(g, Terms(motion.p, motion.bounceUp), motion.s, slot, title = true)
                    })
                    TitleLine(t.artist, g.artistBig, Modifier.placed({ textWidth(g, Terms(motion.p, motion.bounceUp), title = false) }, g.artistBig * 1.35f) {
                        textBox(g, Terms(motion.p, motion.bounceUp), motion.s, slot, title = false)
                    }, second = true)
                }
            }
            // ---- the covers: the song before, this one, the next
            listOf(-1 to prevT, 0 to curT, 1 to nextT).forEach { (slot, t) ->
                if (t != null) key(slot, t.id) {
                    Box(
                        Modifier.placed(g.artBig, g.artBig) {
                            val tm = Terms(motion.p, motion.bounceUp)
                            val a = artBox(g, tm)
                            val spacing = slideSpacing(g, tm, a[2])
                            val sv = motion.s
                            val swell = if (slot == 0) 1f + pulse.floatValue * lerp(0.05f, 0.075f, tm.bcp) else 1f
                            val size = a[2] * swell
                            floatArrayOf(
                                a[0] - (size - a[2]) / 2 - sv * spacing + slot * spacing,
                                a[1] - (size - a[2]) / 2,
                                size / g.artBig,
                                when (slot) { 0 -> 1f - abs(sv); -1 -> (-sv).coerceIn(0f, 1f); else -> sv.coerceIn(0f, 1f) },
                            )
                        },
                    ) {
                        ArtFace(t, radiusOf = {
                            val tm = Terms(motion.p, motion.bounceUp)
                            val sz = artBox(g, tm)[2]
                            lerp(g.dp(6f), g.dp(14f), tm.bcp) * g.artBig / sz.coerceAtLeast(1f)
                        }, glow = tint, glowOf = { Terms(motion.p, motion.bounceUp).bcp },
                            veil = if (slot == 0) ({ lyricsVis * Terms(motion.p, motion.bounceUp).bcp }) else ({ 0f }),
                            veilColor = tint.copy(alpha = 0.25f).compositeOver(nc.bg))
                    }
                }
            }
            // ---- the lyrics, over this song's cover. As Namida's, they keep their size and ride on the
            // cover's middle as it shrinks into the queue or the mini player, fading as they go.
            val shownLyrics = lyrics
            if (shownLyrics != null && (lyricsShowing || lyricsVis > 0f)) key("lyrics", curT.id) {
                LyricsOverCover(
                    shownLyrics, state, tick, tint, live = lyricsLive && lyricsShowing,
                    onSeek = { ms -> player.seekTo(ms) },
                    onOpen = { lyricsFull = true },
                    modifier = Modifier.placed(g.artBig, g.artBig) {
                        val tm = Terms(motion.p, motion.bounceUp)
                        val a = artBox(g, tm)
                        val sv = motion.s
                        val inset = (a[2] - g.artBig) / 2
                        floatArrayOf(a[0] + inset - sv * slideSpacing(g, tm, a[2]), a[1] + inset, 1f, lyricsVis * tm.bcp * (1f - abs(sv)).coerceIn(0f, 1f))
                    },
                )
            }

            }

            // ---- the heart, beside the two lines: comes in from the left as the player opens
            HeartButton(cur.id in hearts, Modifier.placed(g.heart, g.heart) {
                val t = Terms(motion.p, motion.bounceUp)
                floatArrayOf(
                    g.w - g.dp(16f) - g.heart - (1f - t.bcp) * g.dp(100f),
                    g.textBigTop + (g.textBigH - g.heart) / 2 - t.over * g.h * 0.35f,
                    1f, t.fast,
                )
            }) { favourites.toggle(cur.id) }

            // ---- the waveform, with where a seek would land above it
            WaveSeek(
                env = env, state = state, tint = tint, tick = tick,
                // Only while the full player shows: folded away it lies over the mini player, and a
                // tap there (to open it) must not move the song.
                active = { Terms(motion.p, motion.bounceUp).let { it.bcp > 0.9f && it.qcp < 0.1f } },
                onScrub = { seek.value = it },
                onSeek = { ms -> player.seekTo(ms) },
                modifier = Modifier.placed(g.w - g.dp(40f), g.waveH) {
                    val t = Terms(motion.p, motion.bounceUp)
                    floatArrayOf(g.dp(20f), g.waveTop + slideDown(g, t), 1f, t.slow)
                },
            )
            SeekLabel(seek, state, Modifier.placed(g.w, g.dp(22f)) {
                val t = Terms(motion.p, motion.bounceUp)
                floatArrayOf(0f, g.waveTop - g.dp(24f) + slideDown(g, t), 1f, if (seek.value != null) t.slow else 0f)
            })

            // ---- times, either side of the controls
            Times(state, tick, Modifier.placed(g.w - g.dp(40f), g.dp(20f)) {
                val t = Terms(motion.p, motion.bounceUp)
                floatArrayOf(g.dp(20f), g.ctrlY - g.dp(10f) + slideDown(g, t), 1f, t.fast)
            })

            // ---- previous, play, next: from the mini player's right to the middle
            TransportButton(Iconsax.Prev, "Previous", Modifier.placed(g.playBig, g.playBig) {
                ctrlBox(g, Terms(motion.p, motion.bounceUp), -1)
            }) { motion.toSong(-1, player) { around.value(it) } }
            PlayButton(state.playing, nc.main, Modifier.placed(g.playBig, g.playBig) {
                ctrlBox(g, Terms(motion.p, motion.bounceUp), 0)
            }) { player.toggle() }
            TransportButton(Iconsax.Next, "Next", Modifier.placed(g.playBig, g.playBig) {
                ctrlBox(g, Terms(motion.p, motion.bounceUp), 1)
            }) { motion.toSong(1, player) { around.value(it) } }

            // ---- the mini player's progress, a hairline along its foot
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val t = Terms(motion.p, motion.bounceUp)
                    val a = (1f - t.cp * 5f).coerceIn(0f, 1f)
                    if (a <= 0f) return@drawBehind
                    tick.longValue
                    val s = live.value
                    val f = if (s.durationMs > 0) s.positionNow().toFloat() / s.durationMs else 0f
                    val r = panelRect(g, t)
                    val l = r.left + g.miniRadius; val w = r.width - g.miniRadius * 2
                    val y = r.bottom - g.dp(2.5f)
                    drawRoundRect(nc.main.copy(alpha = 0.3f * a), Offset(l, y), Size(w, g.dp(2.5f)), CornerRadius(g.dp(2f)))
                    drawRoundRect(nc.onSurface.copy(alpha = 0.3f).compositeOver(nc.main).copy(alpha = a), Offset(l, y), Size(w * f.coerceIn(0f, 1f), g.dp(2.5f)), CornerRadius(g.dp(2f)))
                },
            )

            // ---- the top row: close, and where in the queue and which album
            TopRow(
                index = state.index, count = state.queue.size, album = cur.album,
                onClose = { motion.collapse() },
                onAlbum = { onOpenAlbum(cur); motion.collapse() },
                modifier = Modifier.placed(g.w, g.topRowH) {
                    val t = Terms(motion.p, motion.bounceUp)
                    floatArrayOf(0f, g.topRowTop + (1f - t.bcp) * -g.dp(100f) - t.qp * g.dp(40f), 1f, t.bcp * (1f - t.qcp))
                },
            )

            // ---- the bottom row: the sound, repeat, and the sound controls
            BottomRow(
                state, info,
                onRepeat = { player.setRepeat(it) },
                onRepeatTimes = { player.setRepeatTimes(it) },
                onSound = { sound = true },
                lyricsOn = lyricsOn, lyrics = lyrics,
                onLyrics = { lyricsOn = !lyricsOn; container.prefs.lyricsShown = lyricsOn },
                modifier = Modifier.placed(g.w, g.bottomRowH) {
                    val t = Terms(motion.p, motion.bounceUp)
                    floatArrayOf(0f, g.bottomRowTop + (1f - t.cp) * g.dp(100f) + slideDown(g, t), 1f, t.mid)
                },
            )

            // ---- the queue, rising under the mini row at the top
            QueuePanel(
                player, state, motion, tint, navBottom,
                modifier = Modifier.placed(g.w, g.h - g.listTop) {
                    val t = Terms(motion.p, motion.bounceUp)
                    floatArrayOf(0f, g.listTop + (1f - t.qp) * (g.h - g.listTop), 1f, if (t.qp > 0f) 1f else 0f)
                },
            )
        }
        // ---- Namida's full-page lyrics, over the whole player
        androidx.compose.animation.AnimatedVisibility(
            lyricsFull,
            enter = fadeIn(tween(280)) + androidx.compose.animation.scaleIn(tween(360, easing = FastOutSlowInEasing), initialScale = 0.96f),
            exit = fadeOut(tween(220)) + androidx.compose.animation.scaleOut(tween(220), targetScale = 0.97f),
        ) {
            Box(SharedAxisBack.over(lyricsSwipe)) {
                FullLyrics(
                    cur, lyrics, state, tick, tint, env, lyricsOn, statusTop, navBottom,
                    onClose = { lyricsFull = false },
                    onLyricsOff = { lyricsFull = false; lyricsOn = false; container.prefs.lyricsShown = false },
                    onSeek = { ms -> player.seekTo(ms) },
                    onPrev = { motion.toSong(-1, player) { around.value(it) } },
                    onToggle = { player.toggle() },
                    onNext = { motion.toSong(1, player) { around.value(it) } },
                )
            }
        }
        if (sound) SoundSheet(player, state, soundFade, onClose = { sound = false })
    }
    }
}

/**
 * Namida's full-page lyrics: the cover blurred far out behind a wash of the page colour; along
 * the top, back, who and what is playing, and the lyrics button (which turns them off); the lines
 * large from the left, the one being sung on a soft card (a tap on a line goes to it); along the
 * foot, the waveform, then the times either side of previous, play and next.
 */
@Composable
private fun FullLyrics(
    cur: TrackDto,
    lyrics: dev.periy.bridge.server.ShownLyrics?,
    state: PhonePlayer.State,
    tick: State<Long>,
    tint: Color,
    env: ByteArray?,
    lyricsOn: Boolean,
    statusTop: Dp,
    navBottom: Dp,
    onClose: () -> Unit,
    onLyricsOff: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrev: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    val nc = Nm.c
    val seek = remember { mutableStateOf<Long?>(null) }
    // It takes every touch on it: none reaches the player under it.
    Box(Modifier.fillMaxSize().pointerInput(Unit) {}.background(nc.bg)) {
        Cover(cur.albumId, cur.album, Modifier.fillMaxSize().blur(90.dp), radius = 0.dp, big = true)
        Box(Modifier.fillMaxSize().background(nc.bg.copy(alpha = if (nc.dark) 0.78f else 0.7f)))
        Column(Modifier.fillMaxSize().padding(top = statusTop, bottom = navBottom)) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Iconsax.Back, "Back to the player", tint = nc.icon, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(cur.title, style = Nm.large.copy(fontSize = 16.nsp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(cur.artist, style = Nm.medium.copy(fontSize = 13.5.nsp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onLyricsOff).semantics { contentDescription = "Hide the lyrics" },
                    contentAlignment = Alignment.Center,
                ) { LyricsIcon(lyricsOn, lyrics, nc.icon) }
            }
            val shown = lyrics?.takeIf { it.timed || it.kind == dev.periy.bridge.server.ShownLyrics.Kind.PLAIN }
            if (shown != null) key(cur.id) {
                LyricsOverCover(
                    shown, state, tick, tint, live = true, onSeek = onSeek, full = true,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp), contentAlignment = Alignment.CenterStart) {
                // The next song's lyrics on their way, or none to be had: said where the lines go.
                Text(
                    when {
                        lyrics == null -> "Looking for the lyrics\u2026"
                        lyrics.kind == dev.periy.bridge.server.ShownLyrics.Kind.INSTRUMENTAL -> "Instrumental"
                        lyrics.offline -> "Lyrics come when the phone is online"
                        else -> "No lyrics for this song"
                    },
                    style = Nm.large.copy(fontSize = 22.nsp, lineHeight = 28.nsp, color = nc.medium),
                )
            }
            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                WaveSeek(
                    env = env, state = state, tint = tint, tick = tick, active = { true },
                    onScrub = { seek.value = it }, onSeek = onSeek,
                    modifier = Modifier.padding(horizontal = 10.dp).fillMaxWidth().height(52.dp),
                )
                SeekLabel(seek, state, Modifier.fillMaxWidth().height(22.dp).offset(y = (-24).dp).graphicsLayer { alpha = if (seek.value != null) 1f else 0f })
            }
            Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                Times(state, tick, Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    TransportButton(Iconsax.Prev, "Previous", Modifier.size(52.dp), onClick = onPrev)
                    PlayButton(state.playing, nc.main, Modifier.size(58.dp), onClick = onToggle)
                    TransportButton(Iconsax.Next, "Next", Modifier.size(52.dp), onClick = onNext)
                }
            }
        }
    }
}

/** The songs either side of [i] in the queue, or -1 where there is none. */
private fun aroundIndex(s: PhonePlayer.State, i: Int): Triple<Int, Int, Int> {
    val n = s.queue.size
    val prev = if (i > 0) i - 1 else if (s.repeat.loops && n > 1) n - 1 else -1
    val next = if (i + 1 < n) i + 1 else if (s.repeat.loops && n > 1) 0 else -1
    return Triple(prev, i, next)
}

/** The panel's box on screen: the mini player's card, growing to the whole screen. */
private fun panelRect(g: Geo, t: Terms): androidx.compose.ui.geometry.Rect {
    val dip = t.under * g.dp(240f)
    return androidx.compose.ui.geometry.Rect(
        lerp(g.side, 0f, t.cp),
        lerp(g.miniTop, 0f, t.cp) + dip,
        lerp(g.w - g.side, g.w, t.cp),
        lerp(g.miniBottom, g.h, t.cp) + dip,
    )
}

/** The mini row the pieces start from: over the tabs, or at the top over the queue. */
private fun rowOf(g: Geo, t: Terms): Geo.Row = if (t.qp > 0f) g.queueRow else g.miniRow

/** How far the full player's lower pieces sit below their place: they rise into it, and bounce past it. */
private fun slideDown(g: Geo, t: Terms): Float = (1f - t.cp) * g.dp(60f) - t.over * g.h * 0.35f

private fun artBox(g: Geo, t: Terms): FloatArray {
    val row = rowOf(g, t)
    val dip = t.under * g.dp(240f)
    val lift = t.over * g.h * 0.35f
    return floatArrayOf(
        lerp(row.artX, g.artBigX, t.bcp),
        lerp(row.artY + dip, g.artBigY - lift, t.bcp),
        lerp(g.miniArt, g.artBig, t.bcp),
    )
}

private fun textBox(g: Geo, t: Terms, s: Float, slot: Int, title: Boolean): FloatArray {
    val row = rowOf(g, t)
    val dip = t.under * g.dp(240f)
    val lift = t.over * g.h * 0.35f
    val scale = if (title) lerp(g.titleMini / g.titleBig, 1f, t.bcp) else lerp(g.artistMini / g.artistBig, 1f, t.bcp)
    val x = lerp(row.textX, g.dp(24f), t.bcp)
    val bigTitleTop = g.textBigTop
    val y = if (title) lerp(row.titleTop + dip, bigTitleTop - lift, t.bcp)
    else lerp(row.artistTop + dip, bigTitleTop + g.titleBig * 1.3f + g.dp(4f) - lift, t.bcp)
    // A little further apart than the covers, so the title slides a little quicker: the parallax.
    val spacing = slideSpacing(g, t, artBox(g, t)[2]) * 1.15f
    val a = when (slot) { 0 -> 1f - abs(s); -1 -> (-s).coerceIn(0f, 1f); else -> s.coerceIn(0f, 1f) }
    return floatArrayOf(x - s * spacing + slot * spacing, y, scale, a)
}

/** The box the songs slide in: the mini row up to its buttons, growing to the whole screen. */
private fun slideClip(g: Geo, t: Terms): androidx.compose.ui.geometry.Rect {
    val r = panelRect(g, t)
    if (t.qp > 0f) return r
    return androidx.compose.ui.geometry.Rect(r.left, r.top, lerp(g.miniRow.slideRight, r.right, t.cp), r.bottom)
}

/** How far apart the songs either side sit: the mini row's sliding width, then most of the screen. */
private fun slideSpacing(g: Geo, t: Terms, art: Float): Float {
    val mini = g.miniRow.slideRight - g.miniRow.left
    return lerp(mini, max(g.w / 1.15f, art + g.dp(16f)), t.cp)
}

/** How wide a title line is laid out, before the motion scales it: to the mini row's buttons, or across the screen. */
private fun textWidth(g: Geo, t: Terms, title: Boolean): Float {
    val row = rowOf(g, t)
    val scale = if (title) lerp(g.titleMini / g.titleBig, 1f, t.bcp) else lerp(g.artistMini / g.artistBig, 1f, t.bcp)
    return lerp(row.textW, g.w - g.dp(44f) - g.heart, t.bcp) / scale
}

/** Previous (-1), play (0), next (1): along the mini row's right, or big in the middle. */
private fun ctrlBox(g: Geo, t: Terms, which: Int): FloatArray {
    val row = rowOf(g, t)
    val big = g.playBig
    val laid = big
    val miniSize = if (which == 0) g.playMini else g.dp(40f)
    val size = lerp(miniSize, big, t.bcp)
    val miniCx = when (which) { -1 -> row.prevX + g.dp(20f); 0 -> row.playX + g.playMini / 2; else -> row.nextX + g.dp(20f) }
    // Namida's previous, play and next sit side by side, each 68 across.
    val bigCx = g.w / 2 + which * big
    val dip = t.under * g.dp(240f)
    val cx = lerp(miniCx, bigCx, t.bcp)
    val cy = lerp(row.cy + dip, g.ctrlY + slideDown(g, t), t.bcp)
    val a = if (which == 0) 1f else 1f - t.qcp
    return floatArrayOf(cx - size / 2, cy - size / 2, size / laid, a)
}

/** A rounded box at [r] on an otherwise full-screen layer: what the panel clips its pieces to. */
private class RectShape(private val r: androidx.compose.ui.geometry.Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(r, CornerRadius(radius)))
}

// ---------------------------------------------------------------------------- gestures

/**
 * Up and down moves the player between mini, full and the queue; sideways (not in the queue)
 * swipes to the song before or after; a tap on the mini player opens it. Whatever a button or
 * the waveform takes first is left to it.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.playerGestures(
    g: Geo,
    motion: PlayerMotion,
    player: PhonePlayer,
    around: (Int) -> Triple<Int, Int, Int>,
    onDismiss: () -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // Inside the mini player's card only, when that is what shows.
        val r = panelRect(g, Terms(motion.p, motion.bounceUp))
        if (!r.contains(down.position)) return@awaitEachGesture
        // In the queue, only the top (the mini row) moves the player; the list scrolls itself.
        if (motion.p > 1.5f && down.position.y > g.listTop) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var dir = 0
        var dx = 0f
        var dy = 0f
        var lastUp: androidx.compose.ui.input.pointer.PointerInputChange? = null
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) { lastUp = ch; break }
            if (dir == 0 && ch.isConsumed) { dir = -1 }
            if (dir == -1) continue
            val d = ch.positionChange()
            tracker.addPosition(ch.uptimeMillis, ch.position)
            if (dir == 0) {
                dx += d.x; dy += d.y
                if (abs(dy) > slop && abs(dy) > abs(dx)) { dir = 1; motion.dragStart() }
                else if (abs(dx) > slop && motion.p <= 1.02f) { dir = 2; motion.swipeStart() }
            }
            when (dir) {
                1 -> { motion.dragBy(-d.y); ch.consume() }
                2 -> { motion.swipeBy(d.x); ch.consume() }
            }
        }
        val v = tracker.calculateVelocity()
        when (dir) {
            1 -> motion.release(v.y, onDismiss)
            2 -> motion.swipeRelease(v.x, player, around)
            0 -> if (lastUp != null && !lastUp.isConsumed && motion.p < 0.1f) { lastUp.consume(); motion.expand() }
        }
    }
}

// ---------------------------------------------------------------------------- pieces

/**
 * A cover, laid out at the full player's size and scaled by the motion; its corners and glow
 * follow it. [veil]: with the lyrics over it, as Namida's: blurred, under a faint veil of [veilColor].
 */
@Composable
private fun ArtFace(t: TrackDto, radiusOf: () -> Float, glow: Color, glowOf: () -> Float, veil: () -> Float = { 0f }, veilColor: Color = Color.Transparent) {
    val img = rememberCover(t.albumId, big = true)
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val r = radiusOf()
                shape = RoundedCornerShape(r)
                clip = true
                shadowElevation = 24.dp.toPx() * glowOf()
                ambientShadowColor = glow.copy(alpha = 0.5f)
                spotShadowColor = glow
            }
            .drawWithContent {
                drawContent()
                val v = veil()
                if (v > 0f) drawRect(veilColor.copy(alpha = veilColor.alpha * LYRICS_VEIL * v))
            },
    ) {
        // Blurred within the rounded corners, which stay sharp.
        val inner = Modifier.fillMaxSize().graphicsLayer {
            val v = veil()
            val r = LYRICS_BLUR.toPx() * v
            renderEffect = if (r > 0.5f) androidx.compose.ui.graphics.BlurEffect(r, r, androidx.compose.ui.graphics.TileMode.Clamp) else null
        }
        if (img != null) androidx.compose.foundation.Image(img, null, inner, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else MadeUpCover(t.album, inner)
    }
}

/** Namida's blur on the cover under the lyrics (its 12, as a radius), and how much of its veil shows. */
private val LYRICS_BLUR = 20.dp
private const val LYRICS_VEIL = 0.25f

/** One of the two lines, laid out at the full player's size; the motion scales it. Both are in Namida's medium style. */
@Composable
private fun TitleLine(text: String, sizePx: Float, modifier: Modifier, second: Boolean = false) {
    val d = LocalDensity.current
    val style = with(d) { Nm.medium.copy(fontSize = sizePx.toSp()) }
    Box(modifier) {
        Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The play button, as Namida's: a disc of the song's colour going grey towards its lower right,
 * glowing onto the player in the same colour, with Iconsax's play or pause on it in white.
 * Pressed, it gives a little, lightens and glows a little more.
 */
@Composable
private fun PlayButton(playing: Boolean, main: Color, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, tween(400), label = "press")
    val base = if (pressed) main.copy(alpha = 233 / 255f).compositeOver(Color.White) else main
    Box(modifier) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { scaleX = s; scaleY = s }
                .shadow(if (pressed) 14.dp else 9.dp, CircleShape, ambientColor = main.copy(alpha = 160 / 255f), spotColor = main.copy(alpha = 160 / 255f))
                .clip(CircleShape)
                .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick)
                .background(Brush.linearGradient(0f to base, 0.7f to main.copy(alpha = 200 / 255f).compositeOver(Color(0xFF9E9E9E)))),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                playing,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "playpause",
            ) { on ->
                Icon(if (on) Iconsax.Pause else Iconsax.Play, if (on) "Pause" else "Play", tint = Color.White.copy(alpha = 180 / 255f),
                    modifier = Modifier.size(40.dp))
            }
        }
    }
}

/** Namida's previous and next: the icon alone, 32 across, in its icon colour. */
@Composable
private fun TransportButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier) {
        Box(Modifier.fillMaxSize().pressable(CircleShape, scaleTo = 0.86f, onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = Nm.c.icon, modifier = Modifier.size(32.dp))
        }
    }
}

/**
 * The heart beside the two lines, as Namida's: Iconsax's heart, 32 across, filled when the song
 * has one, with a spring as it changes. Kept on the phone, the same on the page.
 */
@Composable
private fun HeartButton(on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    val first = remember { mutableStateOf(true) }
    LaunchedEffect(on) {
        if (first.value) { first.value = false; return@LaunchedEffect }
        pop.snapTo(0.72f)
        pop.animateTo(1f, spring(dampingRatio = 0.36f, stiffness = 520f))
    }
    Box(modifier) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).clickable(onClick = onClick).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (on) Iconsax.HeartOn else Iconsax.Heart, if (on) "Take the heart off" else "Give it a heart",
                tint = Nm.c.icon, modifier = Modifier.size(32.dp))
        }
    }
}

/** Where the song is and how long it is, either side of the controls, in Namida's small style at 13: 01:46. */
@Composable
private fun Times(state: PhonePlayer.State, tick: State<Long>, modifier: Modifier) {
    val live = rememberUpdatedState(state)
    val secs by remember { derivedStateOf { tick.value; live.value.positionNow() / 1000 } }
    Row(modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val style = Nm.small.copy(fontFeatureSettings = "tnum")
        Text(clock(secs * 1000), style = style)
        Spacer(Modifier.weight(1f))
        Text(clock(state.durationMs), style = style)
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

/** Over the waveform while a seek is dragged: how far it goes from where the song is. */
@Composable
private fun SeekLabel(seek: State<Long?>, state: PhonePlayer.State, modifier: Modifier) {
    val seekMs = seek.value
    Box(modifier, contentAlignment = Alignment.Center) {
        if (seekMs != null) {
            val diff = seekMs - state.positionNow()
            Text((if (diff >= 0) "+" else "-") + clock(abs(diff)), style = Nm.small.copy(fontFeatureSettings = "tnum"), textAlign = TextAlign.Center)
        }
    }
}

/** Namida's top row: close (its arrow down), and where in the queue (2/14) and the album; a tap shows the album. */
@Composable
private fun TopRow(index: Int, count: Int, album: String, onClose: () -> Unit, onAlbum: () -> Unit, modifier: Modifier) {
    val nc = Nm.c
    Row(modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Iconsax.Down, "Close the player", tint = nc.onSecondaryContainer, modifier = Modifier.size(22.dp))
        }
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onAlbum).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("${index + 1}/$count", style = Nm.small.copy(fontFeatureSettings = "tnum"))
            Text(album, style = Nm.medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // As wide as the close button, so the middle stays in the middle.
        Spacer(Modifier.size(46.dp))
    }
}

/**
 * Namida's bottom row. On the left, what the file is, in the lit colour: FLAC • 1411 kb/s •
 * 44.1 kHz, with the bit depth in a little badge when the file says it (a tap opens the sound
 * controls). On the right, repeat and the sound controls.
 */
@Composable
private fun BottomRow(
    state: PhonePlayer.State,
    info: dev.periy.bridge.server.TrackInfoDto?,
    onRepeat: (PhonePlayer.Repeat) -> Unit,
    onRepeatTimes: (Int) -> Unit,
    onSound: () -> Unit,
    lyricsOn: Boolean,
    lyrics: dev.periy.bridge.server.ShownLyrics?,
    onLyrics: () -> Unit,
    modifier: Modifier,
) {
    val nc = Nm.c
    Row(modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // The chip takes what room the buttons leave, and its details give way first.
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onSound).padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // What the file is, in the lit colour, then the bit depth in its little badge, all
                // one text that may take two lines (Namida's headphones and "Audio" before it left out).
                val details = info?.let { audioDetails(it, state.current?.mime.orEmpty()) }.orEmpty()
                val badge = info?.let(::bitsBadge).orEmpty()
                val small = SpanStyle(fontSize = 13.nsp, color = nc.primary, fontFeatureSettings = "tnum")
                val text = buildAnnotatedString {
                    if (details.isNotEmpty()) withStyle(small) { append(details) }
                    if (badge.isNotEmpty()) { append(" "); appendInlineContent("bits", badge) }
                }
                val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                val badgeStyle = TextStyle(fontFamily = MusicType, fontSize = 11.nsp, color = nc.primary)
                val badgeW = with(LocalDensity.current) { (measurer.measure(badge, badgeStyle).size.width.toDp() + 4.dp + 12.dp + 2.dp + 4.dp).toSp() }
                Text(
                    text,
                    style = TextStyle(fontFamily = MusicType, fontSize = 15.nsp, fontWeight = FontWeight.Medium, color = nc.onSecondaryContainer, lineHeight = 17.nsp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    inlineContent = if (badge.isEmpty()) emptyMap() else mapOf(
                        "bits" to androidx.compose.foundation.text.InlineTextContent(
                            androidx.compose.ui.text.Placeholder(badgeW, 15.nsp, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter),
                        ) {
                            Row(
                                Modifier.clip(RoundedCornerShape(4.dp)).background(nc.cardColor.copy(alpha = 60 / 255f)).padding(horizontal = 4.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Iconsax.Wind, null, tint = nc.primary, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(2.dp))
                                Text(badge, style = badgeStyle, maxLines = 1)
                            }
                        },
                    ),
                )
            }
        }
        RepeatButton(state.repeat, state.repeatTimes, onRepeat, onRepeatTimes)
        RowButton(Iconsax.Sound, "Sound: pitch, speed and volume", size = 21.dp, onClick = onSound)
        // Namida's lyrics button, after the sound controls: the lyrics over the cover, on or off.
        Box(
            Modifier.size(44.dp).pressable(CircleShape, scaleTo = 0.88f, onClick = onLyrics)
                .semantics { contentDescription = if (lyricsOn) "Hide the lyrics" else "Show the lyrics" },
            contentAlignment = Alignment.Center,
        ) { LyricsIcon(lyricsOn, lyrics, nc.onSecondaryContainer) }
        Spacer(Modifier.width(6.dp))
    }
}

/** What a file is, as Namida's chip has it: FLAC • 1411 kb/s • 44.1 kHz (channels only when not stereo). */
internal fun audioDetails(info: dev.periy.bridge.server.TrackInfoDto, mime: String): String = listOfNotNull(
    info.format.ifEmpty { formatBadge(mime) }.takeIf { it.isNotEmpty() },
    info.channels.takeIf { it > 0 && it != 2 }?.let { "$it ch" },
    info.kbps.takeIf { it > 0 }?.let { "$it kb/s" },
    info.sampleRate.takeIf { it > 0 }?.let { "${it / 1000.0} kHz" },
).joinToString(" \u2022 ")

/** The file as the clean look says it: FLAC · 16-bit · 44.1 kHz · 968 kbps (only what the file tells). */
internal fun formatLine(info: dev.periy.bridge.server.TrackInfoDto, mime: String = "", kbps: Boolean = true): String = listOfNotNull(
    info.format.ifEmpty { formatBadge(mime) }.takeIf { it.isNotEmpty() },
    info.bits.takeIf { it > 0 }?.let { "$it-bit" },
    info.sampleRate.takeIf { it > 0 }?.let { r -> (if (r % 1000 == 0) "${r / 1000}" else "%.1f".format(java.util.Locale.US, r / 1000.0)) + " kHz" },
    info.kbps.takeIf { kbps && it > 0 }?.let { "$it kbps" },
).joinToString(" · ")

/** Namida's badge after the details: 24-bit Hi-Res Lossless (Hi-Res from 24 bits, Lossless for a lossless kind of file). */
internal fun bitsBadge(info: dev.periy.bridge.server.TrackInfoDto): String {
    if (info.bits <= 0) return ""
    val lossless = info.format.uppercase() in setOf("FLAC", "ALAC", "WAV", "APE", "DSD", "AIFF", "WV")
    return listOfNotNull("${info.bits}-bit", "Hi-Res".takeIf { info.bits >= 24 }, "Lossless".takeIf { lossless }).joinToString(" ")
}

/** What a repeat mode does, as its menu says it. */
private fun repeatLabel(mode: PhonePlayer.Repeat, times: Int): String = when (mode) {
    PhonePlayer.Repeat.OFF -> "Stop after the last song"
    PhonePlayer.Repeat.ONE -> "Repeat this song"
    PhonePlayer.Repeat.TIMES -> if (times == 1) "Repeat 1 more time" else "Repeat $times more times"
    PhonePlayer.Repeat.ALL -> "Repeat the queue"
    PhonePlayer.Repeat.ALL_SHUFFLE -> "Repeat the queue, shuffled"
}

/**
 * A repeat mode's icon, as Namida draws it: the count inside a broken ring for a number of
 * times, and a small shuffle at the corner of the queue's for shuffled rounds.
 */
@Composable
private fun RepeatIcon(mode: PhonePlayer.Repeat, times: Int, tint: Color, size: Dp = 20.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val icon = when (mode) {
            PhonePlayer.Repeat.OFF -> Iconsax.RepeatOff
            PhonePlayer.Repeat.ONE -> Iconsax.RepeatOne
            PhonePlayer.Repeat.TIMES -> Iconsax.Status
            else -> Iconsax.RepeatAll
        }
        Icon(icon, null, tint = tint, modifier = Modifier.fillMaxSize())
        if (mode == PhonePlayer.Repeat.TIMES) {
            val fs = with(LocalDensity.current) { (size * 0.46f).toSp() }
            Text("$times", style = TextStyle(fontFamily = MusicType, fontWeight = FontWeight.SemiBold, fontSize = fs, lineHeight = fs, color = tint), maxLines = 1)
        }
        if (mode == PhonePlayer.Repeat.ALL_SHUFFLE) Icon(
            Iconsax.Shuffle, null, tint = tint,
            modifier = Modifier.size(size * 0.6f).align(Alignment.BottomEnd).offset(x = size * 0.22f, y = size * 0.22f),
        )
    }
}

/**
 * Namida's repeat button: a tap opens its menu (Namida's popup, over the row), with the five
 * ways to repeat, the one on lit in the song's colour, and the count for "more times" set with
 * its − and +.
 */
@Composable
private fun RepeatButton(mode: PhonePlayer.Repeat, times: Int, onPick: (PhonePlayer.Repeat) -> Unit, onTimes: (Int) -> Unit) {
    val nc = Nm.c
    var open by remember { mutableStateOf(false) }
    // Namida's icon colour in its menus: the song's colour, part see-through, over the text colour.
    val iconColour = nc.main.copy(alpha = 120 / 255f).compositeOver(nc.onSurface)
    Box {
        Box(
            Modifier.size(44.dp).pressable(CircleShape, scaleTo = 0.88f) { open = !open }
                .semantics { contentDescription = repeatLabel(mode, times).let { if (it.startsWith("Repeat")) it else "Repeat: " + it.replaceFirstChar(Char::lowercase) } },
            contentAlignment = Alignment.Center,
        ) { RepeatIcon(mode, times, nc.onSecondaryContainer) }
        if (open) NamidaMenu(onDismiss = { open = false }) { close ->
            PhonePlayer.Repeat.entries.forEach { m ->
                val on = m == mode
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (on) nc.main.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { onPick(m); close() }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RepeatIcon(m, times, iconColour, size = 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(repeatLabel(m, times), style = Nm.medium, modifier = Modifier.weight(1f))
                    if (m == PhonePlayer.Repeat.TIMES) {
                        NmIconButton(Iconsax.MinusCircle, "One fewer", 18.dp, 4.dp, 0.dp, iconColour, repeat = true, enabled = times > 1) { onTimes(times - 1) }
                        NmIconButton(Iconsax.Add, "One more", 18.dp, 4.dp, 0.dp, iconColour, repeat = true, enabled = times < 99) { onTimes(times + 1) }
                    }
                }
            }
        }
    }
}

/** One of the bottom row's buttons: the icon alone, in the row's colour. */
@Composable
private fun RowButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, size: Dp = 20.dp, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).pressable(CircleShape, scaleTo = 0.88f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Nm.c.onSecondaryContainer, modifier = Modifier.size(size))
    }
}

/**
 * Specks drifting behind the full player, as Namida's: faint, in the cover's colour, quicker
 * with the loud moments; they fade in as the player opens and drift to a stop when it pauses.
 */
@Composable
private fun Particles(motion: PlayerMotion, tint: Color, loud: State<Float>, tick: State<Long>, playing: Boolean) {
    val specks = remember { List(46) { Speck() } }
    val clock = remember { LongArray(1) }
    val fade = androidx.compose.animation.core.animateFloatAsState(if (playing) 1f else 0f, tween(1000), label = "specks")
    Box(
        Modifier.fillMaxSize().drawBehind {
            val t = Terms(motion.p, motion.bounceUp)
            tick.value
            val now = System.nanoTime()
            val dt = if (clock[0] == 0L) 0f else ((now - clock[0]) / 1e9f).coerceIn(0f, 0.064f)
            clock[0] = now
            val a = t.cp * (1f - t.qcp * 0.6f) * fade.value
            if (a <= 0.005f) return@drawBehind
            val speed = 1f + loud.value * 5f
            val color = lerp(tint, Color.White, 0.45f)
            specks.forEach { sp ->
                sp.x = (sp.x + sp.vx * dt * speed + 1f) % 1f
                sp.y = (sp.y + sp.vy * dt * speed + 1f) % 1f
                drawCircle(color.copy(alpha = sp.alpha * a * 0.5f), sp.r * density, Offset(sp.x * size.width, sp.y * size.height))
            }
        },
    )
}

private class Speck {
    var x = Math.random().toFloat()
    var y = Math.random().toFloat()
    private val angle = Math.random() * Math.PI * 2
    private val v = 0.006f + Math.random().toFloat() * 0.02f
    val vx = (kotlin.math.cos(angle) * v).toFloat()
    val vy = (kotlin.math.sin(angle) * v).toFloat() - 0.004f
    val r = 1.2f + Math.random().toFloat() * 2.2f
    val alpha = 0.3f + Math.random().toFloat() * 0.7f
}
