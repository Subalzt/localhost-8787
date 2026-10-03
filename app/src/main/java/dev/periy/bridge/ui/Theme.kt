package dev.periy.bridge.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Localhost 8787's look, after the Apple TV app: an edge-to-edge hero, shelves of cards, capsule
 * buttons; with Apple Music's large titles over each screen and its square tiles. Light or dark
 * follows the phone unless chosen. The colour is shared with every page: "auto" is the look's
 * own black and white, or one of [ACCENTS] everywhere.
 */

/** The colours on offer, as the phone's own system colours, in the order they are shown. */
val ACCENTS: List<Pair<String, Color>> = listOf(
    // Apple Music's pink-red.
    "red" to Color(0xFFFA2D48),
    "orange" to Color(0xFFFF9500),
    "yellow" to Color(0xFFFFCC00),
    "green" to Color(0xFF34C759),
    "mint" to Color(0xFF00C7BE),
    "blue" to Color(0xFF0A84FF),
    "purple" to Color(0xFFAF52DE),
)

@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    /** A well inside a surface: text fields, tracks, secondary buttons. */
    val surface2: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val outline: Color,
    val accent: Color,
    val onAccent: Color,
    val blue: Color = Color(0xFF0A84FF),
    val green: Color = Color(0xFF30D158),
    val orange: Color = Color(0xFFFF9F0A),
    val purple: Color = Color(0xFFBF5AF2),
    val red: Color = Color(0xFFFF453A),
    val pink: Color = Color(0xFFFF375F),
    val teal: Color = Color(0xFF40C8E0),
    val indigo: Color = Color(0xFF5E5CE6),
)

private val iosLight = Palette(
    dark = false, bg = Color.White, surface = Color.White, surface2 = Color.White, text = Color.Black,
    muted = Color(0xFF8A8A8E), faint = Color(0xFFC7C7CC), outline = Color(0x333C3C43),
    accent = Color.Black, onAccent = Color.White,
    blue = Color(0xFF007AFF), green = Color(0xFF34C759), orange = Color(0xFFFF9500), purple = Color(0xFFAF52DE),
    red = Color(0xFFFF3B30), pink = Color(0xFFFF2D55), teal = Color(0xFF30B0C7), indigo = Color(0xFF5856D6),
)

/** Theatre, as Apple TV: near-black with lifted cards, white as the button colour; in light, Apple's greys. */
val TheatreDark = Palette(
    dark = true, bg = Color(0xFF000000), surface = Color(0xFF17171A), surface2 = Color(0xFF2B2B2F),
    text = Color.White, muted = Color(0xFFA1A1A6), faint = Color(0xFF4A4A4F), outline = Color(0x2EFFFFFF),
    accent = Color.White, onAccent = Color.Black,
)
val TheatreLight = iosLight.copy(
    bg = Color(0xFFF5F5F7), surface = Color.White, surface2 = Color(0xFFE8E8ED), text = Color(0xFF1D1D1F),
    muted = Color(0xFF6E6E73), outline = Color(0x1F000000), accent = Color(0xFF1D1D1F), onAccent = Color.White,
)

val LocalPalette = staticCompositionLocalOf { TheatreDark }

fun paletteFor(dark: Boolean, accent: String = "auto"): Palette {
    val p = if (dark) TheatreDark else TheatreLight
    val c = ACCENTS.firstOrNull { it.first == accent }?.second ?: return p
    // Yellow is the one colour white text cannot sit on.
    return p.copy(accent = c, onAccent = if (accent == "yellow") Color(0xFF1C1C1E) else Color.White)
}

/** Picks the palette from the shared settings: "system", "light" or "dark", and the colour. */
@Composable
fun BlazeTheme(theme: String, look: dev.periy.bridge.Look = dev.periy.bridge.Look(), content: @Composable () -> Unit) {
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val p = paletteFor(dark, look.accent)
    CompositionLocalProvider(
        LocalPalette provides p,
        LocalTextStyle provides TextStyle(color = p.text),
        content = content,
    )
}

/** Shorthand for the current palette's roles, readable at any call site in a composable. */
object Bridge {
    val Dark: Boolean @Composable @ReadOnlyComposable get() = LocalPalette.current.dark
    val Bg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.bg
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
    val Chip: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface2
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onAccent
    val Text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
    val Faint: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.faint
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.outline
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.red
    val Good: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.green
    val Blue: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.blue
    val Orange: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.orange
    val Purple: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.purple
    val Pink: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.pink
    val Teal: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.teal
    val Indigo: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.indigo

    /**
     * The colour for something that is on, in a list or a tile: the colour chosen in Settings, or
     * green where it is black and white (Automatic), since a black dot reads as off.
     */
    val Lit: Color @Composable @ReadOnlyComposable get() {
        val a = LocalPalette.current.accent
        val mono = a == Color.White || a == Color(0xFF1D1D1F)
        return if (!mono) a else LocalPalette.current.green
    }
}

val CardShape = RoundedCornerShape(22.dp)
val ButtonShape = RoundedCornerShape(50)

/** The corner cards take. */
val cardRadius: Dp = 16.dp

// ---------------------------------------------------------------------------- type

/** Apple's large title: heavy, tight. */
val LargeTitleStyle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
val DisplayStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, fontFeatureSettings = "tnum")
val HeadlineStyle = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
val TitleStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
val LabelStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
val BodyStyle = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
val CaptionStyle = TextStyle(fontSize = 13.sp, lineHeight = 17.sp)
val KickerStyle = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
val MonoStyle = TextStyle(fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
val NumberStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, fontFeatureSettings = "tnum")

/** Text laid on artwork: a soft shadow under it lifts it off the picture, as a title on a poster. */
val OnArt = Shadow(Color(0x66000000), Offset(0f, 3f), 14f)

// ---------------------------------------------------------------------------- surfaces

/** A card: a surface with the style's corners and, in the dark, a faint edge to set it off. */
@Composable
fun Modifier.card(shape: Shape = RoundedCornerShape(cardRadius), color: Color = Bridge.Surface): Modifier {
    val base = this.clip(shape).background(color)
    return if (Bridge.Dark) base.border(0.5.dp, Color.White.copy(alpha = 0.07f), shape) else base
}

/** A card inset from the screen edges, as every card on a screen is. */
@Composable
fun Modifier.panel(): Modifier = this.padding(horizontal = 16.dp).card()

/** A floating piece: the monitor pill and its sheet, the mini player. Solid, with a soft shadow. */
@Composable
fun Modifier.floating(shape: Shape = ButtonShape): Modifier =
    this.shadow(if (Bridge.Dark) 18.dp else 14.dp,
        shape, ambientColor = Color(0x40000000), spotColor = Color(0x59000000))
        .clip(shape)
        .background(if (Bridge.Dark) Color(0xFF1E1E21) else Color.White)
        .then(if (Bridge.Dark) Modifier.border(0.6.dp, Color(0x1FFFFFFF), shape) else Modifier)

/**
 * Depth for artwork and the heroes: a shadow cast in the thing's own colour, so it seems to
 * glow a little onto what is under it, the way lit album art does.
 */
fun Modifier.depth(color: Color, shape: Shape, elevation: Dp = 18.dp): Modifier =
    this.shadow(elevation, shape, clip = false, ambientColor = color.copy(alpha = 0.45f), spotColor = color.copy(alpha = 0.75f))

/** Tappable with the platform ripple, clipped to [shape]. */
@Composable
fun Modifier.tap(shape: Shape? = null, onClick: () -> Unit): Modifier =
    (if (shape != null) this.clip(shape) else this).clickable(onClick = onClick)

/**
 * Tappable, and it gives under the finger: a quick spring down to [scaleTo] while pressed and
 * back when let go, as Apple's cards and buttons do.
 */
@Composable
fun Modifier.pressable(shape: Shape? = null, scaleTo: Float = 0.96f, enabled: Boolean = true, ripple: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scaleTo else 1f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "press")
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .then(if (shape != null) Modifier.clip(shape) else Modifier)
        .clickable(interactionSource = source, indication = if (ripple) ripple() else null, enabled = enabled, onClick = onClick)
}

/** What the screen sits on: the plain background. */
@Composable
fun StyleBackground(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Bridge.Bg)) {
    }
}

// ---------------------------------------------------------------------------- parts

/**
 * A section title, as Apple's apps set them: bold and large, flush with the content, with a
 * chevron when the title itself goes somewhere, and anything else on the right.
 */
@Composable
fun SectionBar(
    title: String,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 34.dp + 20.dp).padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = CaptionStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Muted)
            if (onOpen != null) {
                Spacer(Modifier.width(4.dp))
                Icon(BlazeIcons.Chevron, null, tint = Bridge.Muted, modifier = Modifier.size(20.dp))
            }
        }
        trailing()
    }
}

/** An icon that says what a row or a tile is about: the glyph in the text colour on a quiet grey square. */
@Composable
fun AppIcon(icon: ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = 38.dp) {
    val shape = RoundedCornerShape(size * 0.26f)
    Box(
        modifier.size(size).clip(shape).background(Bridge.Chip),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Bridge.Text, modifier = Modifier.size(size * 0.56f)) }
}

/**
 * Artwork: a square of colour with depth, standing in for an album or show cover. A soft
 * light top-left, the colour deepening to the bottom-right, a fine sheen along the top edge,
 * and the glyph large in a corner (or the middle, with [center]).
 */
@Composable
fun Artwork(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    radius: Dp = 10.dp,
    glyph: Dp = 44.dp,
    center: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    // A soft wash of the colour with the glyph in it, as the page's file tiles are.
    val ink = if (Bridge.Dark) lerp(color, Color.White, 0.25f) else lerp(color, Color.Black, 0.28f)
    Box(
        modifier.clip(RoundedCornerShape(radius)).background(color.copy(alpha = if (Bridge.Dark) 0.22f else 0.15f)),
    ) {
        if (glyph > 0.dp) Icon(
            icon, null, tint = ink,
            modifier = if (center) Modifier.align(Alignment.Center).size(glyph)
            else Modifier.align(Alignment.BottomStart).padding(14.dp).size(glyph),
        )
        content()
    }
}

/** The main button: a capsule that gives when pressed. */
@Composable
fun BridgeButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Bridge.Accent,
    textColor: Color = if (color == Bridge.Accent) Bridge.OnAccent else Color.White,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val shape = ButtonShape
    Row(
        modifier
            .heightIn(min = 48.dp)
            .pressable(shape, enabled = enabled, onClick = onClick)
            .background(if (enabled) color else Bridge.Chip)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) textColor else Bridge.Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = if (enabled) textColor else Bridge.Muted)
    }
}

/** Everything that is not the main action: a quiet capsule. */
@Composable
fun SoftButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Bridge.Text,
    onClick: () -> Unit,
) {
    val shape = ButtonShape
    Row(
        modifier
            .heightIn(min = 38.dp)
            .pressable(shape, onClick = onClick)
            .background(Bridge.Chip)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = tint)
    }
}

/** A round icon button on the well colour. */
@Composable
fun IconChip(icon: ImageVector, description: String, tint: Color = Bridge.Text, bg: Color = Bridge.Chip, size: Dp = 38.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).pressable(CircleShape, scaleTo = 0.9f, onClick = onClick).background(bg),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

/** Body copy inside a card. */
@Composable
fun RowNote(text: String, color: Color = Bridge.Muted, modifier: Modifier = Modifier) {
    Text(text, style = BodyStyle, color = color, modifier = modifier.padding(top = 6.dp))
}

/** A text field: a well inside the card. */
@Composable
fun BridgeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    minHeight: Dp = 96.dp,
    mono: Boolean = false,
) {
    val text = Bridge.Text
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(Bridge.Chip)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = BodyStyle.copy(fontSize = 15.sp), color = Bridge.Faint)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = mono,
            textStyle = if (mono) MonoStyle.copy(color = text) else BodyStyle.copy(color = text, fontSize = 15.sp),
            cursorBrush = SolidColor(Bridge.Blue),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A thin progress bar, as a song's scrubber. */
@Composable
fun BlockProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = Bridge.Text, height: Dp = 5.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(ButtonShape).background(Bridge.Chip)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(ButtonShape).background(color))
    }
}

/** A segmented control: a well with the chosen segment raised on it. */
@Composable
fun SegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val sel = selectedIndex.coerceIn(-1, options.lastIndex)
    BoxWithConstraints(
        modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(Bridge.Chip).padding(2.dp),
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * sel.coerceAtLeast(0), spring(dampingRatio = 0.75f, stiffness = 500f), label = "seg")
        if (sel >= 0) {
            Box(
                Modifier.offset(x = x).width(w).fillMaxHeight()
                    .shadow(if (Bridge.Dark) 0.dp else 3.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (Bridge.Dark) Color(0xFF636366) else Color.White)
            )
        }
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = TextStyle(fontSize = 13.sp, fontWeight = if (i == sel) FontWeight.SemiBold else FontWeight.Medium),
                        color = Bridge.Text,
                    )
                }
            }
        }
    }
}

/** An iOS switch: green when on (or the accent passed in); the knob springs across. */
@Composable
fun Toggle(on: Boolean, modifier: Modifier = Modifier, color: Color = Bridge.Good, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (on) color else Bridge.Faint.copy(alpha = 0.5f), tween(180), label = "track")
    val x by animateDpAsState(if (on) 20.dp else 0.dp, spring(dampingRatio = 0.62f, stiffness = 600f), label = "knob")
    Box(
        modifier
            .width(51.dp)
            .height(31.dp)
            .clip(ButtonShape)
            .background(track)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = ripple(bounded = false, radius = 26.dp)) { onChange(!on) }
            .padding(2.dp),
    ) {
        Box(Modifier.offset(x = x).size(27.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White))
    }
}

/** Rows that belong together, on one card. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().panel(), content = content)
}

/** One setting: optional icon, title, a line of detail, and a control on the right. */
@Composable
fun SettingRow(
    title: String,
    detail: String? = null,
    first: Boolean = false,
    titleColor: Color = Bridge.Text,
    icon: ImageVector? = null,
    iconColor: Color = Bridge.Blue,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val iconSize = 30.dp
    if (!first) Box(
        Modifier.fillMaxWidth().padding(start = if (icon != null) 16.dp + iconSize + 14.dp else 16.dp).height(0.5.dp).background(Bridge.Outline)
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AppIcon(icon, iconColor, size = iconSize)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.2).sp),
                color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) Text(detail, style = CaptionStyle, color = Bridge.Muted)
        }
        trailing()
    }
}

/** Four bars for signal strength, filled up to [level] (0-4). */
@Composable
fun SignalBars(level: Int, modifier: Modifier = Modifier) {
    val on = Bridge.Text
    val off = Bridge.Faint
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until 4) {
            Box(Modifier.width(3.dp).height((4 + i * 3).dp).clip(RoundedCornerShape(1.dp)).background(if (i < level) on else off))
        }
    }
}

/**
 * The tabs: words in a floating capsule along the bottom, in reach of a thumb, the one you are
 * on a filled pill that glides with a swipe.
 */
@Composable
fun PillTabs(
    items: List<Pair<String, ImageVector>>,
    selected: Int,
    modifier: Modifier = Modifier,
    /** Nearly opaque over content; over the dark hero, see-through as it was. */
    solid: Boolean = true,
    /** Where the pages are, in tabs (1.5 is halfway from the second to the third): the pill follows a swipe. */
    position: Float = selected.toFloat(),
    /** The room either side of each word. */
    itemPadding: Dp = 16.dp,
    onSelect: (Int) -> Unit,
) {
    // Each tab's left edge and width, so the pill can glide between them.
    val spans = remember(items.size) { androidx.compose.runtime.mutableStateListOf<Pair<Float, Float>>().apply { repeat(items.size) { add(0f to 0f) } } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(
        // Nearly solid, so the tabs read over whatever scrolls under them: the hero, a photo, white cards.
        modifier
            .then(if (solid) Modifier.shadow(10.dp, ButtonShape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f)) else Modifier)
            .clip(ButtonShape)
            .background(if (!solid) Color(0x2EFFFFFF) else if (Bridge.Dark) Color(0xEB2A2A2E) else Color(0xF2FFFFFF))
            .padding(3.dp),
    ) {
        val at = position.coerceIn(0f, items.lastIndex.toFloat())
        val lo = at.toInt(); val hi = (lo + 1).coerceAtMost(items.lastIndex); val f = at - lo
        val x = spans[lo].first + (spans[hi].first - spans[lo].first) * f
        val w = spans[lo].second + (spans[hi].second - spans[lo].second) * f
        if (w > 0f) Box(Modifier.matchParentSize()) {
            Box(
                Modifier.offset { androidx.compose.ui.unit.IntOffset(x.toInt(), 0) }
                    .width(with(density) { w.toDp() }).fillMaxHeight()
                    .clip(ButtonShape).background(Bridge.Accent)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEachIndexed { i, (label, _) ->
                // Lit by how much of the pill is over it, so the words change colour as it passes.
                val over = (1f - kotlin.math.abs(at - i)).coerceIn(0f, 1f)
                Text(
                    label,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = androidx.compose.ui.graphics.lerp(Bridge.Text, Bridge.OnAccent, over),
                    modifier = Modifier
                        .onGloballyPositioned { c -> spans[i] = c.positionInParent().x to c.size.width.toFloat() }
                        .clip(ButtonShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) }
                        .padding(horizontal = itemPadding, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * The tabs as a phone's own tab bar: an icon over each word across the whole width, the one you
 * are on in the text colour and the rest quiet; the colour follows a swipe between pages.
 */
@Composable
fun BarTabs(
    items: List<Pair<String, ImageVector>>,
    selected: Int,
    modifier: Modifier = Modifier,
    /** Where the pages are, in tabs (1.5 is halfway from the second to the third). */
    position: Float = selected.toFloat(),
    onSelect: (Int) -> Unit,
) {
    val at = position.coerceIn(0f, items.lastIndex.toFloat())
    Row(modifier.fillMaxWidth().height(56.dp)) {
        items.forEachIndexed { i, (label, icon) ->
            val over = (1f - kotlin.math.abs(at - i)).coerceIn(0f, 1f)
            val c = androidx.compose.ui.graphics.lerp(Bridge.Faint, Bridge.Text, over)
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, null, tint = c, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(3.dp))
                Text(label, style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium), color = c, maxLines = 1)
            }
        }
    }
}

/**
 * A row with a thumbnail, as a song in a list: a small square of artwork, the title, a line
 * under it, and anything on the right. Used for files, transfers and devices.
 */
@Composable
fun MediaRow(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    color: Color,
    first: Boolean,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
    onClick: (() -> Unit)? = null,
    below: @Composable ColumnScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val thumb = 48.dp
    val tint = if (dim) Color(0xFF8E8E93) else color
    if (!first) Box(Modifier.fillMaxWidth().padding(start = 16.dp + thumb + 12.dp).height(0.5.dp).background(Bridge.Outline))
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(icon, tint, Modifier.size(thumb), radius = 10.dp, glyph = 22.dp, center = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontSize = 16.sp, letterSpacing = (-0.2).sp), color = Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, style = CaptionStyle, color = Bridge.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            below()
        }
        trailing()
    }
}

/** A row as its own card: used for one-off notes and steps. */
@Composable
fun BridgeRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconColor: Color = Bridge.Blue,
    meta: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .card()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                AppIcon(icon, iconColor, size = 36.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(title, style = TitleStyle.copy(fontSize = 15.5.sp), color = Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (meta != null) {
                Spacer(Modifier.width(8.dp))
                Text(meta, style = LabelStyle, maxLines = 1, color = Bridge.Muted)
            }
            trailing()
        }
        content()
    }
}
