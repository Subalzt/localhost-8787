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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    // Namida's waveform: many fine bars close together, each a little over half its pitch wide.
    val pitch = with(density) { 2.5.dp.toPx() }
    val count = if (widthPx > 0) (widthPx / pitch).toInt().coerceAtLeast(8) else 0
    val barW = pitch * 0.54f
    val bars = remember(env, count) { Loudness.bars(env, count) }
    val scrub = remember { mutableStateOf<Long?>(null) }
    // Every bar faint; those played in the cover's colour worked into the text's.
    val ink = Bridge.Text
    val track = ink.copy(alpha = 0.16f)
    val playedA = lerp(ink, tint, 0.7f).copy(alpha = 0.62f)
    val playedB = lerp(ink, tint, 0.55f).copy(alpha = 0.62f)
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
                val minH = 2.dp.toPx()
                val cy = size.height / 2
                val grow = appear.value
                fun drawBars(color: Color? = null, brush: Brush? = null) {
                    for (i in 0 until n) {
                        val h = minH + (size.height - minH) * bars[i].pow(1.4f) * grow
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
                .floating(ButtonShape).background(Bridge.Surface).padding(start = 18.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
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
                Icon(PlayerIcons.Shuffle, "Shuffle", tint = if (state.shuffle) Bridge.Lit else Bridge.Muted, modifier = Modifier.size(20.dp))
            }
            Box(Modifier.size(40.dp).pressable(CircleShape, scaleTo = 0.88f) {
                scope.launch { list.animateScrollToItem((state.index - 2).coerceAtLeast(0)) }
            }, contentAlignment = Alignment.Center) {
                Icon(PlayerIcons.Disc, "Go to the song playing", tint = Bridge.Muted, modifier = Modifier.size(20.dp))
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
            Icon(PlayerIcons.Trash, null, tint = Color.White, modifier = Modifier.align(if (dx.value > 0) Alignment.CenterStart else Alignment.CenterEnd)
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
            ) { Icon(PlayerIcons.Handle, "Move", tint = Bridge.Faint, modifier = Modifier.size(20.dp)) }
        }
    }
}

private val ROW_H = 64.dp

// ---------------------------------------------------------------------------- the sound controls

/**
 * Namida's sound controls, from the player's bottom row: how fast it plays, how high, and how
 * loud, each on a slider with its value, a mark where it was made, and Reset to put all three
 * back. Rises over the player; Back, a tap outside or Done lets it down again.
 */
@Composable
internal fun SoundSheet(state: PhonePlayer.State, tint: Color, onChange: (speed: Float, pitch: Float, volume: Float) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(420, easing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f))) }
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) { closing = true; scope.launch { shown.animateTo(0f, tween(220)); onClose() } }
    }
    androidx.activity.compose.BackHandler(onBack = close)
    val live = rememberUpdatedState(state)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = shown.value }
                .background(Color.Black.copy(alpha = 0.45f))
                .pointerInput(Unit) { detectTapGestures { close() } },
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .graphicsLayer { translationY = (1f - shown.value) * size.height; alpha = (shown.value * 2f).coerceAtMost(1f) }
                .padding(10.dp).navigationBarsPadding()
                .clip(RoundedCornerShape(28.dp))
                .background(Bridge.Surface)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(PlayerIcons.Sound, null, tint = Bridge.Text, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Sound", style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold), color = Bridge.Text, modifier = Modifier.weight(1f))
                val changed = state.speed != 1f || state.pitch != 1f || state.volume != 1f
                Text(
                    "Reset", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = if (changed) Bridge.Accent else Bridge.Muted.copy(alpha = 0.6f),
                    modifier = Modifier.clip(ButtonShape).clickable(enabled = changed) { onChange(1f, 1f, 1f) }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            SoundSlider("Speed", speedLabel(state.speed), state.speed, 0.5f, 2f, 0.05f, 1f, tint) { onChange(it, live.value.pitch, live.value.volume) }
            SoundSlider("Pitch", speedLabel(state.pitch), state.pitch, 0.5f, 2f, 0.05f, 1f, tint) { onChange(live.value.speed, it, live.value.volume) }
            SoundSlider("Volume", "${(state.volume * 100).roundToInt()}%", state.volume, 0f, 1f, 0.01f, 1f, tint) { onChange(live.value.speed, live.value.pitch, it) }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().height(48.dp).pressable(ButtonShape, onClick = close).background(Bridge.Accent),
                contentAlignment = Alignment.Center,
            ) { Text("Done", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = Bridge.OnAccent) }
        }
    }
}

/**
 * One of the sound controls: its name and value over a thin track, lit from the left to the
 * value, with a mark where the song was made ([mark]) and a round thumb; dragged or tapped,
 * it moves in [step]s.
 */
@Composable
private fun SoundSlider(label: String, value: String, v: Float, min: Float, max: Float, step: Float, mark: Float, tint: Color, onChange: (Float) -> Unit) {
    val set = rememberUpdatedState(onChange)
    val lit = lerp(tint, Bridge.Text, 0.25f)
    val track = Bridge.Chip
    val markColour = Bridge.Muted
    fun at(x: Float, w: Float): Float {
        val raw = min + (x / w).coerceIn(0f, 1f) * (max - min)
        return ((raw / step).roundToInt() * step).coerceIn(min, max)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = Bridge.Text, modifier = Modifier.weight(1f))
            Text(value, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = Bridge.Muted)
        }
        Box(
            Modifier.fillMaxWidth().height(36.dp)
                .pointerInput(min, max, step) {
                    detectTapGestures { set.value(at(it.x, size.width.toFloat())) }
                }
                .pointerInput(min, max, step) {
                    detectHorizontalDragGestures { ch, _ -> ch.consume(); set.value(at(ch.position.x, size.width.toFloat())) }
                }
                .drawBehind {
                    val r = 10.dp.toPx()
                    val l = r; val w = size.width - r * 2
                    val cy = size.height / 2
                    val h = 4.dp.toPx()
                    val f = ((v - min) / (max - min)).coerceIn(0f, 1f)
                    drawRoundRect(track, Offset(l, cy - h / 2), Size(w, h), CornerRadius(h))
                    drawRoundRect(lit, Offset(l, cy - h / 2), Size(w * f, h), CornerRadius(h))
                    val mx = l + w * ((mark - min) / (max - min))
                    drawCircle(markColour, 2.dp.toPx(), Offset(mx, cy + 9.dp.toPx()))
                    drawCircle(lit, r, Offset(l + w * f, cy))
                    drawCircle(Color.White.copy(alpha = 0.9f), r * 0.42f, Offset(l + w * f, cy))
                },
        )
    }
}
