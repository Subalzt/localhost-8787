package dev.periy.bridge.net

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The tunnel across IPv4 (docs/tunnel-protocol.md, "Across IPv4"): when a device cannot reach the
 * phone's IPv6, both ends swap their public IPv4 addresses through a public message board, punch
 * through their NATs over UDP, and run the tunnel over a reliable stream on that path
 * ([UdpCarrier]). The board carries two sealed notes, a few hundred bytes; nothing else passes
 * through anything but the two ends.
 */
object Punch {
    const val BOARD = "https://ntfy.sh"
    val STUN = listOf("stun.l.google.com" to 19302, "stun.cloudflare.com" to 3478)
    /** A hard (symmetric) NAT's side opens this many sockets, each its own mapping. */
    const val SOCKETS = 256
    /** How long both ends knock before giving up. */
    const val PUNCH_MS = 15_000L
    /** A note older (or newer) than this is ignored. */
    const val NOTE_AGE_S = 120

    private val L_UP = "L87P/1 up".toByteArray()
    private val L_DOWN = "L87P/1 down".toByteArray()
    private val L_SEAL = "L87P/1 seal".toByteArray()
    private val L_SEAL_MAC = "L87P/1 seal mac".toByteArray()
    private val L_UDP = "L87P/1 udp".toByteArray()

    private val rng = SecureRandom()

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Where the device leaves notes for the phone, and the phone answers. Unguessable without the psk. */
    fun topicUp(psk: ByteArray) = "l87-" + hex(TunnelCrypto.hmac(psk, L_UP).copyOf(10))
    fun topicDown(psk: ByteArray) = "l87-" + hex(TunnelCrypto.hmac(psk, L_DOWN).copyOf(10))

    /** base64url(nonce[16] || SHAKE256-keystream-xored JSON || HMAC tag[16]). */
    fun seal(psk: ByteArray, json: String): String {
        val nonce = ByteArray(16).also(rng::nextBytes)
        val ct = json.toByteArray()
        TunnelCrypto.shake256(TunnelCrypto.hmac(psk, L_SEAL) + nonce, ct)
        val tag = TunnelCrypto.hmac16(TunnelCrypto.hmac(psk, L_SEAL_MAC), nonce, ct)
        return android.util.Base64.encodeToString(nonce + ct + tag, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    fun open(psk: ByteArray, text: String): String? = runCatching {
        val all = android.util.Base64.decode(text.trim(), android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
        if (all.size < 33) return null
        val nonce = all.copyOf(16)
        val ct = all.copyOfRange(16, all.size - 16)
        val tag = all.copyOfRange(all.size - 16, all.size)
        if (!TunnelCrypto.equal(TunnelCrypto.hmac16(TunnelCrypto.hmac(psk, L_SEAL_MAC), nonce, ct), tag)) return null
        TunnelCrypto.shake256(TunnelCrypto.hmac(psk, L_SEAL) + nonce, ct)
        String(ct)
    }.getOrNull()

    /** The two directions' packet keys for one punch [session]: device to phone, phone to device. */
    fun udpKeys(psk: ByteArray, session: ByteArray): Pair<ByteArray, ByteArray> {
        val k = TunnelCrypto.hmac(psk, L_UDP, session)
        return TunnelCrypto.hmac(k, "dev".toByteArray()) to TunnelCrypto.hmac(k, "phone".toByteArray())
    }

    /** One STUN Binding request (RFC 5389) from [sock]: the address the server saw it come from. */
    fun stun(sock: DatagramSocket, host: String, port: Int, timeoutMs: Int = 1500): InetSocketAddress? = runCatching {
        val server = InetAddress.getAllByName(host).firstOrNull { it is Inet4Address } ?: return null
        val tid = ByteArray(12).also(rng::nextBytes)
        val req = ByteBuffer.allocate(20).putShort(1).putShort(0).putInt(0x2112A442).put(tid).array()
        val buf = ByteArray(512)
        val old = sock.soTimeout
        try {
            sock.soTimeout = 500
            val deadline = System.currentTimeMillis() + timeoutMs
            var sent = 0L
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() - sent > 400) { sock.send(DatagramPacket(req, req.size, server, port)); sent = System.currentTimeMillis() }
                val p = DatagramPacket(buf, buf.size)
                try { sock.receive(p) } catch (e: SocketTimeoutException) { continue }
                if (p.length < 20 || !buf.copyOfRange(8, 20).contentEquals(tid)) continue
                return parseMapped(buf, p.length)
            }
            null
        } finally {
            sock.soTimeout = old
        }
    }.getOrNull()

    private fun parseMapped(b: ByteArray, n: Int): InetSocketAddress? {
        var i = 20
        while (i + 4 <= n) {
            val type = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            val v = i + 4
            if ((type == 0x20 || type == 0x01) && len >= 8 && b[v + 1].toInt() == 1) {
                var port = ((b[v + 2].toInt() and 0xFF) shl 8) or (b[v + 3].toInt() and 0xFF)
                val ip = b.copyOfRange(v + 4, v + 8)
                if (type == 0x20) {
                    port = port xor 0x2112
                    val cookie = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
                    for (k in 0 until 4) ip[k] = (ip[k].toInt() xor cookie[k].toInt()).toByte()
                }
                return InetSocketAddress(InetAddress.getByAddress(ip), port)
            }
            i = v + len + ((4 - len % 4) % 4)
        }
        return null
    }

    /**
     * How [sock] looks from outside: its public address, and whether the NAT is hard (a new port
     * for every destination, so the other side cannot know which port to answer).
     */
    fun mapped(sock: DatagramSocket): Pair<InetSocketAddress, Boolean>? {
        val seen = STUN.mapNotNull { (h, p) -> stun(sock, h, p) }
        val first = seen.firstOrNull() ?: return null
        return first to (seen.size > 1 && seen.any { it.port != first.port })
    }

    /** This phone's own IPv4 addresses on its networks, for a device behind the same router. */
    fun lanAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filter { it is Inet4Address && it.isSiteLocalAddress }
    }.getOrDefault(emptyList())

    fun addr(a: InetSocketAddress) = "${a.address.hostAddress}:${a.port}"

    fun parseAddr(s: String?): InetSocketAddress? = runCatching {
        val i = s!!.lastIndexOf(':')
        InetSocketAddress(InetAddress.getByName(s.substring(0, i)), s.substring(i + 1).toInt())
    }.getOrNull()

    internal fun session(hex: String?): ByteArray? = runCatching { unhex(hex!!).takeIf { it.size == 8 } }.getOrNull()

    /**
     * Knocks from [mine] (one socket, or [SOCKETS] of them when this side's NAT is hard) at the
     * other side's [theirs], spraying random ports on its address when its NAT is hard and this
     * side's is not, until a packet from the other side arrives: then that socket and that address
     * are the path. Null when nothing came back within [PUNCH_MS].
     */
    fun knock(
        main: DatagramSocket, hardHere: Boolean, theirs: List<InetSocketAddress>, hardThere: Boolean,
        tx: ByteArray, rx: ByteArray, role: Int,
    ): Pair<DatagramSocket, InetSocketAddress>? {
        val socks = ArrayList<DatagramSocket>().apply { add(main) }
        if (hardHere && !hardThere) repeat(SOCKETS - 1) { runCatching { socks.add(DatagramSocket(0)) } }
        val found = LinkedBlockingQueue<Pair<DatagramSocket, InetSocketAddress>>()
        val done = AtomicBoolean(false)
        val sealer = Sealer(tx)
        val checker = Sealer(rx)
        val probe = sealer.packet(UdpCarrier.PROBE, role, 0, 0, 0, 0, ByteArray(0))
        val ack = sealer.packet(UdpCarrier.PROBE_ACK, role, 0, 0, 0, 0, ByteArray(0))
        for (s in socks) {
            s.soTimeout = 250
            TunnelConnection.thread("punch-listen") {
                val buf = ByteArray(1500)
                while (!done.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (e: SocketTimeoutException) { continue } catch (e: IOException) { return@thread }
                    if (p.length < UdpCarrier.OVERHEAD || !checker.valid(buf, p.length)) continue
                    val kind = buf[0].toInt()
                    val from = p.socketAddress as InetSocketAddress
                    if (kind == UdpCarrier.PROBE) repeat(3) { runCatching { s.send(DatagramPacket(ack, ack.size, from)) } }
                    if (kind == UdpCarrier.PROBE || kind == UdpCarrier.PROBE_ACK) { found.offer(s to from); return@thread }
                }
            }
        }
        val spray = hardThere && !hardHere
        TunnelConnection.thread("punch-knock") {
            val start = System.currentTimeMillis()
            val ports = sprayOrder(theirs.firstOrNull()?.port ?: 0)
            var next = 0
            var round = 0L
            while (!done.get() && System.currentTimeMillis() - start < PUNCH_MS) {
                // Every socket at every known address, every 200 ms (keeps hard mappings open).
                if (System.currentTimeMillis() - round >= 200) {
                    round = System.currentTimeMillis()
                    for (s in socks) for (t in theirs) runCatching { s.send(DatagramPacket(probe, probe.size, t)) }
                }
                if (spray && theirs.isNotEmpty()) {
                    val host = theirs.first().address
                    repeat(30) {
                        if (next < ports.size) runCatching { main.send(DatagramPacket(probe, probe.size, host, ports[next++])) }
                    }
                    if (next >= ports.size) next = 0
                }
                Thread.sleep(if (spray) 100 else 50)
            }
        }
        val got = found.poll(PUNCH_MS + 500, TimeUnit.MILLISECONDS)
        done.set(true)
        for (s in socks) if (s !== got?.first) runCatching { s.close() }
        got?.first?.soTimeout = 0
        return got
    }

    /** The ports near the one STUN saw first (NATs that count up), then all the others in a random order. */
    private fun sprayOrder(seen: Int): IntArray {
        val near = (1..256).flatMap { listOf(seen + it, seen - it) }.filter { it in 1024..65535 }
        val rest = (1024..65535).filter { it !in near.toSet() && it != seen }.shuffled(rng)
        return (near + rest).toIntArray()
    }

    /**
     * The phone's side: listens on the board for paired devices' notes (only while the tunnel is
     * on). A "where" note gets the phone's current addresses back (its IPv6 changes when mobile
     * data reconnects, and a device away from it has no other way to learn the new one); a
     * "punch" note gets them too, and the phone punches back and hands the path to [serve].
     */
    class Listener(
        private val keys: TunnelKeys,
        private val deviceIds: () -> List<String>,
        /** The phone's HELLO: its name, addresses and tunnel port. */
        private val info: () -> String,
        private val serve: (TunnelLink, String) -> Unit,
    ) {
        @Volatile private var running = false
        @Volatile private var conn: HttpURLConnection? = null
        private val seen = ConcurrentHashMap<String, Long>()

        fun start() {
            if (running) return
            running = true
            TunnelConnection.thread("punch-board") { loop() }
            // A device paired or removed: listen again with the new topics.
            TunnelConnection.thread("punch-devices") {
                var known = deviceIds().toSet()
                while (running) {
                    Thread.sleep(30_000)
                    val now = deviceIds().toSet()
                    if (now != known) { known = now; refresh() }
                }
            }
        }

        fun stop() {
            running = false
            runCatching { conn?.disconnect() }
        }

        /** The device list changed: listen again with the new topics. */
        fun refresh() { runCatching { conn?.disconnect() } }

        private fun loop() {
            var backoff = 2_000L
            while (running) {
                val ids = deviceIds()
                if (ids.isEmpty()) { Thread.sleep(10_000); continue }
                val topics = ids.associateBy { topicUp(keys.psk(it)) }
                try {
                    val c = URL("$BOARD/${topics.keys.joinToString(",")}/json").openConnection() as HttpURLConnection
                    conn = c
                    c.connectTimeout = 15_000
                    c.readTimeout = 120_000   // the board says something every 45 s
                    c.inputStream.bufferedReader().useLines { lines ->
                        backoff = 2_000L
                        for (line in lines) {
                            if (!running) break
                            val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                            if (o["event"]?.jsonPrimitive?.content != "message") continue
                            val id = topics[o["topic"]?.jsonPrimitive?.content] ?: continue
                            val text = o["message"]?.jsonPrimitive?.content ?: continue
                            TunnelConnection.thread("punch-answer") { answer(id, text) }
                        }
                    }
                } catch (e: Exception) {
                    if (running) Log.i(TAG, "Board: ${e.message}")
                } finally {
                    conn = null
                }
                if (running) { Thread.sleep(backoff); backoff = (backoff * 2).coerceAtMost(120_000L) }
            }
        }

        private fun answer(deviceId: String, text: String) {
            val psk = keys.psk(deviceId)
            val note = open(psk, text)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return
            val kind = note["t"]?.jsonPrimitive?.content
            if (kind != "punch" && kind != "where") return
            val at = note["at"]?.jsonPrimitive?.longOrNull ?: return
            if (kotlin.math.abs(System.currentTimeMillis() / 1000 - at) > NOTE_AGE_S) return
            val sHex = note["s"]?.jsonPrimitive?.content ?: return
            val session = session(sHex) ?: return
            if (seen.putIfAbsent(sHex, at) != null) return
            if (seen.size > 200) seen.entries.removeIf { System.currentTimeMillis() / 1000 - it.value > NOTE_AGE_S }
            val phone = runCatching { Json.parseToJsonElement(info()).jsonObject }.getOrNull()
            if (kind == "where") {
                val reply = buildJsonObject {
                    put("t", JsonPrimitive("where"))
                    put("s", JsonPrimitive(sHex))
                    put("at", JsonPrimitive(System.currentTimeMillis() / 1000))
                    phone?.get("addrs")?.let { put("addrs", it) }
                    phone?.get("port")?.let { put("port", it) }
                }.toString()
                post(topicDown(psk), seal(psk, reply))
                return
            }
            val theirs = listOfNotNull(parseAddr(note["addr"]?.jsonPrimitive?.content)) +
                (note["lan"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { parseAddr(it.jsonPrimitive.content) }
            val hardThere = note["hard"]?.jsonPrimitive?.booleanOrNull ?: false
            val sock = DatagramSocket(0)
            val me = mapped(sock)
            val reply = buildJsonObject {
                put("t", JsonPrimitive("punch"))
                put("s", JsonPrimitive(sHex))
                put("at", JsonPrimitive(System.currentTimeMillis() / 1000))
                me?.let { put("addr", JsonPrimitive(addr(it.first))); put("hard", JsonPrimitive(it.second)) }
                put("lan", kotlinx.serialization.json.JsonArray(lanAddresses().map { JsonPrimitive("${it.hostAddress}:${sock.localPort}") }))
                phone?.get("addrs")?.let { put("addrs", it) }
            }.toString()
            if (!post(topicDown(psk), seal(psk, reply))) { sock.close(); return }
            Log.i(TAG, "Punching to ${theirs.joinToString { addr(it) }} (here ${me?.first?.let(::addr)}, hard here ${me?.second}, there $hardThere)")
            val (toPhone, toDevice) = udpKeys(psk, session)
            val path = knock(sock, me?.second ?: false, theirs, hardThere, tx = toDevice, rx = toPhone, role = 1)
            if (path == null) { Log.i(TAG, "Punching: nothing came back"); runCatching { sock.close() }; return }
            Log.i(TAG, "Punched through to ${addr(path.second)}")
            val carrier = UdpCarrier(path.first, path.second, tx = toDevice, rx = toPhone, role = 1)
            serve(carrier, "${path.second.address.hostAddress} (UDP)")
        }

        private companion object { const val TAG = "Punch" }
    }

    /** Leaves [text] on the board under [topic], kept for nobody who is not listening right now. */
    fun post(topic: String, text: String): Boolean = runCatching {
        val c = URL("$BOARD/$topic").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("Cache", "no")
        c.setRequestProperty("Firebase", "no")
        c.outputStream.use { it.write(text.toByteArray()) }
        val ok = c.responseCode in 200..299
        runCatching { c.inputStream.close() }
        ok
    }.getOrDefault(false)
}

/** Packet tags: HMAC-SHA256 over the packet, the first 8 bytes. One key per direction. */
internal class Sealer(key: ByteArray) {
    private val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }

    @Synchronized
    fun tag(b: ByteArray, len: Int): ByteArray {
        mac.update(b, 0, len)
        return mac.doFinal()
    }

    /** A whole datagram: kind, role, seq, ack, sack, window, payload, tag. */
    fun packet(kind: Int, role: Int, seq: Int, ack: Int, sack: Long, wnd: Int, payload: ByteArray, off: Int = 0, len: Int = payload.size): ByteArray {
        val b = ByteArray(UdpCarrier.HEADER + len + UdpCarrier.TAG)
        ByteBuffer.wrap(b).put(kind.toByte()).put(role.toByte()).putInt(seq).putInt(ack).putLong(sack).putShort(wnd.toShort())
        System.arraycopy(payload, off, b, UdpCarrier.HEADER, len)
        val t = tag(b, UdpCarrier.HEADER + len)
        System.arraycopy(t, 0, b, UdpCarrier.HEADER + len, UdpCarrier.TAG)
        return b
    }

    fun valid(b: ByteArray, n: Int): Boolean {
        if (n < UdpCarrier.OVERHEAD) return false
        val t = tag(b, n - UdpCarrier.TAG)
        var diff = 0
        for (i in 0 until UdpCarrier.TAG) diff = diff or (t[i].toInt() xor b[n - UdpCarrier.TAG + i].toInt())
        return diff == 0
    }
}

/**
 * A reliable, ordered stream of bytes over one UDP path, for the tunnel to run on: numbered
 * packets, cumulative and selective acknowledgements, retransmission on loss or timeout, and a
 * congestion window that grows while nothing is lost and halves when something is. Every packet
 * is tagged with the direction's key; the path follows the other end if its address changes
 * (a NAT rebinding), as long as its packets check out.
 */
class UdpCarrier(
    private val sock: DatagramSocket,
    @Volatile private var peer: InetSocketAddress,
    tx: ByteArray, rx: ByteArray,
    /** 0 the device, 1 the phone: in every packet, so one end's packets are never taken as the other's. */
    private val role: Int,
) : TunnelLink {
    companion object {
        const val PROBE = 1
        const val PROBE_ACK = 2
        const val DATA = 3
        const val ACK = 4
        const val KEEP = 5
        const val CLOSE = 6
        const val HEADER = 20
        const val TAG = 8
        const val OVERHEAD = HEADER + TAG
        /** Payload per packet: with the headers, under IPv6's minimum MTU even after NAT64. */
        const val MSS = 1200
        const val MAX_QUEUE = 4096
        const val RECV_WINDOW = 4096
        private const val TAG_LOG = "UdpCarrier"
    }

    private val sealer = Sealer(tx)
    private val checker = Sealer(rx)
    private val lock = Object()
    /** New packets leave in the order they are numbered, whichever thread sends them. */
    private val order = java.util.concurrent.locks.ReentrantLock()
    /** Someone asked to send while another thread was at it. */
    private val again = AtomicBoolean(false)
    private val open = AtomicBoolean(true)

    // Sending.
    private class Sent(val seq: Int, val data: ByteArray, var at: Long, var tries: Int = 0)
    private val pending = ArrayDeque<ByteArray>()
    private val inflight = TreeMap<Int, Sent>()
    private var nextSeq = 0
    private var cwnd = 16.0
    private var ssthresh = 1e9
    private var recoverUntil = 0
    private var peerWnd = RECV_WINDOW
    private var srtt = 0.0
    private var rttvar = 0.0
    private var rto = 1000L
    // CUBIC: the window it last lost at, when growth since then began, and where the curve is.
    private var minRtt = 0.0
    private var wmax = 0.0
    private var epoch = -1L
    private var cubicK = 0.0
    private var origin = 0.0
    @Volatile private var lastSent = System.currentTimeMillis()

    // Receiving.
    private var expected = 0
    private val early = HashMap<Int, ByteArray>()
    private var unacked = 0
    private val chunks = LinkedBlockingQueue<ByteArray>()
    @Volatile private var lastHeard = System.currentTimeMillis()
    @Volatile private var readTimeout = 0

    private val EOF = ByteArray(0)

    init {
        sock.soTimeout = 200
        TunnelConnection.thread("udp-recv") { receiveLoop() }
        TunnelConnection.thread("udp-tick") { tickLoop() }
    }

    override fun setReadTimeout(ms: Int) { readTimeout = ms }

    override val input: InputStream = object : InputStream() {
        private var cur: ByteArray? = null
        private var pos = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var c = cur
            if (c == null || pos >= c.size) {
                val t = readTimeout
                c = if (t > 0) chunks.poll(t.toLong(), TimeUnit.MILLISECONDS) ?: throw SocketTimeoutException("nothing in $t ms")
                    else chunks.take()
                if (c === EOF) { chunks.offer(EOF); return -1 }
                cur = c; pos = 0
            }
            val n = minOf(len, c.size - pos)
            System.arraycopy(c, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = cur?.let { it.size - pos } ?: 0
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var p = off
            val end = off + len
            while (p < end) {
                val n = minOf(MSS, end - p)
                val chunk = b.copyOfRange(p, p + n)
                synchronized(lock) {
                    while (open.get() && pending.size + inflight.size >= MAX_QUEUE) lock.wait(1000)
                    if (!open.get()) throw IOException("the path is closed")
                    pending.add(chunk)
                }
                p += n
            }
            pump()
        }
    }

    private fun send(kind: Int, seq: Int, payload: ByteArray = EOF) {
        val (ack, sack, wnd) = synchronized(lock) { Triple(expected, sackBits(), RECV_WINDOW - early.size) }
        val b = sealer.packet(kind, role, seq, ack, sack, maxOf(0, wnd), payload)
        runCatching { sock.send(DatagramPacket(b, b.size, peer)) }
        lastSent = System.currentTimeMillis()
        if (kind == DATA || kind == ACK || kind == KEEP) synchronized(lock) { unacked = 0 }
    }

    /** Which of the 64 packets after the next expected one have already come. */
    private fun sackBits(): Long {
        var bits = 0L
        for (i in 0 until 64) if (early.containsKey(expected + 1 + i)) bits = bits or (1L shl i)
        return bits
    }

    /**
     * Sends what the window allows. One thread at a time, so packets leave in order; a thread that
     * finds another at it leaves it to that one, which goes round again.
     */
    private fun pump() {
        again.set(true)
        while (again.get()) {
            if (!order.tryLock()) return
            try {
                again.set(false)
                val out = ArrayList<Sent>()
                synchronized(lock) {
                    val room = minOf(cwnd.toInt(), peerWnd) - inflight.size
                    while (out.size < room && out.size < 256 && pending.isNotEmpty()) {
                        val s = Sent(nextSeq++, pending.removeFirst(), System.currentTimeMillis())
                        inflight[s.seq] = s
                        out.add(s)
                    }
                }
                for (s in out) send(DATA, s.seq, s.data)
            } finally {
                order.unlock()
            }
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        while (open.get()) {
            val p = DatagramPacket(buf, buf.size)
            try { sock.receive(p) } catch (e: SocketTimeoutException) { continue } catch (e: IOException) { break }
            val n = p.length
            if (n < OVERHEAD || !checker.valid(buf, n) || buf[1].toInt() == role) continue
            lastHeard = System.currentTimeMillis()
            val from = p.socketAddress as InetSocketAddress
            if (from != peer) peer = from   // the other end's NAT moved it
            val bb = ByteBuffer.wrap(buf, 0, n)
            val kind = bb.get().toInt()
            bb.get()
            val seq = bb.int
            val ack = bb.int
            val sack = bb.long
            val wnd = bb.short.toInt() and 0xFFFF
            when (kind) {
                PROBE -> {
                    // The other end has not heard us yet: say so again.
                    val a = sealer.packet(PROBE_ACK, role, 0, 0, 0, 0, EOF)
                    runCatching { sock.send(DatagramPacket(a, a.size, from)) }
                }
                CLOSE -> { finish("the other end closed the path"); return }
                DATA, ACK, KEEP -> {
                    acked(ack, sack, wnd)
                    if (kind == DATA) data(seq, buf.copyOfRange(HEADER, n - TAG))
                }
            }
        }
    }

    private fun data(seq: Int, payload: ByteArray) {
        var ackNow: Boolean
        synchronized(lock) {
            when {
                seq - expected < 0 -> ackNow = true   // a copy of one already had
                seq == expected -> {
                    chunks.offer(payload)
                    expected++
                    val filled = early.isNotEmpty()
                    while (true) { val e = early.remove(expected) ?: break; chunks.offer(e); expected++ }
                    unacked++
                    ackNow = filled || unacked >= 2
                }
                seq - expected < RECV_WINDOW -> { early[seq] = payload; ackNow = true }
                else -> ackNow = false
            }
        }
        if (ackNow) send(ACK, 0)
    }

    private fun acked(ack: Int, sack: Long, wnd: Int) {
        var resend: List<Sent> = emptyList()
        synchronized(lock) {
            peerWnd = maxOf(wnd, 4)
            val now = System.currentTimeMillis()
            var newly = 0
            var sample = -1L
            var cumulative = false
            var latest = -1L
            while (inflight.isNotEmpty() && inflight.firstKey() - ack < 0) {
                val s = inflight.pollFirstEntry()!!.value
                if (s.tries == 0) sample = now - s.at
                latest = maxOf(latest, s.at)
                newly++; cumulative = true
            }
            for (i in 0 until 64) if (sack and (1L shl i) != 0L) {
                inflight.remove(ack + 1 + i)?.let { if (it.tries == 0) sample = now - it.at; latest = maxOf(latest, it.at); newly++ }
            }
            if (sample >= 0) {
                if (srtt == 0.0) { srtt = sample.toDouble(); rttvar = sample / 2.0 }
                else { rttvar = 0.75 * rttvar + 0.25 * kotlin.math.abs(srtt - sample); srtt = 0.875 * srtt + 0.125 * sample }
                minRtt = if (minRtt == 0.0) sample.toDouble() else minOf(minRtt, sample.toDouble())
            }
            if (cumulative) rto = (srtt + maxOf(4 * rttvar, 200.0)).toLong().coerceAtMost(3000L)   // Linux: at least 200 ms over the round trip
            grow(newly, now)
            // Three or more later packets arrived and this one did not: lost, send it again.
            // A packet sent after this one has come and this one has not (allowing a little
            // reordering): lost, send it again. Originals leave in order, so the first one never
            // resent and sent too late to count ends the search.
            val lost = ArrayList<Sent>()
            if (latest >= 0) {
                val reo = maxOf(srtt / 4, 5.0)
                for (it in inflight.values) {
                    if (lost.size >= 64) break
                    if (it.at < latest - reo) lost.add(it) else if (it.tries == 0) break
                }
            }
            if (lost.isNotEmpty()) {
                // Once per window: one loss, one step back.
                if (lost.first().seq - recoverUntil >= 0) {
                    wmax = cwnd; epoch = -1L
                    ssthresh = maxOf(cwnd * 0.7, 8.0); cwnd = ssthresh; recoverUntil = nextSeq
                }
                lost.forEach { it.at = now; it.tries++ }
                resend = lost
            }
            lock.notifyAll()
        }
        for (s in resend) send(DATA, s.seq, s.data)
        pump()
    }

    /**
     * The window after [n] packets were acknowledged, as Linux grows it: doubling each round trip
     * until the first loss, then CUBIC, back to the size it last lost at in a few seconds whatever
     * the round trip, and probing past it; never slower than Reno's one packet per round trip.
     * Under [lock].
     */
    private fun grow(n: Int, now: Long) {
        if (n == 0) return
        if (cwnd < ssthresh) {
            cwnd += n
        } else {
            if (epoch < 0) {
                epoch = now
                cubicK = Math.cbrt(maxOf(0.0, wmax - cwnd) / 0.4)
                origin = maxOf(wmax, cwnd)
            }
            val t = (now - epoch + minRtt) / 1000.0
            val target = origin + 0.4 * Math.pow(t - cubicK, 3.0)
            cwnd += n * maxOf(target - cwnd, 1.0) / cwnd
        }
        cwnd = cwnd.coerceAtMost(2048.0)
    }

    private fun tickLoop() {
        while (open.get()) {
            Thread.sleep(10)
            val now = System.currentTimeMillis()
            var resend: List<Sent> = emptyList()
            var ackDue = false
            synchronized(lock) {
                // Nothing back for the oldest packet in a whole timeout: send it again, and start
                // slow (once per loss; the acks for it then show what else is missing).
                val first = if (inflight.isEmpty()) null else inflight.firstEntry()!!.value
                if (first != null && now - first.at > rto) {
                    if (first.seq - recoverUntil >= 0) { wmax = cwnd; epoch = -1L; ssthresh = maxOf(cwnd * 0.7, 8.0); cwnd = 8.0; recoverUntil = nextSeq }
                    rto = (rto * 2).coerceAtMost(5000L)
                    first.at = now; first.tries++
                    resend = listOf(first)
                }
                ackDue = unacked > 0
            }
            for (s in resend) send(DATA, s.seq, s.data)
            if (ackDue) send(ACK, 0)
            pump()
            if (now - lastSent > 5_000) send(KEEP, 0)
            if (now - lastHeard > 60_000) finish("nothing from the other end for 60 s")
        }
    }

    private fun finish(why: String) {
        if (!open.compareAndSet(true, false)) return
        Log.i(TAG_LOG, "Path to ${Punch.addr(peer)} closed: $why")
        chunks.offer(EOF)
        synchronized(lock) { lock.notifyAll() }
        runCatching { sock.close() }
    }

    override fun close() {
        if (open.get()) repeat(3) { runCatching { send(CLOSE, 0) } }
        finish("closed here")
    }
}
