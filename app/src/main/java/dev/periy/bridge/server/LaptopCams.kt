package dev.periy.bridge.server

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A laptop's webcam as one of the cameras (server/Cameras.kt): its helper streams the webcam the
 * way it streams its screen to another laptop's page ("display start ... view=ID webcam"), H.264
 * each picture whole, and its microphone as AAC when someone listens; here it is passed on in the
 * cameras' own format (each frame's length, top bit for sound, then when it came in ms), to a page
 * or straight into the app. It runs only while someone watches.
 */
object LaptopCams {
    private const val TAG = "LaptopCams"
    /** The target of a laptop's webcam in the control centre. */
    const val PREFIX = "laptop:"

    /**
     * Laptop [id]'s webcam as a stream of camera frames; closing it stops the webcam. [far]: the
     * viewer is on another network (a smaller picture to start, as the screen's far one).
     */
    fun open(id: String, far: Boolean, listen: Boolean): InputStream {
        val vid = java.util.UUID.randomUUID().toString().take(12)
        val queue = LinkedBlockingQueue<DisplayFeed.Feed>()
        DisplayFeed.views[vid] = queue
        val sound = Channel<ByteArray>(64, BufferOverflow.DROP_OLDEST)
        if (listen) DisplayFeed.viewSound[vid] = sound
        val input = object : PipedInputStream(64 * 1024) {
            @Volatile var closed = false
            override fun close() { closed = true; super.close() }
        }
        val out = PipedOutputStream(input)
        val gate = Any()
        val ackSid = java.util.concurrent.atomic.AtomicReference("")
        val ackBytes = AtomicLong(0)
        val live = java.util.concurrent.atomic.AtomicBoolean(true)

        fun header(n: Int, soundFrame: Boolean): ByteArray {
            val len = if (soundFrame) n or Int.MIN_VALUE else n
            val ms = System.currentTimeMillis().toInt()
            return byteArrayOf(
                (len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte(),
                (ms ushr 24).toByte(), (ms ushr 16).toByte(), (ms ushr 8).toByte(), ms.toByte(),
            )
        }

        fun finish() {
            if (!live.getAndSet(false)) return
            DisplayFeed.views.remove(vid)?.let { q -> while (true) { val f = q.poll() ?: break; runCatching { f.input.close() }; f.done.complete(Unit) } }
            DisplayFeed.viewSound.remove(vid)?.close()
            EventBus.emitTo(id, "display", "stop")
            runCatching { out.close() }
        }

        // The pictures: each whole, as the helper frames them, on with a time.
        Thread({
            try {
                var first = true
                while (live.get() && !input.closed) {
                    val feed = queue.poll(if (first) 30L else 15L, TimeUnit.SECONDS) ?: break
                    first = false
                    ackSid.set(feed.sid); ackBytes.set(0)
                    val d = DataInputStream(feed.input)
                    var sent = 0L
                    try {
                        while (live.get()) {
                            val n = d.readInt()
                            if (n < 0 || n > 64 * 1024 * 1024) break
                            val au = ByteArray(n)
                            d.readFully(au)
                            synchronized(gate) { out.write(header(n, false)); out.write(au); out.flush() }
                            sent += 4 + n
                            ackBytes.set(sent)
                        }
                    } catch (_: IOException) {
                    } finally {
                        runCatching { feed.input.close() }
                        feed.done.complete(Unit)
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "Webcam of $id: ${e.message}")
            } finally { finish() }
        }, "laptop-cam").apply { isDaemon = true; start() }

        // What has gone on, told back to the helper every 50 ms: it waits for word before sending more.
        Thread({
            var last = -1L
            while (live.get()) {
                runCatching { Thread.sleep(50) }
                val n = ackBytes.get()
                if (n != last && ackSid.get().isNotEmpty()) { EventBus.emitTo(id, "displayack", "${ackSid.get()} $n"); last = n }
            }
        }, "laptop-cam-ack").apply { isDaemon = true; start() }

        // The sound, between pictures.
        if (listen) Thread({
            try {
                runBlocking { for (f in sound) { if (!live.get()) break; synchronized(gate) { out.write(header(f.size, true)); out.write(f); out.flush() } } }
            } catch (_: Exception) {
            }
        }, "laptop-cam-sound").apply { isDaemon = true; start() }

        EventBus.emitTo(id, "display", "start 0 1280 720 30 0 http view=$vid mirror webcam" + (if (far) " far" else "") + (if (listen) " listen" else ""))
        return input
    }
}
