package dev.periy.bridge.server

import android.util.Log
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.YuvHelper
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Several pictures made into one, for a call bigger than a mesh carries: the phone that started it
 * draws each phone's latest frame into a grid ([WIDTH] by [HEIGHT], [FPS] times a second), each
 * cropped to fill its tile and turned upright, the one talking framed in green, and sends that one
 * picture to everyone. Each phone then sends its own picture once (to the host) and gets one back,
 * however many are in the call.
 */
class CallCompositor(factory: PeerConnectionFactory) {
    val source: VideoSource = factory.createVideoSource(false)
    val track: VideoTrack = factory.createVideoTrack("group", source)

    private inner class Slot(val key: String, val video: VideoTrack) : VideoSink {
        @Volatile var frame: VideoFrame? = null
        @Volatile var speaking = false
        override fun onFrame(f: VideoFrame) {
            f.retain()
            val old = synchronized(this) { frame.also { frame = f } }
            old?.release()
        }
        fun clear() { synchronized(this) { frame.also { frame = null } }?.release() }
    }

    private val lock = Any()
    private val slots = LinkedHashMap<String, Slot>()
    @Volatile private var running = true
    private val thread = Thread({ loop() }, "call-grid").apply { isDaemon = true }

    init {
        source.capturerObserver.onCapturerStarted(true)
        thread.start()
    }

    /** The pictures to draw now, in order: (a key, its track); tracks gone are let go. */
    fun set(sources: List<Pair<String, VideoTrack>>) {
        synchronized(lock) {
            val want = sources.associate { it.first to it.second }
            slots.values.filter { want[it.key] !== it.video }.forEach { s ->
                runCatching { s.video.removeSink(s) }
                s.clear()
                slots.remove(s.key)
            }
            for ((k, t) in sources) if (k !in slots) {
                val s = Slot(k, t)
                runCatching { t.addSink(s) }
                slots[k] = s
            }
            // Keep the order asked for.
            val ordered = sources.mapNotNull { slots[it.first] }
            slots.clear()
            ordered.forEach { slots[it.key] = it }
        }
    }

    fun speaking(key: String, on: Boolean) { synchronized(lock) { slots[key]?.speaking = on } }

    fun release() {
        running = false
        synchronized(lock) {
            slots.values.forEach { runCatching { it.video.removeSink(it) }; it.clear() }
            slots.clear()
        }
        runCatching { source.capturerObserver.onCapturerStopped() }
    }

    private fun loop() {
        val period = 1000L / FPS
        while (running) {
            val t0 = System.currentTimeMillis()
            runCatching { draw() }.onFailure { Log.w(TAG, "Drawing the grid", it) }
            val left = period - (System.currentTimeMillis() - t0)
            if (left > 0) Thread.sleep(left)
        }
    }

    private fun draw() {
        val tiles = synchronized(lock) {
            slots.values.map { s -> Triple(s, synchronized(s) { s.frame?.also { it.retain() } }, s.speaking) }
        }
        if (tiles.isEmpty()) return
        val out = JavaI420Buffer.allocate(WIDTH, HEIGHT)
        fill(out.dataY, 16); fill(out.dataU, 128); fill(out.dataV, 128)
        val n = tiles.size
        val cols = ceil(sqrt(n.toDouble())).toInt()
        val rows = ceil(n / cols.toDouble()).toInt()
        val tw = (WIDTH / cols) and 3.inv()
        val th = (HEIGHT / rows) and 3.inv()
        tiles.forEachIndexed { i, (_, frame, speaking) ->
            val row = i / cols
            // A last row with fewer tiles sits in the middle.
            val inRow = if (row == rows - 1) n - row * cols else cols
            val x0 = (((WIDTH - inRow * tw) / 2 + (i % cols) * tw)) and 1.inv()
            val y0 = (((HEIGHT - rows * th) / 2 + row * th)) and 1.inv()
            if (frame != null) {
                runCatching { tile(frame, out, x0 + GAP, y0 + GAP, tw - GAP * 2, th - GAP * 2) }
                frame.release()
            }
            if (speaking) frame(out, x0 + GAP, y0 + GAP, tw - GAP * 2, th - GAP * 2)
        }
        val f = VideoFrame(out, 0, System.nanoTime())
        source.capturerObserver.onFrameCaptured(f)
        f.release()
    }

    /** [f] cropped to fill w by h (both even) and turned upright, drawn at (x, y) in [out]. */
    private fun tile(f: VideoFrame, out: JavaI420Buffer, x: Int, y: Int, w0: Int, h0: Int) {
        val w = w0 and 1.inv(); val h = h0 and 1.inv()
        if (w <= 0 || h <= 0) return
        val rot = f.rotation
        val sideways = rot == 90 || rot == 270
        val rw = f.rotatedWidth; val rh = f.rotatedHeight
        // The part of the upright picture with the tile's shape, in the middle; then in the buffer's own turn.
        val (cw, ch) = if (rw.toLong() * h > rh.toLong() * w) (rh.toLong() * w / h).toInt() to rh else rw to (rw.toLong() * h / w).toInt()
        val bw = if (sideways) ch else cw
        val bh = if (sideways) cw else ch
        val buf = f.buffer
        val sx = ((buf.width - bw) / 2).coerceAtLeast(0) and 1.inv()
        val sy = ((buf.height - bh) / 2).coerceAtLeast(0) and 1.inv()
        val scaled = buf.cropAndScale(sx, sy, bw.coerceAtMost(buf.width), bh.coerceAtMost(buf.height), if (sideways) h else w, if (sideways) w else h)
        val i420 = scaled.toI420()
        scaled.release()
        if (i420 == null) return
        try {
            val dy = at(out.dataY, out.strideY, x, y)
            val du = at(out.dataU, out.strideU, x / 2, y / 2)
            val dv = at(out.dataV, out.strideV, x / 2, y / 2)
            if (rot == 0) YuvHelper.I420Copy(i420.dataY, i420.strideY, i420.dataU, i420.strideU, i420.dataV, i420.strideV,
                dy, out.strideY, du, out.strideU, dv, out.strideV, w, h)
            else YuvHelper.I420Rotate(i420.dataY, i420.strideY, i420.dataU, i420.strideU, i420.dataV, i420.strideV,
                dy, out.strideY, du, out.strideU, dv, out.strideV, i420.width, i420.height, rot)
        } finally {
            i420.release()
        }
    }

    /** The plane from (x, y) on, as a buffer of its own. */
    private fun at(plane: ByteBuffer, stride: Int, x: Int, y: Int): ByteBuffer {
        val b = plane.duplicate()
        b.position(y * stride + x)
        return b.slice()
    }

    /** A green frame round a tile: the one talking. */
    private fun frame(out: JavaI420Buffer, x: Int, y: Int, w: Int, h: Int) {
        val t = 6
        fun box(bx: Int, by: Int, bw: Int, bh: Int) {
            for (yy in by until (by + bh).coerceAtMost(HEIGHT)) for (xx in bx until (bx + bw).coerceAtMost(WIDTH)) out.dataY.put(yy * out.strideY + xx, 170.toByte())
            for (yy in by / 2 until ((by + bh) / 2).coerceAtMost(HEIGHT / 2)) for (xx in bx / 2 until ((bx + bw) / 2).coerceAtMost(WIDTH / 2)) {
                out.dataU.put(yy * out.strideU + xx, 54.toByte()); out.dataV.put(yy * out.strideV + xx, 34.toByte())
            }
        }
        box(x, y, w, t); box(x, y + h - t, w, t); box(x, y, t, h); box(x + w - t, y, t, h)
    }

    private fun fill(b: ByteBuffer, v: Int) {
        val d = b.duplicate(); d.position(0)
        val chunk = ByteArray(minOf(d.remaining(), 64 * 1024)) { v.toByte() }
        while (d.hasRemaining()) d.put(chunk, 0, minOf(chunk.size, d.remaining()))
    }

    companion object {
        private const val TAG = "CallGrid"
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FPS = 15
        private const val GAP = 4
    }
}
