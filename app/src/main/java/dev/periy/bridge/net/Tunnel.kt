package dev.periy.bridge.net

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The L87 tunnel (docs/tunnel-protocol.md): how a laptop or another phone reaches this phone
 * from a different network. One TCP connection, our own encryption, every connection a stream.
 */
object TunnelProto {
    const val PORT = 8789
    /**
     * Where the phone's end hands each stream to the page: a loopback address of its own, so the
     * page can tell a request that came through the tunnel from one through adb's 127.0.0.1.
     */
    const val LOCAL_HOST = "127.0.0.87"
    const val HELLO = 1
    const val OPEN = 2
    const val DATA = 3
    const val FIN = 4
    const val RST = 5
    const val CREDIT = 6
    const val PING = 7
    const val PONG = 8
    const val ADDR = 9
    const val BYE = 10

    const val WINDOW = 512 * 1024
    const val CHUNK = 16384
    const val CREDIT_STEP = 128 * 1024
    const val MAX_FRAME = 65536 + 5
    const val IDLE_PING_MS = 20_000L
    const val DEAD_MS = 60_000L
    const val HELLO_LEN = 69

    val MAGIC = byteArrayOf('L'.code.toByte(), '8'.code.toByte(), '7'.code.toByte(), 'T'.code.toByte(), 1)
    val L_PSK = "L87T/1 psk".toByteArray()
    val L_ID = "L87T/1 id".toByteArray()
    val L_HELLO = "L87T/1 hello".toByteArray()
    val L_KEYS = "L87T/1 keys".toByteArray()
    val L_ACCEPT = "L87T/1 accept".toByteArray()

    /** The five keys of a connection, from the exchange: c2s enc/mac, s2c enc/mac, confirm. */
    fun keys(psk: ByteArray, dh: ByteArray, hello: ByteArray, eS: ByteArray): Pair<List<ByteArray>, ByteArray> {
        val th = TunnelCrypto.sha256(hello, eS)
        val okm = TunnelCrypto.hkdfExpand(TunnelCrypto.hmac(psk, dh), L_KEYS + th, 160)
        return (0 until 5).map { okm.copyOfRange(it * 32, it * 32 + 32) } to th
    }
}

/**
 * What a tunnel connection runs over: a TCP socket, or a path punched through two NATs over UDP
 * (UdpCarrier, for IPv4; see Punch.kt). Either way an ordered, reliable stream of bytes.
 */
interface TunnelLink {
    val input: java.io.InputStream
    val output: java.io.OutputStream
    /** How long a read may wait before failing; 0 for ever. */
    fun setReadTimeout(ms: Int)
    fun close()
}

class SocketLink(val socket: Socket) : TunnelLink {
    override val input: java.io.InputStream = socket.getInputStream()
    override val output: java.io.OutputStream = socket.getOutputStream()
    override fun setReadTimeout(ms: Int) { socket.soTimeout = ms }
    override fun close() = socket.close()
}

/** The phone's tunnel secret, and each paired device's keys derived from it. */
class TunnelKeys(filesDir: File) {
    private val file = File(filesDir, "tunnel.key")
    private val secret: ByteArray = runCatching { file.readBytes() }.getOrNull()?.takeIf { it.size == 32 }
        ?: ByteArray(32).also {
            SecureRandom().nextBytes(it)
            runCatching { file.writeBytes(it) }
        }

    /** The link code open now ([LinkCode]), its secret, and until when. */
    private class Open(val code: String, val secret: ByteArray, val until: Long)
    @Volatile private var code: Open? = null

    fun openCode(c: String, s: ByteArray) { code = Open(c, s, System.currentTimeMillis() + LinkCode.TTL_MS) }
    fun closeCode() { code = null }
    private fun open(): Open? = code?.takeIf { it.until > System.currentTimeMillis() }
    /** The open link code, or null when there is none or it has run out. */
    fun openCode(): String? = open()?.code
    /** Keys nothing has: for the code's stand-in once it has closed. */
    private val none = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun psk(deviceId: String): ByteArray =
        if (deviceId == LinkCode.ID) LinkCode.psk(open()?.secret ?: none)
        else TunnelCrypto.hmac(secret, TunnelProto.L_PSK, deviceId.toByteArray())
    fun tid(deviceId: String): ByteArray =
        if (deviceId == LinkCode.ID) LinkCode.tid(open()?.secret ?: none)
        else TunnelCrypto.hmac(secret, TunnelProto.L_ID, deviceId.toByteArray()).copyOf(16)
}

/**
 * One tunnel connection, either end. The reader runs in [run]; each stream has a thread that
 * reads its local socket and one that writes to it, so a slow reader only holds up its stream.
 */
class TunnelConnection(
    private val link: TunnelLink,
    private val tx: TunnelCipher,
    private val rx: TunnelCipher,
    /** Who is at the other end: the device's id on the phone, the phone's name on a client. */
    val peer: String,
    val remote: String,
    private val client: Boolean,
    /** On the phone: a local connection for a stream the other end opens to [Int] (a port). */
    private val connectLocal: ((Int) -> Socket?)? = null,
    private val onInfo: (JsonObject) -> Unit = {},
    private val onClosed: (TunnelConnection) -> Unit = {},
) {
    private val out = link.output
    /**
     * Fair, so streams take turns: on a slow link the writer that just finished would otherwise take
     * the lock straight back, and the other streams would wait for as long as it has data.
     */
    private val sendLock = java.util.concurrent.locks.ReentrantLock(true)
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val nextSid = AtomicLong(if (client) 1 else 2)
    private val open = AtomicBoolean(true)
    @Volatile private var lastRx = System.currentTimeMillis()
    @Volatile private var lastTx = System.currentTimeMillis()
    val bytesIn = AtomicLong()
    val bytesOut = AtomicLong()
    val since = System.currentTimeMillis()

    val alive: Boolean get() = open.get()

    fun send(type: Int, sid: Int, body: ByteArray = EMPTY, off: Int = 0, len: Int = body.size) {
        val plain = ByteArray(5 + len)
        plain[0] = type.toByte()
        ByteBuffer.wrap(plain, 1, 4).putInt(sid)
        System.arraycopy(body, off, plain, 5, len)
        sendLock.lock()
        try {
            if (!open.get()) throw IOException("the tunnel is closed")
            val frame = tx.seal(plain)
            try {
                out.write(frame)
                out.flush()
            } catch (e: IOException) {
                close("write failed")
                throw e
            }
            bytesOut.addAndGet(frame.size.toLong())
            lastTx = System.currentTimeMillis()
        } finally {
            sendLock.unlock()
        }
    }

    /** A client's stream over [local] to the other end's [port]. */
    fun openStream(local: Socket, port: Int) {
        val sid = nextSid.getAndAdd(2).toInt()
        val st = Stream(sid, local)
        streams[sid] = st
        send(TunnelProto.OPEN, sid, ByteBuffer.allocate(2).putShort(port.toShort()).array())
        st.start()
    }

    /** Reads frames until the connection ends. */
    fun run() {
        TunnelConnection.thread("tunnel-keepalive") { keepalive() }
        val input = DataInputStream(link.input.buffered(1 shl 16))
        var why = "closed"
        try {
            val tag = ByteArray(16)
            while (open.get()) {
                val len = input.readInt()
                if (len < 5 || len > TunnelProto.MAX_FRAME) { why = "a frame of $len bytes"; break }
                val buf = ByteArray(len)
                input.readFully(buf)
                input.readFully(tag)
                if (!rx.open(buf, len, tag)) { why = "a frame failed its check"; break }
                bytesIn.addAndGet(len + 20L)
                lastRx = System.currentTimeMillis()
                val type = buf[0].toInt() and 0xFF
                val sid = ByteBuffer.wrap(buf, 1, 4).int
                val body = buf.copyOfRange(5, len)
                val st = streams[sid]
                when (type) {
                    TunnelProto.DATA -> if (st != null) st.deliver(body) else send(TunnelProto.RST, sid)
                    TunnelProto.FIN -> st?.deliver(null)
                    TunnelProto.CREDIT -> if (body.size >= 4) st?.credit(ByteBuffer.wrap(body).int)
                    TunnelProto.RST -> st?.reset(send = false)
                    TunnelProto.PING -> send(TunnelProto.PONG, 0, body)
                    TunnelProto.OPEN -> accept(sid, body)
                    TunnelProto.HELLO, TunnelProto.ADDR -> runCatching {
                        onInfo(Json.parseToJsonElement(String(body)).jsonObject)
                    }
                    TunnelProto.BYE -> { why = "the other end said goodbye"; break }
                }
            }
        } catch (e: IOException) {
            why = e.message ?: e.javaClass.simpleName
        } catch (e: RuntimeException) {
            why = e.message ?: "bad frame"
        } finally {
            close(why)
        }
    }

    private fun accept(sid: Int, body: ByteArray) {
        val connect = connectLocal
        // Streams are the client's to open, with odd numbers.
        if (connect == null || sid % 2 == 0 || streams.containsKey(sid) || body.size < 2) {
            send(TunnelProto.RST, sid, "refused".toByteArray())
            return
        }
        val port = ByteBuffer.wrap(body).short.toInt() and 0xFFFF
        val st = Stream(sid, null)
        streams[sid] = st
        TunnelConnection.thread("tunnel-open") {
            val local = runCatching { connect(port) }.getOrNull()
            if (local == null) st.reset("nothing at $port") else { st.attach(local); st.start() }
        }
    }

    private fun keepalive() {
        val rng = SecureRandom()
        while (open.get()) {
            Thread.sleep(5_000)
            val now = System.currentTimeMillis()
            if (now - lastRx > TunnelProto.DEAD_MS) close("nothing received for ${TunnelProto.DEAD_MS / 1000} s")
            else if (now - lastTx > TunnelProto.IDLE_PING_MS) runCatching { send(TunnelProto.PING, 0, ByteArray(8).also(rng::nextBytes)) }
        }
    }

    fun announce(json: String) = runCatching { send(TunnelProto.ADDR, 0, json.toByteArray()) }

    fun close(why: String) {
        if (!open.compareAndSet(true, false)) return
        Log.i(TAG, "Tunnel with $peer ($remote) closed: $why")
        streams.values.toList().forEach { it.reset(send = false) }
        runCatching { link.close() }
        onClosed(this)
    }

    /** Says goodbye first, so the other end knows it was on purpose. */
    fun bye(why: String) {
        runCatching { send(TunnelProto.BYE, 0, why.toByteArray()) }
        close(why)
    }

    val streamCount: Int get() = streams.size

    private inner class Stream(val sid: Int, @Volatile var sock: Socket?) {
        private val lock = Object()
        private var window = TunnelProto.WINDOW.toLong()
        /** DATA waiting for the local socket; [FIN_MARK] for the end. */
        private val queue = ArrayDeque<ByteArray>()
        private var queued = 0
        private var closed = false
        private var ends = 0

        fun attach(s: Socket) { sock = s }

        fun start() {
            TunnelConnection.thread("tunnel-up-$sid") { up() }
            TunnelConnection.thread("tunnel-down-$sid") { down() }
        }

        private fun up() {
            val s = sock ?: return
            val buf = ByteArray(TunnelProto.CHUNK)
            try {
                val input = s.getInputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) { send(TunnelProto.FIN, sid); end(); return }
                    synchronized(lock) {
                        while (window < n && !closed) lock.wait(5_000)
                        if (closed) return
                        window -= n
                    }
                    send(TunnelProto.DATA, sid, buf, 0, n)
                }
            } catch (e: IOException) {
                reset("the local connection failed")
            } catch (e: InterruptedException) {
                reset()
            }
        }

        private fun down() {
            val s = sock ?: return
            var owed = 0
            try {
                val output = s.getOutputStream()
                while (true) {
                    val data: ByteArray
                    val empty: Boolean
                    synchronized(lock) {
                        while (queue.isEmpty() && !closed) lock.wait()
                        if (queue.isEmpty()) return
                        data = queue.poll()!!
                        queued -= data.size
                        empty = queue.isEmpty()
                    }
                    if (data === FIN_MARK) { runCatching { s.shutdownOutput() }; end(); return }
                    output.write(data)
                    owed += data.size
                    if (owed >= TunnelProto.CREDIT_STEP || (empty && owed > 0)) {
                        output.flush()
                        send(TunnelProto.CREDIT, sid, ByteBuffer.allocate(4).putInt(owed).array())
                        owed = 0
                    }
                }
            } catch (e: IOException) {
                reset("the local connection failed")
            } catch (e: InterruptedException) {
                reset()
            }
        }

        fun deliver(data: ByteArray?) {
            synchronized(lock) {
                if (closed) return
                if (data != null && queued + data.size > TunnelProto.WINDOW) throw IllegalStateException("sent past the window")
                queue.add(data ?: FIN_MARK)
                if (data != null) queued += data.size
                lock.notifyAll()
            }
        }

        fun credit(n: Int) {
            synchronized(lock) {
                window += n
                lock.notifyAll()
            }
        }

        private fun end() {
            synchronized(lock) {
                ends++
                if (ends < 2 || closed) return
                closed = true
                lock.notifyAll()
            }
            runCatching { sock?.close() }
            streams.remove(sid)
        }

        fun reset(why: String? = null, send: Boolean = true) {
            synchronized(lock) {
                if (closed) return
                closed = true
                lock.notifyAll()
            }
            if (send) runCatching { send(TunnelProto.RST, sid, (why ?: "").toByteArray()) }
            runCatching { sock?.close() }
            streams.remove(sid)
        }
    }

    companion object {
        private const val TAG = "Tunnel"
        private val EMPTY = ByteArray(0)
        private val FIN_MARK = ByteArray(0)

        /** A daemon thread whose failure closes what it was doing, never the app. */
        fun thread(name: String, body: () -> Unit) = Thread({
            try { body() } catch (t: Throwable) { Log.w(TAG, "$name: $t") }
        }, name).apply { isDaemon = true; start() }
    }
}

/** A device's live tunnel, for the Devices list and Settings. */
data class TunnelPeer(val deviceId: String, val remote: String, val since: Long, val bytesIn: Long, val bytesOut: Long)

/**
 * The phone's end: listens on [TunnelProto.PORT] on every address, answers only a valid hello
 * from a paired device (anything else is closed without a byte), and connects each stream to the
 * page on this phone.
 */
class TunnelServer(
    private val keys: TunnelKeys,
    /** Paired device ids, current. */
    private val deviceIds: () -> List<String>,
    /** The page's port: the only one a stream may open. */
    private val pagePort: () -> Int,
    /** The phone's HELLO: its name, addresses and tunnel port. */
    private val info: () -> String,
    private val port: Int = TunnelProto.PORT,
) {
    private val rng = SecureRandom()
    private val conns = CopyOnWriteArrayList<TunnelConnection>()
    private var server: ServerSocket? = null
    private val attempts = ConcurrentHashMap<String, ArrayDeque<Long>>()
    /** Across IPv4: devices that cannot reach the phone's IPv6 ask through the board (Punch.kt). */
    private val punch = Punch.Listener(keys, deviceIds, info) { link, who -> serve(link, who) }

    private val _peers = MutableStateFlow<List<TunnelPeer>>(emptyList())
    val peers: StateFlow<List<TunnelPeer>> = _peers

    /** Bytes both ways over every tunnel since the server started. */
    val totalBytes = AtomicLong()

    val running: Boolean get() = server != null

    @Synchronized
    fun start() {
        if (server != null) return
        val ss = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("::"), port), 32)
            }
        }.onFailure { Log.w(TAG, "Could not listen on $port: ${it.message}") }.getOrNull() ?: return
        server = ss
        punch.start()
        TunnelConnection.thread("tunnel-accept") { acceptLoop(ss) }
        TunnelConnection.thread("tunnel-stats") { statsLoop(ss) }
        Log.i(TAG, "Tunnel listening on :$port")
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        punch.stop()
        conns.forEach { it.bye("the phone stopped") }
        conns.clear()
        publish()
    }

    /** Closes the tunnels of devices no longer paired. */
    fun prune() {
        val ids = deviceIds().toSet()
        conns.filter { it.peer !in ids }.forEach { it.bye("this computer was removed") }
        punch.refresh()
    }

    /** Tells every connected device the phone's addresses changed. */
    fun announce() {
        val json = info()
        conns.forEach { it.announce(json) }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = runCatching { ss.accept() }.getOrNull() ?: continue
            TunnelConnection.thread("tunnel-hello") { handshake(s) }
        }
    }

    /** No more than 20 hellos a minute from one address (one /64 for IPv6). */
    private fun allowed(host: InetAddress): Boolean {
        val key = if (host.address.size == 16) host.address.copyOf(8).joinToString("") { "%02x".format(it) } else host.hostAddress ?: ""
        val now = System.currentTimeMillis()
        val q = attempts.getOrPut(key) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first > 60_000) q.poll()
            if (q.size >= 20) return false
            q.add(now)
        }
        if (attempts.size > 1000) attempts.clear()
        return true
    }

    private fun handshake(s: Socket) {
        val remote = s.inetAddress
        val who = (remote.hostAddress ?: "?").substringBefore('%').removePrefix("::ffff:")
        if (!allowed(remote)) { runCatching { s.close() }; return }
        runCatching { s.tcpNoDelay = true; s.keepAlive = true }
        serve(SocketLink(s), who)
    }

    /**
     * A connection that has reached the phone some way (TCP, or a path punched over UDP): the
     * hello, the reply, then frames until it ends. Blocks for the connection's life.
     */
    fun serve(link: TunnelLink, who: String) {
        try {
            link.setReadTimeout(10_000)
            val input = DataInputStream(link.input)
            val hello = ByteArray(TunnelProto.HELLO_LEN)
            input.readFully(hello)
            if (!hello.copyOf(5).contentEquals(TunnelProto.MAGIC)) { link.close(); return }
            val tid = hello.copyOfRange(5, 21)
            val eC = hello.copyOfRange(21, 53)
            val mac1 = hello.copyOfRange(53, 69)
            val id = deviceIds().firstOrNull { TunnelCrypto.equal(keys.tid(it), tid) }
            val psk = id?.let(keys::psk)
            if (psk == null || !TunnelCrypto.equal(TunnelCrypto.hmac16(psk, TunnelProto.L_HELLO, hello.copyOf(53)), mac1)) {
                link.close()   // silence: a stranger learns nothing
                return
            }
            val priv = ByteArray(32).also(rng::nextBytes)
            val eS = TunnelCrypto.x25519(priv, TunnelCrypto.BASE)
            val dh = TunnelCrypto.x25519(priv, eC)
            if (dh.all { it.toInt() == 0 }) { link.close(); return }
            val (k, th) = TunnelProto.keys(psk, dh, hello, eS)
            val out = link.output
            out.write(eS + TunnelCrypto.hmac16(k[4], TunnelProto.L_ACCEPT, th))
            out.flush()
            link.setReadTimeout(0)
            val conn = TunnelConnection(
                link, tx = TunnelCipher(k[2], k[3]), rx = TunnelCipher(k[0], k[1]),
                peer = id, remote = who, client = false,
                connectLocal = { p -> if (p == pagePort()) Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(TunnelProto.LOCAL_HOST, p), 5_000) } else null },
                onClosed = { c -> conns.remove(c); totalBytes.addAndGet(c.bytesIn.get() + c.bytesOut.get()); publish() },
            )
            conns.add(conn)
            publish()
            Log.i(TAG, "Tunnel from $who")
            conn.send(TunnelProto.HELLO, 0, info().toByteArray())
            // Awake while it lasts: with the screen off every request would otherwise wait for the CPU.
            KeepAwake.start()
            try { conn.run() } finally { KeepAwake.end() }
        } catch (e: Exception) {
            runCatching { link.close() }
        }
    }

    private fun statsLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            Thread.sleep(2_000)
            if (conns.isNotEmpty()) publish()
        }
    }

    private fun publish() {
        _peers.value = conns.map { TunnelPeer(it.peer, it.remote, it.since, it.bytesIn.get(), it.bytesOut.get()) }
    }

    /** Bytes over tunnels so far, open ones included. */
    fun bytesSoFar(): Long = totalBytes.get() + conns.sumOf { it.bytesIn.get() + it.bytesOut.get() }

    private companion object {
        const val TAG = "Tunnel"
    }
}

/**
 * The client's end, for a phone reaching another phone: dials, runs the handshake, and serves
 * the tunnel on a loopback port, each connection there a stream to the other phone's page.
 */
object TunnelClient {
    fun dial(
        host: String, port: Int, tid: ByteArray, psk: ByteArray, name: String,
        onInfo: (JsonObject) -> Unit = {}, onClosed: (TunnelConnection) -> Unit = {},
        timeoutMs: Int = 8_000,
    ): TunnelConnection {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(InetAddress.getByName(host), port), timeoutMs)
            s.tcpNoDelay = true
            s.keepAlive = true
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
        return handshake(SocketLink(s), host, tid, psk, name, onInfo, onClosed, timeoutMs)
    }

    /**
     * Every way to the other phone in turn: its known addresses over TCP, then the ones it gives
     * through the board now (mobile IPv6 changes; [onAddrs] hears them, to keep), then a path
     * punched across IPv4. Throws IOException with the last reason when none works.
     */
    fun dialAny(
        addrs: List<String>, port: Int, tid: ByteArray, psk: ByteArray, name: String,
        onInfo: (JsonObject) -> Unit = {}, onAddrs: (List<String>) -> Unit = {},
        onClosed: (TunnelConnection) -> Unit = {},
    ): TunnelConnection {
        var why: Exception? = null
        for (a in addrs) {
            try { return dial(a, port, tid, psk, name, onInfo, onClosed) } catch (e: Exception) { why = e }
        }
        val fresh = Punch.where(psk).filter { it !in addrs }
        if (fresh.isNotEmpty()) {
            onAddrs(fresh)
            for (a in fresh) {
                try { return dial(a, port, tid, psk, name, onInfo, onClosed) } catch (e: Exception) { why = e }
            }
        }
        try {
            val link = Punch.dial(psk)
            return handshake(link, "UDP", tid, psk, name, onInfo, onClosed, 10_000)
        } catch (e: Exception) {
            throw IOException(e.message ?: why?.message ?: "could not reach the other phone", e)
        }
    }

    /** The tunnel's handshake over [link] (a TCP socket, or a punched path), then its frames. */
    fun handshake(
        link: TunnelLink, label: String, tid: ByteArray, psk: ByteArray, name: String,
        onInfo: (JsonObject) -> Unit = {}, onClosed: (TunnelConnection) -> Unit = {},
        timeoutMs: Int = 8_000,
    ): TunnelConnection {
        try {
            link.setReadTimeout(timeoutMs)
            val priv = ByteArray(32).also(SecureRandom()::nextBytes)
            var hello = TunnelProto.MAGIC + tid + TunnelCrypto.x25519(priv, TunnelCrypto.BASE)
            hello += TunnelCrypto.hmac16(psk, TunnelProto.L_HELLO, hello)
            link.output.apply { write(hello); flush() }
            val resp = ByteArray(48)
            DataInputStream(link.input).readFully(resp)
            val eS = resp.copyOf(32)
            val dh = TunnelCrypto.x25519(priv, eS)
            if (dh.all { it.toInt() == 0 }) throw IOException("a bad key from the other phone")
            val (k, th) = TunnelProto.keys(psk, dh, hello, eS)
            if (!TunnelCrypto.equal(resp.copyOfRange(32, 48), TunnelCrypto.hmac16(k[4], TunnelProto.L_ACCEPT, th))) {
                throw IOException("the other phone did not prove it knows this one")
            }
            link.setReadTimeout(0)
            val conn = TunnelConnection(
                link, tx = TunnelCipher(k[0], k[1]), rx = TunnelCipher(k[2], k[3]),
                peer = label, remote = label, client = true, onInfo = onInfo, onClosed = onClosed,
            )
            TunnelConnection.thread("tunnel-read") { conn.run() }
            conn.send(TunnelProto.HELLO, 0, """{"name":${kotlinx.serialization.json.JsonPrimitive(name)},"v":1}""".toByteArray())
            return conn
        } catch (e: IOException) {
            runCatching { link.close() }
            throw e
        }
    }

    /** Serves [conn] on a loopback port; returns the port. Each connection there is a stream to [targetPort]. */
    fun serve(conn: TunnelConnection, targetPort: Int): Int {
        val ss = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        TunnelConnection.thread("tunnel-serve") {
            while (conn.alive) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                runCatching { c.tcpNoDelay = true; conn.openStream(c, targetPort) }.onFailure { runCatching { c.close() } }
            }
            runCatching { ss.close() }
        }
        TunnelConnection.thread("tunnel-serve-watch") {
            while (conn.alive) Thread.sleep(1_000)
            runCatching { ss.close() }
        }
        return ss.localPort
    }
}
