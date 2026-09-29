package dev.periy.bridge.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Stable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import kotlin.math.max

/** Namida's menus come and go in 100 ms, on Flutter's easeInOutQuart. */
private const val MENU_MS = 100
private val EaseInOutQuart = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

/**
 * Where a menu goes: under what opened it (over it, when there is no room under), its middle
 * on it and 10 in from the screen's edges. It notes where the arrow goes, and on which side.
 */
private class MenuPlace(private val margin: Int, private val reach: Int) : PopupPositionProvider {
    /** The arrow's middle, from the menu's left. */
    var arrowX by mutableFloatStateOf(0f)
    /** 1: under what opened it, the arrow on top; -1: over it, the arrow below; 0: no room either way, no arrow. */
    var side by mutableIntStateOf(1)

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        val x = (anchorBounds.center.x - w / 2).coerceIn(margin, max(margin, windowSize.width - margin - w))
        val under = anchorBounds.bottom - reach
        val over = anchorBounds.top + reach - h
        val (y, s) = when {
            under + h <= windowSize.height - margin -> under to 1
            over >= margin -> over to -1
            else -> (windowSize.height - margin - h).coerceAtLeast(margin) to 0
        }
        side = s
        arrowX = (anchorBounds.center.x - x).toFloat()
        return IntOffset(x, y)
    }
}

/**
 * Namida's popup menu, for what it is placed beside (the composable around it): a card rounded
 * 12 with a hairline of the song's colour, shaded corner to corner from the page's colour laid
 * over the song's to a little more of the song's, with a small arrow in the song's colour pointing
 * at what opened it. It fades and grows out of the arrow's tip over 100 ms, and back. As wide as
 * its lines need, from 140 up to 280 (or half the screen). [content] gets the way to close it.
 */
@Composable
internal fun NamidaMenu(onDismiss: () -> Unit, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val nc = Nm.c
    val density = LocalDensity.current
    val place = remember(density) { with(density) { MenuPlace(10.dp.roundToPx(), 6.dp.roundToPx()) } }
    val shown = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(MENU_MS, easing = EaseInOutQuart)) }
    val close: () -> Unit = {
        if (!closing) { closing = true; scope.launch { shown.animateTo(0f, tween(MENU_MS, easing = EaseInOutQuart)); onDismiss() } }
    }
    val screenW = LocalContext.current.resources.configuration.screenWidthDp.dp
    val ground = nc.bg.copy(alpha = 0.5f).compositeOver(if (nc.dark) Color.Black else Color.White)
    val from = ground.copy(alpha = 0.9f).compositeOver(nc.main).copy(alpha = 1f)
    val to = ground.copy(alpha = 0.65f).compositeOver(nc.main).copy(alpha = 1f)
    val shape = RoundedCornerShape(12.dp)
    Popup(popupPositionProvider = place, onDismissRequest = close, properties = PopupProperties(focusable = true)) {
        CompositionLocalProvider(LocalNamida provides nc) {
            Box(
                Modifier
                    .graphicsLayer {
                        val v = shown.value
                        alpha = v
                        scaleX = v; scaleY = v
                        transformOrigin = TransformOrigin(
                            if (size.width > 0f) (place.arrowX / size.width).coerceIn(0f, 1f) else 0.5f,
                            if (place.side == -1) 1f else 0f,
                        )
                    }
                    .drawBehind {
                        // Namida's arrow: 16 by 8, its tip rounded, 4 in from the menu's edge.
                        if (place.side == 0) return@drawBehind
                        val aw = 16.dp.toPx()
                        val ah = 8.dp.toPx()
                        val left = place.arrowX.coerceIn(12.dp.toPx() + 15.dp.toPx() + aw / 2, size.width - 12.dp.toPx() - 15.dp.toPx() - aw / 2) - aw / 2
                        // Its base tucked 2 under the card's edge, its tip towards what opened it.
                        val down = place.side == -1
                        val base = if (down) size.height - 4.dp.toPx() - ah else 4.dp.toPx() + ah
                        fun p(fx: Float, fy: Float) = Offset(left + aw * fx, if (down) base + ah * fy else base - ah * fy)
                        val path = Path().apply {
                            val o = p(0f, 0f); moveTo(o.x, o.y)
                            val e = p(1f, 0f); lineTo(e.x, e.y)
                            val a = p(0.66f, 0.86f); lineTo(a.x, a.y)
                            val c1 = p(0.58f, 1.05f); val c2 = p(0.42f, 1.05f); val b = p(0.34f, 0.86f)
                            cubicTo(c1.x, c1.y, c2.x, c2.y, b.x, b.y)
                            close()
                        }
                        drawPath(path, nc.main)
                    }
                    .padding(vertical = 10.dp),
            ) {
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .widthIn(min = 140.dp, max = minOf(280.dp, screenW * 0.5f))
                        .clip(shape)
                        .background(Brush.linearGradient(listOf(from, to)))
                        .border(1.dp, nc.main.copy(alpha = 0.8f), shape)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 2.dp, vertical = 8.dp),
                ) { content(close) }
            }
        }
    }
}

/**
 * One of a Namida menu's lines: its icon, its name (and a line under it if it has one), and
 * anything at its end; lit faintly when [selected], half-faded when it can't be chosen.
 */
@Composable
internal fun NamidaMenuItem(
    icon: ImageVector, title: String, onClick: () -> Unit,
    subtitle: String? = null, selected: Boolean = false, enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val nc = Nm.c
    Box(Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(horizontal = 2.dp, vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) nc.secondary.copy(alpha = 0.1f) else Color.Transparent)
                .clickable(enabled = enabled, onClick = onClick)
                .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = nc.icon.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = Nm.medium)
                if (subtitle != null) Text(subtitle, style = Nm.small, maxLines = 1)
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
            Spacer(Modifier.width(2.dp))
        }
    }
}

/** Namida's note: a message that comes down from the top of the screen for two seconds. */
@Stable
class NamidaSnack {
    internal var text by mutableStateOf<String?>(null)
    internal var serial by mutableIntStateOf(0)

    fun show(message: String) { text = message; serial++ }
}

/** Flutter's fastLinearToSlowEaseIn, which Namida's notes drop in on. */
private val FastLinearToSlowEaseIn = CubicBezierEasing(0.18f, 1f, 0.04f, 1f)

/**
 * Namida's note, as it shows one: a card rounded 12 with a hairline grey edge, 24 in from the
 * sides and 8 under the status bar, its words semibold in the text colour at 70%, and a line
 * along its foot in the song's colour counting down the two seconds it stays. It drops in over
 * 600 ms, quick then settling, and goes back up the same way; a new one takes its place.
 */
@Composable
internal fun NamidaSnackHost(snack: NamidaSnack, statusTop: Dp) {
    val nc = Nm.c
    val slide = remember { Animatable(0f) }
    val left = remember { Animatable(1f) }
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(snack.serial) {
        val t = snack.text
        if (snack.serial == 0 || t == null) return@LaunchedEffect
        if (shown != null && slide.value > 0f) slide.animateTo(0f, tween(250, easing = EaseInOutQuart))
        shown = t
        left.snapTo(1f)
        slide.animateTo(1f, tween(600, easing = FastLinearToSlowEaseIn))
        left.animateTo(0f, tween(2000, easing = LinearEasing))
        slide.animateTo(0f, tween(600, easing = EaseInOutQuart))
        shown = null
    }
    val words = shown ?: return
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxWidth().padding(top = statusTop + 8.dp, start = 24.dp, end = 24.dp)
            .graphicsLayer { translationY = -(size.height + statusTop.toPx() + 8.dp.toPx()) * (1f - slide.value) }
            .clip(shape)
            .background(nc.bg.copy(alpha = 0.94f))
            .border(0.5.dp, Color.Gray.copy(alpha = 0.5f), shape)
            .drawWithContent {
                drawContent()
                val h = 2.dp.toPx()
                drawRect(nc.primary.copy(alpha = 0.35f), Offset(0f, size.height - h), Size(size.width * left.value, h))
            }
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Text(words, style = TextStyle(fontFamily = LexendDeca, fontWeight = FontWeight.SemiBold, fontSize = 14.nsp, lineHeight = 1.25.em, color = nc.onSurface.copy(alpha = 0.7f)))
    }
}
