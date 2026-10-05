package dev.periy.bridge.server

import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue

/**
 * Where the laptop's screen arrives for the phone's screen view (ui/SecondScreen.kt): each stream
 * the laptop helper sends, H.264 as ffmpeg writes it, waits here for the view to show it.
 *
 * The helper posts it to the page's own port (`POST /api/display/stream`), so it comes the way
 * everything else from that laptop does: over the cable, the hotspot, Wi-Fi, or through the
 * tunnel from another network. An older helper connects to [PORT_OLD] instead, which the view
 * still listens on and hands in here the same way.
 */
object DisplayFeed {
    /**
     * One stream; [sid] is the helper's name for it, echoed in "displayack" so it knows what arrived.
     * [framed]: each picture comes whole with its length before it (u32, big-endian), so it is
     * decoded the moment it is in, not when the next one begins.
     */
    class Feed(val input: InputStream, val done: CompletableFuture<Unit>, val sid: String = "", val framed: Boolean = false)

    val feeds = LinkedBlockingQueue<Feed>()

    /** True while the screen view is open and taking streams. */
    @Volatile var open = false

    /**
     * Another laptop's page watching this laptop: the streams its helper sends for it (posted with
     * `v=` the view's id) wait here, one queue per view, for the page's own request to carry them on.
     */
    val views = java.util.concurrent.ConcurrentHashMap<String, LinkedBlockingQueue<Feed>>()

    /** The same laptop's sound for that page: AAC frames (ADTS), the oldest let go when the page falls behind. */
    val viewSound = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.channels.Channel<ByteArray>>()

    /** Every stream waiting, let go: the view has closed. */
    fun drain() {
        while (true) {
            val f = feeds.poll() ?: break
            runCatching { f.input.close() }
            f.done.complete(Unit)
        }
    }
}
