package dev.periy.bridge.ui

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.periy.bridge.container
import dev.periy.bridge.server.ChatMsg
import dev.periy.bridge.server.Messages
import dev.periy.bridge.service.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A name's own colour, the same everywhere: avatars and names in a group. */
internal fun nameHue(name: String): Float {
    var h = 0
    for (ch in name) h = h * 31 + ch.code
    return ((h % 360) + 360) % 360f
}

/** A round avatar: initials on the name's own colour (a group's shows its first letter). */
@Composable
internal fun ChatAvatar(name: String, size: androidx.compose.ui.unit.Dp, group: Boolean = false) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color.hsl(nameHue(name), 0.62f, 0.6f), Color.hsl(nameHue(name), 0.7f, 0.38f)))),
        contentAlignment = Alignment.Center,
    ) {
        if (group) Icon(BlazeIcons.Phones, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
        else {
            val words = name.split(' ', '-', '_').filter { it.any(Char::isLetterOrDigit) }
            val ini = (if (words.size >= 2) "${words[0].first()}${words[1].first()}" else name.take(2)).uppercase()
            Text(ini, style = TextStyle(fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.SemiBold), color = Color.White)
        }
    }
}

/**
 * A conversation: with a linked phone, or a group. Along the top who it is with, how to call
 * them, and (one to one) unlinking. The messages run up from the foot, each run of one person's
 * close together, a day's divider between days, the time in each bubble, photos that open full
 * screen, voice notes with their waveform, files to open. Along the foot a line to write in, a +
 * for a photo or a file, and the microphone: hold it to record a voice note, slide away to drop
 * it. New messages rise in.
 */
@Composable
fun ChatScreen(name: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val messages = c.messages
    val threads by messages.threads.collectAsState()
    val groups by messages.groups.collectAsState()
    val key = name
    val group = if (key.startsWith(Messages.GROUP)) groups[key.removePrefix(Messages.GROUP)] else null
    val title = group?.name ?: key
    val list = threads[key].orEmpty()
    var unlinking by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<ChatMsg?>(null) }
    // Held a message: its menu (reactions, Reply, Copy, Delete for everyone). Answering one: the bar over the line.
    var menuFor by remember { mutableStateOf<ChatMsg?>(null) }
    var replying by remember { mutableStateOf<ChatMsg?>(null) }
    val typingAll by messages.typing.collectAsState()
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val typingNow = typingAll[key].orEmpty().filterValues { clock - it < Messages.TYPING_MS }.keys.toList()
    LaunchedEffect(typingAll[key]) { while (true) { clock = System.currentTimeMillis(); if (typingAll[key].orEmpty().values.none { clock - it < Messages.TYPING_MS }) break; delay(1000) } }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val startCall = rememberStartCall()
    val callPeople = remember(group, key) {
        if (group == null) listOf(key) else (if (group.host.isEmpty()) group.members else listOf(group.host))
    }

    DisposableEffect(key) {
        messages.open = key
        onDispose { if (messages.open == key) messages.open = null }
    }
    LaunchedEffect(list.size) { if (list.isNotEmpty() && state.firstVisibleItemIndex < 3) state.animateScrollToItem(0) }
    BackHandler(viewing != null) { viewing = null }
    BackHandler(menuFor != null) { menuFor = null }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxSize().background(Bridge.Bg)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // ---- along the top
            Row(
                Modifier.fillMaxWidth().background(Bridge.Bg).padding(top = top + 6.dp, start = 6.dp, end = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(BlazeIcons.Chevron, "Back", tint = Bridge.Text, modifier = Modifier.size(24.dp).rotate(180f))
                }
                ChatAvatar(title, 38.dp, group = group != null)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(title, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val typingLine = when {
                        typingNow.isEmpty() -> null
                        group == null -> "typing…"
                        typingNow.size == 1 -> "${typingNow[0]} is typing…"
                        else -> "${typingNow.size} people are typing…"
                    }
                    Text(
                        typingLine ?: if (group != null) (listOf("You") + messages.others(group)).joinToString(", ") else "End-to-end encrypted",
                        style = CaptionStyle.copy(fontSize = 12.sp), color = if (typingLine != null) Bridge.Accent else Bridge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                HeaderButton(BlazeIcons.Video, "Video call") { startGroupCall(c, startCall, callPeople, true) }
                HeaderButton(BlazeIcons.Call, "Voice call") { startGroupCall(c, startCall, callPeople, false) }
                if (group == null) HeaderButton(BlazeIcons.More, "More") { unlinking = true }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Bridge.Outline))

            // ---- the messages, newest at the foot
            val shown = list.asReversed()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    Modifier.fillMaxSize(), state = state, reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (shown.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(top = 60.dp, bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            ChatAvatar(title, 72.dp, group = group != null)
                            Spacer(Modifier.height(14.dp))
                            Text(title, style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Text)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Messages go straight between the phones, sealed, and wait here when a phone cannot be reached.",
                                style = BodyStyle, color = Bridge.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 36.dp),
                            )
                        }
                    }
                    itemsIndexed(shown, key = { _, m -> m.id + m.mine }) { i, m ->
                        val older = shown.getOrNull(i + 1)
                        val newer = shown.getOrNull(i - 1)
                        val sameAbove = older != null && older.mine == m.mine && older.from == m.from && older.kind != "event" && m.at - older.at <= RUN_MS
                        val sameBelow = newer != null && newer.mine == m.mine && newer.from == m.from && newer.kind != "event" && newer.at - m.at <= RUN_MS
                        val lastMine = m.mine && shown.subList(0, i).none { it.mine }
                        Column(Modifier.fillMaxWidth().animateItem(fadeInSpec = tween(220), placementSpec = spring(stiffness = 500f), fadeOutSpec = tween(150))) {
                            if (older == null || !sameDay(older.at, m.at)) DayDivider(m.at)
                            else if (!sameAbove) Spacer(Modifier.height(8.dp))
                            when (m.kind) {
                                "event" -> EventLine(m, group?.host.orEmpty(), title)
                                else -> MessageRow(
                                    m, group != null, sameAbove, sameBelow, onOpen = { viewing = it },
                                    onHold = { menuFor = it },
                                    onQuote = { id -> shown.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { i -> scope.launch { state.animateScrollToItem(i) } } },
                                    onReaction = { e -> messages.react(key, m.id, if (m.reactions[""] == e) "" else e) },
                                )
                            }
                            if (lastMine && m.kind != "event") Text(
                                when (m.state) { "waiting" -> "Waiting" + if (group == null) " for $title" else ""; "read" -> "Read"; else -> "Delivered" },
                                style = CaptionStyle.copy(fontSize = 11.sp), color = Bridge.Muted, textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth().padding(top = 3.dp, end = 6.dp),
                            )
                        }
                    }
                }
                // Back to the newest, when scrolled up.
                val away by remember { derivedStateOf { state.firstVisibleItemIndex > 4 } }
                androidx.compose.animation.AnimatedVisibility(away, enter = fadeIn() + scaleIn(initialScale = 0.7f), exit = fadeOut() + scaleOut(targetScale = 0.7f),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp)) {
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).background(Bridge.Surface).clickable { scope.launch { state.animateScrollToItem(0) } },
                        contentAlignment = Alignment.Center,
                    ) { Icon(BlazeIcons.ChevronDown, "Newest", tint = Bridge.Text, modifier = Modifier.size(22.dp)) }
                }
            }

            Composer(
                onSend = { messages.send(key, it, replying?.id.orEmpty()); replying = null },
                onFile = { uri, kind -> messages.sendFile(key, uri, kind) }, onVoice = { f, ms -> messages.sendVoice(key, f, ms) },
                replying = replying, replyName = replying?.let { if (it.mine) "yourself" else it.from.ifEmpty { title } }.orEmpty(),
                onCancelReply = { replying = null }, onTyping = { messages.typing(key) },
            )
        }

        // A held message: react, reply, copy, take it back.
        AnimatedVisibility(menuFor != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(140))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menuFor = null },
            )
        }
        AnimatedVisibility(
            menuFor != null, modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(160)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 600f), initialScale = 0.85f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.92f),
        ) {
            var last by remember { mutableStateOf<ChatMsg?>(null) }
            menuFor?.let { last = it }
            last?.let { m ->
                val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                MessageMenu(
                    m,
                    onReact = { e -> messages.react(key, m.id, if (m.reactions[""] == e) "" else e); menuFor = null },
                    onReply = { replying = m; menuFor = null },
                    onCopy = if (m.kind == "text" || (m.kind == "image" && m.text.isNotEmpty())) ({ clip.setText(androidx.compose.ui.text.AnnotatedString(m.text)); menuFor = null }) else null,
                    onDelete = if (m.mine) ({ messages.unsend(key, m.id); if (replying?.id == m.id) replying = null; menuFor = null }) else null,
                )
            }
        }

        // A photo, full screen: pinch to zoom, tap or back to close.
        AnimatedVisibility(viewing != null, enter = fadeIn(tween(200)) + scaleIn(tween(260), initialScale = 0.92f), exit = fadeOut(tween(180)) + scaleOut(tween(200), targetScale = 0.95f)) {
            viewing?.let { PhotoViewer(it) { viewing = null } }
        }
    }

    if (unlinking) androidx.compose.material3.AlertDialog(
        onDismissRequest = { unlinking = false },
        title = { Text("Unlink $title?") },
        text = { Text("The two phones stop sharing files, the clipboard, messages and calls, and each shuts the other out. This conversation stays on this phone.") },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                unlinking = false
                c.peers.find(title)?.let(c.peers::forget)
                onClose()
            }) { Text("Unlink", color = Bridge.Danger) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { unlinking = false }) { Text("Keep", color = Bridge.Text) } },
        containerColor = Bridge.Surface, titleContentColor = Bridge.Text, textContentColor = Bridge.Muted,
    )
}

/** A call with everyone in a conversation this phone can reach: the first called, the rest added. */
private fun startGroupCall(c: dev.periy.bridge.Container, start: (String, Boolean) -> Unit, people: List<String>, video: Boolean) {
    val reach = people.filter { c.peers.find(it) != null }
    val first = reach.firstOrNull() ?: return
    start(first, video)
    reach.drop(1).forEach { c.calls.add(it) }
}

@Composable
private fun HeaderButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).pressable(CircleShape, scaleTo = 0.86f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Bridge.Text, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun DayDivider(at: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(
            dayName(at), style = CaptionStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = Bridge.Muted,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Bridge.Chip).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun EventLine(m: ChatMsg, host: String, title: String) {
    val who = if (m.mine) "You" else m.from.ifEmpty { host.ifEmpty { title } }
    Text(
        "$who ${m.text}", style = CaptionStyle.copy(fontSize = 12.sp), color = Bridge.Muted, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

/** One message, on its side: in a group theirs carry who wrote them (the first of a run) and their avatar (the last). */
@Composable
private fun MessageRow(
    m: ChatMsg, inGroup: Boolean, sameAbove: Boolean, sameBelow: Boolean, onOpen: (ChatMsg) -> Unit,
    onHold: (ChatMsg) -> Unit, onQuote: (String) -> Unit, onReaction: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = if (sameAbove) 2.dp else 0.dp),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (inGroup && !m.mine) {
            if (!sameBelow) ChatAvatar(m.from.ifEmpty { "?" }, 28.dp) else Spacer(Modifier.width(28.dp))
            Spacer(Modifier.width(6.dp))
        }
        Column(horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
            if (inGroup && !m.mine && !sameAbove) Text(
                m.from, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = Color.hsl(nameHue(m.from), 0.6f, 0.62f),
                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
            )
            val view = LocalView.current
            Bubble(
                m, sameAbove, sameBelow, onOpen, onQuote,
                Modifier.onHold(enabled = m.kind != "deleted") { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onHold(m) },
            )
            if (m.reactions.isNotEmpty()) Reactions(m, onReaction)
        }
    }
}

/** The bubble: mine in the accent, theirs on a chip; the corners on the side of a run sit close. */
@Composable
private fun Bubble(m: ChatMsg, joinedAbove: Boolean, joinedBelow: Boolean, onOpen: (ChatMsg) -> Unit, onQuote: (String) -> Unit, modifier: Modifier = Modifier) {
    val big = 20.dp
    val small = 6.dp
    val shape = if (m.mine) RoundedCornerShape(big, if (joinedAbove) small else big, if (joinedBelow) small else big, big)
    else RoundedCornerShape(if (joinedAbove) small else big, big, big, if (joinedBelow) small else big)
    val fg = if (m.mine) Color.White else Bridge.Text
    val bg = if (m.mine) Brush.linearGradient(listOf(Bridge.Accent, Bridge.Accent.copy(alpha = 0.82f).compositeOn(Color.Black)))
    else SolidColor(Bridge.Chip)
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.at))
    if (m.kind == "deleted") {
        Row(
            modifier.widthIn(max = 300.dp).clip(shape).background(Bridge.Chip).padding(start = 13.dp, end = 11.dp, top = 8.dp, bottom = 7.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                if (m.mine) "You deleted this message" else "This message was deleted",
                style = TextStyle(fontSize = 15.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = Bridge.Muted,
            )
            Spacer(Modifier.width(8.dp))
            Text(time, style = TextStyle(fontSize = 11.sp), color = Bridge.Muted.copy(alpha = 0.8f))
        }
        return
    }
    Column(
        modifier.widthIn(max = 300.dp).clip(shape).background(bg).graphicsLayer { alpha = if (m.mine && m.state == "waiting") 0.7f else 1f },
    ) {
        if (m.reply.isNotEmpty()) Quote(m, fg, onQuote)
        when (m.kind) {
            "image" -> Column {
                PhotoThumb(m, Modifier.widthIn(max = 260.dp).clickable(enabled = m.file.isNotEmpty()) { onOpen(m) })
                if (m.text.isNotEmpty()) Text(m.text, style = TextStyle(fontSize = 16.sp, lineHeight = 21.sp), color = fg, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                Text(time, style = TextStyle(fontSize = 11.sp), color = fg.copy(alpha = 0.65f), modifier = Modifier.align(Alignment.End).padding(end = 10.dp, bottom = 6.dp, top = if (m.text.isEmpty()) 4.dp else 0.dp))
            }
            "voice" -> VoiceNote(m, fg, time)
            "file" -> FileCard(m, fg, time)
            else -> Column(Modifier.padding(start = 13.dp, end = 11.dp, top = if (m.reply.isNotEmpty()) 4.dp else 8.dp, bottom = 6.dp)) {
                Text(m.text, style = TextStyle(fontSize = 16.sp, lineHeight = 21.sp), color = fg)
                Text(time, style = TextStyle(fontSize = 11.sp), color = fg.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

/** What a reply answers, at the top of its bubble: who and a few words; a tap goes to it. */
@Composable
private fun Quote(m: ChatMsg, fg: Color, onQuote: (String) -> Unit) {
    val who = m.replyFrom.ifEmpty { "You" }
    val tint = if (m.mine) Color.White else Color.hsl(nameHue(who), 0.6f, 0.62f)
    Row(
        Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp).widthIn(min = 120.dp).clip(RoundedCornerShape(12.dp))
            .background(if (m.mine) Color.Black.copy(alpha = 0.16f) else Bridge.Text.copy(alpha = 0.06f))
            .clickable { onQuote(m.reply) },
    ) {
        Box(Modifier.width(3.dp).height(44.dp).background(tint))
        Column(Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) {
            Text(who, style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold), color = tint, maxLines = 1)
            Text(m.replyText.ifEmpty { "Message" }, style = TextStyle(fontSize = 13.5.sp), color = fg.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A message's reactions, just under it: each emoji once, with how many; a tap on one takes yours back or makes it yours. */
@Composable
private fun Reactions(m: ChatMsg, onReaction: (String) -> Unit) {
    val counts = m.reactions.values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
    Row(
        Modifier.offset(y = (-4).dp).padding(horizontal = 6.dp).clip(RoundedCornerShape(50)).background(Bridge.Surface)
            .padding(horizontal = 3.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((e, n) in counts) {
            val mine = m.reactions[""] == e
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (mine) Bridge.Accent.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onReaction(e) }.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(e, style = TextStyle(fontSize = 14.sp))
                if (n > 1) Text(" $n", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = Bridge.Muted)
            }
        }
    }
}

/** The quick reactions, as WhatsApp's. */
private val QUICK = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/** A held message's menu: the quick reactions, then Reply, Copy and (mine) Delete for everyone. */
@Composable
private fun MessageMenu(m: ChatMsg, onReact: (String) -> Unit, onReply: () -> Unit, onCopy: (() -> Unit)?, onDelete: (() -> Unit)?) {
    Column(Modifier.padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Bridge.Surface).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (e in QUICK) Box(
                Modifier.size(46.dp).clip(CircleShape).background(if (m.reactions[""] == e) Bridge.Accent.copy(alpha = 0.25f) else Color.Transparent)
                    .pressable(CircleShape, scaleTo = 0.8f) { onReact(e) },
                contentAlignment = Alignment.Center,
            ) { Text(e, style = TextStyle(fontSize = 25.sp)) }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            Messages.snippetOf(m), style = BodyStyle, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp).padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(10.dp))
        Column(Modifier.width(250.dp).clip(RoundedCornerShape(16.dp)).background(Bridge.Surface)) {
            MenuLine("Reply", BlazeIcons.Reply, Bridge.Text, onReply)
            if (onCopy != null) { MenuRule(); MenuLine("Copy", BlazeIcons.Copy, Bridge.Text, onCopy) }
            if (onDelete != null) { MenuRule(); MenuLine("Delete for everyone", BlazeIcons.Trash, Bridge.Danger, onDelete) }
        }
    }
}

@Composable
private fun MenuLine(label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = TextStyle(fontSize = 16.sp), color = color, modifier = Modifier.weight(1f))
        Icon(icon, null, tint = color, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun MenuRule() = Box(Modifier.fillMaxWidth().height(0.5.dp).background(Bridge.Outline))

/**
 * A long press, seen before the bubble's own taps (a photo opening, a voice note playing): after it the
 * rest of the touch is the menu's, so lifting does not also open or play.
 */
private fun Modifier.onHold(enabled: Boolean, action: () -> Unit): Modifier = if (!enabled) this else pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var ended = false
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (!ended) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull { it.id == down.id }
                if (ch == null || !ch.pressed || (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) ended = true
            }
        }
        if (!ended) {
            action()
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                ev.changes.forEach { it.consume() }
                if (ev.changes.none { it.pressed }) break
            }
        }
    }
}

private fun Color.compositeOn(under: Color): Color {
    val a = alpha
    return Color(red * a + under.red * (1 - a), green * a + under.green * (1 - a), blue * a + under.blue * (1 - a), 1f)
}

// ---------------------------------------------------------------------- photos

@Composable
private fun rememberPhoto(path: String, maxPx: Int): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, path, maxPx) {
        value = if (path.isEmpty()) null else withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxPx) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bmp
}

@Composable
private fun PhotoThumb(m: ChatMsg, modifier: Modifier) {
    val ratio = if (m.w > 0 && m.h > 0) (m.w.toFloat() / m.h).coerceIn(0.5f, 1.8f) else 1f
    val img = rememberPhoto(m.file, 720)
    Box(modifier.aspectRatio(ratio).background(Color.Black.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
        if (img != null) Image(img, "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Waiting(if (m.file.isEmpty()) "Receiving…" else "")
    }
}

@Composable
private fun Waiting(note: String) {
    val t = rememberInfiniteTransition(label = "wait")
    val a by t.animateFloat(0.3f, 0.8f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Text(note, style = CaptionStyle, color = Color.White.copy(alpha = a))
}

@Composable
private fun PhotoViewer(m: ChatMsg, onClose: () -> Unit) {
    val img = rememberPhoto(m.file, 2048)
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }, onDoubleTap = { if (scale > 1.1f) { scale = 1f; pan = Offset.Zero } else scale = 2.5f }) }
            .pointerInput(Unit) { detectTransformGestures { _, p, z, _ -> scale = (scale * z).coerceIn(1f, 6f); pan = if (scale <= 1f) Offset.Zero else pan + p } },
        contentAlignment = Alignment.Center,
    ) {
        if (img != null) Image(
            img, "Photo", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y },
        )
        if (m.text.isNotEmpty()) Text(
            m.text, style = BodyStyle, color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp)
                .clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------- voice notes

/** The one voice note playing, anywhere in the app. */
private object VoicePlayback {
    val playing = mutableStateOf<String?>(null)
    val progress = mutableFloatStateOf(0f)
    private var mp: MediaPlayer? = null

    fun toggle(id: String, path: String) {
        if (playing.value == id) { stop(); return }
        stop()
        val p = runCatching { MediaPlayer().apply { setDataSource(path); prepare(); start() } }.getOrNull() ?: return
        mp = p
        playing.value = id
        p.setOnCompletionListener { stop() }
    }

    fun tick() { mp?.let { p -> runCatching { if (p.duration > 0) progress.floatValue = p.currentPosition.toFloat() / p.duration } } }

    fun stop() {
        runCatching { mp?.stop(); mp?.release() }
        mp = null
        playing.value = null
        progress.floatValue = 0f
    }
}

@Composable
private fun VoiceNote(m: ChatMsg, fg: Color, time: String) {
    val playing = VoicePlayback.playing.value == m.id
    LaunchedEffect(playing) { while (playing) { VoicePlayback.tick(); delay(50) } }
    val progress = if (playing) VoicePlayback.progress.floatValue else 0f
    // Its waveform: steady for each note (from its id), the part played lit.
    val bars = remember(m.id) {
        var h = m.id.hashCode().toLong()
        List(30) { i -> h = h * 6364136223846793005L + 1442695040888963407L; (0.25f + ((h ushr 33) % 1000) / 1000f * 0.75f) * (1f - abs(i - 15) / 40f) }
    }
    Row(Modifier.padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp).width(230.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(fg.copy(alpha = 0.18f))
                .clickable(enabled = m.file.isNotEmpty()) { VoicePlayback.toggle(m.id, m.file) },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(playing, transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) }, label = "pp") { on ->
                Icon(if (on) BlazeIcons.Pause else BlazeIcons.Play, if (on) "Pause" else "Play", tint = fg, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                val n = bars.size
                val gap = size.width / n
                val bw = gap * 0.55f
                bars.forEachIndexed { i, v ->
                    val bh = size.height * v
                    val lit = (i + 0.5f) / n <= progress
                    drawRoundRect(fg.copy(alpha = if (lit) 1f else 0.4f), Offset(i * gap, (size.height - bh) / 2), Size(bw, bh), CornerRadius(bw / 2))
                }
            }
            Row {
                Text(if (m.file.isEmpty()) "Receiving…" else fmtDur(m.durationMs), style = TextStyle(fontSize = 11.sp), color = fg.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                Text(time, style = TextStyle(fontSize = 11.sp), color = fg.copy(alpha = 0.6f))
            }
        }
    }
}

private fun fmtDur(ms: Long) = (ms / 1000).let { "%d:%02d".format(it / 60, it % 60) }

// ---------------------------------------------------------------------- files

@Composable
private fun FileCard(m: ChatMsg, fg: Color, time: String) {
    val ctx = LocalContext.current
    Row(
        Modifier.width(250.dp).clickable(enabled = m.file.isNotEmpty()) { openFile(ctx, m) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(fg.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(BlazeIcons.File, null, tint = fg, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row {
                Text(if (m.file.isEmpty()) "Receiving…" else formatBytes(m.size), style = TextStyle(fontSize = 11.sp), color = fg.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                Text(time, style = TextStyle(fontSize = 11.sp), color = fg.copy(alpha = 0.6f))
            }
        }
    }
}

private fun openFile(ctx: android.content.Context, m: ChatMsg) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".clip", File(m.file))
        ctx.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, m.mime.ifEmpty { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), m.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

// ---------------------------------------------------------------------- writing

/**
 * The foot: + for a photo or a file, the line to write in, and the button that is the microphone
 * while the line is empty (hold to record, slide left to drop it) and Send once something is written.
 */
@Composable
private fun Composer(
    onSend: (String) -> Unit, onFile: (android.net.Uri, String) -> Unit, onVoice: (File, Long) -> Unit,
    replying: ChatMsg?, replyName: String, onCancelReply: () -> Unit, onTyping: () -> Unit,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var text by remember { mutableStateOf("") }
    var attaching by remember { mutableStateOf(false) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> uris.forEach { onFile(it, "image") } }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach { onFile(it, "file") } }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Recording: since when, how far the finger has slid, and the recorder.
    var recordingSince by remember { mutableLongStateOf(0L) }
    var slide by remember { mutableFloatStateOf(0f) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recFile by remember { mutableStateOf<File?>(null) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recordingSince) { while (recordingSince > 0) { now = System.currentTimeMillis(); delay(100) } }
    val density = LocalDensity.current
    val cancelPx = with(density) { 110.dp.toPx() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(replying?.id) { if (replying != null) runCatching { focus.requestFocus() } }

    fun startRec() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { mic.launch(Manifest.permission.RECORD_AUDIO); return }
        val f = File(ctx.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val r = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder())
        runCatching {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1); r.setAudioSamplingRate(44_100); r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(f.path)
            r.prepare(); r.start()
            recorder = r; recFile = f; recordingSince = System.currentTimeMillis(); slide = 0f
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }.onFailure { runCatching { r.release() } }
    }
    fun stopRec(send: Boolean) {
        val r = recorder ?: return
        val f = recFile
        val ms = System.currentTimeMillis() - recordingSince
        runCatching { r.stop() }
        runCatching { r.release() }
        recorder = null; recordingSince = 0L; slide = 0f
        if (send && f != null && ms >= 700) onVoice(f, ms) else f?.delete()
    }

    Column(Modifier.fillMaxWidth().background(Bridge.Bg).navigationBarsPadding()) {
        // Answering a message: who and what, over the line.
        var lastReply by remember { mutableStateOf<ChatMsg?>(null) }
        replying?.let { lastReply = it }
        AnimatedVisibility(replying != null, enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut()) {
            lastReply?.let { r ->
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(38.dp).clip(RoundedCornerShape(2.dp)).background(Bridge.Accent))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("Replying to $replyName", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Accent, maxLines = 1)
                        Text(Messages.snippetOf(r), style = CaptionStyle, color = Bridge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onCancelReply), contentAlignment = Alignment.Center) {
                        Icon(BlazeIcons.Close, "Cancel reply", tint = Bridge.Muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        // Photo or file, rising over the line.
        AnimatedVisibility(attaching, enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AttachTile(BlazeIcons.Image, "Photo", Color(0xFF5E5CE6)) { attaching = false; photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                AttachTile(BlazeIcons.File, "File", Color(0xFF0A84FF)) { attaching = false; files.launch(arrayOf("*/*")) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            val turn by animateFloatAsState(if (attaching) 45f else 0f, spring(dampingRatio = 0.6f), label = "plus")
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { attaching = !attaching }, contentAlignment = Alignment.Center) {
                Icon(BlazeIcons.Plus, "Attach", tint = Bridge.Text, modifier = Modifier.size(24.dp).rotate(turn))
            }
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(Bridge.Chip).padding(horizontal = 16.dp, vertical = 11.dp)) {
                if (recordingSince > 0) {
                    // Recording: a pulsing dot, the time, and how to drop it.
                    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).graphicsLayer { alpha = pulse }.clip(CircleShape).background(Color(0xFFFF3B30)))
                        Spacer(Modifier.width(8.dp))
                        Text(fmtDur(now - recordingSince), style = TextStyle(fontSize = 16.sp, fontFeatureSettings = "tnum"), color = Bridge.Text)
                        Spacer(Modifier.weight(1f))
                        Text(if (slide < -cancelPx) "Let go to drop it" else "‹ Slide to drop", style = CaptionStyle, color = if (slide < -cancelPx) Color(0xFFFF3B30) else Bridge.Muted,
                            modifier = Modifier.offset { IntOffset((slide / 3).roundToInt(), 0) })
                    }
                } else {
                    if (text.isEmpty()) Text("Message", style = TextStyle(fontSize = 16.sp), color = Bridge.Faint)
                    BasicTextField(
                        text, { if (it.length > text.length) onTyping(); text = it }, textStyle = TextStyle(fontSize = 16.sp, color = Bridge.Text),
                        cursorBrush = SolidColor(Bridge.Accent), maxLines = 6, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            val canSend = text.isNotBlank()
            val bgColor by animateColorAsState(if (canSend || recordingSince > 0) Bridge.Accent else Bridge.Chip, tween(180), label = "send")
            val grow by animateFloatAsState(if (recordingSince > 0) 1.35f else 1f, spring(dampingRatio = 0.55f), label = "grow")
            Box(
                Modifier.size(44.dp).graphicsLayer { scaleX = grow; scaleY = grow }.clip(CircleShape).background(bgColor)
                    .pointerInput(canSend) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (canSend) {
                                val up = waitForUpOrCancel()
                                if (up) { onSend(text); text = "" }
                                return@awaitEachGesture
                            }
                            // Hold to record a voice note; slide left to drop it.
                            startRec()
                            var dx = 0f
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                dx += ch.positionChange().x
                                slide = dx
                                ch.consume()
                            }
                            stopRec(send = dx > -cancelPx)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(canSend, transitionSpec = { (scaleIn(initialScale = 0.5f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.5f) + fadeOut()) }, label = "sendmic") { s ->
                    Icon(if (s) BlazeIcons.Send else BlazeIcons.Mic, if (s) "Send" else "Hold to record", tint = if (s || recordingSince > 0) Color.White else Bridge.Text, modifier = Modifier.size(21.dp))
                }
            }
        }
    }
}

/** Waits for the finger to lift; true when it did (not cancelled). */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForUpOrCancel(): Boolean {
    while (true) {
        val ev = awaitPointerEvent()
        if (ev.changes.all { !it.pressed }) return true
        if (ev.changes.any { it.isConsumed }) return false
    }
}

@Composable
private fun AttachTile(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(70.dp)) {
        Box(Modifier.size(56.dp).pressable(RoundedCornerShape(18.dp), scaleTo = 0.9f, onClick = onClick).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = color, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(label, style = CaptionStyle, color = Bridge.Text)
    }
}

private const val RUN_MS = 5 * 60_000L

private fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }
    val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

private fun dayName(at: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(at, now) -> "Today"
        sameDay(at, now - 86_400_000L) -> "Yesterday"
        now - at < 6 * 86_400_000L -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(at))
        else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(at))
    }
}
