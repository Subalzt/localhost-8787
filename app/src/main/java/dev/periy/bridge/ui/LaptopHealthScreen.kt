package dev.periy.bridge.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.periy.bridge.server.Control
import dev.periy.bridge.server.LaptopHealth
import dev.periy.bridge.server.LaptopHealthDto
import dev.periy.bridge.service.formatBytes
import kotlinx.coroutines.delay

/**
 * How each laptop whose helper runs is doing, from anywhere: CPU, memory, GPU, battery,
 * temperatures, disks and the busiest programs, measured by its helper every 3 s while this is open.
 */
@Composable
fun LaptopHealthScreen(onClose: () -> Unit) {
    val names by Control.connected.collectAsStateWithLifecycle()
    val laptops = remember(names) { Control.laptops() }
    var pick by remember { mutableStateOf<String?>(null) }
    val id = pick?.takeIf { p -> laptops.any { it.first == p } } ?: laptops.firstOrNull()?.first
    var h by remember { mutableStateOf<LaptopHealthDto?>(null) }
    LaunchedEffect(id) {
        h = null
        if (id == null) return@LaunchedEffect
        while (true) {
            h = LaptopHealth.ask(id)
            delay(3_000)
        }
    }
    BackHandler { onClose() }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().background(Bridge.Bg).padding(top = top)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable { onClose() }, contentAlignment = Alignment.Center) {
                Icon(BlazeIcons.Chevron, "Back", tint = Bridge.Accent, modifier = Modifier.size(24.dp).rotate(180f))
            }
            Text("Devices", style = TextStyle(fontSize = 17.sp), color = Bridge.Accent, modifier = Modifier.clickable { onClose() })
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp)) {
            Text(laptops.firstOrNull { it.first == id }?.second ?: "Laptop health", style = LargeTitleStyle, color = Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val d = h
            Text(
                when {
                    id == null -> "No laptop's helper is running"
                    d == null -> "Asking the laptop..."
                    d.error.isNotEmpty() -> d.error
                    else -> listOf(d.cpu?.name.orEmpty(), "up " + uptime(d.uptime)).filter { it.isNotBlank() }.joinToString(" · ")
                },
                style = CaptionStyle, color = Bridge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // More than one laptop: which one, as chips.
        if (laptops.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            laptops.forEach { (lid, name) ->
                val on = lid == id
                Text(
                    name, style = LabelStyle, color = if (on) Bridge.OnAccent else Bridge.Text,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Bridge.Accent else Bridge.Chip)
                        .clickable { pick = lid }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        val d = h
        if (d == null || d.error.isNotEmpty()) return@Column
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = bottom + 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val cpu = d.cpu
                    Gauge("CPU", cpu?.use ?: 0.0, "${(cpu?.use ?: 0.0).toInt()}%", if (cpu != null) "${cpu.cores} threads" else "", Bridge.Blue, Modifier.weight(1f))
                    val m = d.mem
                    val mp = if (m != null && m.total > 0) m.used * 100.0 / m.total else 0.0
                    Gauge("Memory", mp, "${mp.toInt()}%", if (m != null) formatBytes(m.used) + " of " + formatBytes(m.total) else "", Bridge.Purple, Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val g = d.gpus.firstOrNull()
                    if (g != null) Gauge(
                        "GPU", g.use ?: 0.0, "${(g.use ?: 0.0).toInt()}%",
                        listOfNotNull(g.temp?.let { "${it.toInt()}°C" }, g.power?.let { "${it.toInt()} W" }, g.memUsed?.let { m -> g.memTotal?.let { "${(m / 1024).fmt1()} of ${(it / 1024).fmt1()} GB" } }).joinToString(" · "),
                        Bridge.Good, Modifier.weight(1f),
                    ) else Gauge("GPU", 0.0, "—", "No NVIDIA GPU seen", Bridge.Good, Modifier.weight(1f))
                    val b = d.battery
                    if (b != null) Gauge(
                        "Battery", b.percent.toDouble(), "${b.percent}%",
                        when {
                            b.charging -> "Charging"
                            b.plugged -> "Plugged in"
                            b.minutes > 0 -> "${b.minutes / 60} h ${b.minutes % 60} min left"
                            else -> "On battery"
                        },
                        if (b.percent <= 20 && !b.plugged) Bridge.Danger else Bridge.Orange, Modifier.weight(1f),
                    ) else Gauge("Battery", 0.0, "—", "No battery", Bridge.Orange, Modifier.weight(1f))
                }
            }
            if (d.gpus.isNotEmpty()) item { Section("GPU") { d.gpus.forEachIndexed { i, g -> SettingRow(g.name, detail = listOfNotNull(g.use?.let { "${it.toInt()}% busy" }, g.temp?.let { "${it.toInt()}°C" }, g.power?.let { "${it.fmt1()} W" }, g.memUsed?.let { m -> g.memTotal?.let { "${m.toInt()} of ${it.toInt()} MB" } }).joinToString(" · "), first = i == 0) } } }
            if (d.temps.isNotEmpty()) item { Section("Temperatures") { d.temps.forEachIndexed { i, t -> SettingRow(t.name, first = i == 0) { Text("${t.c.toInt()}°C", style = TitleStyle, color = if (t.c >= 85) Bridge.Danger else Bridge.Text) } } } }
            if (d.disks.isNotEmpty()) item {
                Section("Disks") {
                    d.disks.forEachIndexed { i, k ->
                        val share = if (k.total > 0) k.used.toFloat() / k.total else 0f
                        SettingRow(k.name, detail = formatBytes(k.total - k.used) + " free of " + formatBytes(k.total), first = i == 0) {
                            Bar(share, if (share > 0.9f) Bridge.Danger else Bridge.Blue, Modifier.size(width = 72.dp, height = 6.dp))
                        }
                    }
                }
            }
            if (d.top.isNotEmpty()) item {
                Section("Busiest now") {
                    d.top.forEachIndexed { i, p ->
                        SettingRow(p.name, detail = formatBytes(p.mem), first = i == 0) { Text("${p.cpu.fmt1()}%", style = TitleStyle, color = Bridge.Text) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title.uppercase(), style = KickerStyle, color = Bridge.Muted, modifier = Modifier.padding(start = 32.dp, bottom = 6.dp))
        GroupCard { content() }
    }
}

/** A tile: what, how much as a big number, a line under it, and a bar that glides to the new value. */
@Composable
private fun Gauge(title: String, percent: Double, big: String, line: String, color: Color, modifier: Modifier) {
    Column(modifier.card().padding(16.dp)) {
        Text(title, style = LabelStyle, color = Bridge.Muted)
        Text(big, style = NumberStyle, color = Bridge.Text)
        Spacer(Modifier.height(6.dp))
        Bar((percent / 100).toFloat(), color, Modifier.fillMaxWidth().height(6.dp))
        Spacer(Modifier.height(6.dp))
        Text(line.ifEmpty { " " }, style = CaptionStyle, color = Bridge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Bar(share: Float, color: Color, modifier: Modifier) {
    val v by animateFloatAsState(share.coerceIn(0f, 1f), tween(600), label = "bar")
    Box(modifier.clip(RoundedCornerShape(50)).background(Bridge.Chip)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(v).clip(RoundedCornerShape(50)).background(color))
    }
}

private fun Double.fmt1() = String.format(java.util.Locale.US, "%.1f", this)

private fun uptime(s: Long): String = when {
    s >= 86_400 -> "${s / 86_400} d ${s % 86_400 / 3600} h"
    s >= 3600 -> "${s / 3600} h ${s % 3600 / 60} min"
    else -> "${s / 60} min"
}
