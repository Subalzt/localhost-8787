package dev.periy.bridge.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.periy.bridge.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.abs

/** A camera phone as it stands: its settings, and what it is doing. */
@Serializable
data class CamState(
    /** Camera mode on: the camera runs, for viewers and the motion watch. */
    val on: Boolean = false,
    val lens: String = "back",
    val torch: Boolean = false,
    /** The motion watch, and recording a clip when it sees something. */
    val motion: Boolean = true,
    val record: Boolean = true,
    /** The microphone: to listen in, and in clips. */
    val sound: Boolean = true,
    /** 1 low, 2 medium, 3 high. */
    val sensitivity: Int = 2,
    /** Turned by hand on top of how the camera sits (0, 90, 180, 270). */
    val rotate: Int = 0,
    val running: Boolean = false,
    val recording: Boolean = false,
    val lastMotion: Long = 0,
    val viewers: Int = 0,
    /** How far to turn the picture to show it upright. */
    val rotation: Int = 90,
    val error: String = "",
    /** Turning on from afar: the phone wakes over its lock screen for a moment to be let use the camera. */
    val waking: Boolean = false,
    val battery: Int = -1,
    val charging: Boolean = false,
    /** Night: a longer exposure, brighter, fewer pictures a second. */
    val night: Boolean = false,
    /** Zoom in the camera itself, 1 to [maxZoom]. */
    val zoom: Float = 1f,
    val maxZoom: Float = 1f,
)

/** A camera in the control centre: this phone ("self"), or a linked phone by name. */
@Serializable
data class CamDto(
    val target: String, val name: String, val self: Boolean, val state: CamState? = null, val why: String = "",
    /** "phone", or "laptop": a laptop's webcam (server/LaptopCams.kt), live and listen only. */
    val kind: String = "phone",
)

@Serializable
data class CamList(val me: String, val cams: List<CamDto>)

/** A change, for one camera ([target]); only what is given changes. */
@Serializable
data class CamSet(
    val target: String = "",
    val on: Boolean? = null,
    val lens: String? = null,
    val torch: Boolean? = null,
    val motion: Boolean? = null,
    val record: Boolean? = null,
    val sound: Boolean? = null,
    val sensitivity: Int? = null,
    val rotate: Int? = null,
    /** Start (true) or stop (false) a clip by hand. */
    val recordNow: Boolean? = null,
    val night: Boolean? = null,
    val zoom: Float? = null,
)

@Serializable
data class CamClip(val id: String, val at: Long, val ms: Long, val size: Long, val motion: Boolean)

/** Movement seen by a camera, told to the other phones and laptops, with what it saw. */
@Serializable
data class CamAlert(val camera: String, val at: Long, val jpeg: String = "", val target: String = "")

/**
 * Phones as security cameras. On the camera phone: camera mode (CameraService and CamEngine), the
 * live stream to each viewer (each picture whole with its length, the sound between them with the
 * top bit of the length set, as another laptop's screen comes to a page), the motion watch, clips
 * with the few seconds before the movement, talk-back through the speaker, and alerts to the linked
 * phones (sealed) and to this phone's laptops. On a viewing phone: the other cameras through the
 * phones' link, and their alerts as notifications that open the cameras over the lock screen.
 */
class Cameras(
    ctx: Context, private val peers: PeerManager, private val keyHere: (String) -> ByteArray,
    /** The laptops whose helper is running here now: id and name, for their webcams. */
    private val laptops: () -> List<Pair<String, String>> = { emptyList() },
) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("cameras", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dir = File(app.filesDir, "cams").apply {
        mkdirs()
        // A clip cut off part way (the app stopped while it was being written) can never play: gone.
        listFiles { f -> f.name.endsWith(".mp4") && !File(this, f.nameWithoutExtension + ".ms").exists() }?.forEach { f ->
            f.delete(); File(this, f.nameWithoutExtension + ".jpg").delete()
        }
    }

    private val _state = MutableStateFlow(
        CamState(
            on = prefs.getBoolean("on", false), lens = prefs.getString("lens", "back") ?: "back",
            motion = prefs.getBoolean("motion", true), record = prefs.getBoolean("record", true),
            sound = prefs.getBoolean("sound", true), sensitivity = prefs.getInt("sensitivity", 2), rotate = prefs.getInt("rotate", 0),
            night = prefs.getBoolean("night", false), zoom = prefs.getFloat("zoom", 1f),
        ),
    )
    val state: StateFlow<CamState> = _state.asStateFlow()

    private fun change(f: (CamState) -> CamState) {
        val old = _state.value
        val s = f(old)
        if (s == old) return
        _state.value = s
        prefs.edit().putBoolean("on", s.on).putString("lens", s.lens).putBoolean("motion", s.motion).putBoolean("record", s.record)
            .putBoolean("sound", s.sound).putInt("sensitivity", s.sensitivity).putInt("rotate", s.rotate)
            .putBoolean("night", s.night).putFloat("zoom", s.zoom).apply()
        EventBus.emit("cams", "self")
    }

    fun withBattery(s: CamState): CamState {
        val b = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return s
        val level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
        val plugged = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        return s.copy(battery = if (level >= 0) level * 100 / scale else -1, charging = plugged)
    }

    fun allowed(): Boolean = app.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun micAllowed(): Boolean = app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ settings

    /** A change from the control centre, here or from afar. Null when done, else why not. */
    fun set(c: CamSet): String? {
        if (c.on == true && !allowed()) return "Allow the camera for Localhost 8787 on ${peers.deviceName()} first"
        val wasOn = _state.value.on
        val before = _state.value
        change { s ->
            s.copy(
                on = c.on ?: s.on, lens = c.lens?.takeIf { it == "front" || it == "back" } ?: s.lens, torch = c.torch ?: s.torch,
                motion = c.motion ?: s.motion, record = c.record ?: s.record, sound = c.sound ?: s.sound,
                sensitivity = (c.sensitivity ?: s.sensitivity).coerceIn(1, 3), rotate = (((c.rotate ?: s.rotate) % 360) + 360) % 360,
                error = if (c.on == true) "" else s.error,
                night = c.night ?: s.night,
                zoom = (c.zoom ?: s.zoom).coerceIn(1f, maxOf(1f, s.maxZoom)),
            )
        }
        val s = _state.value
        when {
            s.on && !wasOn -> startService()
            !s.on && wasOn -> stopEngine()
            s.on && (s.lens != before.lens || s.sound != before.sound) -> restart()
            s.on && s.torch != before.torch -> engine?.setTorch(s.torch)
        }
        if (s.on && s.night != before.night) { engine?.setNight(s.night); motionReset() }
        if (s.on && s.zoom != before.zoom) { engine?.setZoom(s.zoom); motionReset() }
        when {
            false -> {}
        }
        when (c.recordNow) {
            true -> startClip(motion = false)
            false -> stopClip()
            null -> {}
        }
        return null
    }

    // ------------------------------------------------------------------ the camera service

    @Volatile private var engine: CamEngine? = null

    /** Camera mode: the service that may hold the camera with the phone locked. */
    fun startService() {
        if (engine != null) return
        try {
            androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, dev.periy.bridge.service.CameraService::class.java))
        } catch (e: Exception) {
            // Not allowed from the background: the phone wakes over its lock screen for a moment instead.
            Log.i(TAG, "Service from the background: ${e.message}")
            wake()
        }
    }

    /** CameraService is in the foreground with the camera's and microphone's types: start. */
    fun serviceUp() {
        val s = _state.value
        if (!s.on) return
        if (engine != null) return
        val e = CamEngine(app, sink)
        engine = e
        e.start(s.lens, s.torch, s.sound && micAllowed(), s.night, s.zoom)
    }

    /** CameraService could not take the camera from the background. */
    fun serviceRefused() { wake() }

    private fun restart() {
        stopEngine(keepOn = true)
        startService()
    }

    private fun stopEngine(keepOn: Boolean = false) {
        engine?.stop()
        engine = null
        // Off: every viewer's stream ends (nothing more will come to show they have gone). A restart
        // (the other lens) keeps them: their pictures go on from the next key picture.
        if (!keepOn) viewers.toList().forEach { it.close() }
        stopClip()
        motionReset()
        change { it.copy(running = false, recording = false, torch = if (keepOn) it.torch else false) }
        if (!keepOn) app.stopService(Intent(app, dev.periy.bridge.service.CameraService::class.java))
    }

    @Volatile private var wakeAt = 0L

    /** Wakes the phone over its lock screen (a full-screen notification, as a call rings) to start the camera from there. */
    private fun wake() {
        val now = System.currentTimeMillis()
        if (now - wakeAt < 15_000) return
        wakeAt = now
        change { it.copy(waking = true) }
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(WAKE_CHANNEL, "Camera turning on", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Turning camera mode on when another of your devices asks."
            setSound(null, null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
        val pi = PendingIntent.getActivity(app, 8501, Intent(app, dev.periy.bridge.ui.CamWakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, WAKE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Turning the camera on")
            .setContentText("Asked from another of your devices. Tap if it does not start.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        runCatching { nm.notify(WAKE_ID, n) }
        // Given up on after a minute: said so.
        scope.launch {
            kotlinx.coroutines.delay(60_000)
            if (_state.value.waking && engine == null) change { it.copy(waking = false, error = "${peers.deviceName()} did not let the camera start: open Localhost 8787 there once") }
        }
    }

    fun woke() {
        app.getSystemService(NotificationManager::class.java)?.cancel(WAKE_ID)
    }

    // ------------------------------------------------------------------ what the engine gives

    private val viewers = CopyOnWriteArrayList<Viewer>()

    /** One viewer's stream: frames as they come, the oldest let go (then on to the next key picture) when it falls behind. */
    /**
     * One viewer's stream. A viewer that says what has reached it ([ack], by its [id]) is never let
     * fall more than about half a second behind: past that its pictures wait for the next key picture
     * (asked for as soon as it has caught up), and the picture's bitrate comes down for everyone, back
     * up when every viewer keeps up. One that says nothing is cut off past a few seconds' worth.
     */
    inner class Viewer(val listen: Boolean, val id: String, val far: Boolean) {
        val ch = Channel<ByteArray>(60)
        @Volatile var needKey = true
        /** Bytes handed to it, and what it says has arrived. */
        @Volatile var sent = 0L
        @Volatile var acked = 0L
        @Volatile var acking = false
        /** Behind: waiting for a key picture before more pictures go. */
        @Volatile var skipping = false
        @Volatile var drops = 0
        /** Where each frame given ends in the stream, and when it was given (ms): what an ack says arrived, in time. */
        private val marks = ArrayDeque<LongArray>()
        /** What it said had arrived over the last two seconds: what its way carries now. */
        private val heard = ArrayDeque<LongArray>()
        fun inflight() = sent - acked
        fun mark() = synchronized(marks) {
            marks.addLast(longArrayOf(sent, System.currentTimeMillis()))
            while (marks.size > 4000) marks.removeFirst()
        }
        fun acked(bytes: Long) = synchronized(marks) {
            acked = bytes
            while (marks.isNotEmpty() && marks.first()[0] <= bytes) marks.removeFirst()
            val now = System.currentTimeMillis()
            heard.addLast(longArrayOf(now, bytes))
            while (heard.size > 2 && now - heard.first()[0] > 2000) heard.removeFirst()
        }
        /** How long the oldest frame not yet arrived has been on its way (ms): how far behind it is. */
        fun age(): Long = synchronized(marks) { if (marks.isEmpty()) 0 else System.currentTimeMillis() - marks.first()[1] }
        /** Bytes it may have on their way: about what its way carried in the last 0.7 s, never under 48 KB. */
        fun cap(): Long = synchronized(marks) {
            // Before it has said anything: a key picture and little more, so a slow way starts clear.
            if (!acking || heard.size < 2) return 16_000
            val a = heard.first(); val b = heard.last()
            val bps = if (b[0] > a[0]) (b[1] - a[1]) * 1000 / (b[0] - a[0]) else 0
            maxOf(48_000L, bps * 7 / 10)
        }
        /** Too far behind for another picture now. */
        fun full(): Boolean = age() > BEHIND_MS || inflight() > cap()
        fun close() { viewers.remove(this); ch.close(); change { it.copy(viewers = viewers.size) }; if (viewers.isEmpty()) setRate(CamEngine.BITRATE) }
    }

    fun watch(listen: Boolean, id: String = "", far: Boolean = false): Viewer {
        val v = Viewer(listen, id, far)
        viewers += v
        change { it.copy(viewers = viewers.size) }
        // From afar it starts gently, and climbs as the way allows.
        if (far && rate > FAR_START) setRate(FAR_START)
        engine?.keyNow()
        rateLoop()
        return v
    }

    /** How much of viewer [id]'s stream has reached it. */
    fun ack(id: String, bytes: Long) {
        val v = viewers.firstOrNull { it.id == id } ?: return
        v.acking = true
        if (bytes > v.acked) v.acked(bytes)
        if (v.skipping && v.inflight() == 0L) engine?.keyNow()
    }

    @Volatile private var rate = CamEngine.BITRATE

    private fun setRate(bps: Int) {
        val r = bps.coerceIn(MIN_RATE, CamEngine.BITRATE)
        if (r == rate) return
        rate = r
        engine?.setBitrate(r)
    }

    @Volatile private var rating: kotlinx.coroutines.Job? = null
    private var calm = 0

    /** Once a second while anyone watches: down when a viewer fell behind, up after a few calm seconds. */
    private fun rateLoop() {
        if (rating?.isActive == true) return
        rating = scope.launch {
            var lastDrops = 0
            while (viewers.isNotEmpty()) {
                kotlinx.coroutines.delay(1000)
                // A viewer gone without a word (nothing arrives while it waits for a key picture, so
                // its stream never fails): let go after 15 s of silence.
                for (v in viewers) if (v.age() > 15_000) { Log.i(TAG, "Viewer ${v.id} went quiet"); v.close() }
                val drops = viewers.sumOf { it.drops }
                if (dev.periy.bridge.BuildConfig.DEBUG) for (v in viewers) Log.i(TAG, "viewer ${v.id}: age ${v.age()} ms, on its way ${v.inflight()} of ${v.cap()}, skipping ${v.skipping}, drops ${v.drops}, rate $rate")
                val tight = viewers.any { it.skipping || it.age() > CATCH_UP_MS }
                when {
                    drops > lastDrops -> { setRate(rate * 6 / 10); calm = 0 }
                    tight -> { setRate(rate * 85 / 100); calm = 0 }
                    else -> if (++calm >= 3) { setRate(rate * 120 / 100); calm = 1 }
                }
                lastDrops = drops
            }
        }
    }

    /** A frame for viewers: its length (the top bit set for sound), when it was taken (ms), then it. */
    private fun frame(data: ByteArray, ptsUs: Long, sound: Boolean): ByteArray {
        val n = if (sound) data.size or Int.MIN_VALUE else data.size
        val ms = (ptsUs / 1000).toInt()
        val f = ByteArray(8 + data.size)
        f[0] = (n ushr 24).toByte(); f[1] = (n ushr 16).toByte(); f[2] = (n ushr 8).toByte(); f[3] = n.toByte()
        f[4] = (ms ushr 24).toByte(); f[5] = (ms ushr 16).toByte(); f[6] = (ms ushr 8).toByte(); f[7] = ms.toByte()
        System.arraycopy(data, 0, f, 8, data.size)
        return f
    }

    private fun give(v: Viewer, f: ByteArray, ptsUs: Long): Boolean {
        if (v.ch.trySend(f).isFailure) return false
        v.sent += f.size
        v.mark()
        return true
    }

    @Volatile private var videoFormat: MediaFormat? = null
    @Volatile private var audioFormat: MediaFormat? = null

    private class Au(val data: ByteArray, val key: Boolean, val pts: Long, val audio: Boolean)
    /** The last few seconds, for the start of a clip. */
    private val ring = ArrayDeque<Au>()

    private val sink = object : CamEngine.Out {
        override fun video(au: ByteArray, key: Boolean, ptsUs: Long) {
            val frame = frame(au, ptsUs, false)
            var behind = false
            for (v in viewers) {
                if (v.needKey && !key) continue
                v.needKey = false
                // Behind (what is on its way older than its way's delay, or more of it than its way
                // carries in a moment): no more pictures until all has arrived, then from a key picture.
                if (v.skipping) { if (key && v.inflight() == 0L) v.skipping = false else continue }
                else if (v.full()) { v.skipping = true; v.drops++; continue }
                if (!give(v, frame, ptsUs)) { v.needKey = true; v.drops++; behind = true }
            }
            if (behind) engine?.keyNow()
            keep(Au(au, key, ptsUs, false))
            clip?.video(au, key, ptsUs)
        }
        override fun videoFormat(f: MediaFormat) { videoFormat = f }
        override fun audio(raw: ByteArray, ptsUs: Long) {
            val frame = frame(adts(raw), ptsUs, true)
            // The sound goes on while the picture waits to catch up, unless the way is choked.
            for (v in viewers) if (v.listen && !v.needKey && v.age() < 2 * BEHIND_MS) give(v, frame, ptsUs)
            keep(Au(raw, true, ptsUs, true))
            clip?.audio(raw, ptsUs)
        }
        override fun audioFormat(f: MediaFormat) { audioFormat = f }
        override fun frame(img: Image) { look(img) }
        override fun failed(why: String) {
            engine = null
            if (why == "background") { scope.launch { wake() }; return }
            change { it.copy(running = false, error = why) }
        }
        override fun running(sensor: Int, maxZoom: Float) {
            woke()
            engine?.setBitrate(rate)
            motionReset()
            change { s -> s.copy(running = true, waking = false, error = "", rotation = (sensor + s.rotate) % 360, maxZoom = maxZoom, zoom = s.zoom.coerceIn(1f, maxOf(1f, maxZoom))) }
        }
    }

    private fun keep(a: Au) = synchronized(ring) {
        ring.addLast(a)
        while (ring.isNotEmpty() && a.pts - ring.first().pts > PRE_US + 2_500_000) ring.removeFirst()
    }

    // ------------------------------------------------------------------ the motion watch

    private var bg: FloatArray? = null
    private var startedAt = 0L
    private var hits = 0
    @Volatile private var snapWanted = false
    private var lastAlert = 0L
    private var movingSince = 0L

    private fun motionReset() { bg = null; startedAt = System.currentTimeMillis(); hits = 0 }

    private fun look(img: Image) {
        val s = _state.value
        val y = img.planes[0]
        val buf = y.buffer
        val rs = y.rowStride
        val w = img.width
        val h = img.height
        // The picture as 32 by 24 blocks of brightness.
        val gx = 32; val gy = 24
        val cur = FloatArray(gx * gy)
        for (by in 0 until gy) for (bx in 0 until gx) {
            var sum = 0; var n = 0
            var yy = by * h / gy
            val y2 = (by + 1) * h / gy
            while (yy < y2) {
                var xx = bx * w / gx
                val x2 = (bx + 1) * w / gx
                while (xx < x2) { sum += buf.get(yy * rs + xx).toInt() and 0xFF; n++; xx += 3 }
                yy += 3
            }
            cur[by * gx + bx] = if (n > 0) sum.toFloat() / n else 0f
        }
        if (snapWanted) { snapWanted = false; snapshot = runCatching { jpeg(img, s.rotation) }.getOrNull() }
        if (!s.motion) { bg = null; return }
        val b = bg ?: run { bg = cur; return }
        // The whole picture lighter or darker (a light on, the sun behind a cloud) is not movement.
        var shift = 0f
        for (i in cur.indices) shift += cur[i] - b[i]
        shift /= cur.size
        val (cellT, countT) = when (s.sensitivity) { 1 -> 30f to 24; 3 -> 14f to 5; else -> 20f to 10 }
        var moved = 0
        for (i in cur.indices) if (abs(cur[i] - b[i] - shift) > cellT) moved++
        val now = System.currentTimeMillis()
        // The whole picture changed at once (a light on or off, the phone moved): a new scene, learnt
        // afresh, not movement; and it settles a moment before it is watched again.
        if (abs(shift) > 25 || moved > cur.size / 2) {
            bg = cur; hits = 0; startedAt = now - 2_000; movingSince = 0
            return
        }
        val settled = now - startedAt > 4_000
        if (settled && moved >= countT) hits++ else hits = 0
        // Something that came and stayed (a chair moved, a bag left): part of the scene after 20 s.
        if (moved >= countT) { if (movingSince == 0L) movingSince = now } else movingSince = 0
        if (movingSince != 0L && now - movingSince > 20_000) { bg = cur; hits = 0; movingSince = 0; return }
        // The background follows slowly, faster when nothing moves.
        val k = if (moved >= countT) 0.05f else 0.2f
        for (i in cur.indices) b[i] += (cur[i] - b[i]) * k
        if (hits >= 2) { hits = 0; motion(img, s) }
    }

    @Volatile private var snapshot: ByteArray? = null

    private fun motion(img: Image, s: CamState) {
        val now = System.currentTimeMillis()
        change { it.copy(lastMotion = now) }
        if (s.record) { if (clip == null) startClip(motion = true) else clip?.motionAt = now }
        if (now - lastAlert < ALERT_GAP_MS) return
        lastAlert = now
        val pic = runCatching { jpeg(img, s.rotation) }.getOrNull()
        snapshot = pic
        clip?.thumb = pic
        val alert = CamAlert(peers.deviceName(), now, pic?.let { Base64.encodeToString(it, Base64.NO_WRAP) }.orEmpty(), "self")
        EventBus.emit("camalert", json.encodeToString(alert))
        scope.launch {
            for (p in peers.peers.value) {
                val key = peers.messageKey(p) ?: continue
                val body = json.encodeToString(WhereCrypto.seal(key, json.encodeToString(alert), WhereCrypto.L_CAM)).toByteArray()
                runCatching { peers.deliver(p, "/api/peers/cam/alert", body) }
            }
        }
    }

    /** The newest picture as a JPEG, turned upright: for alerts, clips' covers and the grid before it plays. */
    fun snap(): ByteArray? {
        if (engine == null) return snapshot
        val before = snapshot
        snapWanted = true
        repeat(20) { snapshot?.let { if (it !== before) return it }; Thread.sleep(75) }
        return snapshot
    }

    private fun jpeg(img: Image, rotation: Int): ByteArray {
        val w = img.width; val h = img.height
        val nv21 = ByteArray(w * h * 3 / 2)
        val yp = img.planes[0]; val up = img.planes[1]; val vp = img.planes[2]
        var o = 0
        for (r in 0 until h) for (c in 0 until w) nv21[o++] = yp.buffer.get(r * yp.rowStride + c * yp.pixelStride)
        for (r in 0 until h / 2) for (c in 0 until w / 2) {
            nv21[o++] = vp.buffer.get(r * vp.rowStride + c * vp.pixelStride)
            nv21[o++] = up.buffer.get(r * up.rowStride + c * up.pixelStride)
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 80, out)
        if (rotation % 360 == 0) return out.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        return ByteArrayOutputStream().also { turned.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    // ------------------------------------------------------------------ clips

    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var clip: Clip? = null

    /** A clip being written: MP4 from the encoder's own pictures (and sound), from a key picture a few seconds back. */
    private inner class Clip(val motionClip: Boolean) {
        val at = System.currentTimeMillis()
        val file = File(dir, "clip-$at${if (motionClip) "-m" else ""}.mp4")
        @Volatile var motionAt = at
        @Volatile var thumb: ByteArray? = null
        private var muxer: MediaMuxer? = null
        private var vt = -1
        private var at0 = -1
        private var base = -1L
        private var started = false
        private var lastPts = 0L

        fun open(pre: List<Au>) {
            val vf = videoFormat ?: error("no picture yet")
            val m = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            m.setOrientationHint(_state.value.rotation % 360)
            vt = m.addTrack(vf)
            audioFormat?.let { at0 = m.addTrack(it) }
            m.start()
            muxer = m
            started = true
            val first = pre.indexOfFirst { !it.audio && it.key }
            if (first >= 0) for (a in pre.drop(first)) write(a)
        }

        private fun write(a: Au) {
            val m = muxer ?: return
            if (a.audio && at0 < 0) return
            if (base < 0) { if (a.audio || !a.key) return; base = a.pts }
            val pts = a.pts - base
            if (pts < 0) return
            val info = MediaCodec.BufferInfo().apply { set(0, a.data.size, pts, if (a.key && !a.audio) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0) }
            runCatching { m.writeSampleData(if (a.audio) at0 else vt, ByteBuffer.wrap(a.data), info) }
            if (!a.audio) lastPts = pts
        }

        fun video(au: ByteArray, key: Boolean, pts: Long) = writer.execute { if (started) write(Au(au, key, pts, false)) }
        fun audio(raw: ByteArray, pts: Long) = writer.execute { if (started) write(Au(raw, true, pts, true)) }

        fun close() = writer.execute {
            runCatching { muxer?.stop() }; runCatching { muxer?.release() }
            muxer = null; started = false
            if (lastPts < 1_000_000) { file.delete(); return@execute }
            val pic = thumb ?: snapshot
            pic?.let { runCatching { File(dir, file.nameWithoutExtension + ".jpg").writeBytes(it) } }
            File(dir, file.nameWithoutExtension + ".ms").writeText(((lastPts) / 1000).toString())
            trim()
            EventBus.emit("cams", "clips")
        }
    }

    private fun startClip(motion: Boolean) {
        if (clip != null || engine == null) return
        val c = Clip(motion)
        val pre = synchronized(ring) { ring.toList() }
        try { c.open(pre) } catch (e: Exception) { Log.w(TAG, "Clip", e); return }
        if (c.thumb == null) c.thumb = snapshot
        clip = c
        change { it.copy(recording = true) }
        // Motion clips end 20 s after the last movement, five minutes at most; by hand, thirty.
        scope.launch {
            while (clip === c) {
                kotlinx.coroutines.delay(1000)
                val now = System.currentTimeMillis()
                val over = if (c.motionClip) now - c.motionAt > 20_000 || now - c.at > 300_000 else now - c.at > 1_800_000
                if (over) stopClip()
            }
        }
    }

    private fun stopClip() {
        val c = clip ?: return
        clip = null
        c.close()
        change { it.copy(recording = false) }
    }

    /** Clips kept to 2 GB: the oldest go first. */
    private fun trim() {
        val all = dir.listFiles { f -> f.name.endsWith(".mp4") }.orEmpty().sortedBy { it.lastModified() }
        var total = all.sumOf { it.length() }
        for (f in all) {
            if (total <= MAX_BYTES) break
            total -= f.length()
            deleteClip(f.nameWithoutExtension)
        }
    }

    fun clips(): List<CamClip> = dir.listFiles { f -> f.name.endsWith(".mp4") && f.name != clip?.file?.name }.orEmpty()
        .mapNotNull { f ->
            val at = f.nameWithoutExtension.removePrefix("clip-").substringBefore('-').toLongOrNull() ?: return@mapNotNull null
            val ms = File(dir, f.nameWithoutExtension + ".ms").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0
            CamClip(f.nameWithoutExtension, at, ms, f.length(), f.nameWithoutExtension.endsWith("-m"))
        }.sortedByDescending { it.at }

    fun clipFile(id: String, thumb: Boolean): File? {
        if (!Regex("""clip-\d+(-m)?""").matches(id)) return null
        return File(dir, id + if (thumb) ".jpg" else ".mp4").takeIf { it.exists() }
    }

    fun deleteClip(id: String) {
        if (!Regex("""clip-\d+(-m)?""").matches(id)) return
        listOf(".mp4", ".jpg", ".ms").forEach { File(dir, id + it).delete() }
        EventBus.emit("cams", "clips")
    }

    // ------------------------------------------------------------------ talking through the camera phone

    private var talkTrack: AudioTrack? = null
    @Volatile private var talkAt = 0L

    /** A piece of a viewer's voice (16 kHz, mono, 16-bit), out of this phone's speaker. */
    @Synchronized fun talk(pcm: ByteArray) {
        val t = talkTrack ?: AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(32_000)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play(); talkTrack = it }
        t.write(pcm, 0, pcm.size and 1.inv(), AudioTrack.WRITE_NON_BLOCKING)
        talkAt = System.currentTimeMillis()
        scope.launch {
            kotlinx.coroutines.delay(4_000)
            synchronized(this@Cameras) {
                if (System.currentTimeMillis() - talkAt >= 3_900) { talkTrack?.let { runCatching { it.stop(); it.release() } }; talkTrack = null }
            }
        }
    }

    // ------------------------------------------------------------------ the control centre: every camera

    /** This phone and the linked phones, each as it stands now (a linked phone asked, a few seconds at most). */
    suspend fun all(): CamList {
        val me = CamDto("self", peers.deviceName(), true, withBattery(_state.value))
        val others = peers.peers.value.map { p ->
            scope.async {
                val r = withTimeoutOrNull(4_000) { runCatching { peers.fetch(p, "/api/cams/state") }.getOrNull() }
                val st = r?.let { runCatching { json.decodeFromString<CamState>(it) }.getOrNull() }
                CamDto(p.name, p.name, false, st, if (st == null) "Not reachable now" else "")
            }
        }.awaitAll()
        // Each running laptop's webcam, on when it is watched.
        val webcams = laptops().map { (id, name) ->
            CamDto(LaptopCams.PREFIX + id, "$name webcam", false,
                CamState(on = true, running = true, lens = "front", motion = false, record = false, rotation = 0), kind = "laptop")
        }
        return CamList(peers.deviceName(), listOf(me) + others + webcams)
    }

    /** An alert from a linked phone: on to this phone's pages and laptops (no notification here). */
    fun alertFrom(deviceId: String, from: String, w: CallWire): Boolean {
        val text = WhereCrypto.open(keyHere(deviceId), w, WhereCrypto.L_CAM) ?: return false
        val a = runCatching { json.decodeFromString<CamAlert>(text) }.getOrNull() ?: return false
        val alert = a.copy(target = from)
        EventBus.emit("camalert", json.encodeToString(alert))
        return true
    }

    companion object {
        private const val TAG = "Cameras"
        private const val WAKE_CHANNEL = "camera-wake"
        private const val ALERT_CHANNEL = "camera-alerts"
        private const val WAKE_ID = 8502
        private const val ALERT_ID = 8600
        private const val PRE_US = 3_000_000L
        private const val ALERT_GAP_MS = 60_000L
        private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
        private const val MIN_RATE = 90_000
        private const val FAR_START = 600_000
        /** A viewer whose oldest frame on its way is this old (ms) gets no more pictures until all has arrived. */
        private const val BEHIND_MS = 900L
        /** Older than this, the bitrate comes down. */
        private const val CATCH_UP_MS = 600L

        /** An AAC frame with its ADTS header (48 kHz, mono, LC), as a browser's or the phone's decoder takes it alone. */
        fun adts(raw: ByteArray): ByteArray {
            val len = raw.size + 7
            val h = byteArrayOf(
                0xFF.toByte(), 0xF1.toByte(), ((1 shl 6) or (3 shl 2)).toByte(), ((1 shl 6) or (len shr 11)).toByte(),
                ((len shr 3) and 0xFF).toByte(), (((len and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte(),
            )
            return h + raw
        }
    }
}
