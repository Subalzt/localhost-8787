package dev.periy.bridge.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The music screens' look, in Namida's terms: its colours worked out from the song's colour
 * the way Namida works out its theme (the colour at 40% lightness for what is lit, a dark
 * card grey tinted by it, the Material roles for the chips and the bar), and its three text
 * styles in Lexend Deca. Written for this app from Namida's published values; none of its
 * code is used.
 */
@Immutable
class NamidaColors(
    val dark: Boolean,
    /** The song's colour as it came from the cover. */
    val tint: Color,
    /** The song's colour as Namida lights things with it: its hue at 40% lightness, a little see-through. */
    val main: Color,
    val bg: Color,
    /** The app bar, over the status bar too: lighter than the page, so a line runs along its foot. */
    val appBar: Color,
    /** Tiles and cards (Namida's card theme), and its plainer card colour. */
    val card: Color,
    val cardColor: Color,
    val bar: Color,
    val indicator: Color,
    val primary: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val onSurface: Color,
    val icon: Color,
    val shadow: Color,
    val dialog: Color,
    val large: Color,
    val medium: Color,
    val small: Color,
)

/** Namida's colour while nothing is playing. */
val NamidaDefaultColour = Color(0xFF9C99C1)

fun namidaColors(tint: Color, dark: Boolean): NamidaColors {
    val (h, s0, _) = hsl(tint)
    val s = s0.coerceIn(0f, 1f)
    val base = Color.hsl(h, s, 0.4f)
    val main = base.copy(alpha = (if (dark) 200 else 120) / 255f)
    fun tone(sat: Float, l: Float) = Color.hsl(h, (s * sat).coerceIn(0f, 1f), l)
    val cardGround = if (dark) Color(0xFF232323) else Color.White
    val primary = if (dark) tone(1f, 0.78f) else tone(1f, 0.36f)
    val secondaryContainer = if (dark) tone(0.35f, 0.26f) else tone(0.45f, 0.88f)
    return NamidaColors(
        dark = dark,
        tint = tint,
        main = main,
        bg = if (dark) tone(0.18f, 0.065f) else base.copy(alpha = 60 / 255f).compositeOver(Color.White),
        appBar = if (dark) tone(0.18f, 0.1f) else base.copy(alpha = 25 / 255f).compositeOver(Color.White),
        card = base.copy(alpha = 36 / 255f).compositeOver(cardGround),
        cardColor = base.copy(alpha = 28 / 255f).compositeOver(cardGround),
        bar = if (dark) tone(0.18f, 0.115f) else tone(0.3f, 0.93f),
        indicator = primary.copy(alpha = 20 / 255f).compositeOver(secondaryContainer),
        primary = primary,
        secondary = if (dark) tone(0.35f, 0.76f) else tone(0.35f, 0.4f),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = if (dark) tone(0.4f, 0.88f) else tone(0.4f, 0.12f),
        onSurface = if (dark) tone(0.1f, 0.9f) else tone(0.1f, 0.1f),
        icon = if (dark) Color(233, 233, 233, 200) else Color(40, 40, 40, 200),
        shadow = if (dark) Color(10, 10, 10, 222) else Color(100, 100, 100, 180),
        dialog = if (dark) base.copy(alpha = 16 / 255f).compositeOver(Color(0xFF0C0C0C)) else base.copy(alpha = 48 / 255f).compositeOver(Color.White),
        large = if (dark) Color.White.copy(alpha = 210 / 255f) else Color.Black.copy(alpha = 160 / 255f),
        medium = if (dark) Color.White.copy(alpha = 180 / 255f) else Color.Black.copy(alpha = 150 / 255f),
        small = if (dark) Color.White.copy(alpha = 170 / 255f) else Color.Black.copy(alpha = 120 / 255f),
    )
}

val LocalNamida = staticCompositionLocalOf { namidaColors(NamidaDefaultColour, dark = true) }

/**
 * Namida draws all its text at nine tenths of its styles' sizes (its font scale setting, 0.9 out
 * of the box), so every size on the music screens goes through this.
 */
const val NamidaFontScale = 0.9f

/** A text size on the music screens: Namida's own, at its font scale. */
val Number.nsp: androidx.compose.ui.unit.TextUnit get() = (toFloat() * NamidaFontScale).sp

/**
 * A song's tile, as Namida lays one out on a phone (measured against it side by side): 75 high
 * with 4 under it, its cover 63.
 */
val NamidaTileHeight = 75.dp
val NamidaTileGap = 4.dp
val NamidaThumb = 63.dp

/** Shorthand for the music screens: Namida's colours and its three text styles. */
object Nm {
    val c: NamidaColors @Composable @ReadOnlyComposable get() = LocalNamida.current
    /** Namida's displayLarge: 17, bold. */
    val large: TextStyle @Composable @ReadOnlyComposable get() = TextStyle(fontFamily = LexendDeca, fontWeight = FontWeight.Bold, fontSize = 17.nsp, color = LocalNamida.current.large)
    /** Namida's displayMedium: 15, semibold. */
    val medium: TextStyle @Composable @ReadOnlyComposable get() = TextStyle(fontFamily = LexendDeca, fontWeight = FontWeight.SemiBold, fontSize = 15.nsp, color = LocalNamida.current.medium)
    /** Namida's displaySmall: 13, regular. */
    val small: TextStyle @Composable @ReadOnlyComposable get() = TextStyle(fontFamily = LexendDeca, fontWeight = FontWeight.Normal, fontSize = 13.nsp, color = LocalNamida.current.small)
}

/** Hue (0 to 360), saturation and lightness (0 to 1) of a colour. */
private fun hsl(c: Color): Triple<Float, Float, Float> {
    val r = c.red; val g = c.green; val b = c.blue
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
    val l = (mx + mn) / 2f
    val d = mx - mn
    if (d < 1e-6f) return Triple(0f, 0f, l)
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (mx) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return Triple((h + 360f) % 360f, s, l)
}

/**
 * Namida's button: a faint wash of the colour with a hairline edge in it, the icon and the
 * words in the icon colour, a capsule at least 36 high.
 */
@Composable
internal fun NamidaButton(text: String?, icon: ImageVector?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val nc = Nm.c
    val wash = nc.primary.copy(alpha = 0.3f)
    val fg = lerp(nc.icon, wash.copy(alpha = 1f), 0.15f).copy(alpha = 0.85f)
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.heightIn(min = 36.dp).clip(shape).background(wash.copy(alpha = 0.3f * 0.2f)).border(0.5.dp, wash.copy(alpha = 0.3f * 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (text != null) (if (icon != null) 18.dp else 24.dp) else 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        if (icon != null && text != null) Spacer(Modifier.width(8.dp))
        if (text != null) Text(text, style = Nm.medium.copy(fontSize = 15.5.nsp, color = fg), maxLines = 1, softWrap = false)
    }
}

/**
 * When a page's items came on screen, for Namida's entrance: the items there at the start slide
 * up 25 and fade in, one after another 50 ms apart, over 400 ms; those scrolled to later just show.
 */
@Stable
class Entrance {
    var startedAt by mutableLongStateOf(0L)
        private set

    fun restart() { startedAt = android.os.SystemClock.uptimeMillis() }
}

/** Namida's entrance for the item [order] places down from the top of a page that has just appeared. */
@Composable
fun Modifier.entrance(entrance: Entrance, order: Int, duration: Int = 400, step: Int = 50): Modifier {
    val start = entrance.startedAt
    val progress = remember(start) {
        val age = android.os.SystemClock.uptimeMillis() - start
        Animatable(if (start == 0L || age > 900 || order > 14) 1f else 0f)
    }
    LaunchedEffect(start) {
        if (progress.value >= 1f) return@LaunchedEffect
        val age = android.os.SystemClock.uptimeMillis() - start
        delay((order * step - age).coerceAtLeast(0))
        progress.animateTo(1f, tween(duration, easing = FastOutSlowInEasing))
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * 25.dp.toPx()
    }
}

/**
 * Whether a layer can be blurred (Android 12 and later). Namida's frosted chips fall back to a
 * nearly solid card colour without it, as Namida's own do with its blur switched off.
 */
internal var frostBlur: Boolean = runCatching { android.os.Build.VERSION.SDK_INT >= 31 }.getOrDefault(false)

/**
 * Namida's frosted chip (its NamidaBlurryContainer), set in a corner of a cover: the cover under
 * it seen blurred (sigma 5), the card colour thinly over that (60 in dark, 140 in light, of 255),
 * flush in the corner with only its inner corner rounded ([shape]), so it seems part of the
 * cover. [albumId] and [album] are the cover it sits on, which is [coverSize] square and fills
 * its parent from the top left; [alignment] is the corner, TopEnd or BottomEnd.
 */
@Composable
internal fun FrostedChip(
    albumId: String,
    album: String,
    coverSize: androidx.compose.ui.unit.Dp,
    alignment: Alignment,
    shape: androidx.compose.ui.graphics.Shape,
    padding: androidx.compose.foundation.layout.PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val nc = Nm.c
    val img = rememberCover(albumId)
    val veil = nc.cardColor.copy(alpha = (if (!frostBlur) 220 else if (nc.dark) 60 else 140) / 255f)
    val madeUp = remember(album) { Covers.madeUp(album) }
    androidx.compose.foundation.layout.Box(modifier.clip(shape)) {
        // The cover again, lined up with the one under the chip, and blurred.
        if (frostBlur) androidx.compose.foundation.layout.Box(
            Modifier.matchParentSize()
                .graphicsLayer {
                    val r = 8.dp.toPx()
                    renderEffect = androidx.compose.ui.graphics.BlurEffect(r, r, androidx.compose.ui.graphics.TileMode.Clamp)
                    clip = true
                }
                .drawBehind {
                    val side = coverSize.toPx()
                    val dx = if (alignment == Alignment.TopEnd || alignment == Alignment.BottomEnd) side - size.width else 0f
                    val dy = if (alignment == Alignment.BottomEnd || alignment == Alignment.BottomStart) side - size.height else 0f
                    if (img != null) {
                        // Cropped to a square from the middle, as the cover is drawn.
                        val s = minOf(img.width, img.height)
                        drawImage(
                            img,
                            srcOffset = androidx.compose.ui.unit.IntOffset((img.width - s) / 2, (img.height - s) / 2),
                            srcSize = androidx.compose.ui.unit.IntSize(s, s),
                            dstOffset = androidx.compose.ui.unit.IntOffset(-dx.toInt(), -dy.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(side.toInt(), side.toInt()),
                        )
                    } else drawRect(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(lerp(madeUp, Color.White, 0.18f), madeUp, lerp(madeUp, Color.Black, 0.45f)),
                            start = androidx.compose.ui.geometry.Offset(-dx, -dy),
                            end = androidx.compose.ui.geometry.Offset(side - dx, side - dy),
                        ),
                    )
                },
        )
        androidx.compose.foundation.layout.Box(Modifier.matchParentSize().background(veil))
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) { content() }
    }
}
