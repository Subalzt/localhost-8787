package dev.periy.bridge.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.periy.bridge.container
import dev.periy.bridge.server.CamClip
import dev.periy.bridge.server.CamDto
import dev.periy.bridge.server.CamSet
import dev.periy.bridge.server.CamState
import dev.periy.bridge.server.Cameras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val camJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * The cameras' control centre (server/Cameras.kt), over the lock screen too: this phone as a camera,
 * and every linked phone's camera, live, with what each can do. From the Devices tab, the Cameras
 * tile in Quick Settings, and a movement alert.
 */
class CamerasActivity : ComponentActivity() {
    private var focus by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        focus = intent.getStringExtra("camera")
        setContent { BlazeTheme("dark") { CamerasScreen(focus, { focus = it }) { finish() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("camera")?.let { focus = it }
    }
}

/** Quick Settings: the cameras, from anywhere, the lock screen too. */
class CamTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (container.cameras.state.value.on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Cameras"
            subtitle = if (container.cameras.state.value.on) "This phone is one" else "Watch your phones"
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, CamerasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") startActivityAndCollapse(intent)
    }
}

// ---------------------------------------------------------------------- talking to cameras

/** A change to a camera: this phone's own, or a linked phone's, asked of it. Null when done, else why not. */
private suspend fun camSet(ctx: android.content.Context, target: String, c: CamSet): String? = withContext(Dispatchers.IO) {
    val cams = ctx.container.cameras
    if (target == "self") return@withContext cams.set(c)
    val peers = ctx.container.peers
    val p = peers.find(target) ?: return@withContext "$target is not linked"
    if (peers.deliver(p, "/api/cams/set", camJson.encodeToString(c.copy(target = "self")).toByteArray())) null
    else "$target did not take it (allow its camera there first?)"
}

private suspend fun camClips(ctx: android.content.Context, target: String): List<CamClip> = withContext(Dispatchers.IO) {
    if (target == "self") return@withContext ctx.container.cameras.clips()
    val p = ctx.container.peers.find(target) ?: return@withContext emptyList()
    ctx.container.peers.fetch(p, "/api/cams/clips")?.let { runCatching { camJson.decodeFromString<List<CamClip>>(it) }.getOrNull() }.orEmpty()
}

/** A clip's file or cover: this phone's own, or fetched from the camera phone into the cache. */
private suspend fun camClipFile(ctx: android.content.Context, target: String, id: String, thumb: Boolean): File? = withContext(Dispatchers.IO) {
    if (target == "self") return@withContext ctx.container.cameras.clipFile(id, thumb)
    val f = File(ctx.cacheDir, "cams/${target.hashCode()}-$id" + if (thumb) ".jpg" else ".mp4")
    if (f.exists() && f.length() > 0) return@withContext f
    val p = ctx.container.peers.find(target) ?: return@withContext null
    runCatching {
        f.parentFile?.mkdirs()
        val c = ctx.container.peers.openGet(p, "/api/cams/clip?id=$id" + if (thumb) "&thumb=1" else "", null)
        if (c.responseCode != 200) { c.disconnect(); return@runCatching null }
        c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        c.disconnect()
        f
    }.getOrNull()
}

// ---------------------------------------------------------------------- the screen

@Composable
private fun CamerasScreen(focus: String?, setFocus: (String?) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cams = ctx.container.cameras
    val me by cams.state.collectAsStateWithLifecycle()
    val list by produceState(emptyList<CamDto>()) {
        while (true) {
            value = runCatching { cams.all().cams.filter { !it.self } }.getOrDefault(value)
            delay(4_000)
        }
    }
    val full = focus?.let { f -> list.firstOrNull { it.target == f } }
    BackHandler(focus != null) { setFocus(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text("Cameras", style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold), color = Color.White, modifier = Modifier.weight(1f))
                    RoundIcon(BlazeIcons.Close, "Close", onClick = onClose)
                }
            }
            if (list.isEmpty()) item {
                Text(
                    "Link another phone (Devices, Phones) and turn on camera mode there, or here, to watch it from your other devices.",
                    style = BodyStyle, color = Color.White.copy(alpha = 0.6f),
                )
            }
            items(list, key = { it.target }) { c -> CamTile(c) { setFocus(c.target) } }
            item { SelfCard(me) }
        }
        AnimatedVisibility(full != null, enter = fadeIn(), exit = fadeOut()) {
            var last by remember { mutableStateOf<CamDto?>(null) }
            full?.let { last = it }
            last?.let { CamFull(it) { setFocus(null) } }
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, on: Boolean = false, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = 0.14f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (on) Color.Black else tint, modifier = Modifier.size(21.dp)) }
}

private fun ago(at: Long): String {
    if (at <= 0) return ""
    val s = (System.currentTimeMillis() - at) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(at))
    }
}

private fun statusLine(s: CamState?): String = when {
    s == null -> "Not reachable now"
    s.waking -> "Waking it to start the camera…"
    s.error.isNotEmpty() -> s.error
    !s.on -> "Camera mode off"
    !s.running -> "Starting…"
    else -> listOfNotNull(
        if (s.recording) "Recording" else "Live",
        s.lastMotion.takeIf { it > 0 }?.let { "movement " + ago(it) },
        s.battery.takeIf { it >= 0 }?.let { "$it%" + if (s.charging) " charging" else "" },
    ).joinToString(" · ")
}

/** A linked phone's camera in the list: live when it runs, else what it is doing and a way to turn it on. */
@Composable
private fun CamTile(c: CamDto, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = c.state
    var said by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF151517)).clickable(enabled = s?.running == true, onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF0B0B0C)), contentAlignment = Alignment.Center) {
            if (s?.on == true && s.running) CamVideo(c.target, listen = false, rotation = s.rotation, modifier = Modifier.fillMaxSize())
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(BlazeIcons.Video, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(34.dp))
                if (s != null && !s.on) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Turn on", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = Color.Black,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White)
                            .clickable { scope.launch { said = camSet(ctx, c.target, CamSet(on = true)) ?: "Asked: it starts in a few seconds" } }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
            if (s?.recording == true) Box(Modifier.align(Alignment.TopStart).padding(12.dp).size(10.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(c.name, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(said.ifEmpty { statusLine(s) }, style = CaptionStyle, color = Color.White.copy(alpha = 0.55f), maxLines = 2)
        }
    }
}

/** This phone as a camera: camera mode and what it does. */
@Composable
private fun SelfCard(s: CamState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf("") }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { got ->
        if (got[Manifest.permission.CAMERA] == true) scope.launch { said = camSet(ctx, "self", CamSet(on = true)).orEmpty() }
        else said = "Camera mode needs the camera allowed."
    }
    fun set(c: CamSet) { scope.launch { said = camSet(ctx, "self", c).orEmpty() } }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF151517)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("This phone as a camera", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
                Text(said.ifEmpty { if (s.on) statusLine(s) + if (s.viewers > 0) " · ${s.viewers} watching" else "" else "Your other phones and laptops can watch it, even locked." },
                    style = CaptionStyle, color = Color.White.copy(alpha = 0.55f))
            }
            Switch(
                s.on,
                {
                    if (!it) set(CamSet(on = false))
                    else {
                        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                            .filter { p -> ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED }
                        if (need.isEmpty()) set(CamSet(on = true)) else ask.launch(need.toTypedArray())
                    }
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF34C759)),
            )
        }
        Spacer(Modifier.height(10.dp))
        Toggle("Motion alerts", "Your other devices are told, with a picture", s.motion) { set(CamSet(motion = it)) }
        Toggle("Record clips on movement", "With the few seconds before it; 2 GB kept", s.record) { set(CamSet(record = it)) }
        Toggle("Sound", "To listen in, and in clips", s.sound) { set(CamSet(sound = it)) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Back camera", s.lens == "back") { set(CamSet(lens = "back")) }
            Choice("Front camera", s.lens == "front") { set(CamSet(lens = "front")) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "Less sensitive", 2 to "Normal", 3 to "More sensitive").forEach { (n, l) -> Choice(l, s.sensitivity == n) { set(CamSet(sensitivity = n)) } }
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontSize = 15.sp), color = Color.White)
            Text(sub, style = CaptionStyle.copy(fontSize = 12.sp), color = Color.White.copy(alpha = 0.45f))
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF34C759)))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Choice(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium), color = if (on) Color.Black else Color.White,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** One camera full screen: listen, hold to talk, torch, flip, turn, motion, record, clips, off. */
@Composable
private fun CamFull(c: CamDto, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = c.state ?: CamState()
    var listen by remember { mutableStateOf(false) }
    var talking by remember { mutableStateOf(false) }
    var clips by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf("") }
    fun set(x: CamSet) { scope.launch { said = camSet(ctx, c.target, x).orEmpty() } }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Pinch to zoom, in the camera itself (a laptop's webcam has none): sent as it settles.
        var zoom by remember(c.target) { mutableStateOf(s.zoom) }
        LaunchedEffect(zoom) { if (zoom != s.zoom) { delay(150); set(CamSet(zoom = zoom)) } }
        val pinch = if (c.kind == "laptop" || s.maxZoom <= 1f) Modifier else Modifier.pointerInput(c.target, s.maxZoom) {
            detectTransformGestures { _, _, z, _ -> zoom = (zoom * z).coerceIn(1f, s.maxZoom) }
        }
        if (s.running) CamVideo(c.target, listen = listen && !talking, rotation = s.rotation, modifier = Modifier.fillMaxSize().then(pinch))
        if (zoom > 1.05f) Text(
            "%.1f×".format(zoom), style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = Color.White,
            modifier = Modifier.align(Alignment.Center).padding(top = 140.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
                .clickable { zoom = 1f }.padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(c.name, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
                Text(said.ifEmpty { statusLine(s) }, style = CaptionStyle, color = Color.White.copy(alpha = 0.7f))
            }
            RoundIcon(BlazeIcons.Close, "Close", onClick = onClose)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // A laptop's webcam: watch and listen only.
            val laptop = c.kind == "laptop"
            // Hold to talk: this phone's voice out of the camera phone's speaker.
            if (!laptop) Box(
                Modifier.size(76.dp).clip(CircleShape).background(if (talking) Color(0xFF34C759) else Color.White.copy(alpha = 0.18f))
                    .pointerInput(c.target) {
                        awaitEachGesture {
                            awaitFirstDown()
                            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { mic.launch(Manifest.permission.RECORD_AUDIO); return@awaitEachGesture }
                            talking = true
                            val job = scope.launch(Dispatchers.IO) { talk(ctx, c.target) { talking } }
                            waitForUpOrCancellation()
                            talking = false
                            scope.launch { delay(400); job.cancel() }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(BlazeIcons.Mic, "Hold to talk", tint = Color.White, modifier = Modifier.size(30.dp)) }
            if (!laptop) Text(if (talking) "Talking…" else "Hold to talk", style = CaptionStyle, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundIcon(if (listen) BlazeIcons.VolumeUp else BlazeIcons.Mute, "Listen", on = listen) { listen = !listen }
                if (!laptop) {
                    RoundIcon(BlazeIcons.Bolt, "Torch", on = s.torch) { set(CamSet(torch = !s.torch)) }
                    RoundIcon(BlazeIcons.FlipCamera, "Flip") { set(CamSet(lens = if (s.lens == "back") "front" else "back")) }
                    RoundIcon(BlazeIcons.Refresh, "Turn the picture") { set(CamSet(rotate = s.rotate + 90)) }
                    RoundIcon(BlazeIcons.Pulse, "Motion alerts", on = s.motion) { set(CamSet(motion = !s.motion)) }
                    RoundIcon(BlazeIcons.Moon, "Night", on = s.night) { set(CamSet(night = !s.night)) }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (!laptop) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(if (s.recording) "Stop recording" else "Record", if (s.recording) Color(0xFFFF3B30) else Color.White.copy(alpha = 0.14f)) { set(CamSet(recordNow = !s.recording)) }
                Pill("Clips", Color.White.copy(alpha = 0.14f)) { clips = true }
                Pill("Turn off", Color.White.copy(alpha = 0.14f)) { set(CamSet(on = false)); onClose() }
            }
        }
        AnimatedVisibility(clips, enter = fadeIn(), exit = fadeOut()) { ClipsSheet(c) { clips = false } }
    }
    BackHandler(clips) { clips = false }
}

@Composable
private fun Pill(label: String, bg: Color, onClick: () -> Unit) {
    Text(
        label, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/** A camera's clips, newest first: a tap plays one (fetched from the camera phone first). */
@Composable
private fun ClipsSheet(c: CamDto, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<CamClip>?>(null) }
    var playing by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf("") }
    LaunchedEffect(c.target) { list = camClips(ctx, c.target) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Clips · ${c.name}", style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold), color = Color.White, modifier = Modifier.weight(1f))
                RoundIcon(BlazeIcons.Close, "Close", onClick = onClose)
            }
            if (busy.isNotEmpty()) Text(busy, style = CaptionStyle, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 16.dp))
            val l = list
            if (l == null) Text("Asking ${c.name}…", style = BodyStyle, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(16.dp))
            else if (l.isEmpty()) Text("No clips yet.", style = BodyStyle, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(16.dp))
            else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(l, key = { it.id }) { clip ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF151517)).clickable {
                            scope.launch {
                                busy = "Fetching the clip…"
                                playing = camClipFile(ctx, c.target, clip.id, false)
                                busy = if (playing == null) "Could not fetch it." else ""
                            }
                        }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val thumb by produceState<android.graphics.Bitmap?>(null, clip.id) {
                            value = camClipFile(ctx, c.target, clip.id, true)?.let { f -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path) } }
                        }
                        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B0B0C))) {
                            thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(SimpleDateFormat("EEE d MMM, HH:mm:ss", Locale.getDefault()).format(Date(clip.at)), style = TextStyle(fontSize = 15.sp), color = Color.White)
                            Text((if (clip.motion) "Movement · " else "By hand · ") + "%d:%02d".format(clip.ms / 60000, clip.ms / 1000 % 60) + " · " + dev.periy.bridge.service.formatBytes(clip.size),
                                style = CaptionStyle, color = Color.White.copy(alpha = 0.55f))
                        }
                    }
                }
            }
        }
        playing?.let { f ->
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { playing = null }) {
                AndroidView({ cx -> android.widget.VideoView(cx).apply { setVideoPath(f.path); setMediaController(android.widget.MediaController(cx)); start() } },
                    Modifier.fillMaxSize())
            }
            BackHandler { playing = null }
        }
    }
}

// ---------------------------------------------------------------------- the live picture and sound

/**
 * A camera's live stream on a TextureView: each picture whole (its length first) into the phone's
 * hardware decoder, the sound (its length's top bit set) into an AAC decoder when listening; the
 * picture turned upright and fitted.
 */
@Composable
fun CamVideo(target: String, listen: Boolean, rotation: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val holder = remember(target) { CamPlayer(ctx.applicationContext, target) }
    holder.listen = listen
    holder.rotation = rotation
    DisposableEffect(holder) { onDispose { holder.stop() } }
    AndroidView({ cx -> TextureView(cx).also { holder.attach(it) } }, modifier)
}

private class CamPlayer(private val app: android.content.Context, private val target: String) : TextureView.SurfaceTextureListener {
    @Volatile var listen = false
    @Volatile var rotation = 0
        set(v) { field = v; view?.post { fit() } }
    private var view: TextureView? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var running = true
    private var thread: Thread? = null
    private var conn: java.net.HttpURLConnection? = null
    @Volatile private var vw = 0
    @Volatile private var vh = 0

    fun attach(v: TextureView) {
        view = v
        v.surfaceTextureListener = this
        if (v.isAvailable) v.surfaceTexture?.let { onSurfaceTextureAvailable(it, v.width, v.height) }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(st)
        if (thread == null) thread = Thread({ run() }, "cam-view").apply { isDaemon = true; start() }
        fit()
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { fit() }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { stop(); return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    fun stop() {
        running = false
        runCatching { conn?.disconnect() }
        thread?.interrupt()
    }

    /** Upright and fitted: the picture (stretched to the view by default) turned about its middle. */
    private fun fit() {
        val v = view ?: return
        val w = vw.toFloat(); val h = vh.toFloat()
        val W = v.width.toFloat(); val H = v.height.toFloat()
        if (w <= 0 || h <= 0 || W <= 0 || H <= 0) return
        val turned = rotation % 180 != 0
        val cw = if (turned) h else w
        val ch = if (turned) w else h
        val k = minOf(W / cw, H / ch)
        v.setTransform(Matrix().apply { setScale(w * k / W, h * k / H, W / 2, H / 2); postRotate(rotation.toFloat(), W / 2, H / 2) })
    }

    /** This stream's name at the camera, for saying what has arrived; and how much has. */
    @Volatile private var vid = ""
    /** When the picture being decoded was taken (the camera's clock, µs). */
    private var pts = 0L
    @Volatile private var got = 0L

    private fun open(): InputStream? {
        if (target == "self") return null
        // A laptop's webcam: straight from its helper, here.
        if (target.startsWith(dev.periy.bridge.server.LaptopCams.PREFIX)) {
            vid = ""
            got = 0
            return dev.periy.bridge.server.LaptopCams.open(target.removePrefix(dev.periy.bridge.server.LaptopCams.PREFIX), far = false, listen = listen)
        }
        val p = app.container.peers.find(target) ?: return null
        vid = java.util.UUID.randomUUID().toString().take(12)
        got = 0
        val c = app.container.peers.openGet(p, "/api/cams/stream?v=$vid" + if (listen) "&listen=1" else "", null)
        c.readTimeout = 15_000
        conn = c
        return if (c.responseCode == 200) c.inputStream else { c.disconnect(); null }
    }

    /** Says what has reached here, a few times a second, so the camera never lets this fall behind. */
    private fun acks(session: String) = Thread({
        var last = -1L
        val p = app.container.peers.find(target) ?: return@Thread
        while (running && vid == session) {
            runCatching { Thread.sleep(300) }
            val n = got
            if (n != last) { last = n; runCatching { app.container.peers.deliverBytes(p, "/api/cams/ack?v=$session&n=$n", ByteArray(0)) } }
        }
    }, "cam-ack").apply { isDaemon = true; start() }

    /**
     * When each picture shows: at the pace it was taken, a little behind, the delay growing only as
     * far as the way's unevenness needs and shrinking again when it calms.
     */
    private var base = 0L
    private fun due(ptsUs: Long): Long {
        val now = System.nanoTime()
        val pts = ptsUs * 1000
        if (base == 0L) base = now + 100_000_000L - pts
        var due = base + pts
        if (due < now) { base += now - due + 30_000_000L; due = base + pts }
        else if (due - now > 800_000_000L) { base -= due - now - 150_000_000L; due = base + pts }
        else if (due - now > 200_000_000L) { base -= 1_000_000L; due -= 1_000_000L }
        return due
    }

    private fun run() {
        while (running) {
            var dec: MediaCodec? = null
            val sound = AacOut()
            try {
                val wantListen = listen
                val input = DataInputStream(open() ?: run { Thread.sleep(2000); return@run null } ?: continue)
                acks(vid)
                base = 0L
                var configured = false
                val info = MediaCodec.BufferInfo()
                while (running) {
                    if (listen != wantListen) break
                    val n0 = input.readInt()
                    val ms = input.readInt().toLong() and 0xFFFFFFFFL
                    val audio = n0 < 0
                    val n = n0 and Int.MAX_VALUE
                    if (n > 8 * 1024 * 1024) break
                    val b = ByteArray(n)
                    input.readFully(b)
                    got += 8 + n
                    pts = ms * 1000
                    if (audio) { if (listen) sound.play(b); continue }
                    if (!configured) {
                        val cut = configEnd(b) ?: continue
                        val d = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        d.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720), surface ?: break, null, 0)
                        d.start()
                        dec = d
                        queue(d, b, 0, cut, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        queue(d, b, cut, n - cut, 0)
                        configured = true
                    } else queue(dec!!, b, 0, n, 0)
                    val d = dec ?: continue
                    while (true) {
                        val o = d.dequeueOutputBuffer(info, 0)
                        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val f = d.outputFormat
                            vw = f.getInteger(MediaFormat.KEY_WIDTH); vh = f.getInteger(MediaFormat.KEY_HEIGHT)
                            view?.post { fit() }
                            continue
                        }
                        if (o < 0) break
                        d.releaseOutputBuffer(o, due(info.presentationTimeUs))
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { dec?.stop() }; runCatching { dec?.release() }
                sound.close()
                runCatching { conn?.disconnect() }
            }
            if (running) runCatching { Thread.sleep(800) }
        }
        runCatching { surface?.release() }
    }

    private fun queue(d: MediaCodec, b: ByteArray, off: Int, n: Int, flags: Int) {
        if (n <= 0) return
        val i = d.dequeueInputBuffer(200_000)
        if (i < 0) return
        val buf = d.getInputBuffer(i) ?: return
        buf.clear(); buf.put(b, off, n)
        d.queueInputBuffer(i, 0, n, pts, flags)
    }

    /** Where the settings (SPS, PPS) at the head of a key picture end; null when it does not start with them. */
    private fun configEnd(b: ByteArray): Int? {
        var i = 0
        var sawSps = false
        while (i + 3 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                val t = b[i + 3].toInt() and 0x1F
                val start = if (i > 0 && b[i - 1].toInt() == 0) i - 1 else i
                if (t == 7) sawSps = true
                else if (t != 8) return if (sawSps) start else null
                i += 3
            } else i++
        }
        return null
    }
}

/** A camera's sound (ADTS AAC frames) out of this phone's speaker. */
private class AacOut {
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private val info = MediaCodec.BufferInfo()

    fun play(adts: ByteArray) {
        if (adts.size <= 7) return
        val c = codec ?: run {
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 1).apply {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(f, null, null, 0); start() }.also { codec = it }
        }
        val i = c.dequeueInputBuffer(20_000)
        if (i >= 0) { c.getInputBuffer(i)?.apply { clear(); put(adts) }; c.queueInputBuffer(i, 0, adts.size, 0, 0) }
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            if (o < 0) break
            val buf = c.getOutputBuffer(o)
            if (buf != null && info.size > 0) {
                val pcm = ByteArray(info.size)
                buf.position(info.offset); buf.get(pcm)
                val t = track ?: AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(48_000).setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play(); track = it }
                t.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
            }
            c.releaseOutputBuffer(o, false)
        }
    }

    fun close() {
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        runCatching { track?.stop() }; runCatching { track?.release() }; track = null
    }
}

/** This phone's voice, 16 kHz mono, a tenth of a second at a time, to the camera phone's speaker, while [on]. */
@SuppressLint("MissingPermission")
private fun talk(ctx: android.content.Context, target: String, on: () -> Boolean) {
    val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
    val peer = if (target == "self") null else ctx.container.peers.find(target)
    try {
        rec.startRecording()
        val buf = ByteArray(3200)
        while (on() && !Thread.currentThread().isInterrupted) {
            var got = 0
            while (got < buf.size) { val n = rec.read(buf, got, buf.size - got); if (n <= 0) break; got += n }
            val piece = buf.copyOf(got)
            if (peer == null) ctx.container.cameras.talk(piece) else ctx.container.peers.deliverBytes(peer, "/api/cams/talk", piece)
        }
    } catch (_: Exception) {
    } finally {
        runCatching { rec.stop() }; runCatching { rec.release() }
    }
}
