package dev.periy.bridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.periy.bridge.container
import dev.periy.bridge.server.CallMember
import dev.periy.bridge.server.CallState
import dev.periy.bridge.server.Calls
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.roundToInt

/** Whether this phone may record sound for a call. */
fun hasMic(ctx: android.content.Context) =
    ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Whether this phone may use its camera for a call. */
fun hasCamera(ctx: android.content.Context) =
    ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** A name's own colour, the same every time: for its avatar and the call's background. */
private fun hueOf(name: String): Float {
    var h = 0
    for (ch in name) h = h * 31 + ch.code
    return ((h % 360) + 360) % 360f
}

private fun tone(name: String, s: Float, l: Float) = Color.hsl(hueOf(name), s, l)

private fun initials(name: String): String {
    val words = name.split(' ', '-', '_').filter { it.any(Char::isLetterOrDigit) }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        words.size == 1 -> words[0].take(2)
        else -> "?"
    }.uppercase()
}

/**
 * A call, full screen over the app. Ringing: who, pulsing rings, Decline, and Answer (or Voice and
 * Video for a video call). Under way, voice only: the other phone's avatar (or everyone's, in a
 * group), lit while they talk. With pictures: the other phone's picture full screen and this one's
 * in a corner that can be moved, or everyone in a grid; the buttons along the foot (microphone,
 * camera, the other camera, speaker, add someone, end) go away after a moment and come back at a
 * touch.
 */
@Composable
fun CallScreen(answerNow: Boolean, onAnswered: () -> Unit) {
    val ctx = LocalContext.current
    val calls = ctx.container.calls
    val s by calls.state.collectAsState()
    val call = s ?: return
    val local by calls.localVideo.collectAsState()

    // Answering asks for what it needs first: the microphone, and the camera for video.
    var answerVideo by remember { mutableStateOf(false) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { got ->
        if (hasMic(ctx)) calls.answer(answerVideo && hasCamera(ctx)) else calls.hangUp()
    }
    val answer = { video: Boolean ->
        answerVideo = video
        val need = listOfNotNull(
            Manifest.permission.RECORD_AUDIO.takeIf { !hasMic(ctx) },
            Manifest.permission.CAMERA.takeIf { video && !hasCamera(ctx) },
        )
        if (need.isEmpty()) calls.answer(video) else perms.launch(need.toTypedArray())
    }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) calls.setCamera(true) }
    val toggleCamera = { if (call.camera) calls.setCamera(false) else if (hasCamera(ctx)) calls.setCamera(true) else camPerm.launch(Manifest.permission.CAMERA) }
    // Answer, from the notification: it opened the app to do it.
    LaunchedEffect(answerNow, call.id) { if (answerNow && call.phase == "ringing") { answer(call.video); onAnswered() } }
    BackHandler { }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) { while (call.phase == "active") { now = System.currentTimeMillis(); delay(500) } }
    val status = when (call.phase) {
        "calling" -> if (call.why == "Ringing") "Ringing…" else "Calling…"
        "ringing" -> if (call.video) "Video call" else "Voice call"
        "connecting" -> if (call.test) "Starting the test…" else "Connecting…"
        "active" -> ((now - call.since).coerceAtLeast(0) / 1000).let { if (it >= 3600) "%d:%02d:%02d".format(it / 3600, it / 60 % 60, it % 60) else "%d:%02d".format(it / 60, it % 60) }
        else -> call.why.ifEmpty { "Call ended" }
    }
    val inCall = call.members.filter { it.phase != "left" && it.phase != "invited" }
    val pictures = call.camera || inCall.any { it.camera && it.video != null }
    var adding by remember { mutableStateOf(false) }

    // The background: the first person's colour, deep, down to black.
    val lead = call.members.firstOrNull()?.name ?: call.peer
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tone(lead, 0.45f, 0.16f), Color(0xFF07070A), Color.Black))),
    ) {
        if (call.phase == "ringing" && !call.outgoing) {
            Ringing(call, onDecline = { calls.hangUp() }, onAnswer = answer)
        } else if (pictures && call.phase != "ended") {
            VideoStage(call, local, status, calls, toggleCamera, onAdd = { adding = true })
        } else {
            VoiceStage(call, status, calls, toggleCamera, onAdd = { adding = true })
        }
        AnimatedVisibility(adding, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            AddSheet(call, onPick = { calls.add(it); adding = false }, onClose = { adding = false })
        }
    }
}

// ---------------------------------------------------------------------- ringing

@Composable
private fun Ringing(call: CallState, onDecline: () -> Unit, onAnswer: (Boolean) -> Unit) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val host = call.members.firstOrNull()?.name ?: call.peer
    val others = call.members.drop(1).map { it.name }
    Column(
        Modifier.fillMaxSize().padding(top = top + 64.dp, bottom = bottom + 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (call.video) "Video call" else "Voice call", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp), color = Color.White.copy(alpha = 0.65f))
        Spacer(Modifier.height(36.dp))
        Box(contentAlignment = Alignment.Center) {
            Ripples(tone(host, 0.6f, 0.6f), 132.dp)
            Avatar(host, 132.dp)
        }
        Spacer(Modifier.height(28.dp))
        Text(host, style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold), color = Color.White, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 28.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            if (others.isEmpty()) "Phone to phone · end-to-end encrypted" else "With " + others.joinToString(", "),
            style = TextStyle(fontSize = 15.sp), color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            BigButton(BlazeIcons.Call, "Decline", Color(0xFFFF3B30), rotate = true, onClick = onDecline)
            if (call.video) BigButton(BlazeIcons.Call, "Voice", Color(0xFF34C759).copy(alpha = 0.22f), tint = Color(0xFF34C759)) { onAnswer(false) }
            BigButton(if (call.video) BlazeIcons.Video else BlazeIcons.Call, if (call.video) "Video" else "Answer", Color(0xFF34C759), bounce = true) { onAnswer(call.video) }
        }
    }
}

/** Rings going out from the avatar while it rings or calls. */
@Composable
private fun Ripples(color: Color, size: Dp) {
    val t = rememberInfiniteTransition(label = "ripples")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "p")
    Canvas(Modifier.size(size * 2.1f)) {
        val base = size.toPx() / 2
        for (k in 0 until 3) {
            val f = (p + k / 3f) % 1f
            drawCircle(color.copy(alpha = (1f - f) * 0.35f), base + f * base * 1.05f, style = Stroke(2.dp.toPx() * (1.4f - f)))
        }
    }
}

/** A person's avatar: their initials on their own colour, lit by a ring while they talk. */
@Composable
private fun Avatar(name: String, size: Dp, speaking: Boolean = false) {
    val ring by animateFloatAsState(if (speaking) 1f else 0f, tween(180), label = "speak")
    Box(
        Modifier.size(size)
            .graphicsLayer { val k = 1f + 0.04f * ring; scaleX = k; scaleY = k }
            .border(3.dp * ring, Color(0xFF34C759).copy(alpha = ring), CircleShape)
            .padding(5.dp * ring)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(tone(name, 0.62f, 0.62f), tone(name, 0.7f, 0.38f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(name), style = TextStyle(fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.SemiBold), color = Color.White)
    }
}

// ---------------------------------------------------------------------- voice

@Composable
private fun VoiceStage(call: CallState, status: String, calls: Calls, toggleCamera: () -> Unit, onAdd: () -> Unit) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val people = call.members.filter { it.phase != "left" }
    Column(
        Modifier.fillMaxSize().padding(top = top + 56.dp, bottom = bottom + 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (people.size <= 1) {
            val one = people.firstOrNull()
            val name = one?.name ?: call.peer
            Box(contentAlignment = Alignment.Center) {
                if (call.phase == "calling" || call.phase == "connecting") Ripples(tone(name, 0.6f, 0.6f), 120.dp)
                Avatar(name, 120.dp, speaking = one?.speaking == true)
            }
            Spacer(Modifier.height(24.dp))
            Text(name, style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold), color = Color.White, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
        } else {
            Text(people.joinToString(", ") { it.name }, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold), color = Color.White,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(status, style = TextStyle(fontSize = 17.sp, fontFeatureSettings = "tnum"), color = Color.White.copy(alpha = 0.72f))
        Spacer(Modifier.height(4.dp))
        Sealed(call)
        if (people.size > 1) {
            Spacer(Modifier.height(36.dp))
            // Everyone in the call, two to a row, lit while they talk; phones still ringing are faint.
            Column(verticalArrangement = Arrangement.spacedBy(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                people.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        row.forEach { m ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(112.dp).graphicsLayer { alpha = if (m.phase == "invited") 0.5f else 1f }) {
                                Box(contentAlignment = Alignment.BottomEnd) {
                                    Avatar(m.name, 88.dp, speaking = m.speaking)
                                    if (!m.mic) MutedBadge()
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(m.name, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(memberStatus(m), style = TextStyle(fontSize = 12.sp), color = Color.White.copy(alpha = 0.55f), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (call.phase != "ended") Controls(call, calls, toggleCamera, onAdd, overVideo = false)
    }
}

private fun memberStatus(m: CallMember) = when (m.phase) {
    "invited" -> "Ringing…"
    "joining" -> "Joining…"
    "connected" -> if (!m.mic) "Muted" else ""
    else -> "Left"
}

@Composable
private fun MutedBadge() {
    Box(Modifier.size(26.dp).clip(CircleShape).background(Color(0xFF2C2C2E)).border(2.dp, Color.Black, CircleShape), contentAlignment = Alignment.Center) {
        Icon(BlazeIcons.MicOff, "Muted", tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

/** How the call goes, and that only the phones in it can hear it. */
@Composable
private fun Sealed(call: CallState) {
    if (call.phase != "active") return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(BlazeIcons.Lock, null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            if (call.test) "What you hear and see came back through the call"
            else (if (call.path.isNotEmpty()) call.path + " · " else "") + (if (call.relay) "through ${if (call.host) "this phone" else call.members.firstOrNull()?.name ?: "the host"} · " else "") + "end-to-end encrypted",
            style = TextStyle(fontSize = 12.sp), color = Color.White.copy(alpha = 0.45f),
        )
    }
}

// ---------------------------------------------------------------------- video

@Composable
private fun VideoStage(call: CallState, local: VideoTrack?, status: String, calls: Calls, toggleCamera: () -> Unit, onAdd: () -> Unit) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val others = call.members.filter { it.phase != "left" && it.phase != "invited" }
    var shown by remember { mutableStateOf(true) }
    var touched by remember { mutableLongStateOf(0L) }
    // The buttons go after a few seconds of nothing, and come back at a touch.
    LaunchedEffect(shown, touched) { if (shown && call.phase == "active") { delay(4_500); shown = false } }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { shown = !shown; touched = System.currentTimeMillis() } },
    ) {
        val hostPicture = if (call.relay && !call.host) others.firstOrNull()?.video else null
        if (hostPicture != null) {
            // A big call: everyone in the one picture the host sends, shown whole.
            VideoView(hostPicture, mirror = false, fit = true, modifier = Modifier.fillMaxSize())
            if (local != null) SelfView(local, call.frontCamera, top, bottom)
        } else if (others.size <= 1) {
            val o = others.firstOrNull()
            // The other phone full screen; their avatar while their camera is off.
            if (o != null && o.camera && o.video != null) VideoView(o.video, mirror = false, modifier = Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val name = o?.name ?: call.peer
                    Box(contentAlignment = Alignment.Center) {
                        if (call.phase != "active") Ripples(tone(name, 0.6f, 0.6f), 120.dp)
                        Avatar(name, 120.dp, speaking = o?.speaking == true)
                    }
                    if (o != null && call.phase == "active") {
                        Spacer(Modifier.height(14.dp))
                        Text("Camera off", style = TextStyle(fontSize = 14.sp), color = Color.White.copy(alpha = 0.55f))
                    }
                }
            }
            if (local != null) SelfView(local, call.frontCamera, top, bottom)
        } else {
            Grid(others, local, call, Modifier.fillMaxSize().padding(top = top + 64.dp, bottom = bottom + 120.dp, start = 8.dp, end = 8.dp))
        }

        // Along the top: who, and the time; along the foot, the buttons. Both over a soft shade.
        AnimatedVisibility(shown || call.phase != "active", enter = fadeIn(tween(220)), exit = fadeOut(tween(300)), modifier = Modifier.align(Alignment.TopCenter)) {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                    .padding(top = top + 14.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(call.peer, style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(3.dp))
                Text(status, style = TextStyle(fontSize = 14.sp, fontFeatureSettings = "tnum"), color = Color.White.copy(alpha = 0.75f))
                Spacer(Modifier.height(2.dp))
                Sealed(call)
            }
        }
        AnimatedVisibility(
            shown || call.phase != "active",
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 3 },
            exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                    .padding(top = 30.dp, bottom = bottom + 22.dp),
                contentAlignment = Alignment.Center,
            ) { Controls(call, calls, toggleCamera, onAdd, overVideo = true) }
        }
    }
}

/** This phone's own picture, small in a corner, rounded, moved with a finger to whichever corner it is let go nearest. */
@Composable
private fun SelfView(track: VideoTrack, front: Boolean, top: Dp, bottom: Dp) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = 108.dp; val h = 160.dp; val m = 16.dp
        val maxX = with(density) { (maxWidth - w - m * 2).toPx() }
        val maxY = with(density) { (maxHeight - h - top - bottom - 150.dp).toPx() }
        val scope = rememberCoroutineScope()
        val x = remember { Animatable(maxX) }
        val y = remember { Animatable(0f) }
        Box(
            Modifier
                .offset { IntOffset((with(density) { m.toPx() } + x.value).roundToInt(), (with(density) { (top + 76.dp).toPx() } + y.value).roundToInt()) }
                .size(w, h)
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragEnd = {
                            scope.launch { x.animateTo(if (x.value > maxX / 2) maxX else 0f, spring(dampingRatio = 0.75f, stiffness = 500f)) }
                            scope.launch { y.animateTo(if (y.value > maxY / 2) maxY else 0f, spring(dampingRatio = 0.75f, stiffness = 500f)) }
                        },
                    ) { change, d ->
                        change.consume()
                        scope.launch { x.snapTo((x.value + d.x).coerceIn(0f, maxX)) }
                        scope.launch { y.snapTo((y.value + d.y).coerceIn(0f, maxY)) }
                    }
                },
        ) {
            VideoView(track, mirror = front, overlay = true, corner = 18.dp, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Everyone in a group call with pictures: two stacked, three as one over two, four as two by two; this phone among them. */
@Composable
private fun Grid(others: List<CallMember>, local: VideoTrack?, call: CallState, modifier: Modifier) {
    class Spec(val key: String, val name: String, val track: VideoTrack?, val mirror: Boolean, val speaking: Boolean, val muted: Boolean, val note: String)
    val tiles = others.map { m -> Spec(m.id, m.name, if (m.camera) m.video else null, false, m.speaking, !m.mic, memberStatus(m)) } +
        Spec("me", "You", local, call.frontCamera, call.speaking, call.muted, "")
    val gap = 8.dp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        val rows = when (tiles.size) {
            1, 2 -> tiles.map { listOf(it) }
            3 -> listOf(listOf(tiles[0]), tiles.drop(1))
            in 4..6 -> tiles.chunked(2)
            else -> tiles.chunked(3)
        }
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { t ->
                    androidx.compose.runtime.key(t.key) { Tile(t.name, t.track, t.mirror, t.speaking, t.muted, t.note, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}

@Composable
private fun Tile(name: String, track: VideoTrack?, mirror: Boolean, speaking: Boolean, muted: Boolean, note: String, modifier: Modifier) {
    val edge by animateColorAsState(if (speaking) Color(0xFF34C759) else Color.Transparent, tween(160), label = "edge")
    Box(
        modifier.clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(tone(name, 0.35f, 0.22f), Color(0xFF111114))))
            .border(3.dp, edge, RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (track != null) VideoView(track, mirror = mirror, corner = 22.dp, modifier = Modifier.fillMaxSize())
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(name, 72.dp, speaking = false)
            if (note.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(note, style = TextStyle(fontSize = 12.sp), color = Color.White.copy(alpha = 0.6f)) }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (muted) { Icon(BlazeIcons.MicOff, "Muted", tint = Color.White, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(5.dp)) }
            Text(name, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp))
        }
    }
}

/** A picture from a call: WebRTC's renderer, on the call's drawing context, rounded when asked. */
@Composable
private fun VideoView(track: VideoTrack, mirror: Boolean, modifier: Modifier, overlay: Boolean = false, corner: Dp = 0.dp, fit: Boolean = false) {
    val calls = LocalContext.current.container.calls
    val radius = with(LocalDensity.current) { corner.toPx() }
    var view by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        factory = { c ->
            SurfaceViewRenderer(c).apply {
                init(calls.egl.eglBaseContext, null)
                setScalingType(if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (overlay) setZOrderMediaOverlay(true)
                if (radius > 0f) {
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, radius) }
                    }
                    clipToOutline = true
                }
                view = this
            }
        },
        update = { it.setMirror(mirror) },
        onRelease = { it.release() },
        modifier = modifier,
    )
    DisposableEffect(track, view) {
        val v = view
        if (v != null) runCatching { track.addSink(v) }
        onDispose { if (v != null) runCatching { track.removeSink(v) } }
    }
}

// ---------------------------------------------------------------------- the buttons

@Composable
private fun Controls(call: CallState, calls: Calls, toggleCamera: () -> Unit, onAdd: () -> Unit, overVideo: Boolean) {
    val canAdd = call.host && call.members.count { it.phase != "left" } + 1 < Calls.MAX
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.padding(horizontal = 14.dp).then(
                if (overVideo) Modifier else Modifier.clip(RoundedCornerShape(34.dp)).background(Color.White.copy(alpha = 0.07f)).padding(horizontal = 6.dp, vertical = 10.dp),
            ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToggleButton(if (call.muted) BlazeIcons.MicOff else BlazeIcons.Mic, if (call.muted) "Unmute" else "Mute", call.muted) { calls.setMuted(!call.muted) }
            ToggleButton(if (call.camera) BlazeIcons.Video else BlazeIcons.VideoOff, "Camera", call.camera, onClick = toggleCamera)
            if (call.camera) ToggleButton(BlazeIcons.FlipCamera, "Flip", false) { calls.flipCamera() }
            ToggleButton(BlazeIcons.VolumeUp, "Speaker", call.speaker) { calls.setSpeaker(!call.speaker) }
            if (canAdd) ToggleButton(BlazeIcons.PersonAdd, "Add", false, onClick = onAdd)
        }
        Spacer(Modifier.height(22.dp))
        BigButton(BlazeIcons.Call, "End", Color(0xFFFF3B30), rotate = true, small = true) { calls.hangUp() }
    }
}

/** A round button that is lit (white) while its thing is on. */
@Composable
private fun ToggleButton(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) Color.White else Color.White.copy(alpha = 0.14f), tween(180), label = "bg")
    val fg by animateColorAsState(if (on) Color.Black else Color.White, tween(180), label = "fg")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp)) {
        Box(Modifier.size(54.dp).pressable(CircleShape, scaleTo = 0.88f, onClick = onClick).background(bg), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = fg, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = TextStyle(fontSize = 11.5.sp), color = Color.White.copy(alpha = 0.78f), maxLines = 1)
    }
}

@Composable
private fun BigButton(
    icon: ImageVector, label: String, bg: Color, tint: Color = Color.White, rotate: Boolean = false,
    small: Boolean = false, bounce: Boolean = false, onClick: () -> Unit,
) {
    val t = rememberInfiniteTransition(label = "bounce")
    val lift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "lift")
    val size = if (small) 66.dp else 76.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.graphicsLayer { if (bounce) translationY = -6.dp.toPx() * lift }
                .size(size).pressable(CircleShape, scaleTo = 0.9f, onClick = onClick).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(if (small) 28.dp else 32.dp).then(if (rotate) Modifier.rotate(135f) else Modifier))
        }
        if (!small) {
            Spacer(Modifier.height(10.dp))
            Text(label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = Color.White.copy(alpha = 0.85f))
        }
    }
}

// ---------------------------------------------------------------------- adding someone

/** The linked phones not in the call yet, to add one; up to four phones in a call. */
@Composable
private fun AddSheet(call: CallState, onPick: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val linked by ctx.container.peers.peers.collectAsState()
    val inCall = call.members.filter { it.phase != "left" }.map { it.name }.toSet()
    val choices = linked.map { it.name }.filter { it !in inCall }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Color(0xFF1C1C1E))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(top = 10.dp, bottom = bottom + 16.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
            Spacer(Modifier.height(14.dp))
            Text("Add to call", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold), color = Color.White, modifier = Modifier.padding(horizontal = 22.dp))
            Text("Up to ${Calls.MAX} phones in a call, each straight to the others.", style = TextStyle(fontSize = 13.sp), color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
            Spacer(Modifier.height(8.dp))
            if (choices.isEmpty()) Text("Every linked phone is in the call already.", style = TextStyle(fontSize = 15.sp), color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(22.dp))
            choices.forEach { name ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(name) }.padding(horizontal = 22.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(name, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(name, style = TextStyle(fontSize = 17.sp), color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF34C759)), contentAlignment = Alignment.Center) {
                        Icon(BlazeIcons.Call, "Add $name", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
