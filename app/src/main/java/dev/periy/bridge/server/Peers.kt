package dev.periy.bridge.server

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Another phone running Localhost 8787, seen on the local network. */
data class NearbyPhone(val name: String, val host: String, val port: Int)

/** A phone this one is linked with. */
@Serializable
data class Peer(
    val name: String,
    val host: String,
    val port: Int,
    /** This phone's session on the other one. */
    val cookie: String,
    /** The other phone's entry in this phone's own list of who may come in: its way back here. */
    val deviceId: String = "",
    /** Whether the other phone can come into this one too, so the link works both ways. */
    val mutual: Boolean = false,
    /** When the link was last confirmed both ways; it is renewed now and then. */
    val linkedAt: Long = 0,
    /** The other phone's tunnel details for this one (its /api/tunnel), for reaching it from another network. */
    val tunnel: String = "",
)

/**
 * One phone's greeting to the other once they are linked: how to come back into it, and its
 * tunnel details for the other phone ([tunnel]), for reaching it from another network.
 */
@Serializable
data class PeerHello(val name: String, val port: Int, val cookie: String, val tunnel: String = "")

/** Text copied on a linked phone. [at] is when it was first copied, wherever that was. */
@Serializable
data class PeerClip(val text: String, val at: Long)

/** A linked phone, as the page sees it. */
@Serializable
data class PeerDto(val name: String, val mutual: Boolean, val nearby: Boolean)

/** Where the status of a connection attempt stands, for the screen. */
sealed interface PeerStatus {
    data class Waiting(val code: String) : PeerStatus
    data class Failed(val message: String) : PeerStatus
}

/**
 * Phone to phone.
 *
 * Every phone runs the same server, so another phone is simply one more client: it asks to
 * connect exactly as a browser does, and the owner approves it with a code. Once approved, the
 * phone that asked lets the other one in as well and tells it how to come back (a "hello"), so a
 * single approval links the two both ways. From then on either one can:
 *
 * - send files, through the same resumable, parallel upload path a laptop uses;
 * - look through the other's storage and save files from it, as a laptop's page does;
 * - share one clipboard: a copy on either reaches the other, and every computer on both;
 * - pass a laptop's file on to a computer on the other phone without keeping it (see [Pipes]).
 *
 * Finding each other uses the network's own service discovery (mDNS, "_blazeit._tcp"). Phones that
 * have never shared a network link with a code instead ([linkByCode], [dev.periy.bridge.net.LinkCode]),
 * and linked phones on different networks reach each other through their tunnels.
 */
class PeerManager(
    ctx: Context,
    val deviceName: () -> String,
    private val streams: () -> Int,
    private val direct: dev.periy.bridge.net.DirectLink,
    /** Whether sends to another phone set up a direct link between the two first. */
    private val useDirect: () -> Boolean,
    private val access: Access,
    private val storage: Storage,
    private val index: FileIndex,
    private val clipboard: ClipboardStore,
    private val clipSync: () -> Boolean,
) {

    /** Copies here go on to the linked phones (the phone's Sync clipboard is on). */
    val sharesClipboard: Boolean get() = clipSync()

    /** This phone's own side of a link: letting the other phone in, and shutting it out again. */
    interface Access {
        val port: Int
        /** Lets a phone in; its id in this phone's list of who may come in. */
        fun grant(name: String, ip: String): String
        /** A session for a phone already let in, or null when it has been removed since. */
        fun cookie(deviceId: String): String?
        fun revoke(deviceId: String)
        /** This phone's tunnel details for a phone let in here, or "" when the tunnel is off. */
        fun tunnelFor(deviceId: String): String
        /** Turns on From other networks, so a phone linked from afar can reach this one. */
        fun ensureTunnel()
        /** A phone has just linked with this one: the link code, if one is open, has done its job. */
        fun linked()
    }

    private val app = ctx.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(app.filesDir, "peers.json")

    private val _nearby = MutableStateFlow<List<NearbyPhone>>(emptyList())
    val nearby: StateFlow<List<NearbyPhone>> = _nearby.asStateFlow()

    private val _peers = MutableStateFlow(load())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _route = MutableStateFlow<Map<String, String>>(emptyMap())
    /** How a send to each phone is going out right now, by phone name, for the screen. */
    val route: StateFlow<Map<String, String>> = _route.asStateFlow()

    private val _status = MutableStateFlow<Map<String, PeerStatus>>(emptyMap())
    /** Connection attempts in progress or failed, by host. */
    val status: StateFlow<Map<String, PeerStatus>> = _status.asStateFlow()

    private var advertised: NsdManager.RegistrationListener? = null
    private var ownName: String? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val found = ConcurrentHashMap<String, NearbyPhone>()

    fun find(name: String): Peer? = _peers.value.firstOrNull { it.name == name }

    /** The linked phone behind a request, by the entry it came in on. */
    fun byDevice(deviceId: String): Peer? = _peers.value.firstOrNull { it.deviceId == deviceId }

    fun dto(): List<PeerDto> {
        val here = _nearby.value.map { it.name }.toSet()
        return _peers.value.map { PeerDto(it.name, it.mutual, it.name in here) }
    }

    // ------------------------------------------------------------------ being found

    fun advertise(port: Int) {
        if (advertised != null || nsd == null) return
        val info = NsdServiceInfo().apply {
            serviceName = deviceName()
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { ownName = s.serviceName }
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) { Log.w(TAG, "Advertise failed: $code"); advertised = null }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
        }
        advertised = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { advertised = null }
    }

    fun stopAdvertising() {
        advertised?.let { l -> runCatching { nsd?.unregisterService(l) } }
        advertised = null
    }

    /**
     * The server is up: links made before this version (one way only) are completed, and every
     * link's way back is renewed once a week, long before its session could run out.
     */
    fun online() {
        scope.launch {
            val now = System.currentTimeMillis()
            _peers.value.filter { !it.mutual || now - it.linkedAt > RENEW_MS }.forEach { runCatching { linkBack(it) } }
            _peers.value.forEach { runCatching { fetchTunnel(it) } }
        }
        lookAround()
    }

    // ------------------------------------------------------------------ finding others

    /** The Devices tab is open and looking; while it is, [lookAround] leaves the looking to it. */
    @Volatile
    private var screenLooking = false

    /** Looking for phones for the Devices tab, for as long as it is open. */
    fun startDiscovery() {
        screenLooking = true
        discover()
    }

    fun stopDiscovery() {
        screenLooking = false
        stopDiscover()
    }

    /**
     * A short look around the network: linked phones found again at whatever address they have
     * now, so browsing and the clipboard follow a phone whose hotspot restarted. Run when the
     * server starts and whenever a linked phone cannot be reached.
     */
    fun lookAround() {
        if (_peers.value.isEmpty() || discovery != null) return
        discover()
        scope.launch {
            delay(LOOK_MS)
            if (!screenLooking) stopDiscover()
        }
    }

    private fun discover() {
        if (discovery != null || nsd == null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { discovery = null }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                if (s.serviceName == ownName) return
                resolveQueue.trySend(s)
            }
            override fun onServiceLost(s: NsdServiceInfo) {
                found.remove(s.serviceName)
                publishNearby()
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { discovery = null }
    }

    private fun stopDiscover() {
        discovery?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        discovery = null
    }

    // Android resolves one service at a time; asking for a second while one is running
    // fails, so resolutions go through a queue.
    private val resolveQueue = kotlinx.coroutines.channels.Channel<NsdServiceInfo>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    init {
        scope.launch {
            for (s in resolveQueue) {
                val resolved = kotlinx.coroutines.suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                    @Suppress("DEPRECATION")
                    runCatching {
                        nsd?.resolveService(s, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { if (cont.isActive) cont.resume(null) {} }
                            override fun onServiceResolved(info: NsdServiceInfo) { if (cont.isActive) cont.resume(info) {} }
                        })
                    }.onFailure { if (cont.isActive) cont.resume(null) {} }
                }
                @Suppress("DEPRECATION")
                val host = resolved?.host as? Inet4Address ?: continue
                if (host.hostAddress in ownAddresses()) continue
                found[s.serviceName] = NearbyPhone(s.serviceName, host.hostAddress ?: continue, resolved.port)
                publishNearby()
            }
        }
        watchClipboard()
    }

    private fun publishNearby() {
        _nearby.value = found.values.sortedBy { it.name }
        // A known phone that moved to a new address (a hotspot restart does that) is
        // followed there rather than forgotten.
        val moved = _peers.value.map { p -> found[p.name]?.let { n -> p.copy(host = n.host, port = n.port) } ?: p }
        if (moved != _peers.value) setPeers(moved)
        EventBus.emit("peers", json.encodeToString(dto()))
    }

    private fun ownAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { (it as? Inet4Address)?.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

    // ------------------------------------------------------------------ linking

    /** Asks [phone] to let this one in. The other phone shows a code to compare. */
    fun connect(phone: NearbyPhone) {
        scope.launch {
            val host = phone.host
            try {
                val (name, cookie) = pairWith(phone, host)
                val old = find(name)
                val peer = Peer(name, phone.host, phone.port, cookie, deviceId = old?.deviceId.orEmpty(), tunnel = old?.tunnel.orEmpty())
                setPeers(_peers.value.filterNot { it.name == peer.name } + peer)
                setStatus(host, null)
                // One approval is enough: the other phone is let in here too.
                linkBack(peer)
            } catch (t: Throwable) {
                Log.w(TAG, "Connect to $host failed", t)
                setStatus(host, PeerStatus.Failed(t.message ?: "Could not reach that phone"))
            }
        }
    }

    /**
     * Asks [phone] to let this one in and waits for its owner to allow it, with the code to
     * compare under [key] in [status]. Returns the other phone's name and this one's session there.
     */
    private suspend fun pairWith(phone: NearbyPhone, key: String): Pair<String, String> {
        // A phone added by address is known only by its address until it says its name.
        val name = runCatching {
            json.parseToJsonElement(request("GET", phone, "/api/ping", null, null).body)
                .jsonObject["device"]!!.jsonPrimitive.content
        }.getOrDefault(phone.name)
        val start = request("POST", phone, "/api/pair", null, null)
        if (start.code !in 200..299) {
            Log.w(TAG, "Pair request to ${phone.host} answered ${start.code}: ${start.body.take(300)}")
            error(message(start.body) ?: "The other phone refused (${start.code})")
        }
        val body = json.parseToJsonElement(start.body).jsonObject
        val id = body["id"]!!.jsonPrimitive.content
        val code = body["code"]!!.jsonPrimitive.content
        setStatus(key, PeerStatus.Waiting(code))
        repeat(125) {
            delay(1000)
            val poll = request("GET", phone, "/api/pair/$id", null, null)
            val state = runCatching {
                json.parseToJsonElement(poll.body).jsonObject["state"]!!.jsonPrimitive.content
            }.getOrDefault("")
            when (state) {
                "APPROVED" -> return name to (poll.setCookie ?: error("Approved, but no session came back"))
                "DENIED" -> error("The other phone said no")
                "EXPIRED" -> error("Nobody answered on the other phone")
            }
        }
        error("Nobody answered on the other phone")
    }

    /**
     * Links with a phone on any network by the code it shows ([dev.periy.bridge.net.LinkCode]):
     * the code reaches it through its tunnel, over IPv6 or punched across IPv4; this phone asks to
     * be let in there as on a shared Wi-Fi, takes the other phone's own tunnel details for later,
     * and says how to come back here. Progress shows under [LINK_KEY] in [status].
     */
    fun linkByCode(typed: String) {
        val code = dev.periy.bridge.net.LinkCode.normalize(typed)
        scope.launch {
            var conn: dev.periy.bridge.net.TunnelConnection? = null
            try {
                if (!dev.periy.bridge.net.LinkCode.valid(code)) error("A link code is four digits")
                setStatus(LINK_KEY, PeerStatus.Waiting(""))
                // The code's secret, from the phone showing it, by a key exchange the code alone can finish.
                val secret = runCatching { dev.periy.bridge.net.LinkPake.fetch(code) }
                    .getOrElse { error(it.message ?: "Could not reach a phone with that code") }
                // The other phone will come back to this one the same way.
                access.ensureTunnel()
                val page = kotlinx.coroutines.CompletableDeferred<Int>()
                val c = runCatching {
                    dev.periy.bridge.net.TunnelClient.dialAny(
                        emptyList(), dev.periy.bridge.net.TunnelProto.PORT,
                        dev.periy.bridge.net.LinkCode.tid(secret), dev.periy.bridge.net.LinkCode.psk(secret), deviceName(),
                        onInfo = { info -> info["page"]?.jsonPrimitive?.content?.toIntOrNull()?.let { page.complete(it) } },
                    )
                }.getOrElse { error("Could not reach a phone with that code (${it.message})") }
                conn = c
                val pagePort = kotlinx.coroutines.withTimeoutOrNull(4_000) { page.await() } ?: DEFAULT_PORT
                val there = NearbyPhone("", "127.0.0.1", dev.periy.bridge.net.TunnelClient.serve(c, pagePort))
                val (name, cookie) = pairWith(there, LINK_KEY)
                val details = runCatching { request("GET", there, "/api/tunnel", cookie, null) }.getOrNull()
                    ?.takeIf { it.code == 200 && it.body.contains("\"key\"") }?.body.orEmpty()
                val old = find(name)
                val peer = Peer(name, old?.host.orEmpty(), pagePort, cookie, deviceId = old?.deviceId.orEmpty(), tunnel = details.ifEmpty { old?.tunnel.orEmpty() })
                // This way while it lasts; after that, the other phone's own tunnel.
                synchronized(far) { far[name] = Far(c, there.port) }
                setPeers(_peers.value.filterNot { it.name == name } + peer)
                setStatus(LINK_KEY, null)
                linkBack(peer)
            } catch (t: Throwable) {
                Log.w(TAG, "Link by code failed", t)
                conn?.bye("could not link")
                setStatus(LINK_KEY, PeerStatus.Failed(t.message ?: "Could not link"))
            }
        }
    }

    /**
     * Makes the link work both ways: lets the other phone into this one and tells it how to come
     * in. Run once the other phone has let this one in, and again now and then, which renews the
     * other phone's session here before it can run out. A phone on an older version does not
     * know the greeting; the link then stays one way until it is updated.
     */
    private fun linkBack(peer: Peer): Boolean {
        val id = peer.deviceId.takeIf { it.isNotEmpty() && access.cookie(it) != null } ?: access.grant(peer.name, peer.host)
        val cookie = access.cookie(id) ?: return false
        val body = json.encodeToString(PeerHello(deviceName(), access.port, cookie, access.tunnelFor(id))).toByteArray()
        val ok = runCatching {
            request("POST", peer.asTarget(), "/api/peers/hello", peer.cookie, body, "application/json").code in 200..299
        }.getOrDefault(false)
        update(peer.name) { it.copy(deviceId = id, mutual = ok || it.mutual, linkedAt = if (ok) System.currentTimeMillis() else it.linkedAt) }
        if (ok) fetchTunnel(peer)
        return ok
    }

    /**
     * The other half of [linkBack], on the phone that approved: the phone it let in says how to
     * come into it. [from] is the entry that phone came in on here.
     */
    fun hello(h: PeerHello, ip: String, from: String) {
        val old = _peers.value.firstOrNull { it.deviceId == from || it.name == h.name }
        val kept = _peers.value.filterNot { it.deviceId == from || it.name == h.name }
        // Through a tunnel the greeting comes from loopback: the phone's local address stays as it
        // was, and a phone linked from afar has none (it is reached only through its tunnel).
        val host = if (ip.startsWith("127.") || ip == "::1") old?.host.orEmpty() else ip
        val tunnel = h.tunnel.ifEmpty { old?.tunnel.orEmpty() }
        val peer = Peer(h.name, host, h.port, h.cookie, from, mutual = true, linkedAt = System.currentTimeMillis(), tunnel = tunnel)
        setPeers(kept + peer)
        access.linked()
        scope.launch { fetchTunnel(peer) }
    }

    /** Unlinks both ways: this phone forgets the other and shuts it out, and asks it to do the same. */
    fun forget(peer: Peer) {
        setPeers(_peers.value.filterNot { it.name == peer.name })
        if (peer.deviceId.isNotEmpty()) access.revoke(peer.deviceId)
        scope.launch { runCatching { request("POST", peer.asTarget(), "/api/peers/bye", peer.cookie, null) } }
    }

    /** Every phone, for Unpair everything. */
    fun forgetAll() = _peers.value.forEach(::forget)

    /** The other phone unlinked itself: it is forgotten here and shut out too. */
    fun bye(from: String) {
        val p = byDevice(from) ?: return
        setPeers(_peers.value.filterNot { it.deviceId == from })
        access.revoke(from)
        Log.i(TAG, "${p.name} unlinked itself")
    }

    private fun setStatus(host: String, s: PeerStatus?) {
        _status.value = if (s == null) _status.value - host else _status.value + (host to s)
    }

    private fun setPeers(list: List<Peer>) {
        _peers.value = list
        save()
        EventBus.emit("peers", json.encodeToString(dto()))
    }

    private fun update(name: String, change: (Peer) -> Peer) {
        setPeers(_peers.value.map { if (it.name == name) change(it) else it })
    }

    // ------------------------------------------------------------------ sending

    /**
     * Sends files one after another; each file goes over several connections when large.
     *
     * First, when allowed, the two phones get a network of their own: the other phone starts
     * its direct link and this one joins it (Android asks once, with its own prompt). That
     * is one hop instead of two through a router, and nobody else is on the channel. If any
     * step fails the files go the ordinary way instead.
     */
    fun sendFiles(peer: Peer, uris: List<Uri>) {
        scope.launch {
            val route = if (useDirect()) runCatching { directRoute(peer) }.getOrNull() else null
            setRoute(peer, if (route != null) "Direct link" else "Wi-Fi")
            try {
                for (uri in uris) runCatching { sendOne(peer, uri, route) }.onFailure { Log.w(TAG, "Send failed", it) }
            } finally {
                route?.close()
                setRoute(peer, null)
            }
        }
    }

    /** A private network to the other phone, and its address on it. */
    private inner class Route(
        val target: NearbyPhone,
        val joined: dev.periy.bridge.net.DirectLink.Joined,
        /** Stop the other phone's link afterwards: it was off before this send started it. */
        val stopAfter: Boolean,
        val cookie: String,
    ) : AutoCloseable {
        val network get() = joined.network
        override fun close() {
            if (stopAfter) runCatching { request("POST", target, "/api/direct/stop", cookie, null, network = network) }
            joined.close()
        }
    }

    private suspend fun directRoute(peer: Peer): Route? {
        setRoute(peer, "Setting up a direct link")
        val was = runCatching {
            json.parseToJsonElement(request("GET", peer.asTarget(), "/api/direct", peer.cookie, null).body)
                .jsonObject["state"]!!.jsonPrimitive.content
        }.getOrNull() ?: return null
        val started = request("POST", peer.asTarget(), "/api/direct/start?for=phone", peer.cookie, null)
        if (started.code !in 200..299) return null
        val dto = json.decodeFromString<DirectDto>(started.body)
        val info = dto.info?.takeIf { dto.state == "on" && it.host.isNotEmpty() } ?: return null
        setRoute(peer, "Joining the direct link of " + peer.name)
        val joined = direct.join(info) ?: run {
            if (was != "on") runCatching { request("POST", peer.asTarget(), "/api/direct/stop", peer.cookie, null) }
            return null
        }
        return Route(NearbyPhone(peer.name, info.host, info.port), joined, stopAfter = was != "on", cookie = peer.cookie)
    }

    private fun setRoute(peer: Peer, note: String?) {
        _route.value = if (note == null) _route.value - peer.name else _route.value + (peer.name to note)
    }

    private suspend fun sendOne(peer: Peer, uri: Uri, route: Route?) {
        val (name, size) = describe(uri)
        val id = UUID.randomUUID().toString()
        val to = route?.target ?: peer.asTarget()
        val net = route?.network
        Transfers.begin(id, name + "  →  " + peer.name, Direction.OUTBOUND, size)
        try {
            val meta = "filename " + b64(name) + ",filetype " + b64(app.contentResolver.getType(uri) ?: "application/octet-stream")
            val n = if (size >= PARALLEL_THRESHOLD) streams().coerceIn(1, 8) else 1
            val windows: List<Triple<String, Long, Long>> = if (n > 1) {
                val r = request("POST", to, "/tus/parallel", peer.cookie, null, null,
                    mapOf("Tus-Resumable" to "1.0.0", "Upload-Length" to "$size", "Upload-Metadata" to meta, "Bridge-Streams" to "$n"), net)
                if (r.code !in 200..299) error(message(r.body) ?: "Refused (${r.code})")
                json.parseToJsonElement(r.body).jsonObject["streams"]!!.jsonArray.map {
                    val o = it.jsonObject
                    Triple(o.str("url"), o["base"]!!.jsonPrimitive.long, o["length"]!!.jsonPrimitive.long)
                }
            } else {
                val r = request("POST", to, "/tus", peer.cookie, null, null,
                    mapOf("Tus-Resumable" to "1.0.0", "Upload-Length" to "$size", "Upload-Metadata" to meta), net)
                if (r.code !in 200..299) error(message(r.body) ?: "Refused (${r.code})")
                listOf(Triple(r.location ?: error("No upload address came back"), 0L, size))
            }
            val sent = AtomicLong()
            coroutineScopeAll(windows) { (url, base, length) -> sendWindow(peer, to, net, uri, url, base, length, id, sent) }
            Transfers.progress(id, size)
            Transfers.finish(id, ok = true)
        } catch (t: Throwable) {
            Transfers.finish(id, ok = false)
            throw t
        }
    }

    private suspend fun <T> coroutineScopeAll(items: List<T>, block: suspend (T) -> Unit) =
        kotlinx.coroutines.coroutineScope { items.map { async(Dispatchers.IO) { block(it) } }.awaitAll() }

    /** One window of the file, streamed from its own descriptor in 64 MB requests. */
    private fun sendWindow(
        peer: Peer, to: NearbyPhone, net: android.net.Network?, uri: Uri,
        url: String, base: Long, length: Long, id: String, sent: AtomicLong,
    ) {
        app.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            val ch = FileInputStream(pfd.fileDescriptor).channel
            val buf = ByteBuffer.allocate(1 shl 20)
            var offset = 0L
            while (offset < length) {
                val len = minOf(CHUNK, length - offset)
                val conn = open("PATCH", to, url, peer.cookie, net)
                conn.setRequestProperty("Tus-Resumable", "1.0.0")
                conn.setRequestProperty("Content-Type", "application/offset+octet-stream")
                conn.setRequestProperty("Upload-Offset", "$offset")
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(len)
                ch.position(base + offset)
                conn.outputStream.use { out ->
                    var left = len
                    while (left > 0) {
                        buf.clear()
                        if (left < buf.capacity()) buf.limit(left.toInt())
                        val r = ch.read(buf)
                        if (r <= 0) error("The file ended early")
                        out.write(buf.array(), 0, r)
                        left -= r
                        Monitor.addOut(r)
                        Transfers.progress(id, sent.addAndGet(r.toLong()))
                    }
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code != 204) error("The other phone stopped the upload ($code)")
                offset += len
            }
        }
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) return (c.getString(0) ?: "file") to c.getLong(1)
        }
        error("Cannot read that file")
    }

    // ------------------------------------------------------------------ the other phone's files

    /** A folder on the other phone, as its own page would list it. */
    suspend fun list(peer: Peer, path: String): FsListDto = withContext(Dispatchers.IO) {
        val r = runCatching { request("GET", peer.asTarget(), "/api/fs?path=" + enc(path), peer.cookie, null) }
            .onFailure { lookAround() }.getOrThrow()
        when (r.code) {
            200 -> json.decodeFromString<FsListDto>(r.body)
            401 -> FsListDto(true, path, message = "${peer.name} no longer lets this phone in. Forget it and connect again.")
            else -> FsListDto(true, path, message = message(r.body) ?: "${peer.name} did not answer (${r.code})")
        }
    }

    /** A photo's or a video's small picture, or null. */
    suspend fun thumb(peer: Peer, path: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val c = open("GET", peer.asTarget(), "/api/fs/thumb?path=" + enc(path), peer.cookie)
            try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null } finally { c.disconnect() }
        }.getOrNull()
    }

    /**
     * Saves a file from the other phone, or a whole folder as one zip, into this phone's folder
     * for received files, streamed straight in. [done] gets null, or what went wrong.
     */
    fun save(peer: Peer, path: String, name: String, size: Long, folder: Boolean, done: (String?) -> Unit) {
        scope.launch {
            val id = UUID.randomUUID().toString()
            val file = if (folder) "$name.zip" else name
            Transfers.begin(id, "$file  ←  ${peer.name}", Direction.INBOUND, if (folder) 0 else size)
            var slot: Storage.Slot? = null
            val err = runCatching {
                if (!storage.hasDestination()) error("Choose where received files go first, in Settings")
                val conn = open("GET", peer.asTarget(), (if (folder) "/api/fs/zip?path=" else "/api/fs/file?path=") + enc(path), peer.cookie)
                try {
                    if (conn.responseCode != 200) error("${peer.name} would not send it (${conn.responseCode})")
                    val s = storage.newSlot(id).also { slot = it }
                    var got = 0L
                    s.writer(0).use { w ->
                        conn.inputStream.use { ins ->
                            val buf = ByteArray(1 shl 20)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                w.write(buf, 0, n)
                                got += n
                                Monitor.addIn(n)
                                Transfers.progress(id, got)
                            }
                        }
                        w.sync()
                    }
                    if (!folder && size > 0 && got != size) error("The file ended early")
                    val mime = if (folder) "application/zip"
                        else conn.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"
                    index.add(s.finish(sanitizeFilename(file), mime, Origin.PC, emptyList()))
                    slot = null
                } finally {
                    conn.disconnect()
                }
            }.exceptionOrNull()
            slot?.discard()
            Transfers.finish(id, ok = err == null)
            if (err != null) Log.w(TAG, "Saving $file from ${peer.name} failed", err)
            withContext(Dispatchers.Main) { done(err?.let { it.message ?: "It stopped part way" }) }
        }
    }

    /**
     * A request for the page, passed on to the other phone: its files, their pictures and
     * previews, each with any byte range asked for, so a video still seeks. The caller streams
     * the answer back and disconnects.
     */
    fun openGet(peer: Peer, pathAndQuery: String, range: String?): HttpURLConnection {
        val c = open("GET", peer.asTarget(), pathAndQuery, peer.cookie)
        range?.let { c.setRequestProperty("Range", it) }
        c.readTimeout = 60_000
        return c
    }

    // ------------------------------------------------------------------ straight through

    /** Opens a pipe on the other phone (see [Pipes]); its id there, or what went wrong. */
    fun offerPipe(peer: Peer, offer: PipeOffer): Result<String> = runCatching {
        val r = request("POST", peer.asTarget(), "/api/pipe", peer.cookie, json.encodeToString(offer).toByteArray(), "application/json")
        if (r.code !in 200..299) error(message(r.body) ?: "${peer.name} refused it (${r.code})")
        json.decodeFromString<PipeDto>(r.body).id
    }

    /** An introduction for a direct send, on to pipe [remote] on the other phone, toward its computer. */
    fun signal(peer: Peer, remote: String, msg: String) {
        runCatching { request("POST", peer.asTarget(), "/api/pipe/$remote/signal", peer.cookie, msg.toByteArray(), "application/json") }
    }

    /** An introduction back toward the computer that sent through [peer]'s pipe [local] (its id here). */
    fun signalBack(peer: Peer, local: String, msg: String) {
        val body = """{"remote":"$local","msg":$msg}""".toByteArray()
        runCatching { request("POST", peer.asTarget(), "/api/pipe/back", peer.cookie, body, "application/json") }
    }

    /** The send went straight between the laptops; the other phone's pipe can go. */
    fun closePipe(peer: Peer, remote: String) {
        runCatching { request("POST", peer.asTarget(), "/api/pipe/$remote/close", peer.cookie, null) }
    }

    /** How a pipe on the other phone stands, or null when it cannot be reached. */
    fun pipeState(peer: Peer, remote: String): String? = runCatching {
        val r = request("GET", peer.asTarget(), "/api/pipe/$remote", peer.cookie, null)
        json.decodeFromString<PipeStateDto>(r.body).state
    }.getOrNull()

    /**
     * Passes a file on to the pipe [remote] on the other phone as it arrives. The other phone
     * answers once its computer has all of it; that answer is returned.
     */
    fun pushPipe(peer: Peer, remote: String, input: InputStream, size: Long, progress: (Long) -> Unit): Pair<Int, String?> {
        val conn = open("POST", peer.asTarget(), "/api/pipe/$remote/data", peer.cookie)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setFixedLengthStreamingMode(size)
        conn.readTimeout = 120_000
        try {
            conn.outputStream.use { out ->
                val buf = ByteArray(1 shl 20)
                var sent = 0L
                while (sent < size) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), size - sent).toInt())
                    if (n < 0) error("The sender stopped part way")
                    out.write(buf, 0, n)
                    sent += n
                    Monitor.addOut(n)
                    progress(sent)
                }
            }
            val code = conn.responseCode
            val text = runCatching { (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() }.getOrNull().orEmpty()
            return code to message(text)
        } finally {
            conn.disconnect()
        }
    }

    /** The computers open on the other phone right now, for a laptop here to send straight to. */
    suspend fun remoteTargets(peer: Peer): List<TargetDto> = withContext(Dispatchers.IO) {
        runCatching {
            val c = open("GET", peer.asTarget(), "/api/targets?local=1", peer.cookie)
            c.connectTimeout = 1500
            c.readTimeout = 2500
            try {
                if (c.responseCode != 200) emptyList()
                else json.decodeFromString<TargetsDto>(c.inputStream.bufferedReader().readText()).targets.filter { it.kind == "computer" }
            } finally {
                c.disconnect()
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------ one clipboard

    /**
     * The copy last taken from a linked phone, by when it was first copied, and which phone it
     * came from: it is passed on to the other phones but not back to that one.
     */
    @Volatile
    private var incoming: Pair<Long, String>? = null

    /**
     * Every new copy here goes to the linked phones, and from each on to its computers, so two
     * laptops on two phones share one clipboard. Newest wins: a phone keeps what it has when the
     * copy offered is older, so two copies made at once settle on the later one everywhere
     * instead of bouncing between the phones.
     */
    private fun watchClipboard() {
        scope.launch {
            clipboard.meta.drop(1).collect { m ->
                if (!clipSync() || m.kind == "empty") return@collect
                val skip = incoming?.takeIf { it.first == m.at }?.second
                _peers.value.filter { it.name != skip }.forEach { p ->
                    launch {
                        runCatching { pushClip(p, m) }.onFailure {
                            Log.i(TAG, "Clipboard not passed to ${p.name}: ${it.message}")
                            lookAround()
                        }
                    }
                }
            }
        }
    }

    private fun pushClip(p: Peer, m: ClipMeta) {
        if (m.kind == "text") {
            val body = json.encodeToString(PeerClip(m.text, m.at)).toByteArray()
            request("POST", p.asTarget(), "/api/peers/clip", p.cookie, body, "application/json")
            return
        }
        // The picture or file itself; it may have been replaced already, and then it is not sent.
        val f = clipboard.blob()?.takeIf { clipboard.meta.value.v == m.v } ?: return
        val conn = open("POST", p.asTarget(), "/api/peers/clip/blob?name=" + enc(m.name) + "&at=" + m.at, p.cookie)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", m.mime.ifBlank { "application/octet-stream" })
        conn.setFixedLengthStreamingMode(f.length())
        try {
            conn.outputStream.use { out -> f.inputStream().use { it.copyTo(out, 256 * 1024) } }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    /**
     * A copy from linked phone [from], made at [at]: taken only when newer than what is here.
     * [take] puts it on the clipboard; returns whether it did.
     */
    fun takeClip(from: String, at: Long, take: () -> Boolean): Boolean {
        if (!clipSync() || at <= clipboard.meta.value.at) return false
        incoming = at to from
        return take()
    }

    // ------------------------------------------------------------------ http

    private class Response(val code: Int, val body: String, val setCookie: String?, val location: String?)

    // ------------------------------------------------------------------ from another network
    //
    // A linked phone that is not on this phone's networks is reached through its tunnel
    // (docs/tunnel-protocol.md), served here on a loopback port: every request below then goes
    // there unchanged. The moment the phone answers on its local address again, that is used.

    private class Far(val conn: dev.periy.bridge.net.TunnelConnection, val port: Int)

    private val far = ConcurrentHashMap<String, Far>()
    private val localAt = ConcurrentHashMap<String, Long>()
    private val tunnelAt = ConcurrentHashMap<String, Long>()

    // ------------------------------------------------------------------ messages

    /**
     * The key this phone seals messages to [peer] with: the tunnel key that phone made for this
     * one (asked for first when it is not known yet). Null when it cannot be had.
     */
    fun messageKey(peer: Peer): ByteArray? {
        if (peer.tunnel.isEmpty()) { tunnelAt.remove(peer.name); fetchTunnel(peer) }
        val t = find(peer.name)?.tunnel?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            json.parseToJsonElement(t).jsonObject["key"]?.jsonPrimitive?.content?.let { Base64.getDecoder().decode(it) }
        }.getOrNull()
    }

    /** Posts [body] to [path] on [peer], whichever way it can be reached now; true when it took it. */
    fun deliver(peer: Peer, path: String, body: ByteArray): Boolean = runCatching {
        request("POST", peer.asTarget(), path, peer.cookie, body, "application/json").code in 200..299
    }.getOrDefault(false)

    /** Keeps the other phone's tunnel details for this one, at most every ten minutes. */
    private fun fetchTunnel(peer: Peer) {
        val now = System.currentTimeMillis()
        if (now - (tunnelAt[peer.name] ?: 0) < 600_000) return
        tunnelAt[peer.name] = now
        val r = runCatching { request("GET", peer.asTarget(), "/api/tunnel", peer.cookie, null) }.getOrNull() ?: return
        if (r.code == 200 && r.body.contains("\"key\"")) update(peer.name) { it.copy(tunnel = r.body) }
    }

    private fun answersAt(host: String, port: Int): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 700); true }
    }.getOrDefault(false)

    private fun Peer.asTarget(): NearbyPhone {
        // A phone linked from afar has no local address: never this phone's own loopback.
        val local = NearbyPhone(name, host.ifEmpty { NOWHERE }, port)
        if (tunnel.isEmpty() && far[name]?.conn?.alive != true) return local
        val now = System.currentTimeMillis()
        if (host.isNotEmpty()) {
            if (now - (localAt[name] ?: 0) < 15_000) return local
            if (answersAt(host, port)) {
                localAt[name] = now
                far.remove(name)?.conn?.bye("close again")
                if (now - (tunnelAt[name] ?: 0) > 600_000) scope.launch { fetchTunnel(this@asTarget) }
                return local
            }
        }
        return viaTunnel(this)?.let { NearbyPhone(name, "127.0.0.1", it) } ?: local
    }

    /** The loopback port the other phone's tunnel is served on, dialling it first when needed. */
    private fun viaTunnel(peer: Peer): Int? = synchronized(far) {
        far[peer.name]?.takeIf { it.conn.alive }?.let { return it.port }
        val t = runCatching { json.parseToJsonElement(peer.tunnel).jsonObject }.getOrNull() ?: return null
        val tid = t["id"]?.jsonPrimitive?.content?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray() ?: return null
        val psk = t["key"]?.jsonPrimitive?.content?.let { Base64.getDecoder().decode(it) } ?: return null
        val tport = t["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: dev.periy.bridge.net.TunnelProto.PORT
        val addrs = (t["addrs"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        // Its known addresses, then the ones it gives through the board now, then across IPv4.
        val conn = runCatching {
            dev.periy.bridge.net.TunnelClient.dialAny(
                addrs, tport, tid, psk, deviceName(),
                onInfo = { info -> info["addrs"]?.let { na -> update(peer.name) { p -> p.copy(tunnel = mergeAddrs(p.tunnel, na)) } } },
                onAddrs = { na -> update(peer.name) { p -> p.copy(tunnel = mergeAddrs(p.tunnel, kotlinx.serialization.json.JsonArray(na.map { kotlinx.serialization.json.JsonPrimitive(it) }))) } },
            )
        }.onFailure { Log.i(TAG, "Tunnel to ${peer.name}: ${it.message}") }.getOrNull() ?: return null
        val port = dev.periy.bridge.net.TunnelClient.serve(conn, peer.port)
        far[peer.name] = Far(conn, port)
        Log.i(TAG, "Reaching ${peer.name} through its tunnel (${conn.remote})")
        return port
    }

    private fun mergeAddrs(tunnel: String, addrs: kotlinx.serialization.json.JsonElement): String = runCatching {
        JsonObject(json.parseToJsonElement(tunnel).jsonObject + ("addrs" to addrs)).toString()
    }.getOrDefault(tunnel)

    private fun open(method: String, to: NearbyPhone, path: String, cookie: String?, network: android.net.Network? = null): HttpURLConnection {
        val url = URL("http://${to.host}:${to.port}$path")
        // On a direct link the connection has to go out over that network specifically. And never
        // through a proxy: the other phone is on this network, and a Wi-Fi with a proxy set (with
        // no exceptions, as some have) would send even this there, which answers "Gateway Timeout".
        val conn = (network?.openConnection(url, java.net.Proxy.NO_PROXY) ?: url.openConnection(java.net.Proxy.NO_PROXY)) as HttpURLConnection
        // Android's HttpURLConnection accepts PATCH; if a build ever refuses it, fall back to
        // tus's standard override, which the server also understands.
        try {
            conn.requestMethod = method
        } catch (_: java.net.ProtocolException) {
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-HTTP-Method-Override", method)
        }
        conn.connectTimeout = 5000
        conn.readTimeout = 60_000
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", "BlazeItPhone/1 (${deviceName()})")
        cookie?.let { conn.setRequestProperty("Cookie", it) }
        return conn
    }

    private fun request(
        method: String, to: NearbyPhone, path: String, cookie: String?, body: ByteArray?,
        type: String? = null, headers: Map<String, String> = emptyMap(),
        network: android.net.Network? = null,
    ): Response {
        val conn = open(method, to, path, cookie, network)
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            type?.let { conn.setRequestProperty("Content-Type", it) }
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        } else if (method == "POST") {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0)
            conn.outputStream.close()
        }
        val code = conn.responseCode
        val text = runCatching { (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() }.getOrNull().orEmpty()
        val cookie = conn.headerFields["Set-Cookie"]?.firstOrNull { it.startsWith(SESSION_COOKIE + "=") }?.substringBefore(';')
        val location = conn.getHeaderField("Location")
        conn.disconnect()
        return Response(code, text, cookie, location)
    }

    private fun message(body: String): String? =
        runCatching { json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun load(): List<Peer> = runCatching {
        if (file.exists()) json.decodeFromString<List<Peer>>(file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private fun save() = runCatching { file.writeText(json.encodeToString(_peers.value)) }

    companion object {
        private const val TAG = "Peers"
        const val SERVICE_TYPE = "_blazeit._tcp."
        private const val PARALLEL_THRESHOLD = 16L * 1024 * 1024
        private const val CHUNK = 64L * 1024 * 1024
        /** A link's way back is renewed once a week; its session lasts a year. */
        private const val RENEW_MS = 7L * 24 * 60 * 60 * 1000
        const val SESSION_TTL_MS = 365L * 24 * 60 * 60 * 1000
        /** Where a link made with a code shows its progress in [status]. */
        const val LINK_KEY = "link code"
        /** A host that resolves nowhere: for a phone with no local address. */
        private const val NOWHERE = "nowhere.invalid"
        private const val DEFAULT_PORT = 8787
        /** How long a look around the network lasts when nobody has the Devices tab open. */
        private const val LOOK_MS = 20_000L
    }
}
