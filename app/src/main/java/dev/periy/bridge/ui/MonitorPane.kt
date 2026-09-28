package dev.periy.bridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import dev.periy.bridge.server.Monitor
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.periy.bridge.server.LaptopLink
import dev.periy.bridge.server.MonitorSnapshot
import dev.periy.bridge.server.PhoneLink
import dev.periy.bridge.service.formatBytes
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The live monitor, floating over whatever screen is open: a small pill with speed
 * each way, ping and signal. Drag it out of the way; tap it for the full picture -- a minute
 * of history, gaps (seconds where a transfer was running but nothing moved), and both ends
 * of the Wi-Fi link.
 */
@Composable
fun MonitorOverlay(m: MonitorSnapshot, running: Boolean, onClose: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // The sampler only runs while someone is looking; this is the phone looking.
    DisposableEffect(Unit) {
        Monitor.watchUi(true)
        onDispose { Monitor.watchUi(false) }
    }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        val maxX = with(density) { (maxWidth / 2 - 90.dp).toPx() }
        val maxY = with(density) { (maxHeight - 150.dp).toPx() }

        // The capsule, parked in its own lane under the title (the screens make room) until moved.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
                .padding(top = 58.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        dx = (dx + drag.x).coerceIn(-maxX, maxX)
                        dy = (dy + drag.y).coerceIn(-50f, maxY)
                    }
                }
                .floating(ButtonShape)
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) { Capsule(m, running) }

        AnimatedVisibility(expanded, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x59000000))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = false }
            )
        }
        AnimatedVisibility(
            expanded,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Sheet(m, running, onHide = { expanded = false }, onClose = { expanded = false; onClose() })
        }
    }
}

@Composable
private fun Capsule(m: MonitorSnapshot, running: Boolean) {
    val num = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (!running) Bridge.Faint else if (m.activeTransfers > 0) Bridge.Good else Bridge.Muted))
        if (!running) {
            Text("Off", style = num, color = Bridge.Muted)
            return@Row
        }
        Text("↓ " + short(m.inBps), style = num, color = Bridge.Orange)
        Text("↑ " + short(m.outBps), style = num, color = Bridge.Blue)
        Text(if (m.rttMs >= 0) "${m.rttMs} ms" else "– ms", style = num, color = pingColor(m.rttMs))
        SignalBars(linkLevel(m))
    }
}

@Composable
private fun Sheet(m: MonitorSnapshot, running: Boolean, onHide: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 56.dp)
            .fillMaxWidth()
            .heightIn(max = 640.dp)
            .floating(CardShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .verticalScroll(rememberScrollState())
            .padding(vertical = 14.dp)
    ) {
        Row(Modifier.padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Monitor", style = TitleStyle.copy(fontSize = 20.sp), color = Bridge.Text, modifier = Modifier.weight(1f))
            Text("Hide", style = LabelStyle, color = Bridge.Muted, modifier = Modifier.clip(ButtonShape).clickable(onClick = onClose).padding(8.dp))
            Spacer(Modifier.width(4.dp))
            IconChip(BlazeIcons.Close, "Close", tint = Bridge.Muted, onClick = onHide)
        }
        if (!running) {
            Text("Turn Localhost 8787 on to see live traffic.", style = BodyStyle, color = Bridge.Muted, modifier = Modifier.padding(20.dp))
            return@Column
        }

        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Rate("Receiving", m.inBps, Bridge.Orange, Modifier.weight(1f))
            Rate("Sending", m.outBps, Bridge.Blue, Modifier.weight(1f))
            Column(Modifier.weight(0.7f)) {
                Text("Ping", style = LabelStyle, color = Bridge.Muted)
                Spacer(Modifier.height(4.dp))
                Text(if (m.rttMs >= 0) "${m.rttMs} ms" else "–", style = NumberStyle.copy(fontSize = 22.sp), color = pingColor(m.rttMs))
            }
        }
        Box(Modifier.padding(horizontal = 20.dp)) { Graph(m) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            Text("Last minute", style = LabelStyle, color = Bridge.Faint)
        }

        ChannelUse(m)
        Stat("Peak", "↓ " + mbps(m.peakInBps) + "   ↑ " + mbps(m.peakOutBps), first = true)
        Stat("Moved", "↓ " + formatBytes(m.totalIn) + "   ↑ " + formatBytes(m.totalOut))
        Stat(
            "Gaps",
            if (m.gaps == 0) "None" else "${m.gaps} s" + (if (m.lastGapAt > 0) " · last " + ago(m.lastGapAt) else ""),
            if (m.gaps == 0) Bridge.Good else Bridge.Orange,
        )
        Stat("Open requests · transfers", "${m.requests} · ${m.activeTransfers}")
        Stat("Server up", uptime(m.uptimeSec))
        PhoneLinkRows(m.phone)
        LaptopLinkRows(m.laptop)
    }
}

/**
 * How full the link in use is, and with what. The whole is what that link can carry (the
 * cable's USB generation, or the Wi-Fi hop's link rate), not the fastest second seen. Files,
 * music and speed tests share it, and so does traffic that is not Localhost 8787's, which the laptop
 * helper measures.
 */
@Composable
private fun ChannelUse(m: MonitorSnapshot) {
    val used = m.filesBps + m.musicBps + m.testBps + m.otherBps
    val cap = m.capacityBps
    val pct = if (cap > 0) (used * 100 / cap).coerceIn(0, 100) else -1
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Channel", style = LabelStyle, color = Bridge.Muted, modifier = Modifier.weight(1f))
            Text(
                if (pct < 0) "–" else "$pct% of ~${mbps(cap)}",
                style = LabelStyle.copy(fontFeatureSettings = "tnum"), color = Bridge.Text,
            )
        }
        Spacer(Modifier.height(8.dp))
        val parts = listOf(m.filesBps to Bridge.Orange, m.musicBps to LaneMusic, m.testBps to Bridge.Blue, m.otherBps to LaneOther)
        Row(Modifier.fillMaxWidth().height(10.dp).clip(ButtonShape).background(Bridge.Chip)) {
            if (cap > 0) {
                var left = 1f
                parts.forEach { (bps, color) ->
                    val f = (bps.toFloat() / cap).coerceIn(0f, left)
                    if (f > 0.002f) { Box(Modifier.weight(f).fillMaxHeight().background(color)); left -= f }
                }
                if (left > 0.002f) Spacer(Modifier.weight(left))
            }
        }
        Spacer(Modifier.height(8.dp))
        // Wraps to a second line when all four are there, rather than squeezing the last.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Legend("Files", Bridge.Orange, m.filesBps)
            Legend("Music", LaneMusic, m.musicBps)
            if (m.testBps > 0) Legend("Test", Bridge.Blue, m.testBps)
            if (m.otherBps > 0) Legend("Other", LaneOther, m.otherBps)
        }
        Text(
            if (cap > 0) "Of what ${m.capacityLabel} can carry."
            else "Waiting for the laptop helper's Wi-Fi rates.",
            style = BodyStyle.copy(fontSize = 12.sp), color = Bridge.Faint, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Legend(label: String, color: Color, bps: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Text("  $label ${mbps(bps)}", style = LabelStyle.copy(fontSize = 12.sp, fontFeatureSettings = "tnum"), color = Bridge.Muted,
            maxLines = 1, softWrap = false)
    }
}

/** Music's pink, as on the page. */
private val LaneMusic = Color(0xFFFF2D55)
/** Traffic that is not Localhost 8787's: a neutral grey, so it never reads as one of the app's own. */
private val LaneOther = Color(0xFF8E8E93)

@Composable
private fun Rate(label: String, bps: Long, color: Color, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(color); Text("  $label", style = LabelStyle, color = Bridge.Muted)
        }
        Spacer(Modifier.height(4.dp))
        Text(mbps(bps), style = NumberStyle.copy(fontSize = 22.sp), color = Bridge.Text)
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(8.dp).clip(CircleShape).background(color))

@Composable
private fun Graph(m: MonitorSnapshot) {
    val inColor = Bridge.Orange
    val outColor = Bridge.Blue
    val grid = Bridge.Outline
    val samples = m.samples
    val top = max(1L, samples.maxOfOrNull { max(it.inBps, it.outBps) } ?: 1L).toFloat() * 1.15f
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (samples.size < 2) return@Canvas
        val step = size.width / (60 - 1)
        val x0 = size.width - step * (samples.size - 1)
        fun line(pick: (Long, Long) -> Long, color: Color) {
            val p = Path()
            samples.forEachIndexed { i, s ->
                val x = x0 + step * i
                val y = size.height - size.height * pick(s.inBps, s.outBps) / top
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            val fill = Path().apply {
                addPath(p)
                lineTo(x0 + step * (samples.size - 1), size.height)
                lineTo(x0, size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.3f), Color.Transparent)))
            drawPath(p, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        line({ i, _ -> i }, inColor)
        line({ _, o -> o }, outColor)
    }
}

@Composable
private fun Stat(title: String, value: String, color: Color = Bridge.Text, first: Boolean = false) {
    SettingRow(title, first = first) { Text(value, style = LabelStyle, color = color) }
}

@Composable
private fun PhoneLinkRows(p: PhoneLink?) {
    if (p == null) {
        SettingRow("This phone", "Not on Wi-Fi, or hosting the hotspot")
        return
    }
    val band = if (p.frequencyMhz >= 5925) "6 GHz" else if (p.frequencyMhz >= 4900) "5 GHz" else "2.4 GHz"
    SettingRow("Phone's Wi-Fi", "${p.standard} · $band · ${p.rssi} dBm · ${quality(rssiLevel(p.rssi))}") {
        SignalBars(rssiLevel(p.rssi))
    }
    SettingRow("Phone link rate", "↓ ${p.rxMbps} Mbps   ↑ ${p.txMbps} Mbps")
}

@Composable
private fun LaptopLinkRows(l: LaptopLink?) {
    if (l == null) {
        SettingRow("Laptop", "Run the laptop helper to see its signal")
        return
    }
    SettingRow("Laptop", "${l.radio} · ${l.band} · channel ${l.channel} · ${l.signalPercent}% · ${quality(percentLevel(l.signalPercent))}") {
        SignalBars(percentLevel(l.signalPercent))
    }
    SettingRow("Laptop link rate", "↓ ${l.rxMbps} Mbps   ↑ ${l.txMbps} Mbps")
}

/** Signal for the capsule: the laptop's view when the helper reports it, else this phone's. */
private fun linkLevel(m: MonitorSnapshot): Int = when {
    m.laptop != null -> percentLevel(m.laptop.signalPercent)
    m.phone != null -> rssiLevel(m.phone.rssi)
    else -> 0
}

@Composable
private fun pingColor(ms: Int): Color = when {
    ms < 0 -> Bridge.Muted
    ms <= 15 -> Bridge.Good
    ms <= 60 -> Bridge.Text
    else -> Bridge.Danger
}

private fun percentLevel(p: Int) = when {
    p >= 80 -> 4; p >= 60 -> 3; p >= 40 -> 2; p > 0 -> 1; else -> 0
}

private fun rssiLevel(rssi: Int) = when {
    rssi >= -55 -> 4; rssi >= -67 -> 3; rssi >= -75 -> 2; rssi >= -85 -> 1; else -> 0
}

private fun quality(level: Int) = when (level) {
    4 -> "excellent range"; 3 -> "good range"; 2 -> "fair range"; 1 -> "weak - move closer"; else -> "no signal"
}

private fun mbps(bps: Long): String = if (bps < 1_048_576) "%.0f KB/s".format(bps / 1024.0) else "%.1f MB/s".format(bps / 1_048_576.0)

/** Rate for the capsule: always MB/s, so the numbers keep their place. */
private fun short(bps: Long): String = "%.1f MB/s".format(bps / 1_048_576.0)

private fun ago(at: Long): String {
    val s = (System.currentTimeMillis() - at) / 1000
    return if (s < 60) "${s}s ago" else "${s / 60} min ago"
}

private fun uptime(s: Long): String = when {
    s < 60 -> "${s}s"
    s < 3600 -> "${s / 60} min"
    else -> "${s / 3600} h ${(s % 3600) / 60} min"
}
