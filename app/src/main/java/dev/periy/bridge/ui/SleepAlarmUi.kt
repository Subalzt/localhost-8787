package dev.periy.bridge.ui

import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.periy.bridge.container
import dev.periy.bridge.music.AlarmDto
import dev.periy.bridge.music.Alarms
import dev.periy.bridge.server.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Whether the Alarms screen is open: from the sleep button, or the alarm clock in the status bar. */
object AlarmsUi {
    var open by mutableStateOf(false)
}

/**
 * The sleep timer's button on Now Playing: a clock, lit with the minutes left while it runs. A tap
 * opens Namida's menu: so many minutes, the end of this song, off, and the alarms.
 */
@Composable
internal fun SleepButton() {
    val ctx = LocalContext.current
    val sleep = ctx.container.sleep
    val st by sleep.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var left by remember { mutableLongStateOf(sleep.leftMs()) }
    LaunchedEffect(st) { while (st.on) { left = sleep.leftMs(); delay(1000) } }
    val nc = Nm.c
    val tint = if (st.on) nc.main.copy(alpha = 0.9f).let { androidx.compose.ui.graphics.lerp(it, nc.onSecondaryContainer, 0.25f) } else nc.onSecondaryContainer
    Box {
        Box(
            Modifier.size(44.dp).pressable(CircleShape, scaleTo = 0.85f, ripple = false) { open = !open }
                .semantics { contentDescription = if (st.on) "Sleep timer: " + sleepLeft(left) else "Sleep timer" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Iconsax.Timer, null, tint = tint, modifier = Modifier.size(21.dp))
            if (st.on) Text(
                if (st.endOfSong) "♪" else "${(left + 59_999) / 60_000}",
                style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = tint,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-4).dp, y = (-4).dp),
            )
        }
        if (open) NamidaMenu(onDismiss = { open = false }) { close ->
            if (st.on) {
                NamidaMenuItem(Iconsax.Timer, if (st.endOfSong) "Stops when this song ends" else "Stops in " + sleepLeft(left), selected = true, onClick = {})
                if (!st.endOfSong) NamidaMenuItem(Iconsax.Add, "15 minutes more", onClick = { close(); sleep.start(((left + 59_999) / 60_000).toInt() + 15) })
                NamidaMenuItem(Iconsax.Clear, "Turn off", onClick = { close(); sleep.cancel() })
            } else {
                listOf(15, 30, 45, 60, 90).forEach { m ->
                    NamidaMenuItem(Iconsax.Timer, if (m < 60) "$m minutes" else if (m == 60) "1 hour" else "1½ hours", onClick = { close(); sleep.start(m) })
                }
                NamidaMenuItem(Iconsax.Music, "End of this song", onClick = { close(); sleep.endOfSong() })
            }
            NamidaMenuItem(Iconsax.Clock, "Alarms", subtitle = nextAlarmLine(ctx), onClick = { close(); AlarmsUi.open = true })
        }
    }
}

private fun sleepLeft(ms: Long): String {
    val m = (ms + 59_999) / 60_000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

/** "Tomorrow 07:00", "Wed 06:30", or null when no alarm is on. */
private fun nextAlarmLine(ctx: android.content.Context): String? {
    val n = ctx.container.alarms.next() ?: return null
    return whenText(n.second)
}

private fun whenText(at: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = at }
    val today = java.util.Calendar.getInstance()
    val days = ((java.util.Calendar.getInstance().apply { timeInMillis = at; set(java.util.Calendar.HOUR_OF_DAY, 12) }.timeInMillis -
        today.apply { set(java.util.Calendar.HOUR_OF_DAY, 12) }.timeInMillis) / 86_400_000L).toInt()
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(c.time)
    return when (days) {
        0 -> "Today $time"
        1 -> "Tomorrow $time"
        else -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(c.time) + " " + time
    }
}

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private fun daysText(d: Int): String = when (d) {
    0 -> "Once"
    0b1111111 -> "Every day"
    0b0011111 -> "Weekdays"
    0b1100000 -> "Weekends"
    else -> (0..6).filter { d and (1 shl it) != 0 }.joinToString(", ") { DAY_NAMES[it] }
}

private fun timeText(h: Int, m: Int): String {
    val c = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m) }
    return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(c.time)
}

/**
 * The alarms, full screen: each with its time, days and song, switched on and off in place; a tap
 * edits one, + adds one. The next to ring is said at the top, and a snooze waiting can be dropped.
 */
@Composable
fun AlarmsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val alarms = ctx.container.alarms
    val list by alarms.list.collectAsStateWithLifecycle()
    val snoozeAt by alarms.snoozeAt.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AlarmDto?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }
    Dialog(onDismissRequest = { if (editing != null) editing = null else onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(Modifier.fillMaxSize().background(Bridge.Bg).padding(top = top)) {
            val e = editing
            if (e != null) {
                AlarmEditor(e, onDone = { editing = null })
                return@Box
            }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Done", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Accent,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClose).padding(horizontal = 10.dp, vertical = 8.dp))
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { editing = AlarmDto(0, 7, 0) }, contentAlignment = Alignment.Center) { Icon(BlazeIcons.Plus, "Add an alarm", tint = Bridge.Accent, modifier = Modifier.size(24.dp)) }
                }
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp)) {
                    Text("Alarms", style = LargeTitleStyle, color = Bridge.Text)
                    val n = remember(list, snoozeAt, now) { alarms.next() }
                    Text(
                        if (n == null) "None on" else "Next: " + whenText(n.second) + " · in " + inText(n.second - now),
                        style = CaptionStyle, color = Bridge.Muted,
                    )
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (snoozeAt > now) item {
                        GroupCard {
                            SettingRow("Snoozed till " + timeText(java.util.Calendar.getInstance().apply { timeInMillis = snoozeAt }.get(java.util.Calendar.HOUR_OF_DAY),
                                java.util.Calendar.getInstance().apply { timeInMillis = snoozeAt }.get(java.util.Calendar.MINUTE)), first = true, icon = Iconsax.Clock, iconColor = Bridge.Orange) {
                                Text("Cancel", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Accent,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { alarms.cancelSnooze() }.padding(8.dp))
                            }
                        }
                    }
                    if (list.isEmpty()) item {
                        Text("No alarms yet. + adds one that wakes you with your own music, fading in.", style = BodyStyle, color = Bridge.Muted,
                            modifier = Modifier.padding(horizontal = 20.dp))
                    }
                    items(list, key = { it.id }) { a ->
                        Row(
                            Modifier.fillMaxWidth().panel().clickable { editing = a }.padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(timeText(a.hour, a.minute), style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp, fontFeatureSettings = "tnum"),
                                    color = if (a.on) Bridge.Text else Bridge.Faint)
                                Text(daysText(a.days) + " · " + a.trackTitle.ifEmpty { "Your favourites" }, style = CaptionStyle,
                                    color = if (a.on) Bridge.Muted else Bridge.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Toggle(a.on, color = Bridge.Orange) { alarms.setOn(a.id, it) }
                        }
                    }
                }
            }
        }
    }
}

private fun inText(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(0)
    return if (m >= 60 * 24) "${m / (60 * 24)} d ${m % (60 * 24) / 60} h" else if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

/** One alarm: its time on the clock face, the days, the song, how slowly it fades in; Save, Delete, Hear it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditor(start: AlarmDto, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val alarms = ctx.container.alarms
    val time = rememberTimePickerState(start.hour, start.minute, is24Hour = android.text.format.DateFormat.is24HourFormat(ctx))
    var days by remember { mutableStateOf(start.days) }
    var fade by remember { mutableStateOf(start.fadeSec) }
    var track by remember { mutableStateOf(start.trackId to start.trackTitle) }
    var picking by remember { mutableStateOf(false) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    if (picking) {
        SongPicker(onPick = { t -> track = (t?.id ?: -1L) to (t?.title.orEmpty()); picking = false }, onBack = { picking = false })
        return
    }
    val now = { start.copy(hour = time.hour, minute = time.minute, days = days, fadeSec = fade, trackId = track.first, trackTitle = track.second, on = true) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Cancel", style = TextStyle(fontSize = 17.sp), color = Bridge.Accent,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onDone).padding(horizontal = 10.dp, vertical = 8.dp))
            Spacer(Modifier.weight(1f))
            Text(if (start.id == 0) "New alarm" else "Alarm", style = TitleStyle, color = Bridge.Text)
            Spacer(Modifier.weight(1f))
            Text("Save", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Accent,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { alarms.put(now()); onDone() }.padding(horizontal = 10.dp, vertical = 8.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = bottom + 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(
                        time,
                        colors = TimePickerDefaults.colors(
                            clockDialColor = Bridge.Chip, selectorColor = Bridge.Orange, containerColor = Bridge.Bg,
                            clockDialSelectedContentColor = androidx.compose.ui.graphics.Color.Black, clockDialUnselectedContentColor = Bridge.Text,
                            periodSelectorSelectedContainerColor = Bridge.Orange.copy(alpha = 0.25f), periodSelectorUnselectedContainerColor = Bridge.Chip,
                            periodSelectorSelectedContentColor = Bridge.Text, periodSelectorUnselectedContentColor = Bridge.Muted,
                            timeSelectorSelectedContainerColor = Bridge.Orange.copy(alpha = 0.25f), timeSelectorUnselectedContainerColor = Bridge.Chip,
                            timeSelectorSelectedContentColor = Bridge.Text, timeSelectorUnselectedContentColor = Bridge.Muted,
                        ),
                    )
                }
            }
            item {
                Column {
                    Text("REPEAT", style = KickerStyle, color = Bridge.Muted, modifier = Modifier.padding(start = 32.dp, bottom = 6.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        (0..6).forEach { i ->
                            val on = days and (1 shl i) != 0
                            Box(
                                Modifier.size(42.dp).clip(CircleShape).background(if (on) Bridge.Orange else Bridge.Chip)
                                    .clickable { days = days xor (1 shl i) }
                                    .semantics { contentDescription = DAY_NAMES[i] + if (on) ", on" else "" },
                                contentAlignment = Alignment.Center,
                            ) { Text(DAY_LETTERS[i], style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = if (on) androidx.compose.ui.graphics.Color.Black else Bridge.Text) }
                        }
                    }
                    Text(daysText(days), style = CaptionStyle, color = Bridge.Muted, modifier = Modifier.padding(start = 32.dp, top = 6.dp))
                }
            }
            item {
                GroupCard {
                    SettingRow("Song", detail = track.second.ifEmpty { "Your favourites, shuffled" }, first = true, icon = Iconsax.Music, iconColor = Bridge.Pink,
                        onClick = { picking = true }) { Icon(BlazeIcons.Chevron, null, tint = Bridge.Faint, modifier = Modifier.size(18.dp)) }
                    SettingRow("Fades in over", icon = Iconsax.Volume, iconColor = Bridge.Orange) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(15 to "15 s", 60 to "1 min", 120 to "2 min", 300 to "5 min").forEach { (s, label) ->
                                Text(label, style = LabelStyle, color = if (fade == s) androidx.compose.ui.graphics.Color.Black else Bridge.Text,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (fade == s) Bridge.Orange else Bridge.Chip)
                                        .clickable { fade = s }.padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                    }
                }
            }
            item {
                GroupCard {
                    SettingRow("Hear it now", detail = "Rings as it will, from silence up", first = true, icon = Iconsax.Sound, iconColor = Bridge.Blue, onClick = {
                        // Saved first, so the ring is the alarm as set here.
                        val id = alarms.put(now())
                        ctx.startActivity(Intent(ctx, AlarmActivity::class.java).putExtra(Alarms.EXTRA_ID, id).putExtra(AlarmActivity.EXTRA_TRY, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        onDone()
                    })
                    if (start.id != 0) SettingRow("Delete alarm", titleColor = Bridge.Danger, onClick = { alarms.remove(start.id); onDone() })
                }
            }
        }
    }
}

/** A song of your own for the alarm, searched by title or artist; or back to the favourites, shuffled. */
@Composable
private fun SongPicker(onPick: (TrackDto?) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val all by produceState(emptyList<TrackDto>()) { value = withContext(Dispatchers.IO) { ctx.container.music.tracks() } }
    var q by remember { mutableStateOf("") }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Back", style = TextStyle(fontSize = 17.sp), color = Bridge.Accent,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onBack).padding(horizontal = 10.dp, vertical = 8.dp))
        }
        Text("Wake up to", style = LargeTitleStyle, color = Bridge.Text, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(Bridge.Chip).padding(horizontal = 14.dp, vertical = 11.dp)) {
            if (q.isEmpty()) Text("Search songs", style = BodyStyle.copy(fontSize = 16.sp), color = Bridge.Faint)
            BasicTextField(q, { q = it }, singleLine = true, textStyle = BodyStyle.copy(fontSize = 16.sp, color = Bridge.Text),
                cursorBrush = SolidColor(Bridge.Blue), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(10.dp))
        val shown = remember(all, q) {
            val n = q.trim()
            (if (n.isEmpty()) all else all.filter { it.title.contains(n, true) || it.artist.contains(n, true) }).take(300)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().clickable { onPick(null) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(Iconsax.Heart, Bridge.Pink, size = 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Your favourites, shuffled", style = TitleStyle, color = Bridge.Text)
                }
            }
            items(shown, key = { it.id }) { t ->
                Column(Modifier.fillMaxWidth().clickable { onPick(t) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text(t.title, style = TextStyle(fontSize = 16.sp), color = Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, style = CaptionStyle, color = Bridge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Handoff from Now Playing: the song goes on in a laptop's page from the same moment (an open
 * page takes it; with none, the laptop's helper opens one), and the phone stops. With more than
 * one laptop, which one.
 */
@Composable
internal fun HandoffButton() {
    val ctx = LocalContext.current
    val c = ctx.container
    val nc = Nm.c
    var open by remember { mutableStateOf(false) }
    val send = { laptop: String? ->
        val s = c.player.state.value
        if (s.current == null) android.widget.Toast.makeText(ctx, "Nothing playing", android.widget.Toast.LENGTH_SHORT).show()
        else {
            val any = dev.periy.bridge.server.Handoff.musicToLaptop(s.queue.map { it.id }, s.index, s.positionNow(), c.peers.deviceName(), laptop)
            android.widget.Toast.makeText(ctx, if (any) "Carrying on on the laptop" else "Offered to any page open on a laptop", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    Box {
        Box(
            Modifier.size(46.dp).clip(CircleShape).clickable {
                val laptops = dev.periy.bridge.server.Control.laptops()
                if (laptops.size > 1) open = true else send(laptops.firstOrNull()?.first)
            }.semantics { contentDescription = "Continue on the laptop" },
            contentAlignment = Alignment.Center,
        ) { Icon(BlazeIcons.Laptop, null, tint = nc.onSecondaryContainer, modifier = Modifier.size(21.dp)) }
        if (open) NamidaMenu(onDismiss = { open = false }) { close ->
            dev.periy.bridge.server.Control.laptops().forEach { (id, name) ->
                NamidaMenuItem(BlazeIcons.Laptop, "Continue on $name", onClick = { close(); send(id) })
            }
        }
    }
}
