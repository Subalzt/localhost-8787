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
    val bars = remember(env, count) { Loudness.bars(env, count) }
    val scrub = remember { mutableStateOf<Long?>(null) }
    // Namida's colours: every bar in the text colour at 40/255; those played in the song's colour
    // laid over it (180, then 140), at 110/255.
    val nc = Nm.c
    val track = nc.onSurface.copy(alpha = 40 / 255f)
    val playedA = nc.main.copy(alpha = 180 / 255f).compositeOver(nc.onSurface).copy(alpha = 110 / 255f)
    val playedB = nc.main.copy(alpha = 140 / 255f).compositeOver(nc.onSurface).copy(alpha = 110 / 255f)
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
                fun drawBars(color: Color? = null, brush: Brush? = null) {
                    for (i in 0 until n) {
                        val h = minH + (size.height - minH) * bars[i].pow(1.4f) * grow
                        val tl = Offset(gap + i * step, cy - h / 2)
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
    onConfigure: () -> Unit = {},
    onAdd: () -> Unit = {},
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
    val lit = Color.White
    val favourites = androidx.compose.ui.platform.LocalContext.current.container.favourites
    val hearts by favourites.ids.collectAsState()
    // Namida's jump button: a disc while the song playing is in view, else an arrow towards it.
    val jump by remember {
        derivedStateOf {
            val info = list.layoutInfo.visibleItemsInfo
            val i = live.value.index
            when {
                info.isEmpty() -> Iconsax.Cd
                i < info.first().index -> Iconsax.ArrowUp
                i > info.last().index -> Iconsax.ArrowDown
                else -> Iconsax.Cd
            }
        }
    }

    Box(modifier) {
        // Namida's queue: a sheet with corners of 32, its header washed in the song's colour.
        Column(Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).background(nc.bg)) {
            QueueHeader(state, onConfigure = onConfigure, onClear = { player.clear() }, onClose = { motion.expand() })
            LazyColumn(
                Modifier.weight(1f).nestedScroll(pull),
                state = list,
                contentPadding = PaddingValues(bottom = navBottom + 48.dp + 12.dp),
            ) {
                itemsIndexed(state.queue, key = { i, _ -> keys.getOrElse(i) { "x$i" } }) { i, t ->
                    val k = keys.getOrElse(i) { "x$i" }
                    val dragging = drag.key == k
                    QueueRow(
                        t, k, current = i == state.index, sounding = state.playing, tint = tint, lit = lit, drag = drag,
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

        // Namida's row along the foot: clear some of it, add songs, go to the song playing, shuffle.
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(nc.bg)
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp + navBottom).height(48.dp - 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var clearing by remember { mutableStateOf(false) }
            Box {
                NamidaButton(null, Iconsax.Broom) { clearing = true }
                if (clearing) androidx.compose.ui.window.Popup(
                    alignment = Alignment.BottomStart,
                    offset = IntOffset(0, with(LocalDensity.current) { -44.dp.roundToPx() }),
                    onDismissRequest = { clearing = false },
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalNamida provides nc) {
                        Column(Modifier.width(220.dp).clip(RoundedCornerShape(16.dp))
                            .background(nc.cardColor.copy(alpha = 180 / 255f).compositeOver(if (nc.dark) Color.Black else Color.White)).padding(vertical = 6.dp)) {
                            QueueMenuRow("Remove the songs before") { clearing = false; player.removeBefore() }
                            QueueMenuRow("Remove the songs after") { clearing = false; player.removeAfter() }
                            QueueMenuRow("Remove them all") { clearing = false; player.clear() }
                        }
                    }
                }
            }
            NamidaButton(null, Iconsax.Add) { onAdd() }
            NamidaButton(null, jump) { scope.launch { list.animateScrollToItem((state.index - 2).coerceAtLeast(0)) } }
            NamidaButton("Shuffle", Iconsax.Shuffle) { player.shuffleUpcoming() }
        }
    }
}

/**
 * Namida's queue header: "Queue", where in it and the time left after the song playing, washed
 * in the song's colour; its round buttons (configure, more) and the arrow back down to the player.
 */
@Composable
private fun QueueHeader(state: PhonePlayer.State, onConfigure: () -> Unit, onClear: () -> Unit, onClose: () -> Unit) {
    val nc = Nm.c
    val left = state.queue.drop(state.index.coerceAtLeast(0)).sumOf { it.durationMs }
    var more by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.linearGradient(listOf(
                nc.bg.copy(alpha = 0.9f).compositeOver(nc.tint).copy(alpha = 0.5f).compositeOver(nc.bg),
                nc.bg.copy(alpha = 0.65f).compositeOver(nc.tint).copy(alpha = 0.5f).compositeOver(nc.bg),
            )))
            .padding(vertical = 12.dp).heightIn(min = 42.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text("Queue", style = Nm.medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val small = Nm.small.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
                Text("${state.index + 1}/${state.queue.size} \u2022", style = small)
                Spacer(Modifier.width(4.dp))
                Icon(Iconsax.Timer, null, tint = nc.small, modifier = Modifier.size(8.dp))
                Spacer(Modifier.width(2.dp))
                Text(fmtMinutes(left), style = small)
            }
        }
        QueueAction(Iconsax.Configure, "Configure") { onConfigure() }
        Spacer(Modifier.width(6.dp))
        Box {
            QueueAction(Iconsax.More, "More") { more = true }
            if (more) androidx.compose.ui.window.Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(LocalDensity.current) { 44.dp.roundToPx() }),
                onDismissRequest = { more = false },
            ) {
                androidx.compose.runtime.CompositionLocalProvider(LocalNamida provides nc) {
                    Column(Modifier.width(220.dp).clip(RoundedCornerShape(16.dp))
                        .background(nc.cardColor.copy(alpha = 180 / 255f).compositeOver(if (nc.dark) Color.Black else Color.White)).padding(vertical = 6.dp)) {
                        QueueMenuRow("Stop and clear the queue") { more = false; onClear() }
                    }
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Iconsax.Down, "Back to the player", tint = nc.icon, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(8.dp))
    }
}

/** One of the queue header's round buttons, as Namida's: a tonal disc with the icon. */
@Composable
private fun QueueAction(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val nc = Nm.c
    Box(
        Modifier.size(40.dp).pressable(CircleShape, scaleTo = 0.9f, onClick = onClick)
            .background(nc.secondaryContainer.copy(alpha = if (nc.dark) 0.55f else 0.7f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = nc.onSecondaryContainer, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun QueueMenuRow(label: String, onClick: () -> Unit) {
    Text(label, style = Nm.medium, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp))
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
        val shrink by animateFloatAsState(if (current) 0.96f else 1f, tween(400), label = "thumb")
        Row(
            Modifier.fillMaxWidth().height(NamidaTileHeight)
                .offset { IntOffset(dx.value.roundToInt(), 0) }
                .background(
                    // Held stronger in light mode, so the white type on it reads.
                    if (current) Brush.linearGradient(0.6f to if (nc.dark) nc.main else nc.main.copy(alpha = 220 / 255f), 1f to nc.tint.copy(alpha = 0.4f))
                    else androidx.compose.ui.graphics.SolidColor(nc.card.copy(alpha = 0.9f)),
                )
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
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // As Namida's queue has it: its track tile, with a handle on the right.
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(NamidaThumb).graphicsLayer { scaleX = shrink; scaleY = shrink }) {
                Cover(t.albumId, t.album, Modifier.fillMaxSize(), radius = 8.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = Nm.medium.copy(color = if (current) lit.copy(alpha = 170 / 255f) else nc.medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist, style = Nm.small.copy(fontWeight = FontWeight.Medium, color = if (current) lit.copy(alpha = 140 / 255f) else nc.small),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(t.album, t.year.takeIf { it > 0 }?.toString()).joinToString(" \u2022 "),
                    style = Nm.small.copy(color = if (current) lit.copy(alpha = 130 / 255f) else nc.small), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(fmtTime(t.durationMs), style = Nm.small.copy(fontWeight = FontWeight.Medium, color = if (current) lit.copy(alpha = 170 / 255f) else nc.small))
                Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onHeart), contentAlignment = Alignment.Center) {
                    Icon(if (hearted) Iconsax.HeartOn else Iconsax.Heart, if (hearted) "Take the heart off" else "Give it a heart",
                        tint = if (current) lit.copy(alpha = 140 / 255f) else nc.icon, modifier = Modifier.size(20.dp))
                }
            }
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
            ) { Icon(Iconsax.Handle, "Move", tint = if (current) lit.copy(alpha = 160 / 255f) else nc.icon, modifier = Modifier.size(22.dp)) }
            // Namida's "more", upright: take it off the queue.
            var menu by remember { mutableStateOf(false) }
            Box {
                Box(Modifier.clip(RoundedCornerShape(4.dp)).clickable { menu = true }.padding(6.dp), contentAlignment = Alignment.Center) {
                    Icon(Iconsax.More, "More for ${t.title}", tint = if (current) lit.copy(alpha = 160 / 255f) else nc.icon,
                        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 90f })
                }
                if (menu) androidx.compose.ui.window.Popup(
                    alignment = Alignment.TopEnd,
                    offset = IntOffset(0, with(LocalDensity.current) { 36.dp.roundToPx() }),
                    onDismissRequest = { menu = false },
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalNamida provides nc) {
                        Column(Modifier.width(210.dp).clip(RoundedCornerShape(16.dp))
                            .background(nc.cardColor.copy(alpha = 180 / 255f).compositeOver(if (nc.dark) Color.Black else Color.White)).padding(vertical = 6.dp)) {
                            QueueMenuRow("Take it off the queue") { menu = false; onRemove() }
                        }
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

/** Namida's track tile, with the gap under it. */
private val ROW_H = NamidaTileHeight + NamidaTileGap

// ---------------------------------------------------------------------------- the sound controls

/**
 * Namida's sound controls, from the player's chip or its sound button: a dialog titled
 * Configure, with pitch, speed and volume each as an icon, its name, its percentage and a
 * slider; reset in its corner and Done. Rises over the player; Back, a tap outside or Done
 * lets it go.
 */
@Composable
internal fun SoundSheet(state: PhonePlayer.State, tint: Color, onChange: (speed: Float, pitch: Float, volume: Float) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(320, easing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f))) }
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) { closing = true; scope.launch { shown.animateTo(0f, tween(180)); onClose() } }
    }
    androidx.activity.compose.BackHandler(onBack = close)
    val live = rememberUpdatedState(state)
    val nc = Nm.c
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = shown.value }
                .background(Color.Black.copy(alpha = 0.45f))
                .pointerInput(Unit) { detectTapGestures { close() } },
        )
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 38.dp).fillMaxWidth()
                .graphicsLayer { val k = 0.92f + 0.08f * shown.value; scaleX = k; scaleY = k; alpha = shown.value }
                .clip(RoundedCornerShape(24.dp))
                .background(nc.dialog)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(top = 18.dp, bottom = 10.dp),
        ) {
            Text("Configure", style = Nm.large.copy(fontSize = 20.nsp), modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))
            SoundSlider(Iconsax.Pitch, "Pitch", state.pitch, 0.5f, 2f) { onChange(live.value.speed, it, live.value.volume) }
            SoundSlider(Iconsax.Speed, "Speed", state.speed, 0.5f, 2f) { onChange(it, live.value.pitch, live.value.volume) }
            SoundSlider(if (state.volume > 0f) Iconsax.Volume else Iconsax.Mute, "Volume", state.volume, 0f, 1f) {
                onChange(live.value.speed, live.value.pitch, it)
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { onChange(1f, 1f, 1f) }, contentAlignment = Alignment.Center) {
                    Icon(Iconsax.Reset, "Put them back", tint = nc.icon, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(20.dp)).background(nc.primary.copy(alpha = 0.3f * 0.8f))
                        .clickable(onClick = close).padding(horizontal = 24.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Done", style = Nm.medium.copy(fontSize = 15.5.nsp, color = Color.White.copy(alpha = 0.85f))) }
            }
        }
    }
}

/**
 * One of the sound controls, as Namida's: its icon, its name in the large style and its
 * percentage beside it, over a slider of the Material kind Namida uses: a thick track, lit from
 * the left up to a thin upright thumb, with a gap either side of it. Dragged or tapped, it moves
 * in hundredths.
 */
@Composable
private fun SoundSlider(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, v: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    val set = rememberUpdatedState(onChange)
    val nc = Nm.c
    val active = nc.primary.copy(alpha = 0.85f)
    val inactive = nc.secondary.copy(alpha = 0.2f)
    fun at(x: Float, w: Float, pad: Float): Float {
        val raw = min + ((x - pad) / (w - pad * 2)).coerceIn(0f, 1f) * (max - min)
        return ((raw * 100f).roundToInt() / 100f).coerceIn(min, max)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = nc.icon, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, style = Nm.large)
            Spacer(Modifier.width(8.dp))
            Text("${(v * 100).roundToInt()}%", style = Nm.medium.copy(fontFeatureSettings = "tnum"))
        }
        Box(
            Modifier.fillMaxWidth().height(40.dp)
                .pointerInput(min, max) {
                    val pad = 10.dp.toPx()
                    detectTapGestures { set.value(at(it.x, size.width.toFloat(), pad)) }
                }
                .pointerInput(min, max) {
                    val pad = 10.dp.toPx()
                    detectHorizontalDragGestures { ch, _ -> ch.consume(); set.value(at(ch.position.x, size.width.toFloat(), pad)) }
                }
                .drawBehind {
                    val pad = 10.dp.toPx()
                    val l = pad; val w = size.width - pad * 2
                    val cy = size.height / 2
                    val th = 6.dp.toPx()
                    val gap = 4.dp.toPx()
                    val thumbW = 5.dp.toPx(); val thumbH = 24.dp.toPx()
                    val f = ((v - min) / (max - min)).coerceIn(0f, 1f)
                    val x = l + w * f
                    val r = CornerRadius(th / 2)
                    if (x - thumbW / 2 - gap > l) drawRoundRect(active, Offset(l, cy - th / 2), Size(x - thumbW / 2 - gap - l, th), r)
                    if (l + w > x + thumbW / 2 + gap) drawRoundRect(inactive, Offset(x + thumbW / 2 + gap, cy - th / 2), Size(l + w - (x + thumbW / 2 + gap), th), r)
                    drawRoundRect(active, Offset(x - thumbW / 2, cy - thumbH / 2), Size(thumbW, thumbH), CornerRadius(thumbW / 2))
                },
        )
    }
}
