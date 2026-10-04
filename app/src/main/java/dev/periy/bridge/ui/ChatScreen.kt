package dev.periy.bridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.periy.bridge.container
import dev.periy.bridge.server.ChatMsg
import dev.periy.bridge.server.Peer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * One conversation with a linked phone: the messages, newest at the foot, and a line to write
 * in. Unlinking the phone is here too, asked first.
 */
@Composable
fun ChatScreen(name: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val messages = c.messages
    val threads by messages.threads.collectAsState()
    val list = threads[name].orEmpty()
    var text by remember { mutableStateOf("") }
    var unlinking by remember { mutableStateOf(false) }
    val state = rememberLazyListState()

    // Read while it is open, and what arrives now is read as it comes.
    DisposableEffect(name) {
        messages.open = name
        onDispose { if (messages.open == name) messages.open = null }
    }
    LaunchedEffect(list.size) { if (list.isNotEmpty()) state.animateScrollToItem(0) }
    BackHandler(onBack = onClose)

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxSize().background(Bridge.Bg).padding(top = top).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(BlazeIcons.Chevron, "Back", tint = Bridge.Text, modifier = Modifier.size(24.dp).rotate(180f))
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(name, style = TitleStyle, color = Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Phone to phone, sealed", style = CaptionStyle, color = Bridge.Muted, maxLines = 1)
            }
            val mic = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) { ok -> if (ok) c.calls.call(name) }
            IconChip(BlazeIcons.Call, "Call $name", tint = Bridge.Text, size = 40.dp) {
                if (hasMic(ctx)) c.calls.call(name) else mic.launch(android.Manifest.permission.RECORD_AUDIO)
            }
            Text(
                "Unlink", style = TextStyle(fontSize = 15.sp), color = Bridge.Danger,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { unlinking = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        if (unlinking) androidx.compose.material3.AlertDialog(
            onDismissRequest = { unlinking = false },
            title = { Text("Unlink $name?") },
            text = { Text("The two phones stop sharing files, the clipboard and messages, and each shuts the other out. This conversation stays on this phone.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    unlinking = false
                    c.peers.find(name)?.let(c.peers::forget)
                    onClose()
                }) { Text("Unlink", color = Bridge.Danger) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { unlinking = false }) { Text("Keep", color = Bridge.Text) } },
            containerColor = Bridge.Surface, titleContentColor = Bridge.Text, textContentColor = Bridge.Muted,
        )
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Bridge.Outline))

        // Newest at the foot: the list runs upwards from the line to write in.
        val shown = list.asReversed()
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = state, reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (shown.isEmpty()) item {
                Text(
                    "Say hello. It goes straight to $name, and waits here when that phone cannot be reached.",
                    style = BodyStyle, color = Bridge.Muted, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(28.dp),
                )
            }
            itemsIndexed(shown, key = { _, m -> m.id + m.mine }) { i, m ->
                val older = shown.getOrNull(i + 1)
                val newer = shown.getOrNull(i - 1)
                // The newest of mine says where it stands; a gap of a while gets the time over it.
                val lastMine = m.mine && shown.subList(0, i).none { it.mine }
                Column(Modifier.fillMaxWidth()) {
                    if (older == null || m.at - older.at > GAP_MS) {
                        Text(
                            longTime(m.at), style = CaptionStyle, color = Bridge.Muted, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                        )
                    } else if (older.mine != m.mine) Spacer(Modifier.height(6.dp))
                    Bubble(m, joinedAbove = older != null && older.mine == m.mine && m.at - older.at <= GAP_MS, joinedBelow = newer != null && newer.mine == m.mine)
                    if (lastMine) Text(
                        when (m.state) { "waiting" -> "Waiting for $name"; "read" -> "Read"; else -> "Delivered" },
                        style = CaptionStyle, color = Bridge.Muted, textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp, end = 4.dp),
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BridgeTextField(text, { text = it }, Modifier.weight(1f), placeholder = "Message", minHeight = 44.dp)
            Spacer(Modifier.size(8.dp))
            IconChip(
                BlazeIcons.Send, "Send",
                tint = if (text.isBlank()) Bridge.Muted else Bridge.Bg,
                bg = if (text.isBlank()) Bridge.Chip else Bridge.Text, size = 44.dp,
            ) {
                if (text.isNotBlank()) { messages.send(name, text); text = "" }
            }
        }
    }
}

/** A message: mine on the right in the text colour, theirs on the left on a chip; a run of them sits close. */
@Composable
private fun Bubble(m: ChatMsg, joinedAbove: Boolean, joinedBelow: Boolean) {
    val big = 18.dp
    val small = 6.dp
    val shape = if (m.mine) RoundedCornerShape(big, if (joinedAbove) small else big, if (joinedBelow) small else big, big)
    else RoundedCornerShape(if (joinedAbove) small else big, big, big, if (joinedBelow) small else big)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Text(
            m.text,
            style = TextStyle(fontSize = 16.sp, lineHeight = 21.sp),
            color = if (m.mine) Bridge.Bg else Bridge.Text,
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(shape)
                .background(if (m.mine) Bridge.Text.copy(alpha = if (m.state == "waiting") 0.55f else 1f) else Bridge.Chip)
                .padding(horizontal = 13.dp, vertical = 8.dp),
        )
    }
}

private const val GAP_MS = 15 * 60_000L

/** For the list: the time today, the weekday this week, the date before that. */
private fun shortTime(at: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = at }
    val pattern = when {
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> "HH:mm"
        now.timeInMillis - at < 6 * 86_400_000L -> "EEE"
        else -> "d MMM"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(at))
}

/** Over a run of messages: the day and the time. */
private fun longTime(at: Long): String {
    val today = shortTime(at).contains(':')
    return SimpleDateFormat(if (today) "'Today' HH:mm" else "EEE d MMM, HH:mm", Locale.getDefault()).format(Date(at))
}
