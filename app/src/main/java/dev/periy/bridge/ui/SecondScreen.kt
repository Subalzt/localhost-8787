package dev.periy.bridge.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.periy.bridge.BuildConfig
import dev.periy.bridge.server.Control
import dev.periy.bridge.server.DisplayFeed
import dev.periy.bridge.server.EventBus
import java.util.concurrent.TimeUnit
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The laptop's screen on the phone: as a second screen, or the laptop's own screen to see and
 * use it, from the same room or from anywhere.
 *
 * The laptop helper captures a monitor (a virtual one, from a virtual-display driver, so it is a
 * real extra screen; or the laptop's own, to mirror it) and encodes it on its GPU as H.264, and
 * posts it to the phone the way it reaches the phone for everything else, the tunnel included
 * (server/DisplayFeed.kt); from another network it sends the main screen, smaller and at a bit
 * rate the link carries. This decodes it with the phone's hardware decoder straight onto the
 * screen, and turns touches into the laptop's mouse on that monitor: tap to click, hold to
 * right-click, drag to drag, two fingers to scroll, pinch to zoom in (and two fingers then move
 * round), and the keyboard button types. Back ends it, and the laptop stops streaming.
 */
class SecondScreenActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var surface: SurfaceView
    private lateinit var note: TextView
    private lateinit var keys: KeyCatcher
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var codec: MediaCodec? = null
    /** The picture's size, once the decoder knows it, for fitting it on the screen. */
    private var picW = 0
    private var picH = 0

    /** The panel's fastest refresh rate, asked for while this is open, and told to the laptop. */
    private var hz = 60

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Left alone, the phone runs a video at 60 Hz; the laptop can send up to the panel's rate.
        @Suppress("DEPRECATION")
        val disp = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        disp?.let { d ->
            val now = d.mode
            d.supportedModes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
                .maxByOrNull { it.refreshRate }?.let { fastest ->
                    hz = fastest.refreshRate.roundToInt()
                    window.attributes = window.attributes.apply { preferredDisplayModeId = fastest.modeId }
                }
        }
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        surface = SurfaceView(this)
        note = TextView(this).apply {
            setTextColor(0xFFBBBBBB.toInt()); textSize = 16f; gravity = Gravity.CENTER
            text = "Waiting for the laptop...\nThe Localhost 8787 helper must be running there."
        }
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        root.addView(note, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // The phone's keyboard types into the laptop: an unseen field takes it, as on the trackpad.
        keys = KeyCatcher(this,
            onText = { t -> Control.send("t " + java.net.URLEncoder.encode(t, "UTF-8").replace("+", "%20")) },
            onKey = { k -> Control.send("k $k") })
        root.addView(keys, FrameLayout.LayoutParams(1, 1))
        val d = resources.displayMetrics.density
        val kb = TextView(this).apply {
            text = "Keyboard"; textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 20 * d; setColor(0x88000000.toInt()); setStroke((1 * d).toInt(), 0x44FFFFFF) }
            setOnClickListener {
                keys.requestFocus()
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(keys, 0)
            }
        }
        root.addView(kb, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, (16 * d).toInt(), (16 * d).toInt())
        })
        setContentView(root)
        surface.holder.addCallback(this)
        surface.setOnTouchListener { _, e -> touch(e); true }
        immersive()
    }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // HyperOS drops the panel to 60 Hz whenever the screen is not being touched, unless
        // something asks for more: every way an app can ask, strongest first.
        runCatching {
            when {
                Build.VERSION.SDK_INT >= 36 -> holder.surface.setFrameRate(hz.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_AT_LEAST, Surface.CHANGE_FRAME_RATE_ALWAYS)
                Build.VERSION.SDK_INT >= 31 -> holder.surface.setFrameRate(hz.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
                Build.VERSION.SDK_INT >= 30 -> holder.surface.setFrameRate(hz.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }
        if (Build.VERSION.SDK_INT >= 35) runCatching {
            surface.setRequestedFrameRate(hz.toFloat())
            (surface.parent as View).setRequestedFrameRate(hz.toFloat())
        }
        running = true
        Thread({ receive(holder) }, "second-screen").apply { isDaemon = true; start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) { shutdown() }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (!running) return
        running = false
        DisplayFeed.open = false
        EventBus.emit("display", "stop")
        runCatching { current?.input?.close() }
        current?.done?.complete(Unit)
        DisplayFeed.drain()
        runCatching { socket?.close() }
        runCatching { server?.close() }
    }

    /** The stream being shown now. */
    @Volatile private var current: DisplayFeed.Feed? = null

    // -------------------------------------------------------------- the stream

    private fun receive(holder: SurfaceHolder) {
        try {
            DisplayFeed.drain()
            DisplayFeed.open = true
            // An older helper connects here instead; its streams join the others.
            val ss = ServerSocket(PORT).also { server = it }
            Thread({
                while (running) {
                    val s = runCatching { ss.accept() }.getOrNull() ?: break
                    s.tcpNoDelay = true
                    socket = s
                    val done = java.util.concurrent.CompletableFuture<Unit>()
                    DisplayFeed.feeds.put(DisplayFeed.Feed(s.getInputStream(), done))
                    Thread({ runCatching { done.get() }; runCatching { s.close() } }, "second-screen-old").apply { isDaemon = true; start() }
                }
            }, "second-screen-accept").apply { isDaemon = true; start() }
            // Tell the laptop to start, with what it has to match: this screen's size, its refresh
            // rate, how much the decoder can take (16x16 blocks a second), which decides how sharp a
            // picture can come at that rate; and that it may post the stream to the page's port.
            val dm = resources.displayMetrics
            val w = maxOf(dm.widthPixels, dm.heightPixels); val h = minOf(dm.widthPixels, dm.heightPixels)
            EventBus.emit("display", "start $PORT $w $h $hz ${decoderBlocksPerSecond()} http")
            // The laptop starts a new stream whenever its monitors change (the display extended,
            // moved or resized), or the link asks for another size, so after one ends, wait a
            // while for the next before giving up.
            var first = true
            while (running) {
                val f = DisplayFeed.feeds.poll(if (first) 600_000L else RECONNECT_MS.toLong(), TimeUnit.MILLISECONDS) ?: break
                current = f
                first = false
                runOnUiThread { note.visibility = View.GONE }
                try {
                    decode(f.input, holder)
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "stream ended", e)
                } finally {
                    runCatching { codec?.stop(); codec?.release() }
                    codec = null
                    runCatching { f.input.close() }
                    f.done.complete(Unit)
                    current = null
                }
                if (running) runOnUiThread { note.text = "Picking the laptop's screen up again..."; note.visibility = View.VISIBLE }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "second screen ended", e)
        } finally {
            runCatching { codec?.stop(); codec?.release() }
            codec = null
            if (running) runOnUiThread { finish() }
        }
    }

    /**
     * The H.264 decoder's rated throughput in 16x16 blocks a second (a 4K picture is 32,400
     * blocks), from what it declares for 4K; 0 when it cannot be read.
     */
    private fun decoderBlocksPerSecond(): Long = runCatching {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val name = list.findDecoderForFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080))
        val caps = list.codecInfos.first { it.name == name }.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
        (caps.getSupportedFrameRatesFor(3840, 2160).upper * 240 * 135).toLong()
    }.getOrDefault(0L)

    /** Splits the H.264 byte stream at its start codes and feeds each unit to the decoder. */
    private fun decode(input: InputStream, holder: SurfaceHolder) {
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = c
        fun format(fast: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
            setInteger(MediaFormat.KEY_MAX_WIDTH, 3840)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, 2160)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            // The stream's rate (HyperOS picks the panel's rate for a video from it), and the
            // decoder clocked for it rather than for a film's.
            setInteger(MediaFormat.KEY_FRAME_RATE, hz)
            if (fast) setFloat(MediaFormat.KEY_OPERATING_RATE, hz.toFloat())
        }
        try {
            c.configure(format(true), holder.surface, null, 0)
        } catch (e: Exception) {
            c.reset()
            c.configure(format(false), holder.surface, null, 0)
        }
        c.start()
        val info = MediaCodec.BufferInfo()
        val buf = ByteArray(4 * 1024 * 1024)
        var len = 0
        val chunk = ByteArray(256 * 1024)
        while (running) {
            val n = input.read(chunk)
            if (n < 0) break
            if (len + n > buf.size) len = 0 // a unit bigger than 4 MB is not a picture we can use
            System.arraycopy(chunk, 0, buf, len, n)
            len += n
            // Every complete unit: from one start code up to the next.
            var start = startCode(buf, 0, len)
            while (start >= 0) {
                val next = startCode(buf, start + 3, len)
                if (next < 0) break
                queue(c, buf, start, next - start)
                start = next
            }
            if (start > 0) { System.arraycopy(buf, start, buf, 0, len - start); len -= start }
            drain(c, info)
        }
    }

    private fun startCode(b: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i + 3 <= to) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                // A four-byte start code begins one earlier.
                return if (i > from && b[i - 1].toInt() == 0) i - 1 else i
            }
            i++
        }
        return -1
    }

    private fun queue(c: MediaCodec, b: ByteArray, off: Int, n: Int) {
        val idx = c.dequeueInputBuffer(20_000)
        if (idx < 0) return
        val ib = c.getInputBuffer(idx) ?: return
        ib.clear()
        ib.put(b, off, minOf(n, ib.capacity()))
        val head = if (b[off + 2].toInt() == 1) off + 3 else off + 4
        val type = b[head].toInt() and 0x1F
        val flags = if (type == 7 || type == 8) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
        c.queueInputBuffer(idx, 0, minOf(n, ib.capacity()), SystemClock.elapsedRealtimeNanos() / 1000, flags)
    }

    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val out = c.dequeueOutputBuffer(info, 0)
            when {
                out >= 0 -> {
                    c.releaseOutputBuffer(out, true) // show every picture the moment it is ready
                    if (BuildConfig.DEBUG) countFrame()
                }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val of = c.outputFormat
                    val w = of.getInteger(MediaFormat.KEY_WIDTH)
                    val h = of.getInteger(MediaFormat.KEY_HEIGHT)
                    runOnUiThread { fit(w, h) }
                }
                else -> return
            }
        }
    }

    private var frames = 0
    private var framesSince = 0L

    /** Debug builds: pictures shown each second, in the log (adb logcat -s SecondScreen). */
    private fun countFrame() {
        frames++
        val now = SystemClock.elapsedRealtime()
        if (framesSince == 0L) framesSince = now
        if (now - framesSince >= 1000) {
            Log.d(TAG, "fps $frames at ${picW}x$picH")
            frames = 0; framesSince = now
        }
    }

    /** Sizes the picture to the screen without stretching it. */
    private fun fit(w: Int, h: Int) {
        picW = w; picH = h
        val root = surface.parent as View
        val sw = root.width.toFloat(); val sh = root.height.toFloat()
        if (sw <= 0 || sh <= 0 || w <= 0 || h <= 0) return
        val k = minOf(sw / w, sh / h)
        surface.layoutParams = FrameLayout.LayoutParams((w * k).toInt(), (h * k).toInt(), Gravity.CENTER)
    }

    // -------------------------------------------------------------- touch

    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var scrolling = false
    private var held = false
    private var lastMidY = 0f
    private var lastMidX = 0f
    private val longPress = Runnable {
        if (!dragging && !scrolling && !held) {
            held = true
            at(downX, downY); Control.send("c r")
            surface.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /** Where on the laptop's monitor a point of the picture is, as fractions of its width and height. */
    private fun at(x: Float, y: Float) {
        val fx = (x / surface.width).coerceIn(0f, 1f)
        val fy = (y / surface.height).coerceIn(0f, 1f)
        Control.send("da " + String.format(java.util.Locale.US, "%.5f %.5f", fx, fy))
    }

    // Zoom: the picture scaled about its top left corner and moved, kept covering the screen.
    private var zoom = 1f
    private var pinching = false
    private var startDist = 0f
    private var startZoom = 1f
    /** The point of the picture (in its own pixels) under the fingers when the pinch began. */
    private var anchorX = 0f
    private var anchorY = 0f

    /** Where a finger is on the picture's frame before zooming: the screen point, less where the frame sits. */
    private fun framePoint(e: MotionEvent, i: Int): Pair<Float, Float> {
        val loc = IntArray(2)
        (surface.parent as View).getLocationOnScreen(loc)
        return (e.getRawX(i) - loc[0] - surface.left) to (e.getRawY(i) - loc[1] - surface.top)
    }

    private fun mid(e: MotionEvent): Pair<Float, Float> {
        val a = framePoint(e, 0); val b = framePoint(e, 1)
        return (a.first + b.first) / 2 to (a.second + b.second) / 2
    }

    private fun dist(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)) * zoom

    private fun applyZoom(tx: Float, ty: Float) {
        surface.pivotX = 0f; surface.pivotY = 0f
        surface.scaleX = zoom; surface.scaleY = zoom
        surface.translationX = tx.coerceIn(surface.width * (1 - zoom), 0f)
        surface.translationY = ty.coerceIn(surface.height * (1 - zoom), 0f)
    }

    private fun touch(e: MotionEvent) {
        val slop = 12 * resources.displayMetrics.density
        // Two fingers apart or together: a pinch, zooming the picture on the phone (nothing on the laptop).
        if (e.pointerCount >= 2 && e.actionMasked == MotionEvent.ACTION_MOVE) {
            if (!pinching && abs(dist(e) - startDist) > slop * 2) {
                pinching = true
                scrolling = false
                val (mx, my) = mid(e)
                startZoom = zoom
                anchorX = (mx - surface.translationX) / zoom
                anchorY = (my - surface.translationY) / zoom
                startDist = dist(e)
            }
            if (pinching) {
                zoom = (startZoom * dist(e) / startDist.coerceAtLeast(1f)).coerceIn(1f, 5f)
                val (mx, my) = mid(e)
                applyZoom(mx - anchorX * zoom, my - anchorY * zoom)
                return
            }
        }
        if (pinching && (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)) {
            pinching = false
            if (zoom < 1.05f) { zoom = 1f; applyZoom(0f, 0f) }
            return
        }
        if (pinching) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = e.eventTime; downX = e.x; downY = e.y
                dragging = false; scrolling = false; held = false
                surface.postDelayed(longPress, 500)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                surface.removeCallbacks(longPress)
                if (dragging) { Control.send("b l u"); dragging = false }
                scrolling = true
                startDist = dist(e)
                lastMidX = (e.getX(0) + e.getX(1)) / 2; lastMidY = (e.getY(0) + e.getY(1)) / 2
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrolling && e.pointerCount >= 2) {
                    val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                    if (zoom > 1f) {
                        // Zoomed in: two fingers move round the picture rather than scroll the laptop.
                        applyZoom(surface.translationX + (mx - lastMidX) * zoom, surface.translationY + (my - lastMidY) * zoom)
                        lastMidX = mx; lastMidY = my
                        return
                    }
                    val dy = ((my - lastMidY) * 4).toInt(); val dx = ((lastMidX - mx) * 4).toInt()
                    if (abs(dy) >= 1 || abs(dx) >= 1) { Control.send("w $dy $dx"); lastMidX = mx; lastMidY = my }
                } else if (!scrolling && !held) {
                    if (!dragging && hypot(e.x - downX, e.y - downY) > slop) {
                        surface.removeCallbacks(longPress)
                        dragging = true
                        at(downX, downY); Control.send("b l d")
                    }
                    if (dragging) at(e.x, e.y)
                }
            }
            MotionEvent.ACTION_UP -> {
                surface.removeCallbacks(longPress)
                when {
                    dragging -> { at(e.x, e.y); Control.send("b l u") }
                    !scrolling && !held -> { at(e.x, e.y); Control.send("c l") }
                }
                dragging = false; scrolling = false; held = false
            }
            MotionEvent.ACTION_CANCEL -> {
                surface.removeCallbacks(longPress)
                if (dragging) Control.send("b l u")
                dragging = false; scrolling = false; held = false
            }
        }
    }

    companion object {
        private const val TAG = "SecondScreen"
        const val PORT = 8791
        /** How long to wait for the laptop's next stream after one ends. */
        private const val RECONNECT_MS = 15_000
    }
}
