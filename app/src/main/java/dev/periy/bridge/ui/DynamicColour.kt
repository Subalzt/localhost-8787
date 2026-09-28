package dev.periy.bridge.ui

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The wallpaper's colours (Material You, Android 12 and later), for the Material look while the
 * colour is Automatic; null on older phones, which build their colours from Material's baseline.
 */
fun dynamicRoles(ctx: Context, dark: Boolean): M3Roles? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val s = runCatching { if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx) }.getOrNull() ?: return null
    return s.toRoles(dark)
}

/** [dynamicRoles] for the screen being drawn. */
@Composable
fun wallpaperRoles(dark: Boolean): M3Roles? {
    val ctx = LocalContext.current
    return remember(ctx, dark) { dynamicRoles(ctx, dark) }
}

/** The wallpaper's main colour, which the page builds its Material colours from; null before Android 12. */
fun wallpaperSeed(ctx: Context): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return runCatching { dynamicLightColorScheme(ctx).primary.hex() }.getOrNull()
}

private fun ColorScheme.toRoles(dark: Boolean) = M3Roles(
    dark = dark,
    primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
    error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer,
    surface = surface, onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
    outline = outline, outlineVariant = outlineVariant,
    containerLowest = surfaceContainerLowest, containerLow = surfaceContainerLow, container = surfaceContainer,
    containerHigh = surfaceContainerHigh, containerHighest = surfaceContainerHighest,
    inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface, inversePrimary = inversePrimary,
)
