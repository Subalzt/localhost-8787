package dev.periy.bridge.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Material look's own chrome, which Theatre draws its own way (MainActivity.kt): the docked
 * navigation bar, the top app bar, and Home's status and send cards.
 */

/**
 * Material 3 Expressive's rounded polygons (its shape library), for icon containers: a scalloped
 * "cookie" of [lobes] soft points, [depth] of the radius deep (a deep one with four lobes is its clover).
 */
class CookieShape(private val lobes: Int, private val depth: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = min(cx, cy)
        val steps = lobes * 24
        val path = Path()
        for (i in 0..steps) {
            val t = i * 2.0 * PI / steps
            // Out to the full radius at each point, in by twice the depth between them.
            val rr = r * (1f - depth + depth * cos(lobes * t).toFloat())
            val x = cx + rr * cos(t - PI / 2).toFloat()
            val y = cy + rr * sin(t - PI / 2).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

val Cookie9 = CookieShape(9, 0.05f)
val Cookie12 = CookieShape(12, 0.035f)
val Clover4 = CookieShape(4, 0.1f)

/** The shape a row's avatar takes: the devices a cookie, the files a clover, the rest round. */
fun avatarShape(icon: ImageVector): Shape = when (icon.name) {
    "phones", "laptop", "trackpad" -> Cookie9
    "upload", "download", "file", "folder", "image" -> Clover4
    else -> CircleShape
}

/** The navigation bar's height, above the system's. */
val MaterialNavHeight = 80.dp

/** The top app bar's height, under the status bar. */
val MaterialTopHeight = 64.dp

/**
 * Material's navigation bar, docked along the bottom: each tab's icon and name, the one you are
 * on with a pill behind its icon that glides with a swipe ([position] in tabs, 1.5 halfway from
 * the second to the third). In Namida's look, in its bar's colours.
 */
@Composable
fun MaterialNavBar(
    items: List<Pair<String, ImageVector>>,
    selected: Int,
    position: Float,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val m = LocalM3.current
    val namida = LocalNamidaUi.current
    val nc = LocalNamida.current
    val bar = if (namida) nc.bar else m.container
    val pill = if (namida) nc.indicator else m.secondaryContainer
    val onPill = if (namida) nc.onSecondaryContainer else m.onSecondaryContainer
    val idle = if (namida) Bridge.Muted else m.onSurfaceVariant
    val lit = if (namida) Bridge.Text else m.onSurface
    // It takes every touch on it, so none reaches the page scrolled under it.
    Box(modifier.fillMaxWidth().pointerInput(Unit) {}.background(bar).padding(bottom = bottomInset)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(MaterialNavHeight)) {
            val w = maxWidth / items.size
            val at = position.coerceIn(0f, items.lastIndex.toFloat())
            Box(Modifier.offset(x = w * at + (w - 64.dp) / 2, y = 12.dp).size(64.dp, 32.dp).clip(ButtonShape).background(pill))
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { i, (label, icon) ->
                    // Lit by how much of the pill is over it, so the colours change as it passes.
                    val over = (1f - abs(at - i)).coerceIn(0f, 1f)
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) }
                            .padding(top = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(64.dp, 32.dp), contentAlignment = Alignment.Center) {
                            Icon(icon, null, tint = lerp(idle, onPill, over), modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            label,
                            style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp,
                                fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium),
                            color = lerp(idle, lit, over), maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Material's top app bar: the screen's name, and its actions on the right. It takes the
 * container colour once the page has scrolled under it.
 */
@Composable
fun MaterialTopBar(title: String, statusTop: Dp, scrolled: Boolean, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val raised = if (LocalNamidaUi.current) LocalNamida.current.bar else LocalM3.current.container
    val bg by animateColorAsState(if (scrolled) raised else Bridge.Bg, tween(200), label = "bar")
    Row(
        modifier.fillMaxWidth().pointerInput(Unit) {}.background(bg).padding(top = statusTop).height(MaterialTopHeight).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(title, Modifier.weight(1f), animationSpec = tween(160), label = "title") { t ->
            Text(t, style = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp),
                color = Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/**
 * The ink of what sits in Home's status: white on Theatre's dark hero ([shadow] under it, to lift
 * it off the picture); on Material's card, the card's own ink, with [lit] and [onLit] for the
 * connection picked.
 */
@Immutable
class HeroInk(val ink: Color, val lit: Color, val onLit: Color, val shadow: Shadow?)

val LocalHeroInk = staticCompositionLocalOf { HeroInk(Color.White, Color.White, Color(0xFF111114), OnArt) }

/**
 * Home's status in Material's look: a large card, primary container while the app is on and
 * the highest neutral container while it is off, holding what Theatre's hero holds.
 */
@Composable
fun MaterialStatusCard(on: Boolean, modifier: Modifier = Modifier, label: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val m = LocalM3.current
    val bg by animateColorAsState(if (on) m.primaryContainer else m.containerHighest, tween(220), label = "status")
    val ink = if (on) m.onPrimaryContainer else m.onSurface
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(32.dp)).background(bg).padding(20.dp)) {
        CompositionLocalProvider(LocalHeroInk provides HeroInk(ink, m.primary, m.onPrimary, null)) {
            // "Live · USB", or "Off": a dot that breathes while the app is on.
            if (label != null) Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    if (on) {
                        val breath by rememberInfiniteTransition(label = "live").animateFloat(
                            0.25f, 0f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "halo",
                        )
                        Box(Modifier.size(16.dp).graphicsLayer { val s = 1.25f - breath * 2f; scaleX = s; scaleY = s; alpha = breath * 3f }
                            .clip(CircleShape).background(m.primary))
                    }
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) m.primary else ink.copy(alpha = 0.38f)))
                }
                Spacer(Modifier.size(6.dp))
                Text(label, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp), color = ink.copy(alpha = 0.82f))
            }
            content()
        }
    }
}

/** The app's switch in Material's look: Material's own, the power sign on its thumb. */
@Composable
fun MaterialPowerSwitch(on: Boolean, onToggle: () -> Unit) {
    Switch(
        checked = on, onCheckedChange = { onToggle() },
        thumbContent = {
            Icon(BlazeIcons.Power, if (on) "Turn Localhost 8787 off" else "Turn Localhost 8787 on", modifier = Modifier.size(SwitchDefaults.IconSize))
        },
    )
}

/**
 * Send files in Material's look: a card in the tertiary container, so Home's two jobs read apart
 * at a glance (the status in the primary one), with a clover of the colour behind the title's
 * icon, what is happening, and Files (filled) and Folder (tonal).
 */
@Composable
fun MaterialSendCard(status: String, onFiles: () -> Unit, onFolder: () -> Unit) {
    val m = LocalM3.current
    // In Namida's look the card stays its own (Namida's colours, not Material's).
    if (LocalNamidaUi.current) {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp).panel().padding(20.dp)) {
            Text("Send files", style = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium), color = Bridge.Text)
            if (status.isNotEmpty()) Text(status, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = Bridge.Muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BridgeButton("Files", Modifier.weight(1f), icon = BlazeIcons.Upload, onClick = onFiles)
                SoftButton("Folder", Modifier.weight(1f), icon = BlazeIcons.Folder, onClick = onFolder)
            }
        }
        return
    }
    val ink = m.onTertiaryContainer
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(MaterialCardRadius)).background(m.tertiaryContainer).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(Clover4).background(m.tertiary), contentAlignment = Alignment.Center) {
                Icon(BlazeIcons.Send, null, tint = m.onTertiary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Send files", style = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium), color = ink)
                Text(
                    status.ifEmpty { "To your computer" }, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = ink.copy(alpha = 0.78f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MaterialButton("Files", Modifier.weight(1f), true, m.tertiary, m.onTertiary, BlazeIcons.Upload, onFiles)
            MaterialButton("Folder", Modifier.weight(1f), true, ink.copy(alpha = 0.10f), ink, BlazeIcons.Folder, onFolder)
        }
    }
}
