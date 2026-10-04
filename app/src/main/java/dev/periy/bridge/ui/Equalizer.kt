package dev.periy.bridge.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.periy.bridge.music.EqMath
import dev.periy.bridge.music.EqStore
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private const val EQ_MS = 300

/**
 * The equalizer, presented as the Configure dialog is: its title on a band with the switch, the
 * curve (exactly as it plays, through every point) with a point for each band to drag
 * up or down (a double tap puts one back to 0), the famous curves to choose from, and how far the
 * level comes down so a boost never clips. The same setting as every page's.
 */
@Composable
internal fun EqualizerSheet(store: EqStore, fade: MutableFloatState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(EQ_MS, easing = LinearOutSlowInEasing)) }
    val close: () -> Unit = {
        if (!closing) { closing = true; scope.launch { shown.animateTo(0f, tween(EQ_MS, easing = FastOutLinearInEasing)); onClose() } }
    }
    val swipe = rememberBackSwipe(enabled = !closing) { swiped -> if (swiped) { closing = true; onClose() } else close() }
    LaunchedEffect(Unit) { snapshotFlow { shown.value * (1f - swipe.progress.value) }.collect { fade.floatValue = it } }
    DisposableEffect(Unit) { onDispose { fade.floatValue = 0f } }

    val s by store.state.collectAsState()
    val nc = Nm.c
    val peak = remember(s.gains) { EqMath.Curve(s.gains).peak() }

    Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value * (1f - swipe.progress.value) }) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).pointerInput(Unit) { detectTapGestures { close() } })
        Column(
            Modifier.align(Alignment.Center)
                .padding(horizontal = 22.dp, vertical = 32.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .graphicsLayer { val k = 0.96f * (1f - 0.06f * swipe.progress.value); scaleX = k; scaleY = k }
                .clip(RoundedCornerShape(24.dp))
                .background(nc.dialog)
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                Modifier.fillMaxWidth().background(nc.primary.copy(alpha = 0.02f).compositeOver(nc.card)).padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Equalizer", style = Nm.medium, textAlign = TextAlign.Center)
                Toggle(s.on, Modifier.align(Alignment.CenterEnd), color = nc.primary) { store.setOn(it) }
            }

            EqCurve(s.gains, s.on, onBand = { i, db -> store.setBand(i, db) })

            // The famous curves, along a row that scrolls.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val names = EqMath.PRESETS.map { it.id to it.name } + (if (s.preset == "custom") listOf("custom" to "Custom") else emptyList())
                for ((id, name) in names) {
                    val on = s.on && s.preset == id
                    Text(
                        name, style = Nm.small.copy(fontSize = 13.nsp), maxLines = 1,
                        color = if (on) nc.onSecondaryContainer else nc.small,
                        modifier = Modifier.clip(RoundedCornerShape(9.dp))
                            .background(nc.secondaryContainer.copy(alpha = if (on) 0.75f else 0.22f))
                            .clickable { EqMath.PRESETS.firstOrNull { it.id == id }?.let { store.choose(it) } ?: store.setOn(true) }
                            .padding(horizontal = 11.dp, vertical = 7.dp),
                    )
                }
            }

            Text(
                when {
                    !s.on -> "Off: the music plays as it was made."
                    peak > 0.05 -> String.format(Locale.US, "The level comes down %.1f dB, so a boost never clips.", peak)
                    else -> "Cuts only, so the level stays as it is."
                },
                style = Nm.small.copy(fontSize = 11.5.nsp),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NmIconButton(Iconsax.Reset, "Flat", 24.dp, 8.dp, 0.dp, nc.secondary) { store.choose(EqMath.PRESETS.first()) }
                Spacer(Modifier.width(6.dp))
                NamidaButton("Done", null, onClick = close)
            }
        }
    }
}

/** The curve and its ten points, on a grid of ±12 dB, 20 Hz to 20 kHz spaced as hearing is. */
@Composable
private fun EqCurve(gains: List<Float>, on: Boolean, onBand: (Int, Float) -> Unit) {
    val nc = Nm.c
    val view = LocalView.current
    val g = rememberUpdatedState(gains)
    val set = rememberUpdatedState(onBand)
    var held by remember { mutableIntStateOf(-1) }
    val dim by animateFloatAsState(if (on) 1f else 0.4f, tween(250), label = "eq-on")
    // The curve eases to a new preset instead of jumping.
    val shown = remember { gains.map { Animatable(it) } }
    gains.forEachIndexed { i, v -> LaunchedEffect(v) { if (held == i) shown[i].snapTo(v) else shown[i].animateTo(v, tween(260)) } }
    val measurer = rememberTextMeasurer()
    val labelStyle = Nm.small.copy(fontSize = 9.5.nsp, color = nc.small.copy(alpha = 0.8f))
    val tipStyle = Nm.medium.copy(fontSize = 11.nsp, color = nc.dialog)
    val line = nc.primary
    val max = EqMath.MAX_DB

    Canvas(
        Modifier.fillMaxWidth().height(210.dp).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
            .semantics { contentDescription = "Equalizer curve: drag a band's point up or down" }
            .pointerInput(Unit) {
                val left = 30.dp.toPx(); val bottomPad = 18.dp.toPx(); val pad = 10.dp.toPx()
                fun xOf(f: Float) = left + (size.width - left - pad) * (ln(f / 20f) / ln(1000f))
                fun dbAt(y: Float): Float {
                    val top = pad; val h = size.height - bottomPad - pad
                    val v = (1f - (y - top) / h) * 2f * max - max
                    return ((v * 2f).roundToInt() / 2f).coerceIn(-max, max)
                }
                var lastTap = 0L; var lastBand = -1
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val band = EqMath.BANDS.indices.minBy { abs(xOf(EqMath.BANDS[it]) - down.position.x) }
                    // A second tap on the same point soon after: back to 0.
                    if (band == lastBand && down.uptimeMillis - lastTap < 300) { set.value(band, 0f); lastBand = -1; return@awaitEachGesture }
                    lastTap = down.uptimeMillis; lastBand = band
                    held = band
                    var moved = false
                    var lastDb = g.value[band]
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        if (!moved && abs(c.position.y - down.position.y) < 6f) continue
                        moved = true
                        c.consume()
                        val db = dbAt(c.position.y)
                        if (db != lastDb) {
                            if (db.roundToInt().toFloat() == db) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            lastDb = db
                            set.value(band, db)
                        }
                        if (c.positionChange() == Offset.Zero) continue
                    }
                    held = -1
                }
            },
    ) {
        val left = 30.dp.toPx(); val bottomPad = 18.dp.toPx(); val pad = 10.dp.toPx()
        val w = size.width - left - pad; val top = pad; val h = size.height - bottomPad - pad
        fun xOf(f: Double) = left + w * (ln(f / 20.0) / ln(1000.0)).toFloat()
        fun yOf(db: Double) = top + h * (1f - ((db + max) / (2 * max)).toFloat().coerceIn(0f, 1f))
        val grid = nc.onSurface.copy(alpha = 0.08f)

        // dB lines, 0 a little brighter, with their figures down the left.
        for (db in listOf(12, 6, 0, -6, -12)) {
            val y = yOf(db.toDouble())
            drawLine(if (db == 0) nc.onSurface.copy(alpha = 0.18f) else grid, Offset(left, y), Offset(left + w, y), 1.dp.toPx())
            val t = measurer.measure(if (db > 0) "+$db" else "$db", labelStyle)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), y - t.size.height / 2f))
        }
        // A line up from each band, with its frequency under it.
        val names = listOf("32", "64", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
        EqMath.BANDS.forEachIndexed { i, f ->
            val x = xOf(f.toDouble())
            drawLine(grid, Offset(x, top), Offset(x, top + h), 1.dp.toPx())
            val t = measurer.measure(names[i], labelStyle)
            drawText(t, topLeft = Offset(x - t.size.width / 2f, top + h + 4.dp.toPx()))
        }

        // The curve, as it plays, filled to the 0 line.
        val now = shown.map { it.value }
        val curve = EqMath.Curve(now)
        val path = Path(); val fill = Path()
        val steps = 180
        val zero = yOf(0.0)
        for (k in 0..steps) {
            val f = 20.0 * 1000.0.pow(k / steps.toDouble())
            val x = xOf(f); val y = yOf(curve.db(f))
            if (k == 0) { path.moveTo(x, y); fill.moveTo(x, zero); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(left + w, zero); fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.28f * dim), line.copy(alpha = 0.04f * dim), line.copy(alpha = 0.28f * dim)), top, top + h))
        drawPath(path, line.copy(alpha = dim), style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round))

        // A point for each band, at its own setting; the one held a little larger, with its figure over it.
        EqMath.BANDS.forEachIndexed { i, f ->
            val c = Offset(xOf(f.toDouble()), yOf(now[i].toDouble()))
            val r = (if (held == i) 8.dp else 6.dp).toPx()
            drawCircle(nc.dialog, r + 2.dp.toPx(), c)
            drawCircle(line.copy(alpha = dim), r, c)
            if (held == i) {
                val v = now[i]
                val t = measurer.measure(String.format(Locale.US, "%+.1f dB", v), tipStyle)
                val pw = t.size.width + 12.dp.toPx(); val ph = t.size.height + 6.dp.toPx()
                val px = (c.x - pw / 2).coerceIn(left, left + w - pw)
                val py = (c.y - r - ph - 6.dp.toPx()).let { if (it < 0f) c.y + r + 6.dp.toPx() else it }
                drawRoundRect(line, Offset(px, py), Size(pw, ph), CornerRadius(ph / 2))
                drawText(t, topLeft = Offset(px + 6.dp.toPx(), py + 3.dp.toPx()))
            }
        }
    }
}
