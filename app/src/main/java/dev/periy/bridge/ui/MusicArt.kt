package dev.periy.bridge.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.periy.bridge.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Album covers for the phone's Music: decoded once at the size they are shown, kept while
 * they are likely to be scrolled back to, and each cover's colour, which tints the player.
 */
object Covers {
    /** Rows and tiles: small, many. */
    private val small = LruCache<String, ImageBitmap>(160)
    /** The player's cover: large, few. */
    private val large = LruCache<String, ImageBitmap>(6)
    private val tints = LruCache<String, Color>(400)
    /** Albums that have no cover yet (until the catalogue finds one). */
    private val none = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Bumped when a cover arrives from the catalogue, so covers drawn as missing look again. */
    val version = MutableStateFlow(0)

    fun forget(albumId: String) {
        small.remove(albumId); large.remove(albumId); tints.remove(albumId); none.remove(albumId)
        version.value++
    }

    fun cached(albumId: String, big: Boolean): ImageBitmap? = (if (big) large else small).get(albumId)
    fun tint(albumId: String): Color? = tints.get(albumId)

    /**
     * Decodes the album's cover: small for lists, from the cover the pages get; large for the
     * player, as sharp as the phone has it. Null when it has none.
     */
    fun load(library: dev.periy.bridge.server.MusicLibrary, albumId: String, big: Boolean): ImageBitmap? {
        cached(albumId, big)?.let { return it }
        if (albumId in none) return null
        val id = albumId.toLongOrNull() ?: return null
        val bmp = if (big) library.artwork(id, 1000) else {
            val bytes = library.cover(id)
            if (bytes == null) null else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 220) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
        if (bmp == null) { none += albumId; return null }
        if (tints.get(albumId) == null) tints.put(albumId, tintOf(bmp))
        return bmp.asImageBitmap().also { (if (big) large else small).put(albumId, it) }
    }

    /**
     * The colour a cover is, worked out the way the page does it (artTint in bridge.html), so
     * an album is the same colour on the laptop and the phone: drawn at 16 by 16, every pixel
     * weighted by how vivid and how light it is, then lifted to a mid tone. A cover with no
     * colour at all gives a graphite.
     */
    fun tintOf(bmp: Bitmap): Color {
        val s = Bitmap.createScaledBitmap(bmp, 16, 16, true)
        var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
        for (y in 0 until 16) for (x in 0 until 16) {
            val p = s.getPixel(x, y)
            val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
            val mx = maxOf(pr, pg, pb); val mn = minOf(pr, pg, pb)
            val sat = if (mx > 0) (mx - mn).toDouble() / mx else 0.0
            val lum = mx / 255.0
            val k = sat * sat * (if (lum > 0.18) lum else lum * 0.2)
            r += pr * k; g += pg * k; b += pb * k; w += k
        }
        if (s !== bmp) s.recycle()
        if (w < 0.6) return Color(72, 72, 80)
        r /= w; g /= w; b /= w
        val top = maxOf(r, g, b)
        val lift = if (top < 170) 170 / top else 1.0
        return Color((r * lift).toInt().coerceAtMost(255), (g * lift).toInt().coerceAtMost(255), (b * lift).toInt().coerceAtMost(255))
    }

    /** A made-up cover's colour for an album with none: steady for the same name. */
    fun madeUp(name: String): Color {
        val palette = listOf(0xFFFA2D48, 0xFFFF9500, 0xFF34C759, 0xFF00C7BE, 0xFF0A84FF, 0xFFAF52DE, 0xFFFF375F, 0xFF5E5CE6)
        return Color(palette[(name.hashCode() and 0x7FFFFFFF) % palette.size])
    }
}

/** The album's cover and its colour, as they load. */
@Composable
fun rememberCover(albumId: String, big: Boolean = false): ImageBitmap? {
    val library = LocalContext.current.container.music
    val v by Covers.version.collectAsState()
    // The large one starts from the small one, if that is at hand, so a cover never blinks.
    return produceState(Covers.cached(albumId, big) ?: if (big) Covers.cached(albumId, false) else null, albumId, big, v) {
        value = Covers.cached(albumId, big) ?: withContext(Dispatchers.IO) { runCatching { Covers.load(library, albumId, big) }.getOrNull() } ?: value
    }.value
}

/**
 * An album cover, or a made-up one in the album's own colour with its first letter while
 * there is none, as the page draws them.
 */
@Composable
fun Cover(albumId: String, album: String, modifier: Modifier = Modifier, radius: Dp = 8.dp, big: Boolean = false) {
    val img = rememberCover(albumId, big)
    val shape = RoundedCornerShape(radius)
    if (img != null) Image(img, null, modifier.clip(shape), contentScale = ContentScale.Crop)
    else MadeUpCover(album, modifier.clip(shape))
}

@Composable
fun MadeUpCover(album: String, modifier: Modifier = Modifier) {
    val c = Covers.madeUp(album)
    Box(modifier.background(Brush.linearGradient(listOf(lerp(c, Color.White, 0.18f), c, lerp(c, Color.Black, 0.45f)))), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val letter = album.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "♪"
            androidx.compose.material3.Text(
                letter,
                style = TextStyle(fontSize = (maxWidth.value * 0.42f).sp, fontWeight = FontWeight.Bold),
                color = Color.White.copy(alpha = 0.92f),
            )
        }
    }
}
