package dev.periy.bridge.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import dev.periy.bridge.music.Loudness
import dev.periy.bridge.music.PhonePlayer
import dev.periy.bridge.server.TrackDto
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------- the waveform

/**
 * The seek bar, drawn as the song's own waveform: grey where it is still to come, the cover's
 * colour where it has played. It grows up out of a flat line the moment the song's loudness
 * is known. Touch and drag to choose a place ([onScrub] says where, to show it); let go to go
 * there. Dragging up off it takes the seek back, with a tick to say so.
 */
@Composable
internal fun WaveSeek(
    env: ByteArray?,
    state: PhonePlayer.State,
    tint: Color,
    tick: State<Long>,
    onScrub: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
) {
    val live = rememberUpdatedState(state)
    val view = LocalView.current
    val density = LocalDensity.current
    val appear = animateFloatAsState(if (env != null) 1f else 0f, tween(700, easing = FastOutSlowInEasing), label = "wave")
    var widthPx by remember { mutableIntStateOf(0) }
    val barW = with(density) { 3.dp.toPx() }
    val gap = with(density) { 2.4.dp.toPx() }
    val count = if (widthPx > 0) ((widthPx + gap) / (barW + gap)).toInt().coerceAtLeast(8) else 0
    val bars = remember(env, count) { Loudness.bars(env, count) }
    val scrub = remember { mutableStateOf<Long?>(null) }
    val track = Bridge.Text.copy(alpha = 0.17f)
    val playedA = lerp(tint, Color.White, if (Bridge.Dark) 0.35f else 0f)
    val playedB = if (Bridge.Dark) tint else lerp(tint, Color.Black, 0.2f)
    val cancelAt = with(density) { 52.dp.toPx() }

    Box(
        modifier
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
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
                val step = if (n > 1) (size.width - barW) / (n - 1) else 0f
                val minH = 3.dp.toPx()
                val cy = size.height / 2
                val grow = appear.value
                fun drawBars(color: Color? = null, brush: Brush? = null) {
                    for (i in 0 until n) {
                        val h = minH + (size.height - minH) * bars[i].pow(1.25f) * grow
                        val tl = Offset(i * step, cy - h / 2)
                        val sz = Size(barW, h)
                        if (brush != null) drawRoundRect(brush, tl, sz, CornerRadius(barW / 2))
                        else drawRoundRect(color!!, tl, sz, CornerRadius(barW / 2))
                    }
                }
                drawBars(color = track)
                val playedX = size.width * f
                if (playedX > 0f) clipRect(right = playedX) {
                    drawBars(brush = Brush.horizontalGradient(listOf(playedA, playedB), 0f, playedX.coerceAtLeast(1f)))
                }
            },
    )
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
            scope.launch { list.scrollToItem((live.value.index - 2).coerceAtLeast(0)) }
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
    val lit = if (Bridge.Dark) lerp(tint, Color.White, 0.35f) else lerp(tint, Color.Black, 0.35f)

    Box(modifier) {
        Column(Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(Bridge.Surface)) {
            LazyColumn(
                Modifier.weight(1f).nestedScroll(pull),
                state = list,
                contentPadding = PaddingValues(top = 10.dp, bottom = navBottom + 90.dp),
            ) {
                itemsIndexed(state.queue, key = { i, _ -> keys.getOrElse(i) { "x$i" } }) { i, t ->
                    val k = keys.getOrElse(i) { "x$i" }
                    val dragging = drag.key == k
                    QueueRow(
                        t, k, current = i == state.index, sounding = state.playing, tint = tint, lit = lit, drag = drag,
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

        // How much is queued, shuffle, and back to the song playing: floating along the foot.
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = navBottom + 14.dp)
                .floating(ButtonShape).padding(start = 18.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val total = state.queue.sumOf { it.durationMs }
            val min = (total / 60_000).toInt()
            Text(
                "${state.queue.size} " + (if (state.queue.size == 1) "song" else "songs") + " · " + if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min",
                style = LabelStyle.copy(fontWeight = FontWeight.SemiBold), color = Bridge.Text,
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(40.dp).pressable(CircleShape, scaleTo = 0.88f) { player.setShuffle(!state.shuffle) }, contentAlignment = Alignment.Center) {
                Icon(BlazeIcons.Shuffle, "Shuffle", tint = if (state.shuffle) Bridge.Lit else Bridge.Muted, modifier = Modifier.size(20.dp))
            }
            Box(Modifier.size(40.dp).pressable(CircleShape, scaleTo = 0.88f) {
                scope.launch { list.animateScrollToItem((state.index - 2).coerceAtLeast(0)) }
            }, contentAlignment = Alignment.Center) {
                Icon(BlazeIcons.Locate, "Go to the song playing", tint = Bridge.Muted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun QueueRow(
    t: TrackDto,
    key: String,
    current: Boolean,
    sounding: Boolean,
    tint: Color,
    lit: Color,
    drag: QueueDrag,
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
            Icon(BlazeIcons.Trash, null, tint = Color.White, modifier = Modifier.align(if (dx.value > 0) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 24.dp).size(22.dp).graphicsLayer { alpha = (abs(dx.value) / rowW * 4f).coerceIn(0f, 1f) })
        }
        Row(
            Modifier.fillMaxSize()
                .offset { IntOffset(dx.value.roundToInt(), 0) }
                .background(if (current) lerp(Bridge.Surface, tint, 0.16f) else Bridge.Surface)
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
                .clickable(onClick = onTap)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Cover(t.albumId, t.album, Modifier.size(44.dp), radius = 8.dp)
                if (current) Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    PlayingBars(Color.White, sounding, Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = TextStyle(fontSize = 15.5.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, letterSpacing = (-0.2).sp),
                    color = if (current) lit else Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist, style = CaptionStyle, color = Bridge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(fmtTime(t.durationMs), style = CaptionStyle.copy(fontFeatureSettings = "tnum"), color = Bridge.Muted)
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
            ) { Icon(BlazeIcons.Handle, "Move", tint = Bridge.Faint, modifier = Modifier.size(20.dp)) }
        }
    }
}

private val ROW_H = 64.dp
