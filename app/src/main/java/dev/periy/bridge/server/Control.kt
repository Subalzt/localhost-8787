package dev.periy.bridge.server

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone as the laptop's trackpad and keyboard.
 *
 * A browser tab is not allowed to move the cursor or press keys, so the laptop runs a small
 * helper (tools/blazeit-pc.bat) that pairs like any other computer, then holds one long
 * request open on `/api/control/stream`. Everything the trackpad screen does becomes one
 * short text line on that stream, which the helper turns into real input with SendInput.
 *
 * Lines (all numbers are integers):
 *   m dx dy        move the pointer
 *   b l|r|m d|u    button down / up
 *   c l|r|m        click
 *   w dy dx        wheel, 120 = one notch; smaller values scroll smoothly
 *   z n            zoom n notches (Ctrl + wheel), negative zooms out
 *   kd name        key down     ku name   key up     k name   tap a key
 *   h a+b+c        press a shortcut, e.g. h win+tab
 *   t text         type text (URL-encoded)
 *   p              keep-alive
 */
object Control {

    private const val TAG = "Control"

    /** One queue per connected helper; lines are fanned out to all of them. */
    private val helpers = CopyOnWriteArrayList<Channel<String>>()

    /** When a helper last said it was closing on purpose (not asleep or off the internet). */
    @Volatile var byeAt = 0L

    private val _connected = MutableStateFlow<List<String>>(emptyList())
    /** Names of the laptops whose helper is listening right now. */
    val connected: StateFlow<List<String>> = _connected.asStateFlow()
    private val names = CopyOnWriteArrayList<Pair<Channel<String>, String>>()
    /** Each helper's queue by its device id, for lines meant for one laptop (another laptop's page driving it). */
    private val byDevice = java.util.concurrent.ConcurrentHashMap<Channel<String>, String>()

    /**
     * True while the Control tab is on screen. Otherwise nothing will be sent, so the
     * stream to the helper only needs a rare keep-alive instead of one every few seconds.
     */
    @Volatile var inUse = false

    /** The laptop's master volume (0..1) and mute, as its helper last reported; level -1 until it has. */
    data class Volume(val level: Float = -1f, val muted: Boolean = false)

    private val _volume = MutableStateFlow(Volume())
    val volume: StateFlow<Volume> = _volume.asStateFlow()

    fun reportVolume(level: Float, muted: Boolean) { _volume.value = Volume(level.coerceIn(0f, 1f), muted) }

    fun send(line: String) {
        for (h in helpers) h.trySend(line)
    }

    /** One laptop only: [deviceId] is its helper's. */
    fun sendTo(deviceId: String, line: String) {
        for ((ch, id) in byDevice) if (id == deviceId) ch.trySend(line)
    }

    /** The helpers listening now, by device id. */
    fun online(): Set<String> = byDevice.values.toSet()

    fun attach(name: String, deviceId: String? = null): Channel<String> {
        // Unlimited: a stalled laptop must never block the touch thread. The writer
        // drains whatever has piled up in one go, so a backlog clears in one flush.
        val ch = Channel<String>(Channel.UNLIMITED)
        helpers += ch
        names += ch to name
        if (deviceId != null) byDevice[ch] = deviceId
        publish()
        return ch
    }

    fun detach(ch: Channel<String>) {
        helpers -= ch
        names.removeAll { it.first === ch }
        byDevice.remove(ch)
        ch.close()
        publish()
    }

    private fun publish() {
        _connected.value = names.map { it.second }
        // Pages list the laptops they can see and drive (BridgeServer's /api/laptops).
        EventBus.emit("laptops", "")
    }

    // ------------------------------------------------------------------ discovery

    /**
     * Answers "XOOSH?" broadcasts so the laptop helper can find the phone without being
     * told its address -- which changes every time the hotspot restarts.
     */
    class Beacon(private val ctx: Context, private val httpPort: Int, private val deviceName: String) {
        @Volatile private var socket: DatagramSocket? = null
        // Many phones filter broadcast packets out in the Wi-Fi chip to save power; this
        // lock asks for them to be let through while the server is running.
        private var multicast: WifiManager.MulticastLock? = null

        fun start() {
            if (socket != null) return
            val s = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
            }.getOrElse {
                Log.w(TAG, "Discovery port $DISCOVERY_PORT unavailable", it)
                return
            }
            socket = s
            multicast = runCatching {
                ctx.applicationContext.getSystemService(WifiManager::class.java)
                    ?.createMulticastLock("xoosh-discovery")?.apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
            Thread({
                val buf = ByteArray(64)
                while (!s.isClosed) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        if (String(p.data, 0, p.length, Charsets.US_ASCII).trim() != "XOOSH?") continue
                        val reply = "XOOSH $httpPort $deviceName".toByteArray(Charsets.UTF_8)
                        s.send(DatagramPacket(reply, reply.size, p.socketAddress))
                    } catch (_: SocketException) {
                        break
                    } catch (t: Throwable) {
                        Log.w(TAG, "Discovery reply failed", t)
                    }
                }
            }, "xoosh-beacon").apply { isDaemon = true }.start()
        }

        fun stop() {
            socket?.close()
            socket = null
            runCatching { multicast?.takeIf { it.isHeld }?.release() }
            multicast = null
        }
    }

    const val DISCOVERY_PORT = 8788
}
