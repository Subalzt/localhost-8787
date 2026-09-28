package dev.periy.bridge.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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

/** Namida's own curves, as it settles: quick to leave, slow to arrive; with a bounce into the mini player. */
private val ToFull = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val ToMini = CubicBezierEasing(0.175f, 0.885f, 0.32f, 1.125f)
private val ToQueue = CubicBezierEasing(0.15f, 0.96f, 0.28f, 1.04f)
private val ToSong = CubicBezierEasing(0.18f, 1f, 0.04f, 1f)

/** Where the player is and how it moves: [p] up and down, [s] sideways. */
@Stable
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
    /** The songs shown while a swipe settles, before and after: frozen so nothing jumps under it. */
    var frozen by mutableStateOf<Triple<Int, Int, Int>?>(null)
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
        p = (p + dy / height).coerceIn(-HEADROOM, 2f)
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
        go(1f, if (bounceUp) spring(dampingRatio = 0.74f, stiffness = 330f) else tween(340, easing = ToFull))
    }

    fun collapse() {
        bounceUp = false
        go(0f, tween(380, easing = ToMini))
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
        go(2f, tween(380, easing = ToQueue))
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
        frozen = around(st.index)
        if (dir > 0) player.next() else player.previous()
        haptic()
        val from = s
        sJob = scope.launch {
            animate(from, dir.toFloat(), animationSpec = tween(560, easing = ToSong)) { v, _ -> s = v }
            settleNow()
        }
    }

    private fun settle(target: Float) {
        val from = s
        sJob?.cancel()
        sJob = scope.launch {
            animate(from, target, animationSpec = tween(520, easing = ToSong)) { v, _ -> s = v }
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

    val miniH = dp(64f)
    val side = dp(10f)
    val miniBottom = h - miniLift
    val miniTop = miniBottom - miniH
    val miniArt = dp(48f)
    val miniPad = dp(8f)
    val miniRadius = dp(18f)

    /** In the queue, the mini row sits at the top, over the list. */
    val qTop = statusTop + dp(6f)
    val listTop = qTop + miniH + dp(10f)

    val topRowTop = statusTop
    val topRowH = dp(56f)
    val bottomRowH = dp(52f)
    val bottomRowTop = h - navBottom - dp(10f) - bottomRowH
    val playBig = dp(74f)
    val playMini = dp(42f)
    val ctrlY = bottomRowTop - dp(20f) - playBig / 2
    val waveH = dp(58f)
    val waveBottom = ctrlY - playBig / 2 - dp(16f)
    val waveTop = waveBottom - waveH
    // Namida's two lines: the artist first, large and bold, and the song under it.
    val titleBig = with(d) { 21.sp.toPx() }
    val titleMini = with(d) { 14.5.sp.toPx() }
    val artistBig = with(d) { 16.sp.toPx() }
    val artistMini = with(d) { 12.5.sp.toPx() }
    /** The heart beside the two lines, in the full player. */
    val heart = dp(44f)
    val textBigH = titleBig * 1.3f + artistBig * 1.35f + dp(2f)
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
        val nextX = right - dp(4f) - dp(40f)
        val playX = nextX - dp(2f) - playMini
        val prevX = playX - dp(2f) - dp(40f)
        val cy = top + miniH / 2
        val textX = artX + miniArt + dp(12f)
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
    val shown = motion.frozen ?: aroundIndex(state, state.index)
    val prevT = state.queue.getOrNull(shown.first)
    val curT = state.queue.getOrNull(shown.second) ?: cur
    val nextT = state.queue.getOrNull(shown.third)

    // The cover's colour, as the page takes it; the album's made-up colour until it loads.
    val bigCover = rememberCover(cur.albumId, big = true)
    val tintTarget = (if (bigCover != null) Covers.tint(cur.albumId) else null) ?: Covers.madeUp(cur.album)
    val tint by animateColorAsState(tintTarget, tween(600), label = "tint")
    // The whole player in the cover's colour, as Namida's is: everything in it reads this.
    val basePalette = LocalPalette.current
    val palette = remember(tint, basePalette) { playerPalette(basePalette, tint) }
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
    // What the file is, for the chip: FLAC, 1,411 kbps, 44.1 kHz.
    val info by produceState<dev.periy.bridge.server.TrackInfoDto?>(null, cur.id) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { library.info(cur.id) }.getOrNull() }
    }

    CompositionLocalProvider(LocalPalette provides palette) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val g = remember(constraints.maxWidth, constraints.maxHeight, statusTop, navBottom, lift, density) {
            with(density) {
                Geo(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), density, statusTop.toPx(), navBottom.toPx(), lift.toPx())
            }
        }
        motion.width = g.w
        motion.height = g.h

        val floatBase = palette.surface
        val bg = palette.bg
        val ink = palette.text
        val onDismiss = rememberUpdatedState { player.clear() }
        val around = rememberUpdatedState { i: Int -> aroundIndex(player.state.value, i) }

        // ---- the panel: from the mini player's card to the whole screen
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val t = Terms(motion.p, motion.bounceUp)
                    val r = panelRect(g, t)
                    val radius = lerp(g.miniRadius, 0f, t.cp)
                    shape = RectShape(r, radius)
                    clip = true
                    // The mini player floats as the app's floating pieces do (Theme.kt, floating).
                    shadowElevation = lerp(g.dp(if (dark) 18f else 14f), 0f, t.cp) * (1f - t.under * 4f).coerceIn(0f, 1f)
                    alpha = (1f + (t.under - 0.04f).coerceAtLeast(0f) * -9f).coerceIn(0f, 1f)
                    ambientShadowColor = Color(0x40000000)
                    spotShadowColor = Color(0x59000000)
                }
                .drawBehind {
                    val t = Terms(motion.p, motion.bounceUp)
                    val r = panelRect(g, t)
                    val base = lerp(floatBase, bg, t.cp)
                    drawRect(base, Offset(r.left, r.top), Size(r.width, r.height))
                    // Namida's wash over it: the colour lifted a little at the top, deeper at the foot.
                    val a = if (dark) 1f else 0.8f
                    drawRect(
                        Brush.verticalGradient(
                            0f to lerp(tint, ink, 0.39f).copy(alpha = lerp(0.28f, 0.38f, t.cp) * a),
                            1f to lerp(tint, ink, 0.16f).copy(alpha = lerp(0.22f, 0.10f, t.cp) * a),
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
                        }, glow = tint, glowOf = { Terms(motion.p, motion.bounceUp).bcp })
                    }
                }
            }

            // ---- the titles, sliding a little faster than the covers
            listOf(-1 to prevT, 0 to curT, 1 to nextT).forEach { (slot, t) ->
                if (t != null) key("t", slot, t.id) {
                    TitleLine(t.artist, g.titleBig, Modifier.placed({ textWidth(g, Terms(motion.p, motion.bounceUp), title = true) }, g.titleBig * 1.3f) {
                        textBox(g, Terms(motion.p, motion.bounceUp), motion.s, slot, title = true)
                    })
                    TitleLine(t.title, g.artistBig, Modifier.placed({ textWidth(g, Terms(motion.p, motion.bounceUp), title = false) }, g.artistBig * 1.35f) {
                        textBox(g, Terms(motion.p, motion.bounceUp), motion.s, slot, title = false)
                    }, second = true)
                }
            }
            }

            // ---- the heart, beside the two lines: comes in from the left as the player opens
            HeartButton(cur.id in hearts, Modifier.placed(g.heart, g.heart) {
                val t = Terms(motion.p, motion.bounceUp)
                floatArrayOf(
                    g.w - g.dp(20f) - g.heart - (1f - t.bcp) * g.dp(100f),
                    g.textBigTop + (g.textBigH - g.heart) / 2 - t.over * g.h * 0.35f,
                    1f, t.fast,
                )
            }) { favourites.toggle(cur.id) }

            // ---- the waveform, with where a seek would land above it
            WaveSeek(
                env = env, state = state, tint = tint, tick = tick,
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
            TransportButton(PlayerIcons.Prev, "Previous", Modifier.placed(g.dp(56f), g.dp(56f)) {
                ctrlBox(g, Terms(motion.p, motion.bounceUp), -1)
            }) { motion.toSong(-1, player) { around.value(it) } }
            PlayButton(state.playing, tint, Modifier.placed(g.playBig, g.playBig) {
                ctrlBox(g, Terms(motion.p, motion.bounceUp), 0)
            }) { player.toggle() }
            TransportButton(PlayerIcons.Next, "Next", Modifier.placed(g.dp(56f), g.dp(56f)) {
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
                    drawRoundRect(tint.copy(alpha = 0.25f * a), Offset(l, y), Size(w, g.dp(2.5f)), CornerRadius(g.dp(2f)))
                    drawRoundRect(lerp(tint, Color.White, 0.25f).copy(alpha = a), Offset(l, y), Size(w * f.coerceIn(0f, 1f), g.dp(2.5f)), CornerRadius(g.dp(2f)))
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
                onRepeat = { player.cycleRepeat() },
                onSound = { sound = true },
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
        if (sound) SoundSheet(state, tint, onChange = { sp, pi, vo -> player.setSound(sp, pi, vo) }, onClose = { sound = false })
    }
    }
}

/**
 * The player's colours, from the cover's, as Namida builds its player's theme: the ground a
 * deep (or, in light, a pale) shade of the colour, the text faintly tinted by it, and the
 * colour itself for what is lit. Everything in the player reads these, the queue included.
 */
internal fun playerPalette(base: Palette, tint: Color): Palette =
    if (base.dark) base.copy(
        bg = lerp(Color(0xFF0F0F11), tint, 0.16f),
        surface = lerp(Color(0xFF19191C), tint, 0.24f),
        surface2 = lerp(Color(0xFF28282C), tint, 0.3f),
        text = lerp(Color.White, tint, 0.06f),
        muted = lerp(Color(0xFFB4B4B8), tint, 0.22f),
        faint = lerp(Color(0xFF56565B), tint, 0.25f),
        outline = tint.copy(alpha = 0.16f),
        accent = lerp(tint, Color.White, 0.3f),
        onAccent = Color.White,
    ) else base.copy(
        bg = lerp(Color(0xFFF8F8FA), tint, 0.12f),
        surface = lerp(Color.White, tint, 0.14f),
        surface2 = lerp(Color(0xFFEDEDF0), tint, 0.22f),
        text = lerp(Color(0xFF141416), tint, 0.16f),
        muted = lerp(Color(0xFF5E5E63), tint, 0.22f),
        faint = lerp(Color(0xFFB8B8BD), tint, 0.25f),
        outline = tint.copy(alpha = 0.18f),
        accent = lerp(tint, Color.Black, 0.25f),
        onAccent = Color.White,
    )

/** The songs either side of [i] in the queue, or -1 where there is none. */
private fun aroundIndex(s: PhonePlayer.State, i: Int): Triple<Int, Int, Int> {
    val n = s.queue.size
    val prev = if (i > 0) i - 1 else if (s.repeat == PhonePlayer.Repeat.ALL && n > 1) n - 1 else -1
    val next = if (i + 1 < n) i + 1 else if (s.repeat == PhonePlayer.Repeat.ALL && n > 1) 0 else -1
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
    else lerp(row.artistTop + dip, bigTitleTop + g.titleBig * 1.3f + g.dp(2f) - lift, t.bcp)
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
    val laid = if (which == 0) big else g.dp(56f)
    val miniSize = if (which == 0) g.playMini else g.dp(40f)
    val size = lerp(miniSize, if (which == 0) big else g.dp(56f), t.bcp)
    val miniCx = when (which) { -1 -> row.prevX + g.dp(20f); 0 -> row.playX + g.playMini / 2; else -> row.nextX + g.dp(20f) }
    val bigCx = g.w / 2 + which * (big / 2 + g.dp(30f) + g.dp(28f))
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

/** A cover, laid out at the full player's size and scaled by the motion; its corners and glow follow it. */
@Composable
private fun ArtFace(t: TrackDto, radiusOf: () -> Float, glow: Color, glowOf: () -> Float) {
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
            },
    ) {
        if (img != null) androidx.compose.foundation.Image(img, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else MadeUpCover(t.album, Modifier.fillMaxSize())
    }
}

/** One of the two lines, laid out at the full player's size; the motion scales it. The second is the song's name. */
@Composable
private fun TitleLine(text: String, sizePx: Float, modifier: Modifier, second: Boolean = false) {
    val d = LocalDensity.current
    val style = with(d) {
        if (second) TextStyle(fontSize = sizePx.toSp(), fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp)
        else TextStyle(fontSize = sizePx.toSp(), fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
    }
    Box(modifier) {
        Text(
            text, style = style, color = if (second) Bridge.Text.copy(alpha = 0.82f) else Bridge.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The play button, as Namida's: a disc in the cover's colour going grey towards its lower
 * right, glowing onto the player in the same colour, with the outline of play or pause on it.
 * Pressed, it gives a little and glows a little more.
 */
@Composable
private fun PlayButton(playing: Boolean, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, tween(400), label = "press")
    Box(modifier) {
        Box(
            Modifier.fillMaxSize().padding(9.dp)
                .graphicsLayer { scaleX = s; scaleY = s }
                .shadow(if (pressed) 16.dp else 11.dp, CircleShape, ambientColor = tint, spotColor = tint)
                .clip(CircleShape)
                .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick)
                .background(Brush.linearGradient(0f to (if (pressed) lerp(tint, Color.White, 0.09f) else tint), 0.7f to lerp(tint, Color(0xFF9E9E9E), 0.22f))),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                playing,
                transitionSpec = { (scaleIn(tween(200), 0.7f) + fadeIn(tween(200))) togetherWith (scaleOut(tween(200), 0.7f) + fadeOut(tween(160))) },
                label = "playpause",
            ) { on ->
                Icon(if (on) PlayerIcons.Pause else PlayerIcons.Play, if (on) "Pause" else "Play", tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun TransportButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier) {
        Box(Modifier.fillMaxSize().pressable(CircleShape, scaleTo = 0.86f, onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = Bridge.Text, modifier = Modifier.size(30.dp))
        }
    }
}

/**
 * The heart beside the two lines: the outline in the player's quiet colour; tapped, it fills
 * with the cover's colour and springs, as Namida's does. Kept on the phone, the same on the page.
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
    val lit = Bridge.Accent
    Box(modifier) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).clickable(onClick = onClick).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (on) PlayerIcons.HeartOn else PlayerIcons.Heart, if (on) "Take the heart off" else "Give it a heart",
                tint = if (on) lit.copy(alpha = 0.9f) else Bridge.Muted.copy(alpha = 0.85f), modifier = Modifier.size(28.dp))
        }
    }
}

/** Where the song is and how long it is, either side of the controls, as a clock reads: 01:46. */
@Composable
private fun Times(state: PhonePlayer.State, tick: State<Long>, modifier: Modifier) {
    val live = rememberUpdatedState(state)
    val secs by remember { derivedStateOf { tick.value; live.value.positionNow() / 1000 } }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
        Text(clock(secs * 1000), style = style, color = Bridge.Text.copy(alpha = 0.78f))
        Spacer(Modifier.weight(1f))
        Text(clock(state.durationMs), style = style, color = Bridge.Text.copy(alpha = 0.78f))
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
            Text((if (diff >= 0) "+" else "\u2212") + clock(abs(diff)), style = LabelStyle.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = Bridge.Text, textAlign = TextAlign.Center)
        }
    }
}

/** Namida's top row: close, and where in the queue (2/14) and what it is playing from (a tap shows the album). */
@Composable
private fun TopRow(index: Int, count: Int, album: String, onClose: () -> Unit, onAlbum: () -> Unit, modifier: Modifier) {
    Row(modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(PlayerIcons.Down, "Close the player", tint = Bridge.Text, modifier = Modifier.size(24.dp))
        }
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onAlbum).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("${index + 1}/$count", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"), color = Bridge.Text.copy(alpha = 0.8f))
            Text(album, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Text.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // As wide as the close button, so the middle stays in the middle.
        Spacer(Modifier.size(44.dp))
    }
}

/**
 * Namida's bottom row: on the left, the headphones in a disc and what the file is (the kind of
 * file, then its bitrate and sample rate; a tap opens the sound controls too); on the right,
 * repeat and the sound controls. The queue is a drag up away; the other songs are the Tracks
 * and Albums pages.
 */
@Composable
private fun BottomRow(
    state: PhonePlayer.State,
    info: dev.periy.bridge.server.TrackInfoDto?,
    onRepeat: () -> Unit,
    onSound: () -> Unit,
    modifier: Modifier,
) {
    val repeat = state.repeat
    val changed = state.speed != 1f || state.pitch != 1f || state.volume != 1f
    Row(modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val kind = info?.format?.takeIf { it.isNotEmpty() } ?: state.current?.mime?.let(::formatBadge)?.takeIf { it.isNotEmpty() } ?: "Audio"
        val details = listOfNotNull(
            info?.let(::fileDetails)?.takeIf { it.isNotEmpty() },
            speedLabel(state.speed).takeIf { state.speed != 1f },
        ).joinToString(" \u2022 ")
        // The chip takes what room the buttons leave, and its details give way first.
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier.clip(ButtonShape).clickable(onClick = onSound).padding(start = 2.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Bridge.Chip), contentAlignment = Alignment.Center) {
                Icon(PlayerIcons.Headphones, null, tint = Bridge.Text, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(9.dp))
            Text(kind, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = Bridge.Text, maxLines = 1)
            if (details.isNotEmpty()) {
                Spacer(Modifier.width(7.dp))
                Text(details, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                    color = Bridge.Text.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        }
        Toggled(if (repeat == PhonePlayer.Repeat.ONE) PlayerIcons.RepeatOne else PlayerIcons.Repeat,
            when (repeat) { PhonePlayer.Repeat.OFF -> "Repeat"; PhonePlayer.Repeat.ALL -> "Repeat all"; else -> "Repeat this song" },
            repeat != PhonePlayer.Repeat.OFF, onClick = onRepeat)
        Toggled(PlayerIcons.Sound, "Sound: speed, pitch and volume", changed, onClick = onSound)
    }
}

/** A speed as the sound controls show it: 1.25×. */
internal fun speedLabel(v: Float): String = "%.2f\u00D7".format(v).replace(".00\u00D7", "\u00D7")

@Composable
private fun Toggled(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).pressable(CircleShape, scaleTo = 0.88f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = if (on) Bridge.Accent else Bridge.Text.copy(alpha = 0.85f), modifier = Modifier.size(21.dp))
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
