package dev.periy.bridge.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Material 3's colour roles for the Material look: from the wallpaper (Android 12 and later,
 * DynamicColour.kt) or worked out from one colour here. Material's tones are laid on the colour's
 * hue as lightness (primary at 40 in light and 80 in dark, containers at 90 and 30, the neutral
 * surfaces barely tinted), the same way the page works them out, so the two match.
 */
@Immutable
class M3Roles(
    val dark: Boolean,
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    val secondary: Color, val onSecondary: Color, val secondaryContainer: Color, val onSecondaryContainer: Color,
    val tertiary: Color, val onTertiary: Color, val tertiaryContainer: Color, val onTertiaryContainer: Color,
    val error: Color, val onError: Color, val errorContainer: Color, val onErrorContainer: Color,
    val surface: Color, val onSurface: Color, val surfaceVariant: Color, val onSurfaceVariant: Color,
    val outline: Color, val outlineVariant: Color,
    /** The surface containers, lowest to highest: cards, bars and wells sit on these. */
    val containerLowest: Color, val containerLow: Color, val container: Color, val containerHigh: Color, val containerHighest: Color,
    val inverseSurface: Color, val inverseOnSurface: Color, val inversePrimary: Color,
)

/** Material's own baseline colour, for when there is no other. */
val M3Baseline = Color(0xFF6750A4)

fun m3RolesFrom(seed: Color, dark: Boolean): M3Roles {
    val (h0, s0) = hueSat(seed)
    // A grey has no hue of its own: Material's baseline one stands in.
    val h = if (s0 < 0.04f) 256f else h0
    // Enough colour for the primary tones even from a greyish seed; neutrals keep a trace of it.
    val s = s0.coerceIn(0f, 1f)
    fun p(t: Int) = Color.hsl(h, max(s, 0.36f).coerceAtMost(0.9f), t / 100f)
    // The secondary a good deal quieter than the primary, but still of its colour (the pills, tonal buttons).
    fun sec(t: Int) = Color.hsl(h, (max(s, 0.36f) * 0.4f).coerceAtMost(0.35f), t / 100f)
    fun ter(t: Int) = Color.hsl((h + 60f) % 360f, (max(s, 0.3f) * 0.55f).coerceAtMost(0.5f), t / 100f)
    fun n(t: Int) = Color.hsl(h, (s * 0.06f).coerceAtMost(0.06f), t / 100f)
    fun nv(t: Int) = Color.hsl(h, (s * 0.12f).coerceAtMost(0.12f), t / 100f)
    return if (!dark) M3Roles(
        dark = false,
        primary = p(40), onPrimary = p(100), primaryContainer = p(90), onPrimaryContainer = p(10),
        secondary = sec(40), onSecondary = sec(100), secondaryContainer = sec(90), onSecondaryContainer = sec(10),
        tertiary = ter(40), onTertiary = ter(100), tertiaryContainer = ter(90), onTertiaryContainer = ter(10),
        error = Color(0xFFB3261E), onError = Color.White, errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
        surface = n(98), onSurface = n(10), surfaceVariant = nv(90), onSurfaceVariant = nv(30),
        outline = nv(50), outlineVariant = nv(80),
        containerLowest = n(100), containerLow = n(96), container = n(94), containerHigh = n(92), containerHighest = n(90),
        inverseSurface = n(20), inverseOnSurface = n(95), inversePrimary = p(80),
    ) else M3Roles(
        dark = true,
        primary = p(80), onPrimary = p(20), primaryContainer = p(30), onPrimaryContainer = p(90),
        secondary = sec(80), onSecondary = sec(20), secondaryContainer = sec(30), onSecondaryContainer = sec(90),
        tertiary = ter(80), onTertiary = ter(20), tertiaryContainer = ter(30), onTertiaryContainer = ter(90),
        error = Color(0xFFF2B8B5), onError = Color(0xFF601410), errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
        surface = n(6), onSurface = n(90), surfaceVariant = nv(30), onSurfaceVariant = nv(80),
        outline = nv(60), outlineVariant = nv(30),
        containerLowest = n(4), containerLow = n(10), container = n(12), containerHigh = n(17), containerHighest = n(22),
        inverseSurface = n(90), inverseOnSurface = n(20), inversePrimary = p(40),
    )
}

/** The roles as Material's own colour scheme, for its components (switches, buttons, ripples). */
fun M3Roles.toColorScheme(): ColorScheme = if (dark) darkColorScheme(
    primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer, inversePrimary = inversePrimary,
    secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
    background = surface, onBackground = onSurface, surface = surface, onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
    surfaceTint = primary, inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
    error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer,
    outline = outline, outlineVariant = outlineVariant,
) else lightColorScheme(
    primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer, inversePrimary = inversePrimary,
    secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
    background = surface, onBackground = onSurface, surface = surface, onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
    surfaceTint = primary, inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
    error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer,
    outline = outline, outlineVariant = outlineVariant,
)

/** The Material look's roles, for the pieces drawn in it. */
val LocalM3 = staticCompositionLocalOf { m3RolesFrom(M3Baseline, dark = true) }

/** Hue (0 to 360) and saturation (0 to 1) of a colour. */
private fun hueSat(c: Color): Pair<Float, Float> {
    val r = c.red; val g = c.green; val b = c.blue
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
    val l = (mx + mn) / 2f
    val d = mx - mn
    if (d < 1e-6f) return 0f to 0f
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (mx) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return (h + 360f) % 360f to s
}

/**
 * A colour as Material's tonal pair on its hue, for an avatar that still says what kind of thing
 * a row is: the container (tone 90, or 30 in the dark) and the icon on it (10, or 90).
 */
fun tonalPair(c: Color, dark: Boolean): Pair<Color, Color> {
    val (h, s) = hueSat(c)
    val sat = s.coerceIn(0f, 0.55f)
    return if (dark) Color.hsl(h, sat, 0.30f) to Color.hsl(h, sat, 0.90f)
    else Color.hsl(h, sat, 0.90f) to Color.hsl(h, sat, 0.12f)
}

/** "#RRGGBB" of a colour. */
fun Color.hex(): String {
    fun c(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    return "#" + c(red) + c(green) + c(blue)
}

/** A colour from "#RRGGBB", or null. */
fun colorOfHex(s: String?): Color? = s?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
