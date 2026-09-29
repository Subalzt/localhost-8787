package dev.periy.bridge.ui

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.periy.bridge.music.PhonePlayer
import dev.periy.bridge.server.LyricLine
import dev.periy.bridge.server.ShownLyrics
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The song's lyrics over its cover, as Namida's player shows them when its lyrics button is on:
 * the cover blurred under a veil of the song's colour, and the lines over it in Namida's medium
 * style, centred. The line being sung sits in the middle on a card of the song's colour; the
 * lines around it fade the further they are from it. Word by word, for songs timed that way,
 * each word lights as it is sung; in a long gap three dots fill across it. A tap on a line goes
 * there; the list can be scrolled by hand, and comes back to the song three seconds later.
 * Untimed lyrics scroll as a page.
 */

/** Lines turn a moment early, as the singer's breath comes before the word (the page's lead too). */
private const val LEAD_MS = 180L
/** A pause in the singing this long gets its dots. */
private const val GAP_MS = 4500L
/** Scrolled by hand, the lyrics wait this long before they follow the song again. */
private const val HOLD_MS = 3000L
private val Glide = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private sealed interface LyricItem { val t: Long }
private class LineItem(val line: LyricLine) : LyricItem { override val t get() = line.t }
private class GapItem(val from: Long, val to: Long) : LyricItem { override val t get() = from }

private fun itemsOf(l: ShownLyrics): List<LyricItem> {
    val out = mutableListOf<LyricItem>()
    l.lines.forEachIndexed { i, line ->
        val from = if (i == 0) 0L else l.lines[i - 1].e
        if (line.t - from > GAP_MS && (i > 0 || line.t > GAP_MS)) out += GapItem(from, line.t)
        out += LineItem(line)
    }
    return out
}

/** The last item to have started at [at]; -1 before the first. */
private fun itemAt(items: List<LyricItem>, at: Long): Int {
    var lo = 0; var hi = items.size - 1; var idx = -1
    while (lo <= hi) { val mid = (lo + hi) ushr 1; if (items[mid].t <= at) { idx = mid; lo = mid + 1 } else hi = mid - 1 }
    return idx
}

/**
 * The lyrics, laid out at the full player's cover size (the motion scales them with it), or
 * with [full], as Namida's full-page lyrics: large, from the left, the line being sung on a soft
 * card. [live]: whether they take taps and scrolling (only while the player is open on them).
 * Over the cover a tap opens the full page ([onOpen]); on the full page a tap on a line goes to it.
 */
@Composable
internal fun LyricsOverCover(
    lyrics: ShownLyrics,
    state: PhonePlayer.State,
    tick: State<Long>,
    tint: Color,
    live: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
    full: Boolean = false,
    onOpen: (() -> Unit)? = null,
) {
    val nc = Nm.c
    // Namida fades the lyrics out at the top and bottom edges.
    val fade = Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.14f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }
    BoxWithConstraints(modifier.then(fade)) {
        val half = maxHeight / 2
        if (lyrics.kind == ShownLyrics.Kind.PLAIN) {
            Box(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = live)
                    .then(if (live && onOpen != null) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen) else Modifier)
                    .padding(horizontal = if (full) 18.dp else 16.dp, vertical = 40.dp),
            ) {
                if (full) Text(lyrics.plain, style = FullLine.copy(color = nc.medium, lineHeight = 22.nsp * 1.6f), modifier = Modifier.fillMaxWidth())
                else Text(lyrics.plain, style = Nm.medium.copy(lineHeight = 15.nsp * 1.8f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            return@BoxWithConstraints
        }
        val items = remember(lyrics) { itemsOf(lyrics) }
        val live0 = rememberUpdatedState(state)
        val current by remember(items) { derivedStateOf { tick.value; itemAt(items, live0.value.positionNow() + LEAD_MS) } }
        // First shown at the line being sung (the padding above puts it near the middle); after
        // that, gliding to each as it comes.
        val list = rememberLazyListState(initialFirstVisibleItemIndex = current.coerceAtLeast(0))
        var userAt by remember { mutableLongStateOf(0L) }
        val dragged by list.interactionSource.collectIsDraggedAsState()
        LaunchedEffect(dragged) { if (dragged) userAt = SystemClock.uptimeMillis() }
        LaunchedEffect(items, current, dragged) {
            if (dragged) return@LaunchedEffect
            if (userAt > 0L) {
                val wait = HOLD_MS - (SystemClock.uptimeMillis() - userAt)
                if (wait > 0) delay(wait)
            }
            // Not before the list has been laid out once.
            if (list.layoutInfo.visibleItemsInfo.isEmpty()) androidx.compose.runtime.withFrameNanos { }
            list.centre(current.coerceAtLeast(0))
        }
        // Over the cover, the line on a wash of the song's colour; on the full page, a soft card of the text colour.
        val selectedBg = if (full) nc.onSurface.copy(alpha = 0.09f) else tint.copy(alpha = 140 / 255f).compositeOver(nc.bg).copy(alpha = 0.5f)
        LazyColumn(
            state = list,
            userScrollEnabled = live,
            contentPadding = PaddingValues(vertical = (half - 24.dp).coerceAtLeast(0.dp)),
            horizontalAlignment = if (full) Alignment.Start else Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(items, key = { i, _ -> i }) { i, item ->
                val d = if (current < 0) i + 1 else abs(i - current)
                when (item) {
                    is GapItem -> Dots(item, on = d == 0, state = live0, tick = tick, full = full)
                    is LineItem -> Line(
                        item.line, lyrics.kind == ShownLyrics.Kind.WORD, distance = d, selectedBg = selectedBg, state = live0, tick = tick,
                        full = full,
                        onClick = when {
                            !live -> null
                            onOpen != null -> onOpen
                            else -> ({ userAt = 0L; onSeek(item.line.t) })
                        },
                    )
                }
            }
        }
    }
}

/** A line on Namida's full-page lyrics: large and bold, from the left. */
private val FullLine = TextStyle(fontFamily = LexendDeca, fontWeight = FontWeight.Bold, fontSize = 22.nsp, lineHeight = 28.nsp)

/** Glides so item [index] sits in the middle (from further off, it goes there first). */
private suspend fun LazyListState.centre(index: Int) {
    fun delta(): Float? {
        val info = layoutInfo
        val it = info.visibleItemsInfo.firstOrNull { v -> v.index == index } ?: return null
        // Item offsets and the viewport's ends are measured the same way, padding and all.
        return it.offset + it.size / 2f - (info.viewportStartOffset + info.viewportEndOffset) / 2f
    }
    val dd = delta() ?: run { scrollToItem(index); androidx.compose.runtime.withFrameNanos { }; delta() } ?: return
    if (abs(dd) >= 1f) animateScrollBy(dd, tween(620, easing = Glide))
}

/** One line: faded by how far it is from the one being sung, which is full and on its card; word by word, its words light as they are sung. */
@Composable
private fun Line(
    line: LyricLine,
    words: Boolean,
    distance: Int,
    selectedBg: Color,
    state: State<PhonePlayer.State>,
    tick: State<Long>,
    full: Boolean,
    onClick: (() -> Unit)?,
) {
    val nc = Nm.c
    val on = distance == 0
    val k by animateFloatAsState(
        if (full) when (distance) { 0 -> 1f; 1 -> 0.72f; 2 -> 0.5f; else -> 0.3f } else when (distance) { 0 -> 1f; 1 -> 0.5f; 2 -> 0.4f; else -> 0.25f },
        tween(300), label = "line",
    )
    val bg by animateColorAsState(if (on) selectedBg else selectedBg.copy(alpha = 0f), tween(300), label = "card")
    val base = nc.medium
    val style = if (full) FullLine.copy(color = base.copy(alpha = base.alpha * k))
    else Nm.medium.copy(color = base.copy(alpha = base.alpha * k), textAlign = TextAlign.Center)
    Box(
        (if (full) Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
        else Modifier.padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(8.dp)))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = if (full) 10.dp else 8.dp),
        contentAlignment = if (full) Alignment.CenterStart else Alignment.Center,
    ) {
        if (on && words && line.words.isNotEmpty()) {
            // Sung words full, those to come dim; the one being sung brightens across its time.
            val pos by remember(line) { derivedStateOf { tick.value; state.value.positionNow() } }
            val dim = base.copy(alpha = base.alpha * 0.45f)
            val text = buildAnnotatedString {
                line.words.forEach { w ->
                    val p = ((pos - w.t).toFloat() / (w.e - w.t).coerceAtLeast(1)).coerceIn(0f, 1f)
                    withStyle(SpanStyle(color = lerp(dim, base, p))) { append(w.w) }
                }
            }
            Text(text, style = style.copy(color = base))
        } else Text(line.text, style = style)
    }
}

/** A long pause in the singing: three dots, lit one by one across it while it lasts. */
@Composable
private fun Dots(gap: GapItem, on: Boolean, state: State<PhonePlayer.State>, tick: State<Long>, full: Boolean = false) {
    val nc = Nm.c
    val shown by animateFloatAsState(if (on) 1f else 0f, tween(400), label = "gap")
    val f by remember(gap) { derivedStateOf { tick.value; ((state.value.positionNow() - gap.from).toFloat() / (gap.to - gap.from).coerceAtLeast(1)).coerceIn(0f, 1f) } }
    Row(
        Modifier.then(if (full) Modifier.padding(start = 18.dp) else Modifier).height(28.dp * shown).graphicsLayer { alpha = shown },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val lit = if (on && f > (i + 1) / 4f) 1f else 0.3f
            val a by animateFloatAsState(lit, tween(300), label = "dot$i")
            Box(Modifier.size(6.dp).graphicsLayer { alpha = a; scaleX = 0.8f + 0.2f * a; scaleY = 0.8f + 0.2f * a }.clip(CircleShape).background(nc.medium))
        }
    }
}

/**
 * Namida's lyrics button: its document icon while lyrics are on (with a ? when the song has
 * none, an x when they could not be looked up), its slashed card while off.
 */
@Composable
internal fun LyricsIcon(on: Boolean, lyrics: ShownLyrics?, tint: Color) {
    val icon: ImageVector = if (on) Iconsax.Lyrics else Iconsax.LyricsOff
    val mark = when {
        !on || lyrics == null -> ""
        lyrics.offline -> "x"
        lyrics.kind == ShownLyrics.Kind.NONE -> "?"
        else -> ""
    }
    Box(contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        if (mark.isNotEmpty()) Text(
            mark, style = Nm.small.copy(fontSize = 9.nsp, fontWeight = FontWeight.Bold, color = tint, lineHeight = 9.nsp),
            modifier = Modifier.padding(top = 3.dp, start = 1.dp),
        )
    }
}
