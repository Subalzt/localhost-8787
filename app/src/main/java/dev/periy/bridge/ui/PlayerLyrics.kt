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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import dev.periy.bridge.music.Loudness
import kotlin.math.PI
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
    /** The song's loudness (music/Loudness.kt), which moves a held note on the full page. */
    env: ByteArray? = null,
) {
    val nc = Nm.c
    val hot = remember(tint) { vividOf(tint) }
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
            list.centre(current.coerceAtLeast(0), if (full) 0.3f else 0.5f)
        }
        // Over the cover, the line on a wash of the song's colour; on the full page, a soft card of the text colour.
        val selectedBg = if (full) Color.Transparent else tint.copy(alpha = 140 / 255f).compositeOver(nc.bg).copy(alpha = 0.5f)
        LazyColumn(
            state = list,
            userScrollEnabled = live,
            // The line being sung a third of the way down, as a word-synced lyrics app holds it, with room below for the rest.
            contentPadding = if (full) PaddingValues(top = maxHeight * 0.3f, bottom = maxHeight * 0.7f) else PaddingValues(vertical = (half - 24.dp).coerceAtLeast(0.dp)),
            horizontalAlignment = if (full) Alignment.Start else Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(items, key = { i, _ -> i }) { i, item ->
                val d = if (current < 0) i + 1 else abs(i - current)
                when (item) {
                    is GapItem -> Dots(item, on = d == 0, state = live0, tick = tick, full = full)
                    is LineItem -> Line(
                        item.line, lyrics.kind == ShownLyrics.Kind.WORD, distance = d, selectedBg = selectedBg, state = live0, tick = tick,
                        full = full, hot = hot, env = env,
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
private val FullLine = TextStyle(fontFamily = MusicType, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp)

/** Glides so item [index] sits at [at] of the way down: its middle at the middle, or (above the middle) its top there. */
private suspend fun LazyListState.centre(index: Int, at: Float = 0.5f) {
    fun delta(): Float? {
        val info = layoutInfo
        val it = info.visibleItemsInfo.firstOrNull { v -> v.index == index } ?: return null
        // Item offsets and the viewport's ends are measured the same way, padding and all.
        val mark = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) * at
        return if (at == 0.5f) it.offset + it.size / 2f - mark else it.offset - mark
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
    hot: Color,
    env: ByteArray?,
    onClick: (() -> Unit)?,
) {
    val nc = Nm.c
    val on = distance == 0
    val k by animateFloatAsState(
        if (full) when (distance) { 0 -> 1f; 1 -> 0.42f; 2 -> 0.3f; else -> 0.2f } else when (distance) { 0 -> 1f; 1 -> 0.5f; 2 -> 0.4f; else -> 0.25f },
        tween(300), label = "line",
    )
    val bg by animateColorAsState(if (on) selectedBg else selectedBg.copy(alpha = 0f), tween(300), label = "card")
    val grow by animateFloatAsState(if (on || !full) 1f else 0.94f, tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "grow")
    // The lines around the one being sung softer the further off (on the full page).
    val soft by animateDpAsState(if (!full) 0.dp else when (distance) { 0 -> 0.dp; 1 -> 1.2.dp; 2 -> 2.dp; else -> 2.8.dp }, tween(420), label = "soft")
    val base = nc.medium
    val style = if (full) FullLine.copy(color = base.copy(alpha = base.alpha * k))
    else Nm.medium.copy(color = base.copy(alpha = base.alpha * k), textAlign = TextAlign.Center)
    Box(
        (if (full) Modifier.fillMaxWidth().graphicsLayer {
            // The line being sung a little larger than the rest, growing from its left edge.
            scaleX = grow; scaleY = grow
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
        }.then(if (soft > 0.05.dp) Modifier.blur(soft, BlurredEdgeTreatment.Unbounded) else Modifier).padding(horizontal = 14.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
        else Modifier.padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(8.dp)))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = if (full) 10.dp else 8.dp),
        contentAlignment = if (full) Alignment.CenterStart else Alignment.Center,
    ) {
        if (on && full && words && line.words.isNotEmpty()) SungWords(line, style.copy(color = base), base, base.copy(alpha = base.alpha * 0.42f), hot, env, state, tick)
        else if (on && full) Text(line.text, style = style.copy(shadow = glowOf(base)))
        else if (on && words && line.words.isNotEmpty()) {
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

/** The soft light round sung words on the full page (it is always on the dark of the blurred cover). */
@Composable
private fun glowOf(base: Color): Shadow = Shadow(base.copy(alpha = 0.5f), Offset.Zero, with(LocalDensity.current) { 10.dp.toPx() })

/**
 * The song's colour made vivid enough to light words on the dark: its hue, at least half saturated and
 * at full brightness. A grey cover lights them in the app's yellow instead.
 */
internal fun vividOf(tint: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(tint.toArgb(), hsv)
    if (hsv[1] < 0.12f) return Color(0xFFFFD60A)
    hsv[1] = hsv[1].coerceAtLeast(0.5f)
    hsv[2] = 1f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** One letter of a word being sung: its glyph, where it sits in the word, when it lights and when its syllable ends. */
private class SungLetter(val glyph: TextLayoutResult, val x: Float, val at: Long, val end: Long, val n: Int)

/**
 * The line being sung, letter by letter, as this app sings it rather than as a music app wipes it:
 * the letters of each syllable catch one after another in a quick run as it starts (so a syllable
 * reads as a burst, not a slow fill), each jumping on a spring (up, a small dip under the line, then
 * settling) and flashing white before cooling into the song's own colour while it is held; a held
 * note keeps moving, a wave running through its letters as high as the music is loud; once sung,
 * the letters settle to a soft white with the song's colour in it. Words to come wait a little low
 * and dim, and crouch just before they are sung. Drawn per letter on one canvas per word, so the
 * word keeps its kerning, and only redrawn (never laid out again) as the song plays.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SungWords(line: LyricLine, style: TextStyle, base: Color, dim: Color, hot: Color, env: ByteArray?, state: State<PhonePlayer.State>, tick: State<Long>) {
    val density = LocalDensity.current
    val em = with(density) { style.fontSize.toPx() }
    val space = with(density) { (em * 0.27f).toDp() }
    val measurer = rememberTextMeasurer()
    // A word's syllables (pieces with no space between them) go together, so a word never breaks across lines.
    val words = remember(line) {
        val out = mutableListOf<MutableList<dev.periy.bridge.server.LyricWord>>()
        var open = false
        line.words.forEach { w ->
            if (!open || (w.w.isNotEmpty() && w.w.first().isWhitespace())) out += mutableListOf<dev.periy.bridge.server.LyricWord>()
            out.last() += w
            open = !(w.w.isNotEmpty() && w.w.last().isWhitespace())
        }
        out.filter { p -> p.any { it.w.isNotBlank() } }
    }
    // Sung: white with a little of the song's colour; the flash as a letter catches: white.
    val sung = lerp(base, hot, 0.22f)
    FlowRow {
        words.forEachIndexed { wi, pieces ->
            if (wi > 0) Spacer(Modifier.width(space))
            val text = pieces.joinToString("") { it.w.trim() }
            val whole = remember(text, style) { measurer.measure(text, style, softWrap = false, maxLines = 1) }
            val letters = remember(whole, pieces) {
                val out = mutableListOf<SungLetter>()
                var i = 0
                pieces.forEach { w ->
                    val piece = w.w.trim()
                    // The run across a syllable's letters: quick, but never longer than the syllable.
                    val step = ((w.e - w.t) / piece.length.coerceAtLeast(1)).coerceAtMost(RUN_MS)
                    var j = 0
                    while (j < piece.length) {
                        val k = if (Character.isHighSurrogate(piece[j]) && j + 1 < piece.length) 2 else 1
                        val g = measurer.measure(piece.substring(j, j + k), style, softWrap = false, maxLines = 1)
                        out += SungLetter(g, whole.getHorizontalPosition(i + j, true), w.t + step * j, w.e, out.size)
                        j += k
                    }
                    i += piece.length
                }
                out
            }
            Spacer(
                Modifier.size(with(density) { whole.size.width.toDp() }, with(density) { whole.size.height.toDp() })
                    .drawBehind {
                        tick.value
                        val now = state.value.positionNow()
                        val loud = Loudness.at(env, now)
                        letters.forEach { l -> drawLetter(l, now, loud, em, base, dim, sung, hot) }
                    },
            )
        }
    }
}

/** How quickly a syllable's letters catch, one after the next. */
private const val RUN_MS = 70L

private fun DrawScope.drawLetter(l: SungLetter, now: Long, loud: Float, em: Float, base: Color, dim: Color, sung: Color, hot: Color) {
    val a = (now - l.at).toFloat()
    var y: Float
    var s = 1f
    val c: Color
    var glow = 0f
    if (a < 0f) {
        // To come: a little low, crouching further just before it is sung.
        val crouch = ((a + 160f) / 160f).coerceIn(0f, 1f)
        y = em * (0.03f + 0.04f * crouch)
        s = 1f - 0.04f * crouch
        c = lerp(dim, base.copy(alpha = dim.alpha + 0.12f), crouch)
    } else {
        // The jump: a spring kicked upwards, overshooting under the line once, then still.
        val damp = kotlin.math.exp(-a / 150f)
        val swing = kotlin.math.sin(a / 40f)
        y = -em * 0.24f * damp * swing
        s = 1f + 0.2f * damp * swing.coerceAtLeast(0f)
        val held = now < l.end
        if (held) {
            // Held: white as it catches, cooling into the song's colour; a wave runs through it with the music.
            c = lerp(Color.White, hot, (a / 260f).coerceIn(0f, 1f))
            val sway = ((a - 220f) / 300f).coerceIn(0f, 1f) * (0.25f + 0.75f * loud)
            y += -em * 0.07f * sway * kotlin.math.sin(now / 1000f * 2f * PI.toFloat() * 2.1f - l.n * 0.8f)
            s += 0.06f * sway
            glow = 1f
        } else {
            // Sung: it cools from the song's colour to the soft white of the sung, its glow going.
            val after = ((now - l.end) / 420f).coerceIn(0f, 1f)
            c = lerp(if (a < 260f) lerp(Color.White, hot, a / 260f) else hot, sung, after)
            glow = 1f - after
        }
    }
    val g = l.glyph
    val w = g.size.width.toFloat()
    val pivot = Offset(l.x + w / 2f, g.size.height * 0.78f)
    withTransform({ scale(s, s, pivot) }) {
        drawText(
            g, color = c, topLeft = Offset(l.x, y),
            shadow = if (glow > 0.02f) Shadow(hot.copy(alpha = 0.75f * glow), Offset.Zero, em * 0.5f) else null,
        )
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
