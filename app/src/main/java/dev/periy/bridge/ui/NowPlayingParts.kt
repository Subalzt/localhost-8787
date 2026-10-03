package dev.periy.bridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.ln
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.periy.bridge.container
import dev.periy.bridge.music.Loudness
import dev.periy.bridge.music.PhonePlayer
import dev.periy.bridge.server.TrackDto
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------- the waveform

/**
 * The seek bar, drawn as the song's own waveform: faint where it is still to come, nearly solid
 * where it has played. It grows up out of a flat line the moment the song's loudness is known.
 * While the song plays, what has played melts into one smooth shape whose front edge rises and
 * falls with the music, and the rest settles into a plain bar; paused or touched, it is bars again. Touch and drag to choose a place ([onScrub] says where, to show it); let go to go
 * there. Dragging up off it takes the seek back, with a tick to say so.
 */
@Composable
internal fun WaveSeek(
    env: ByteArray?,
    state: PhonePlayer.State,
    tint: Color,
    tick: State<Long>,
    /** Whether it takes touches now: a touch while it is not is left for the player to handle. */
    active: () -> Boolean,
    onScrub: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
) {
    val live = rememberUpdatedState(state)
    val activeNow = rememberUpdatedState(active)
    val view = LocalView.current
    val density = LocalDensity.current
    val appear = animateFloatAsState(if (env != null) 1f else 0f, tween(700, easing = FastOutSlowInEasing), label = "wave")
    var widthPx by remember { mutableIntStateOf(0) }
    // Namida's waveform: many fine bars close together, each a little over half its pitch wide.
    val pitch = with(density) { 2.5.dp.toPx() }
    val count = if (widthPx > 0) (widthPx / pitch).toInt().coerceAtLeast(8) else 0
    val barW = pitch * 0.54f
    // Spread over the whole height, as the page draws it: the quietest bar low, the loudest near
    // full, eased so the loud parts stand up out of the rest.
    val raw = remember(env, count) { Loudness.bars(env, count, average = true) }
    val bars = remember(raw) { spreadBars(raw) }
    // For the shape: the bars evened out over their neighbours, so its outline is smooth.
    val smooth = remember(bars) { smoothBars(smoothBars(bars, 4), 4) }
    val scrub = remember { mutableStateOf<Long?>(null) }
    val shape = animateFloatAsState(
        if (state.playing && scrub.value == null) 1f else 0f, tween(320, easing = FastOutSlowInEasing), label = "shape",
    )
    // The front edge's level, eased frame to frame: [level, frame time].
    val edge = remember { floatArrayOf(0f, 0f) }
    val path = remember { Path() }
    // In the type colour: faint still to come, nearly solid where it has played.
    val nc = Nm.c
    val track = nc.onSurface.copy(alpha = 0.22f)
    val playedA = nc.onSurface.copy(alpha = 0.9f)
    val cancelAt = with(density) { 52.dp.toPx() }

    Box(
        modifier
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!activeNow.value()) return@awaitEachGesture
                    // Taken here, so a drag on the waveform seeks rather than swiping songs.
                    down.consume()
                    val dur = live.value.durationMs
                    if (dur <= 0) return@awaitEachGesture
                    fun at(x: Float) = ((x / size.width).coerceIn(0f, 1f) * dur).toLong()
                    var cancelled = false
                    scrub.value = at(down.position.x)
                    onScrub(scrub.value)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        ch.consume()
                        if (cancelled) continue
                        if (down.position.y - ch.position.y > cancelAt) {
                            cancelled = true
                            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                            scrub.value = null
                            onScrub(null)
                        } else {
                            scrub.value = at(ch.position.x)
                            onScrub(scrub.value)
                        }
                    }
                    val target = scrub.value
                    scrub.value = null
                    onScrub(null)
                    if (!cancelled && target != null) onSeek(target)
                }
            }
            .drawBehind {
                tick.value
                val n = bars.size
                if (n == 0) return@drawBehind
                val s = live.value
                val pos = scrub.value ?: s.positionNow()
                val f = if (s.durationMs > 0) (pos.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f
                // Spaced evenly, a gap before, between and after the bars, as Namida's are.
                val gap = (size.width - barW * n) / (n + 1)
                val step = barW + gap
                val minH = 2.dp.toPx()
                val cy = size.height / 2
                val grow = appear.value
                fun drawBars(color: Color? = null, brush: Brush? = null, flat: Float = 0f) {
                    for (i in 0 until n) {
                        val h = minH + (size.height - minH) * bars[i] * grow * (1f - flat)
                        val tl = Offset(gap + i * step, cy - h / 2)
                        val sz = Size(barW, h)
                        if (brush != null) drawRoundRect(brush, tl, sz, CornerRadius(barW / 2))
                        else drawRoundRect(color!!, tl, sz, CornerRadius(barW / 2))
                    }
                }
                val playedX = size.width * f
                val k = shape.value
                // How loud the song is now, on the bars' scale, eased so the edge never flickers.
                val now = tick.value.toFloat()
                val lo = raw.minOrNull() ?: 0f
                val hi = raw.maxOrNull() ?: 1f
                val loud = Loudness.at(env, pos)
                val target = if (hi - lo < 1e-4f) loud else 0.04f + 0.94f * ((loud - lo) / (hi - lo)).coerceIn(0f, 1f).pow(1.6f)
                val dt = if (edge[1] == 0f) 16f else (now - edge[1]).coerceIn(0f, 64f)
                edge[0] += (target - edge[0]) * (1f - kotlin.math.exp(-dt / 110f))
                edge[1] = now

                // Still to come: faint bars, settling into a plain bar while the song plays.
                if (k < 0.99f) clipRect(left = playedX) { drawBars(color = track, flat = k) }
                if (k > 0.01f && playedX < size.width) {
                    val th = 3.dp.toPx()
                    drawRoundRect(
                        nc.onSurface.copy(alpha = 0.24f * k), Offset(playedX, cy - th / 2),
                        Size(size.width - playedX, th), CornerRadius(th / 2),
                    )
                }
                if (playedX <= 0f) return@drawBehind
                if (k < 0.99f) clipRect(right = playedX) {
                    drawBars(color = playedA.copy(alpha = playedA.alpha * (1f - k)))
                }
                if (k > 0.01f) {
                    // What has played as one shape: points at fixed places along it, so the
                    // history holds still and only the front edge moves.
                    fun at(px: Float): Float {
                        val fi = ((px - gap - barW / 2) / step).coerceIn(0f, (n - 1).toFloat())
                        val i = fi.toInt()
                        val j = (i + 1).coerceAtMost(n - 1)
                        return smooth[i] + (smooth[j] - smooth[i]) * (fi - i)
                    }
                    val p = 4.dp.toPx()
                    val reach = 14.dp.toPx()
                    val xs = ArrayList<Float>()
                    val hs = ArrayList<Float>()
                    var px = 0f
                    while (true) {
                        val last = px >= playedX
                        if (last) px = playedX
                        // Near the front the outline leans a little towards how loud the song
                        // is now: enough to move with it, never a spike.
                        val e = (1f - (playedX - px) / reach).coerceAtLeast(0f)
                        val w = k * e * e * (3f - 2f * e)
                        val base = at(px)
                        val v = (base + w * 0.3f * (edge[0] - base)).coerceIn(0f, 1f)
                        xs.add(px); hs.add((minH + (size.height - minH) * v * grow) / 2)
                        if (last) break
                        px += p
                    }
                    path.reset()
                    path.moveTo(0f, cy)
                    for (j in xs.indices) {
                        if (j == xs.lastIndex) { path.lineTo(xs[j], cy - hs[j]); break }
                        path.quadraticTo(xs[j], cy - hs[j], (xs[j] + xs[j + 1]) / 2, cy - (hs[j] + hs[j + 1]) / 2)
                    }
                    path.lineTo(playedX, cy)
                    for (j in xs.indices.reversed()) {
                        if (j == 0) { path.lineTo(xs[j], cy + hs[j]); break }
                        path.quadraticTo(xs[j], cy + hs[j], (xs[j] + xs[j - 1]) / 2, cy + (hs[j] + hs[j - 1]) / 2)
                    }
                    path.close()
                    drawPath(path, playedA.copy(alpha = playedA.alpha * k))
                }
            },
    )
}

/** Each bar the mean of itself and [r] either side (the page's npSmooth). */
internal fun smoothBars(bars: FloatArray, r: Int): FloatArray = FloatArray(bars.size) { i ->
    var m = 0f
    var c = 0
    for (k in maxOf(0, i - r)..minOf(bars.lastIndex, i + r)) { m += bars[k]; c++ }
    if (c > 0) m / c else 0f
}

/** Bars from 4% to 98% of the height, from the quietest to the loudest, eased up by 1.6 (as the page's npBarsSpread). */
internal fun spreadBars(raw: FloatArray): FloatArray {
    if (raw.isEmpty()) return raw
    val lo = raw.min()
    val hi = raw.max()
    if (hi - lo < 1e-4f) return FloatArray(raw.size) { 0.04f + 0.94f * raw[it].coerceIn(0f, 1f) }
    return FloatArray(raw.size) { 0.04f + 0.94f * ((raw[it] - lo) / (hi - lo)).pow(1.6f) }
}

// ---------------------------------------------------------------------------- the queue

/** The song being dragged by its handle, and how far from its place. */
@Stable
private class QueueDrag {
    var key by mutableStateOf<String?>(null)
    var offset by mutableFloatStateOf(0f)
}

/**
 * The queue, under the mini row at the top: every song in the order it will play, the one
 * playing lit. Tap one to play it; drag its handle to move it; swipe it away to take it off.
 * Pulled down from the top of the list, the player comes back down with it.
 */
@Composable
internal fun QueuePanel(
    player: PhonePlayer,
    state: PhonePlayer.State,
    motion: PlayerMotion,
    tint: Color,
    navBottom: Dp,
    modifier: Modifier,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val live = rememberUpdatedState(state)
    // Opening, the list is already at the song playing, so it rises into place with it.
    SideEffect {
        motion.onQueueOpening = {
            scope.launch { list.scrollToItem(live.value.index.coerceAtLeast(0)) }
        }
    }
    val pull = remember(motion) {
        object : NestedScrollConnection {
            var pulling = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!pulling || source != NestedScrollSource.UserInput) return Offset.Zero
                motion.dragBy(-available.y)
                return Offset(0f, available.y)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f || motion.p < 1.5f) return Offset.Zero
                if (!pulling) { pulling = true; motion.dragStart() }
                motion.dragBy(-available.y)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!pulling) return Velocity.Zero
                pulling = false
                motion.release(available.y) {}
                return available
            }
        }
    }
    // Songs can be in the queue twice: each keeps its own key.
    val keys = remember(state.queue) {
        val seen = HashMap<Long, Int>()
        state.queue.map { t -> val n = seen.merge(t.id, 1, Int::plus)!!; "${t.id}#$n" }
    }
    val liveKeys = rememberUpdatedState(keys)
    val drag = remember { QueueDrag() }
    val nc = Nm.c
    val favourites = androidx.compose.ui.platform.LocalContext.current.container.favourites
    val hearts by favourites.ids.collectAsState()
    Box(modifier) {
        // Namida's queue: a sheet with corners of 32, its header washed in the song's colour.
        Column(Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).background(nc.bg)) {
            QueueHeader(state, onShuffle = { player.setShuffle(!state.shuffle) }, onClose = { motion.expand() })
            LazyColumn(
                Modifier.weight(1f).nestedScroll(pull),
                state = list,
                contentPadding = PaddingValues(bottom = navBottom + 12.dp),
            ) {
                itemsIndexed(state.queue, key = { i, _ -> keys.getOrElse(i) { "x$i" } }) { i, t ->
                    val k = keys.getOrElse(i) { "x$i" }
                    val dragging = drag.key == k
                    QueueRow(
                        t, k, current = i == state.index, sounding = state.playing, tint = tint, drag = drag,
                        hearted = t.id in hearts, onHeart = { favourites.toggle(t.id) },
                        modifier = (if (dragging) Modifier else Modifier.animateItem())
                            .zIndex(if (dragging) 1f else 0f),
                        keysNow = { liveKeys.value },
                        onMove = { from, to -> player.move(from, to) },
                        onTap = { player.skipTo(liveKeys.value.indexOf(k).takeIf { it >= 0 } ?: i) },
                        onRemove = { liveKeys.value.indexOf(k).takeIf { it >= 0 }?.let { player.remove(it) } },
                    )
                }
            }
        }

    }
}

/**
 * The queue's header: "Queue", where in it and the time left after the song playing; Shuffle
 * (the same switch as the page's); and the arrow back down to the player.
 */
@Composable
private fun QueueHeader(state: PhonePlayer.State, onShuffle: () -> Unit, onClose: () -> Unit) {
    val nc = Nm.c
    val left = state.queue.drop(state.index.coerceAtLeast(0)).sumOf { it.durationMs }
    Row(
        Modifier.fillMaxWidth().background(nc.bg)
            .drawBehind { drawLine(nc.onSurface.copy(alpha = 0.12f), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) }
            .padding(top = 16.dp, bottom = 10.dp).heightIn(min = 42.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(start = 20.dp)) {
            Text("Queue", style = TextStyle(fontFamily = MusicType, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = nc.large))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val small = Nm.small.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
                Text("${state.index + 1}/${state.queue.size} \u2022", style = small)
                Spacer(Modifier.width(4.dp))
                Icon(Iconsax.Timer, null, tint = nc.small, modifier = Modifier.size(8.dp))
                Spacer(Modifier.width(2.dp))
                Text(fmtMinutes(left), style = small)
            }
        }
        // Shuffle, as on the page: dim while off, the icon colour while on.
        val shuffleTint by androidx.compose.animation.animateColorAsState(
            if (state.shuffle) nc.icon else nc.icon.copy(alpha = 0.4f), tween(200), label = "shuffle",
        )
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onShuffle)
                .semantics { contentDescription = if (state.shuffle) "Shuffle is on" else "Shuffle" },
            contentAlignment = Alignment.Center,
        ) { Icon(Iconsax.Shuffle, null, tint = shuffleTint, modifier = Modifier.size(21.dp)) }
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Iconsax.Down, "Back to the player", tint = nc.icon, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(8.dp))
    }
}



@Composable
private fun QueueRow(
    t: TrackDto,
    key: String,
    current: Boolean,
    sounding: Boolean,
    tint: Color,
    drag: QueueDrag,
    hearted: Boolean,
    onHeart: () -> Unit,
    modifier: Modifier,
    keysNow: () -> List<String>,
    onMove: (Int, Int) -> Unit,
    onTap: () -> Unit,
    onRemove: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    var rowW by remember { mutableIntStateOf(1) }
    val density = LocalDensity.current
    val rowH = with(density) { ROW_H.toPx() }
    val danger = Bridge.Danger
    Box(
        modifier.fillMaxWidth().height(ROW_H)
            .onSizeChanged { rowW = it.width.coerceAtLeast(1) }
            .graphicsLayer {
                val dragging = drag.key == key
                translationY = if (dragging) drag.offset else 0f
                shadowElevation = if (dragging) 10.dp.toPx() else 0f
            },
    ) {
        // Swiped aside, the row shows red under it: let go past a third and it is taken off.
        Box(Modifier.fillMaxSize().drawBehind {
            val f = (abs(dx.value) / rowW).coerceIn(0f, 1f)
            if (f > 0f) drawRect(danger.copy(alpha = (f * 2.2f).coerceAtMost(0.9f)))
        }) {
            Icon(Iconsax.Trash, null, tint = Color.White, modifier = Modifier.align(if (dx.value > 0) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 24.dp).size(22.dp).graphicsLayer { alpha = (abs(dx.value) / rowW * 4f).coerceIn(0f, 1f) })
        }
        val nc = Nm.c
        val shrink by animateFloatAsState(if (current) 0.94f else 1f, tween(400), label = "thumb")
        val line = nc.onSurface.copy(alpha = 0.12f)
        Row(
            Modifier.fillMaxSize()
                .offset { IntOffset(dx.value.roundToInt(), 0) }
                .background(nc.bg)
                // The song playing lifted onto a card of its own, as in the library.
                .drawBehind { if (current) playingCard(nc, 1f) }
                .pointerInput(key) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (abs(dx.value) > rowW / 3f) {
                                    dx.animateTo(if (dx.value > 0) rowW.toFloat() else -rowW.toFloat(), tween(160))
                                    onRemove()
                                    dx.snapTo(0f)
                                } else dx.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
                            }
                        },
                        onDragCancel = { scope.launch { dx.animateTo(0f) } },
                    ) { ch, amount -> ch.consume(); scope.launch { dx.snapTo(dx.value + amount) } }
                }
                .clickable(onClick = onTap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A plain row, as the library's: the cover, the song and who it is by, how long; the one
            // playing in the lit colour with bars on its cover. A swipe either way takes it off;
            // the handle moves it.
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier.size(46.dp).graphicsLayer { scaleX = shrink; scaleY = shrink }
                    .clip(RoundedCornerShape(6.dp)).border(0.5.dp, line, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Cover(t.albumId, t.album, Modifier.fillMaxSize(), radius = 6.dp)
            }
            Spacer(Modifier.width(14.dp))
            Row(
                Modifier.weight(1f).fillMaxHeight().drawBehind {
                    if (!current) drawLine(line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = TextStyle(fontFamily = MusicType, fontSize = 16.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        color = nc.large), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, style = TextStyle(fontFamily = MusicType, fontSize = 14.sp, color = nc.small), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (hearted) Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onHeart), contentAlignment = Alignment.Center) {
                    Icon(Iconsax.HeartOn, "Take the heart off", tint = nc.small, modifier = Modifier.size(14.dp))
                }
                Text(fmtTime(t.durationMs), style = TextStyle(fontFamily = MusicType, fontSize = 13.sp, color = nc.small, fontFeatureSettings = "tnum"),
                    modifier = Modifier.padding(start = 4.dp))
                // The handle: drag it up or down and the song moves through the queue.
                Box(
                    Modifier.size(44.dp).pointerInput(key) {
                        detectDragGestures(
                            onDragStart = { drag.key = key; drag.offset = 0f },
                            onDragEnd = { drag.key = null; drag.offset = 0f },
                            onDragCancel = { drag.key = null; drag.offset = 0f },
                        ) { ch, amount ->
                            ch.consume()
                            drag.offset += amount.y
                            val keys = keysNow()
                            val i = keys.indexOf(key)
                            if (i < 0) return@detectDragGestures
                            if (drag.offset > rowH / 2 && i < keys.lastIndex) { onMove(i, i + 1); drag.offset -= rowH }
                            else if (drag.offset < -rowH / 2 && i > 0) { onMove(i, i - 1); drag.offset += rowH }
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(Iconsax.Handle, "Move", tint = nc.small, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}

/** A row of the queue. */
private val ROW_H = 64.dp

// ---------------------------------------------------------------------------- the sound controls

/** Namida's dialogs come and go over 300 ms. */
private const val DIALOG_MS = 300

/** Tuned to 432 Hz, as a pitch: Namida's one-tap choice beside Pitch. */
private const val HZ432 = 432f / 440f

private fun toSemitones(ratio: Float): Float = if (ratio <= 0f) -12f else (12.0 * ln(ratio.toDouble()) / ln(2.0)).toFloat()
private fun fromSemitones(st: Float): Float = 2.0.pow(st / 12.0).toFloat()

/** Namida's figures: a percentage to two places at most (100.0%, 98.18%), speed as 1.00x, pitch as 0.0 st. */
private fun percent(v: Float): String {
    val p = (v * 10000f).roundToInt() / 100.0
    return (if (p % 1.0 == 0.0) String.format(Locale.US, "%.1f", p) else p.toString()) + "%"
}
private fun times(v: Float) = String.format(Locale.US, "%.2fx", v)
private fun stText(st: Float) = String.format(Locale.US, "%.1f st", st)
private fun wholePercent(v: Float) = "${(v * 100).roundToInt()}%"

/**
 * Namida's Configure dialog, from the player's chip or its sound button, presented as Namida
 * presents every dialog: the player behind blurred ([fade] is how far, for the player to read)
 * under black at 45%, the dialog at 0.96 of its size, the whole fading in over 300 ms. Its title
 * centred on a band across the top; pitch, speed and volume, each a line (its icon, its name,
 * its value, a restore) over a slider with a step either side of it; reset all and Done along
 * the foot. Back (which it follows as it is swiped), a tap outside or Done lets it go.
 */
@Composable
internal fun SoundSheet(player: PhonePlayer, state: PhonePlayer.State, fade: MutableFloatState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(DIALOG_MS, easing = LinearOutSlowInEasing)) }
    val close: () -> Unit = {
        if (!closing) { closing = true; scope.launch { shown.animateTo(0f, tween(DIALOG_MS, easing = FastOutLinearInEasing)); onClose() } }
    }
    val swipe = rememberBackSwipe(enabled = !closing) { swiped -> if (swiped) { closing = true; onClose() } else close() }
    LaunchedEffect(Unit) { snapshotFlow { shown.value * (1f - swipe.progress.value) }.collect { fade.floatValue = it } }
    DisposableEffect(Unit) { onDispose { fade.floatValue = 0f } }

    val live = rememberUpdatedState(state)
    fun set(speed: Float = live.value.speed, pitch: Float = live.value.pitch, volume: Float = live.value.volume) = player.setSound(speed, pitch, volume)
    var inSemitones by remember { mutableStateOf(player.pitchInSemitones) }
    var linked by remember { mutableStateOf(player.speedCarriesPitch) }
    val pitchAlpha by animateFloatAsState(if (linked) 0.6f else 1f, tween(300), label = "pitch")
    val nc = Nm.c

    Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value * (1f - swipe.progress.value) }) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).pointerInput(Unit) { detectTapGestures { close() } })
        Column(
            Modifier.align(Alignment.Center)
                .padding(horizontal = 38.dp, vertical = 32.dp)
                .widthIn(max = 428.dp)
                .fillMaxWidth()
                .graphicsLayer { val k = 0.96f * (1f - 0.06f * swipe.progress.value); scaleX = k; scaleY = k }
                .clip(RoundedCornerShape(24.dp))
                .background(nc.dialog)
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState()),
        ) {
            // The title, on a band of the card colour across the top.
            Box(
                Modifier.fillMaxWidth().background(nc.primary.copy(alpha = 0.02f).compositeOver(nc.card)).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Configure", style = Nm.medium, textAlign = TextAlign.Center) }

            Column(Modifier.padding(vertical = 12.dp)) {
                // Pitch: counted as a percentage or in semitones (a tap on its line switches), and
                // held by speed while the two are linked.
                val is432 = abs(state.pitch - HZ432) < 0.0005f
                Column(Modifier.graphicsLayer { alpha = pitchAlpha }) {
                    SoundLine(
                        Iconsax.Pitch, "Pitch", if (inSemitones) "(Semitones)" else "(Percentage)",
                        if (inSemitones) stText((toSemitones(state.pitch) * 10f).roundToInt() / 10f) else percent(state.pitch),
                        onTap = { inSemitones = !inSemitones; player.pitchInSemitones = inSemitones },
                        onRestore = { set(pitch = 1f) },
                        enabled = !linked,
                        featured = {
                            SoundChip("432Hz", is432, enabled = !linked, onClick = { set(pitch = if (is432) 1f else HZ432) }) {
                                Text("✓ ", style = Nm.small)
                            }
                        },
                    )
                    if (inSemitones) CuteSlider(toSemitones(state.pitch), -12f, 12f, 1f, 0.5f, ::stText, enabled = !linked) { set(pitch = fromSemitones(it)) }
                    else CuteSlider(state.pitch, 0f, 2f, 0.01f, 0.01f, ::wholePercent, enabled = !linked) { set(pitch = it) }
                }
                Spacer(Modifier.height(6.dp))
                SoundLine(
                    Iconsax.Speed, "Speed", null, times(state.speed),
                    onTap = null,
                    onRestore = { set(speed = 1f, pitch = if (linked) 1f else live.value.pitch) },
                    featured = {
                        SoundChip("Pitch", linked, onClick = {
                            linked = !linked
                            player.speedCarriesPitch = linked
                            if (linked) set(pitch = live.value.speed)
                        }) {
                            Icon(Iconsax.Link, null, tint = nc.icon, modifier = Modifier.padding(end = 4.dp).size(12.dp))
                        }
                    },
                )
                CuteSlider(state.speed, 0f, 2f, 0.01f, 0.01f, ::wholePercent) { set(speed = it, pitch = if (linked) it else live.value.pitch) }
                Spacer(Modifier.height(6.dp))
                SoundLine(
                    if (state.volume > 0f) Iconsax.Volume else Iconsax.Mute, "Volume", null, percent(state.volume),
                    onTap = null,
                    onRestore = { set(volume = 1f) },
                )
                CuteSlider(state.volume, 0f, 1f, 0.01f, 0.01f, ::wholePercent) { set(volume = it) }
                Spacer(Modifier.height(6.dp))
            }
            // Reset all, and Done.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NmIconButton(Iconsax.Reset, "Put them all back", 24.dp, 8.dp, 0.dp, nc.secondary) { set(1f, 1f, 1f) }
                Spacer(Modifier.width(6.dp))
                NamidaButton("Done", null, onClick = close)
            }
        }
    }
}

/**
 * Namida's icon button: the icon and nothing round it, dimmed to half while pressed. With
 * [repeat], held down it goes on doing it, ten times a second.
 */
@Composable
internal fun NmIconButton(
    icon: ImageVector, desc: String, size: Dp, padH: Dp, padV: Dp, tint: Color,
    repeat: Boolean = false, enabled: Boolean = true, action: () -> Unit,
) {
    val act = rememberUpdatedState(action)
    var pressed by remember { mutableStateOf(false) }
    val a by animateFloatAsState(if (pressed) 0.5f else 1f, tween(200), label = "press")
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .semantics {
                role = Role.Button
                contentDescription = desc
                if (enabled) onClick(label = null) { act.value(); true }
            }
            .pointerInput(repeat, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    var held = false
                    val job = if (repeat) scope.launch {
                        delay(viewConfiguration.longPressTimeoutMillis)
                        held = true
                        while (true) { act.value(); delay(100) }
                    } else null
                    val up = waitForUpOrCancellation()
                    job?.cancel()
                    pressed = false
                    if (up != null) { up.consume(); if (!held) act.value() }
                }
            }
            .graphicsLayer { alpha = a }
            .padding(horizontal = padH, vertical = padV),
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size)) }
}

/**
 * One of the sound controls' lines, as Namida's: its icon, its name (and under it how it is
 * counted, where that can change), its value, then any quick choice and the restore.
 */
@Composable
private fun SoundLine(
    icon: ImageVector, title: String, subtitle: String?, value: String,
    onTap: (() -> Unit)?, onRestore: () -> Unit,
    enabled: Boolean = true,
    featured: (@Composable () -> Unit)? = null,
) {
    val nc = Nm.c
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 8.dp)
            .then(if (onTap != null) Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onTap).padding(vertical = 6.dp) else Modifier)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = nc.secondary, modifier = Modifier.padding(horizontal = 6.dp).size(24.dp))
        Spacer(Modifier.width(6.dp))
        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f, fill = false)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = Nm.large.copy(fontSize = 16.nsp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (onTap != null) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Iconsax.Swap, null, tint = nc.icon, modifier = Modifier.size(12.dp))
                    }
                }
                if (subtitle != null) Text(subtitle, style = Nm.small.copy(fontSize = 10.nsp), maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            Text(value, style = Nm.medium.copy(fontSize = 13.5.nsp, fontFeatureSettings = "tnum"), maxLines = 1)
        }
        if (featured != null) {
            Spacer(Modifier.width(2.dp))
            featured()
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onRestore).semantics { contentDescription = "$title back to normal" },
            contentAlignment = Alignment.Center,
        ) { Icon(Iconsax.Reset, null, tint = nc.icon, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(10.dp))
    }
}

/** Namida's small quick choice beside a line's value: lit more while it is on, a mark sliding in before its words. */
@Composable
private fun SoundChip(text: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit, mark: @Composable () -> Unit) {
    val nc = Nm.c
    Row(
        Modifier.clip(RoundedCornerShape(7.2.dp)).background(nc.secondaryContainer.copy(alpha = if (on) 0.5f else 0.2f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 7.56.dp, vertical = 3.78.dp)
            .semantics { stateDescription = if (on) "On" else "Off" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            on,
            enter = expandHorizontally(tween(300, easing = FastLinearToSlowEaseIn)) + fadeIn(tween(300, easing = FastLinearToSlowEaseIn)),
            exit = shrinkHorizontally(tween(300, easing = FastLinearToSlowEaseIn)) + fadeOut(tween(300, easing = FastLinearToSlowEaseIn)),
        ) { mark() }
        Text(text, style = Nm.small, maxLines = 1)
    }
}

/** Flutter's fastLinearToSlowEaseIn, which Namida's quick choices come in on. */
private val FastLinearToSlowEaseIn = CubicBezierEasing(0.18f, 1f, 0.04f, 1f)

/**
 * Namida's slider with a step either side: the Material kind it uses (a track 6 thick, lit from
 * the left up to an upright thumb 5 by 24, with a gap of 4 either side of it and small corners
 * there), moved by sliding along it, a tap on it moving nothing; while sliding, the value shows
 * on a label over the thumb. The steps go by [arrowStep], held down ten a second.
 */
@Composable
private fun CuteSlider(
    value: Float, min: Float, max: Float, step: Float, arrowStep: Float,
    label: (Float) -> String, enabled: Boolean = true, onChange: (Float) -> Unit,
) {
    val nc = Nm.c
    val set = rememberUpdatedState(onChange)
    val now = rememberUpdatedState(value)
    fun fine(v: Float) = (v * 10000f).roundToInt() / 10000f
    fun snap(v: Float) = fine((v / step).roundToInt() * step).coerceIn(min, max)
    val active = nc.primary.copy(alpha = 0.85f)
    val inactive = nc.secondary.copy(alpha = 0.2f)
    val bubble = if (nc.dark) Color(0xFF232323) else Color.White
    val labelStyle = TextStyle(
        fontFamily = MusicType, fontSize = 14.nsp,
        color = if (nc.dark) Color.White.copy(alpha = 210 / 255f) else Color.Black.copy(alpha = 160 / 255f),
    )
    val measurer = rememberTextMeasurer()
    var dragging by remember { mutableStateOf(false) }
    val lit by animateFloatAsState(if (dragging) 1f else 0f, tween(if (dragging) 160 else 120), label = "label")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(12.dp))
        NmIconButton(Iconsax.Back, "Less", 20.dp, 4.dp, 4.dp, nc.secondary, repeat = true, enabled = enabled) {
            set.value(fine(now.value - arrowStep).coerceAtLeast(min))
        }
        Box(
            Modifier.weight(1f).height(44.dp)
                .pointerInput(min, max, step, enabled) {
                    if (!enabled) return@pointerInput
                    var v = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { v = now.value; dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { ch, dx ->
                        ch.consume()
                        v = (v + dx / (size.width - 20.dp.toPx()) * (max - min)).coerceIn(min, max)
                        val s = snap(v)
                        if (s != now.value) set.value(s)
                    }
                }
                .drawBehind {
                    val pad = 10.dp.toPx()
                    val l = pad
                    val w = size.width - pad * 2
                    val cy = size.height / 2
                    val th = 6.dp.toPx()
                    val gap = 4.dp.toPx()
                    val thumbW = 5.dp.toPx()
                    val thumbH = 24.dp.toPx()
                    val x = l + w * ((value.coerceIn(min, max) - min) / (max - min))
                    val outer = CornerRadius(th / 2)
                    val inner = CornerRadius(2.dp.toPx())
                    fun bar(from: Float, to: Float, left: CornerRadius, right: CornerRadius, c: Color) {
                        if (to - from <= 0.5f) return
                        drawPath(Path().apply {
                            addRoundRect(RoundRect(Rect(from, cy - th / 2, to, cy + th / 2), topLeft = left, topRight = right, bottomRight = right, bottomLeft = left))
                        }, c)
                    }
                    bar(l, x - thumbW / 2 - gap, outer, inner, active)
                    bar(x + thumbW / 2 + gap, l + w, inner, outer, inactive)
                    drawRoundRect(active, Offset(x - thumbW / 2, cy - thumbH / 2), Size(thumbW, thumbH), CornerRadius(thumbW / 2))
                    if (lit > 0.01f) {
                        val text = measurer.measure(label(value), labelStyle)
                        val bw = maxOf(text.size.width + 16.dp.toPx(), 32.dp.toPx())
                        val bh = 32.dp.toPx()
                        val bottom = cy - thumbH / 2 - 4.dp.toPx()
                        withTransform({ scale(lit, lit, pivot = Offset(x, bottom)) }) {
                            drawRoundRect(bubble, Offset(x - bw / 2, bottom - bh), Size(bw, bh), CornerRadius(bh / 2))
                            drawText(text, topLeft = Offset(x - text.size.width / 2f, bottom - bh / 2 - text.size.height / 2f))
                        }
                    }
                },
        )
        NmIconButton(Iconsax.StepUp, "More", 20.dp, 4.dp, 4.dp, nc.secondary, repeat = true, enabled = enabled) {
            set.value(fine(now.value + arrowStep).coerceAtMost(max))
        }
        Spacer(Modifier.width(12.dp))
    }
}
