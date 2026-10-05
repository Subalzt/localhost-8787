package dev.periy.bridge.server

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.withCharset
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveStream
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.options
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "BridgeServer"
private const val TUS_VERSION = "1.0.0"

/** What of a linked phone's files the page may ask for through this one. */
private val PEER_FS = setOf("file", "view", "text", "thumb", "zip")

/** A linked phone's answer headers passed back as they are (length and type go separately). */
private val PASSED_HEADERS = listOf(
    "Content-Range", "Accept-Ranges", "Content-Disposition", "Cache-Control", "Content-Security-Policy", "X-Content-Type-Options",
)

/** How other phones and laptop helpers are named in the device list (see describeUserAgent). */

/** An introduction for a direct send (a WebRTC offer or candidate) is a few kilobytes at most. */
private const val SIGNAL_MAX = 64 * 1024

/**
 * The HTTP server the PC talks to. The phone is the origin; there is nothing else in the
 * path, by design.
 *
 * Three things here are worth reading before changing anything:
 *
 * 1. **Nothing is ever buffered whole.** Uploads stream from the request channel into
 *    [TusStore.append]; downloads stream out of a seeked file descriptor. The only
 *    `readBytes()` calls in this file are for the app's own small assets: the HTML page
 *    the turntable images and the laptop helper.
 * 2. **Auth is a pipeline interceptor, not per-route.** A route added later without an
 *    auth check is a hole; making the default "closed" and listing the exceptions in one
 *    place means a new route cannot accidentally be public.
 * 3. **tus responses always carry `Tus-Resumable`.** Clients are entitled to reject a
 *    response without it, and omitting it on the error paths is the classic way to make
 *    a resumable upload silently non-resumable.
 */
/** The tunnel as the server sees it: whether it is on, and each device's keys (docs/tunnel-protocol.md). */
class RemoteDoor(
    val tunnel: dev.periy.bridge.net.TunnelServer,
    private val keys: dev.periy.bridge.net.TunnelKeys,
    val enabled: () -> Boolean,
    private val info: () -> String,
) {
    /** The phone's HELLO plus this device's id and key, as the helper keeps it. */
    fun forDevice(deviceId: String): String {
        val base = kotlinx.serialization.json.Json.parseToJsonElement(info()).jsonObject
        return kotlinx.serialization.json.JsonObject(
            base + mapOf(
                "id" to kotlinx.serialization.json.JsonPrimitive(keys.tid(deviceId).joinToString("") { "%02x".format(it) }),
                "key" to kotlinx.serialization.json.JsonPrimitive(android.util.Base64.encodeToString(keys.psk(deviceId), android.util.Base64.NO_WRAP)),
                "on" to kotlinx.serialization.json.JsonPrimitive(enabled()),
            )
        ).toString()
    }

    /** From other networks on or off; the tunnel itself keeps listening for the phone's own networks. */
    fun set(on: Boolean) { tunnel.start(); tunnel.setFar(on) }
}

class BridgeServer(
    private val ctx: Context,
    private val config: ServerConfig,
    private val storage: Storage,
    private val tus: TusStore,
    private val index: FileIndex,
    private val clipboard: ClipboardStore,
    private val devices: DeviceRegistry,
    private val pairing: PairingManager,
    private val music: MusicLibrary,
    private val direct: dev.periy.bridge.net.DirectLink,
    private val peers: PeerManager,
    private val loudness: dev.periy.bridge.music.Loudness,
    private val favourites: dev.periy.bridge.music.Favourites,
    /** Lyrics, shared with the phone's own player, which looks them up itself too. */
    private val lyricsFinder: LyricsFinder,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val outbound = OutboundTracker()
    private val lyrics = lyricsFinder.store
    private val beacon = Control.Beacon(ctx, config.port, config.deviceName)

    /** The paired device behind this request, or null. Signature, expiry and revocation. */
    private fun ApplicationCall.device(): PairedDevice? {
        val id = Session.verify(config.sessionKey(), request.cookies[SESSION_COOKIE]) ?: return null
        return devices.get(id)
    }

    /** Songs made smaller for the internet (CD-quality FLAC or AAC), kept in the cache. */
    private val transcoder by lazy { dev.periy.bridge.music.Transcoder(ctx, music) }

    /** The page, gzipped once (it only changes with the app). */
    private val pageGzip: ByteArray by lazy {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { gz -> ctx.assets.open("bridge.html").use { it.copyTo(gz) } }
        out.toByteArray()
    }

    /** The page's tag: a browser that has it asks "still this?" and is told so in one round trip. */
    private val pageTag: String by lazy { tagOf(pageGzip, "p") }

    private fun tagOf(bytes: ByteArray, kind: String): String =
        "W/\"$kind" + java.util.zip.CRC32().apply { update(bytes) }.value.toString(16) + "-" + bytes.size + "\""

    /** True (and answered 304) when the browser already has what carries [tag]. */
    private suspend fun ApplicationCall.unchanged(tag: String): Boolean {
        response.header(HttpHeaders.ETag, tag)
        if (request.header(HttpHeaders.IfNoneMatch)?.split(',')?.any { it.trim() == tag } != true) return false
        respond(HttpStatusCode.NotModified)
        return true
    }

    /** [bytes] gzipped for a browser that takes it: a song list or a listing is a sixth of the size. */
    private suspend fun ApplicationCall.respondPacked(bytes: ByteArray, type: ContentType) {
        response.header(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
        if (bytes.size > 1024 && request.header(HttpHeaders.AcceptEncoding)?.contains("gzip") == true) {
            val out = java.io.ByteArrayOutputStream(bytes.size / 4)
            java.util.zip.GZIPOutputStream(out).use { it.write(bytes) }
            response.header(HttpHeaders.ContentEncoding, "gzip")
            respondBytes(out.toByteArray(), type)
        } else {
            respondBytes(bytes, type)
        }
    }

    /** Website sign-ins on their way to the plain address: code to device id and when it runs out. */
    private val handoffs = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    @Volatile
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    val isRunning: Boolean get() = engine != null

    /** The tunnel, for paired devices on other networks; set by the container before [start]. */
    var remote: RemoteDoor? = null

    /** The phone as a website (docs/website.md); set by the container before [start]. */
    var site: dev.periy.bridge.net.Site? = null

    /** Came through the website's TLS door: the PIN, never a pairing prompt. */
    private fun ApplicationCall.viaSite(): Boolean = remoteIp() == dev.periy.bridge.net.Site.LOCAL_HOST

    /**
     * Came through the tunnel: the phone's end connects to the page at its own loopback address,
     * so this side of the socket says so. (The other side is 127.0.0.1, as adb's forward is too:
     * look at this first.)
     */
    private fun ApplicationCall.viaTunnel(): Boolean =
        request.local.localAddress.removePrefix("::ffff:").substringBefore('%') == dev.periy.bridge.net.TunnelProto.LOCAL_HOST

    /**
     * Came through a tunnel from one of the phone's own networks (sealed, but near): that device's
     * tunnel, which knows the addresses at both ends; null for anything else.
     */
    private fun ApplicationCall.nearTunnel(): dev.periy.bridge.net.TunnelPeer? {
        if (request.local.localAddress.removePrefix("::ffff:").substringBefore('%') != dev.periy.bridge.net.TunnelProto.LOCAL_NEAR) return null
        remote?.tunnel?.byPort(request.origin.remotePort)?.let { return it }
        val id = device()?.id
        val near = remote?.tunnel?.peers?.value?.filter { it.near }.orEmpty()
        return near.firstOrNull { it.deviceId == id } ?: near.singleOrNull()
    }

    /** The phone's address the request came in on: through a near tunnel, the one the tunnel came in on. */
    private fun ApplicationCall.localAddr(): String =
        nearTunnel()?.here?.takeIf { it.isNotEmpty() } ?: request.local.localAddress.removePrefix("::ffff:").substringBefore('%')

    /** The far end of this device's tunnel: "2409:…" over TCP, or "152.59.187.3 (UDP)" punched through. */
    private fun tunnelFar(deviceId: String?): String? =
        deviceId?.let { id -> remote?.tunnel?.peers?.value?.firstOrNull { it.deviceId == id }?.remote }

    /** Who is really there: through the website, the browser's own address; through the tunnel, the laptop's. */
    private fun ApplicationCall.clientIp(): String = when {
        viaSite() -> site?.clientFor(request.origin.remotePort) ?: "?"
        // The tunnel this very request came through (a page a helper passes on comes through the
        // helper's), so the page's address is its laptop's, as the helper's is.
        viaTunnel() -> (remote?.tunnel?.byPort(request.origin.remotePort)?.remote ?: tunnelFar(device()?.id))?.removeSuffix(" (UDP)") ?: "the tunnel"
        else -> remoteIp()
    }

    fun start() {
        if (engine != null) return
        storage.refresh()
        tus.sweep()

        // Ktor 3 has no embeddedServer overload taking port, host *and* configure
        // together -- configure only exists on the environment-based overload, and the
        // bind address moves into a connector block. Both are needed here: the port is
        // user-configurable, and the idle timeout has to be raised.
        val server = embeddedServer(
            CIO,
            configure = {
                // "::" is dual-stack on Android: IPv4 clients arrive as ::ffff:a.b.c.d, and a
                // global IPv6 on mobile data can be reached from outside (docs/remote-plan.md).
                connector {
                    host = "::"
                    port = config.port
                }
                // The default (45s) closes SSE streams and would also drop a paused
                // upload's connection before the browser has finished backing off.
                connectionIdleTimeoutSeconds = 180
            },
            module = { module() },
        )
        server.start(wait = false)
        engine = server
        // Always on: devices on the phone's own networks reach it sealed through it too; from the
        // internet only with From other networks on (the tunnel checks).
        remote?.tunnel?.start()
        site?.start()
        beacon.start()
        Monitor.start(ctx)
        Log.i(TAG, "Listening on :${config.port}")
    }

    fun stop() {
        remote?.tunnel?.stop()
        site?.stop()
        beacon.stop()
        Monitor.stop()
        engine?.stop(GRACE_MS, TIMEOUT_MS)
        engine = null
        Log.i(TAG, "Stopped")
    }

    // ------------------------------------------------------------------ module

    private fun Application.module() {
        install(DefaultHeaders)
        install(ContentNegotiation) { json(this@BridgeServer.json) }
        install(PartialContent)
        install(SSE)
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                Log.w(TAG, "Unhandled error on ${call.request.path()}", cause)
                // Keep Tus-Resumable on error responses too, or a client is within its
                // rights to treat the upload as unresumable and start over.
                call.response.header("Tus-Resumable", TUS_VERSION)
                call.respond(HttpStatusCode.InternalServerError, ApiResult(false, cause.message))
            }
        }

        // Counts requests being served, for the monitor. The two long-lived streams are
        // left out: they are always open and would only hide what is actually moving.
        intercept(ApplicationCallPipeline.Monitoring) {
            val path = call.request.path()
            if (path == "/events" || path == "/api/control/stream") return@intercept
            Monitor.requestStarted()
            try { proceed() } finally { Monitor.requestEnded() }
        }

        intercept(ApplicationCallPipeline.Plugins) {
            // The server listens on IPv6 too, so on mobile data the whole internet can reach it:
            // from outside the phone's own networks the way in is the tunnel, where devices are
            // known before a byte of HTTP. Here they get nothing, not even a pairing prompt.
            if (!call.fromLocalNetwork()) {
                call.respond(HttpStatusCode.Forbidden, ApiResult(false, "Only from the phone's own networks."))
                finish()
                return@intercept
            }
            val path = call.request.path()
            // Through the website, as on the local network: the page and asking the phone (each ask
            // limited, see Site.mayAsk); nothing else until the phone says yes.
            if (call.viaSite()) {
                if (path in PUBLIC_PATHS || path.startsWith("/api/pair") || path == "/api/site" || path == "/api/site/enroll") return@intercept
            } else if (path in PUBLIC_PATHS || path.startsWith("/api/pair") || path == "/api/site" || path == "/api/site/handoff/use") return@intercept
            val device = call.device()
            if (device != null) {
                devices.touch(device.id, call.clientIp())
                // How it came, for Home's "Connected now": looked at again after 5 s at most.
                val last = Ways.last(device.id)
                if (last == null || System.currentTimeMillis() - last.at > 5_000) runCatching { Ways.seen(device.id, call.way(device.id)) }
                return@intercept
            }
            call.response.header("Tus-Resumable", TUS_VERSION)
            call.respond(HttpStatusCode.Unauthorized, ApiResult(false, "Not paired"))
            finish()
        }

        routing {
            page()
            auth()
            api()
            events()
            bench()
            tusRoutes()
            musicRoutes()
            themeRoutes()
            controlRoutes()
            monitorRoutes()
            directRoutes()
            p2pTrial()
            phoneFileRoutes()
            peerRoutes()
            pipeRoutes()
            notificationRoutes()
        }
    }

    // ------------------------------------------------------------------ music

    private fun io.ktor.server.routing.Route.musicRoutes() {
        get("/api/music") {
            val refresh = call.request.queryParameters["refresh"] == "1"
            val bytes = withContext(Dispatchers.IO) {
                json.encodeToString(MusicDto.serializer(), MusicDto(granted = music.granted(), tracks = music.tracks(refresh))).toByteArray()
            }
            // Asked after each time, sent again only when the songs changed; gzipped, a sixth of the size.
            call.response.header(HttpHeaders.CacheControl, "private, no-cache")
            if (call.unchanged(tagOf(bytes, "m"))) return@get
            call.respondPacked(bytes, ContentType.Application.Json)
        }

        // Served with byte ranges, so the browser starts playing after the first few
        // kilobytes and can seek anywhere without fetching what it skips.
        get("/api/music/stream/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
            val track = id?.let { withContext(Dispatchers.IO) { music.find(it) } }
            if (track == null) {
                call.respond(HttpStatusCode.NotFound, ApiResult(false, "No such track"))
                return@get
            }
            // The file will not change under this id, so the browser may keep what it has
            // fetched -- replaying a song then costs nothing at all.
            call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
            // Over a thin link the page asks for the smaller copy its info said (?q=cd or aac), made
            // here once and served as a file, byte ranges and all. Not made yet: it is sent as it is
            // being made, so the song starts within a second instead of after the whole copy; a
            // seek before it is finished waits for it.
            val mode = dev.periy.bridge.music.Transcoder.Mode.of(call.request.queryParameters["q"])
            if (mode != null) {
                val made = transcoder.ready(track.id, mode)
                if (made != null) {
                    call.respond(io.ktor.server.http.content.LocalFileContent(made, ContentType.parse(mode.mime)))
                    return@get
                }
                val job = transcoder.start(track.id, mode)
                val ranged = call.request.header(HttpHeaders.Range)?.let { !it.startsWith("bytes=0-") } == true
                if (ranged) {
                    job.await()?.let {
                        call.respond(io.ktor.server.http.content.LocalFileContent(it, ContentType.parse(mode.mime)))
                        return@get
                    }
                } else {
                    // The copy as it grows, followed until it is done.
                    val part = transcoder.part(track.id, mode)
                    var waited = 0
                    while (!part.isFile && !job.isCompleted && waited < 10_000) { kotlinx.coroutines.delay(20); waited += 20 }
                    if (part.isFile || job.isCompleted) {
                        call.response.header(HttpHeaders.CacheControl, "no-store")
                        call.respondOutputStream(ContentType.parse(mode.mime)) {
                            val src = runCatching { java.io.RandomAccessFile(if (part.isFile) part else (job.await() ?: part), "r") }.getOrNull() ?: return@respondOutputStream
                            src.use { raf ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = raf.read(buf)
                                    if (n > 0) { write(buf, 0, n); flush(); continue }
                                    if (job.isCompleted) {
                                        // Done: whatever was written after the last read, then the end.
                                        while (true) { val m = raf.read(buf); if (m <= 0) break; write(buf, 0, m) }
                                        break
                                    }
                                    Thread.sleep(40)
                                }
                            }
                        }
                        return@get
                    }
                }
            }
            call.respond(
                UriRangeContent(
                    scope = call,
                    resolver = ctx.contentResolver,
                    uri = music.uri(track.id),
                    length = track.size,
                    type = runCatching { ContentType.parse(track.mime) }.getOrDefault(ContentType.Audio.Any),
                )
            )
        }

        // The next song's smaller copy, made now in the background (it takes the phone a few
        // seconds), so it is ready the moment the page asks for it.
        post("/api/music/prepare/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
            val mode = dev.periy.bridge.music.Transcoder.Mode.of(call.request.queryParameters["q"])
            if (id != null && mode != null) transcoder.start(id, mode)
            call.respond(HttpStatusCode.Accepted, ApiResult(true))
        }

        // A song's lyrics, as a page (or the phone itself) found them online and left here; see LyricsStore.
        get("/api/music/lyrics/{id}") {
            val track = call.parameters["id"]?.toLongOrNull()?.let { withContext(Dispatchers.IO) { music.find(it) } }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            var doc = track?.let { withContext(Dispatchers.IO) { lyrics.get(it) } }
            // None kept yet, or words with no timing: the phone looks them up on LRCLIB itself
            // (timed ones first, once a run for untimed), keeps what it finds, and answers with it.
            val untimed = doc != null && doc.source != "none" && !doc.instrumental && doc.lrc.isBlank() && doc.lyricsfile.isBlank()
            if (track != null && (doc == null || untimed)) {
                runCatching { lyricsFinder.forTrack(track) }
                doc = withContext(Dispatchers.IO) { lyrics.get(track) } ?: doc
            }
            call.respond(LyricsDto(doc != null, doc))
        }
        put("/api/music/lyrics/{id}") {
            val track = call.parameters["id"]?.toLongOrNull()?.let { withContext(Dispatchers.IO) { music.find(it) } }
            val declared = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: 0
            if (track == null || declared > LyricsStore.MAX_BYTES) {
                call.respond(HttpStatusCode.BadRequest, ApiResult(false, "No such track, or too large"))
                return@put
            }
            val doc = runCatching { call.receive<LyricsDoc>() }.getOrNull()
            if (doc == null || doc.source.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiResult(false, "Bad lyrics"))
                return@put
            }
            withContext(Dispatchers.IO) { lyrics.put(track, doc) }
            // The phone's player shows them too, if it is on this song.
            lyricsFinder.forget(track.id)
            call.respond(ApiResult(true))
        }

        // How loud each moment of a song is (a byte per 50 ms), worked out here once: the page
        // draws its seek bar from it and swells the cover with it, as the phone's player does.
        get("/api/music/loudness/{id}") {
            val track = call.parameters["id"]?.toLongOrNull()?.let { withContext(Dispatchers.IO) { music.find(it) } }
            val bytes = track?.let { loudness.of(it.id) }
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            // The same song always sounds the same: kept for good.
            call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            call.respondBytes(bytes, ContentType.Application.OctetStream)
        }

        // What a song's file is (FLAC, 1,411 kbps, 44.1 kHz), for the player's chip.
        get("/api/music/info/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
            val info = id?.let { withContext(Dispatchers.IO) { music.info(it) } }
            if (id == null || info == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            // ?link=kbps (the page, over the internet): what will be sent over a link that fast,
            // the song's own file, or a smaller copy (and what that copy is).
            val link = call.request.queryParameters["link"]?.toIntOrNull()
            if (link != null) {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val mode = transcoder.plan(info, link)
                call.respond(if (mode == null) info else transcoder.infoOf(id, info, mode).copy(smaller = mode.name.lowercase()))
                return@get
            }
            call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
            call.respond(info)
        }

        // The songs with a heart: the same on the phone's player and every page's.
        get("/api/music/favourites") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondBytes(favourites.json().toByteArray(), ContentType.Application.Json)
        }
        post("/api/music/favourites/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
            val on = runCatching { call.receive<FavouriteDto>().on }.getOrNull()
            if (id == null || on == null) {
                call.respond(HttpStatusCode.BadRequest, ApiResult(false, "Which song, and heart or not"))
                return@post
            }
            favourites.set(id, on)
            call.respondBytes(favourites.json().toByteArray(), ContentType.Application.Json)
        }

        // The equalizer: one for the phone's player and every page's; the curves to choose from come with it.
        get("/api/music/eq") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond((ctx.applicationContext as dev.periy.bridge.BridgeApp).container.eq.dto())
        }
        put("/api/music/eq") {
            val s = runCatching { call.receive<dev.periy.bridge.music.EqState>() }.getOrNull()
            if (s == null) {
                call.respond(HttpStatusCode.BadRequest, ApiResult(false, "On or off, the curve and its ten gains"))
                return@put
            }
            val store = (ctx.applicationContext as dev.periy.bridge.BridgeApp).container.eq
            store.set(s)
            call.respond(store.dto())
        }

        get("/api/music/art/{albumId}") {
            val albumId = call.parameters["albumId"]?.toLongOrNull()
            // ?s=128 or 384: a small WebP for a row or a tile, a tenth of the full cover's bytes.
            val px = call.request.queryParameters["s"]?.toIntOrNull()?.let { if (it <= 128) 128 else 384 }
            val bytes = albumId?.let { withContext(Dispatchers.IO) { if (px != null) music.cover(it, px) else music.cover(it) } }
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.response.header(HttpHeaders.CacheControl, "private, max-age=604800")
            val webp = bytes.size > 12 && bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte()
            call.respondBytes(bytes, if (webp) ContentType("image", "webp") else ContentType.Image.JPEG)
        }
    }

    // ------------------------------------------------------------------ monitor

    private fun io.ktor.server.routing.Route.monitorRoutes() {
        get("/api/monitor") {
            Monitor.touchWeb()
            Monitor.noteVia(call.arrivedOn().second.name.lowercase())
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(Monitor.snapshot.value)
        }
        // The laptop helper reports its own side of the Wi-Fi link every couple of seconds.
        post("/api/monitor/rtt") {
            runCatching { call.receive<RttReport>() }.getOrNull()?.takeIf { it.ms >= 0 }?.let { Monitor.reportRtt(it.ms) }
            call.respond(ApiResult(true))
        }
        // The answer tells the helper whether anyone is watching, so it can report every
        // couple of seconds while the monitor is open and rarely otherwise.
        post("/api/monitor/link") {
            Monitor.noteVia(call.arrivedOn().second.name.lowercase())
            runCatching { call.receive<LaptopLink>() }.getOrNull()?.let(Monitor::reportLaptop)
            call.respond(ApiResult(true, if (Monitor.watched()) "watch" else "idle"))
        }
    }

    // ------------------------------------------------------------------ direct link

    /**
     * The phone's own offline network. A paired laptop (through its helper) or phone reads
     * the name and password here and joins it by itself; the page offers the same button.
     */
    private fun io.ktor.server.routing.Route.directRoutes() {
        get("/api/direct") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(directDto())
        }
        get("/api/route") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            if (call.viaSite()) {
                // Through the website: the name, and how the browser itself is connected.
                val who = call.clientIp()
                // The phone's public STUN addresses too, so pages on two networks can still try to
                // send to each other directly (WebRTC) before going through the phone.
                val stun = dev.periy.bridge.net.NetInfo.publicAddresses(ctx)
                    .map { if (':' in it) "[$it]:${dev.periy.bridge.net.StunServer.PORT}" else "$it:${dev.periy.bridge.net.StunServer.PORT}" }
                val over = localOver(who, dev.periy.bridge.net.NetInfo.addresses(), site?.destFor(call.request.origin.remotePort).orEmpty())
                call.respond(RouteDto(via = "website", host = site?.state?.value?.name.orEmpty(), stun = stun, ip = if (':' in who) "IPv6" else "IPv4", over = over))
                return@get
            }
            // Through the helper's adb forward (the cable, tethering off): from the phone's own loopback.
            // The tunnel comes from loopback too, but to its own address: not adb.
            if (!call.viaTunnel() && call.remoteIp().let { it == "127.0.0.1" || it == "::1" }) {
                call.respond(RouteDto(via = "adb", host = "127.0.0.1"))
                return@get
            }
            val all = dev.periy.bridge.net.NetInfo.addresses()
            val (here, via) = call.arrivedOn(all)
            val usb = all.firstOrNull { it.kind == dev.periy.bridge.net.LinkKind.USB && !it.isIpv6 }
            val tunnelled = here == dev.periy.bridge.net.TunnelProto.LOCAL_HOST
            val stunPort = dev.periy.bridge.net.StunServer.PORT
            val stun = (listOfNotNull(here.takeIf { !it.startsWith("127.") }) + dev.periy.bridge.net.NetInfo.publicAddresses(ctx))
                .distinct().map { if (':' in it) "[$it]:$stunPort" else "$it:$stunPort" }
            // Through the tunnel: this device's tunnel says how it came (IPv6 over TCP, or IPv4
            // punched through over UDP). Otherwise the phone's address that answered says it.
            val far = if (tunnelled) remote?.tunnel?.byPort(call.request.origin.remotePort)?.remote ?: tunnelFar(call.device()?.id) else null
            val punched = far?.endsWith("(UDP)") == true
            val ip = when {
                tunnelled -> if (punched || far == null || ':' !in far) "IPv4" else "IPv6"
                ':' in here -> "IPv6"
                else -> "IPv4"
            }
            call.respond(
                RouteDto(
                    via = if (tunnelled) "internet" else via.name.lowercase(),
                    host = here,
                    // A cable on the phone is another laptop's business when this one is on another network.
                    usb = usb?.host?.takeIf { via != dev.periy.bridge.net.LinkKind.USB && !tunnelled },
                    usbMbps = if (via == dev.periy.bridge.net.LinkKind.USB) Monitor.laptopUsbMbps() else 0,
                    stun = stun,
                    ip = ip,
                    punched = punched,
                )
            )
        }
        post("/api/direct/start") {
            // In hotspot mode only the phone can turn its hotspot on; say so.
            if (config.laptopLink() == "hotspot" && call.request.queryParameters["for"] != "phone") {
                call.respond(directDto())
                return@post
            }
            // "for=phone" when another phone asks for it for a send; a laptop then stays put.
            direct.start(config.port, laptop = call.request.queryParameters["for"] != "phone")
            // Starting takes a second or two; wait for it so the caller gets the details.
            withTimeoutOrNull(8_000) {
                direct.state.first { it is dev.periy.bridge.net.DirectLink.State.On && it.info.host.isNotEmpty() ||
                    it is dev.periy.bridge.net.DirectLink.State.Failed }
            }
            call.respond(directDto())
        }
        post("/api/direct/stop") {
            if (config.laptopLink() == "hotspot" && direct.state.value !is dev.periy.bridge.net.DirectLink.State.On) {
                call.respond(directDto())
                return@post
            }
            direct.stop()
            // stop() finishes on the main thread; answer with the state it leaves behind.
            withTimeoutOrNull(2_000) { direct.state.first { it !is dev.periy.bridge.net.DirectLink.State.On } }
            call.respond(directDto())
        }
    }

    // ------------------------------------------------------------------ the phone's storage

    private val phoneFiles = PhoneFiles()

    /**
     * Read-only browsing of the phone's shared storage from the page. Every route sits behind
     * the pairing gate like the rest, and nothing is reachable until the phone's owner turns on
     * "All files access" for Localhost 8787.
     */
    private fun io.ktor.server.routing.Route.phoneFileRoutes() {
        get("/api/fs") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(withContext(Dispatchers.IO) { phoneFiles.list(call.request.queryParameters["path"]) })
        }
        get("/api/fs/file") {
            val f = phoneFiles.resolve(call.request.queryParameters["path"])
            if (!phoneFiles.granted() || f == null || !f.isFile) {
                call.respond(HttpStatusCode.NotFound, ApiResult(false, "No such file"))
                return@get
            }
            var type = runCatching { ContentType.parse(PhoneFiles.mimeOf(f.name)) }.getOrDefault(ContentType.Application.OctetStream)
            // "?inline=1" is the page's preview: the browser shows the file instead of saving it.
            // Only kinds a browser displays by itself are sent inline, text always as plain text,
            // and everything inline is sandboxed (bar PDF, whose viewer will not run sandboxed),
            // so an HTML or SVG file on the phone can never run as this page.
            val preview = PhoneFiles.previewOf(f.name)
            val inline = call.request.queryParameters["inline"] == "1" &&
                preview in setOf("image", "video", "audio", "pdf", "text")
            if (inline && preview == "text") type = ContentType.Text.Plain.withCharset(Charsets.UTF_8)
            if (inline && preview != "pdf") call.response.header("Content-Security-Policy", "sandbox")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header(
                HttpHeaders.ContentDisposition,
                (if (inline) ContentDisposition.Inline else ContentDisposition.Attachment)
                    .withParameter(ContentDisposition.Parameters.FileName, f.name).toString(),
            )
            call.respond(FileRangeContent(call, f, type))
        }
        // A photo the browser cannot decode itself (HEIC, DNG...), as a large JPEG to look at.
        get("/api/fs/view") {
            val f = phoneFiles.resolve(call.request.queryParameters["path"])
            val bytes = if (phoneFiles.granted() && f != null && f.isFile && PhoneFiles.previewOf(f.name) == "photo")
                withContext(Dispatchers.IO) { phoneFiles.viewImage(f, 2560) } else null
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound, ApiResult(false, "No preview for this file"))
                return@get
            }
            call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
            call.respondBytes(bytes, ContentType.Image.JPEG)
        }
        // The text of an Office or OpenDocument file, which browsers cannot show.
        get("/api/fs/text") {
            val f = phoneFiles.resolve(call.request.queryParameters["path"])
            val text = if (phoneFiles.granted() && f != null && f.isFile && PhoneFiles.previewOf(f.name) == "doc")
                withContext(Dispatchers.IO) { phoneFiles.documentText(f) } else null
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(DocTextDto(text != null, text.orEmpty()))
        }
        get("/api/fs/thumb") {
            val f = phoneFiles.resolve(call.request.queryParameters["path"])
            val bytes = if (phoneFiles.granted() && f != null && f.isFile) withContext(Dispatchers.IO) { phoneFiles.thumbnail(f, 240) } else null
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
            call.respondBytes(bytes, ContentType.Image.JPEG)
        }
        // A whole folder as one zip, streamed as it is made: no size up front, nothing staged.
        get("/api/fs/zip") {
            val dir = phoneFiles.resolve(call.request.queryParameters["path"])
            if (!phoneFiles.granted() || dir == null || !dir.isDirectory) {
                call.respond(HttpStatusCode.NotFound, ApiResult(false, "No such folder"))
                return@get
            }
            val name = (if (dir == phoneFiles.root) "Phone" else dir.name) + ".zip"
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString(),
            )
            call.respondOutputStream(ContentType.parse("application/zip")) {
                runCatching { phoneFiles.zip(dir, this) }
            }
        }
    }

    // ------------------------------------------------------------------ linked phones

    /**
     * Phone to phone (see PeerManager): the greeting that links two phones both ways, the
     * goodbye that unlinks them, the clipboard passed between them; and for the page, the
     * linked phones and their files, passed through this one.
     */
    private fun io.ktor.server.routing.Route.peerRoutes() {
        post("/api/peers/hello") {
            val me = call.device() ?: return@post
            val h = runCatching { call.receive<PeerHello>() }.getOrNull()
            if (h == null || h.cookie.isBlank() || h.name.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiResult(false, "Bad greeting"))
                return@post
            }
            // A greeting from this phone itself (its own session in it) links nothing.
            if (!peers.hello(h, call.remoteIp(), me.id)) {
                call.respond(HttpStatusCode.Conflict, ApiResult(false, "That is this phone"))
                return@post
            }
            call.respond(ApiResult(true, config.deviceName))
        }
        post("/api/peers/bye") {
            call.device()?.let { peers.bye(it.id) }
            call.respond(ApiResult(true))
        }
        // A copy on a linked phone. Taken only when newer than what is here (see PeerManager.takeClip).
        // A message from a linked phone, sealed with the key this phone made for it (server/Messages.kt).
        post("/api/peers/msg") {
            val me = call.device() ?: return@post
            val from = peers.byDevice(me.id)?.name
            val w = runCatching { call.receive<MsgWire>() }.getOrNull()
            if (from == null || w == null || w.c.length > 64_000) {
                call.respond(HttpStatusCode.BadRequest, ApiResult(false, "Bad message"))
                return@post
            }
            if ((ctx.applicationContext as dev.periy.bridge.BridgeApp).container.messages.receive(from, me.id, w)) call.respond(ApiResult(true))
            else call.respond(HttpStatusCode.Forbidden, ApiResult(false, "That message does not open here"))
        }
        // A message's attachment from a linked phone, sealed the same way (server/Messages.kt).
        post("/api/peers/msg/blob") {
            val me = call.device() ?: return@post
            val id = call.request.queryParameters["id"].orEmpty()
            val len = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
            if (peers.byDevice(me.id) == null || id.isEmpty() || len > Messages.MAX_FILE + 64) {
                call.respond(HttpStatusCode.BadRequest, ApiResult(false, "Bad attachment"))
                return@post
            }
            val bytes = withContext(Dispatchers.IO) { call.receiveChannel().toInputStream().readBytes() }
            if ((ctx.applicationContext as dev.periy.bridge.BridgeApp).container.messages.receiveBlob(me.id, id, bytes)) call.respond(ApiResult(true))
            else call.respond(HttpStatusCode.Forbidden, ApiResult(false, "That attachment does not open here"))
        }
        post("/api/peers/call") {
            val me = call.device() ?: return@post
            val from = peers.byDevice(me.id)?.name
            val w = runCatching { call.receive<CallWire>() }.getOrNull()
            if (from == null || w == null || w.c.length > 64_000) {
                call.respond(HttpStatusCode.BadRequest, ApiResult(false, "Bad call signal"))
                return@post
            }
            if ((ctx.applicationContext as dev.periy.bridge.BridgeApp).container.calls.receive(from, me.id, w)) call.respond(ApiResult(true))
            else call.respond(HttpStatusCode.Forbidden, ApiResult(false, "That signal does not open here"))
        }
        // ---- Where your phones and laptops are (server/Where.kt): for the page's map; laptops' helpers
        // say where they are, linked phones where they and their laptops are.
        get("/api/where") {
            call.device() ?: return@get
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val w = where()
            call.respond(WhereDto(w.places(), w.allowed(), w.allowedAlways()))
        }
        post("/api/where/laptop") {
            val me = call.device() ?: return@post
            val f = runCatching { call.receive<LaptopFix>() }.getOrNull()
            if (f == null || f.lat !in -90.0..90.0 || f.lon !in -180.0..180.0 || f.acc <= 0f) { call.respond(HttpStatusCode.BadRequest, ApiResult(false, "Bad position")); return@post }
            where().laptop(me.id, helperMachine(me.name).removeSuffix(" (website)"),
                Fix(f.lat, f.lon, f.acc, if (f.at > 0) f.at else System.currentTimeMillis(), src = "wifi", battery = f.battery))
            call.respond(ApiResult(true))
        }
        post("/api/where/forget") {
            call.device() ?: return@post
            val id = runCatching { call.receive<ChatKey>() }.getOrNull()?.key.orEmpty()
            if (id.isNotEmpty()) where().forget(id)
            call.respond(ApiResult(true))
        }

        // ---- Another laptop, seen and driven from this page: its screen as its helper streams it
        // (passed on here as it comes, each picture with its length), and this page's mouse and keys
        // sent to its helper as the phone's Control tab sends them.
        get("/api/laptops") {
            val me = call.device() ?: return@get
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val all = devices.devices.value
            val online = Control.online()
            // Debug builds: "?self=1" lists this page's own laptop too, to try it all on one machine.
            val self = call.request.queryParameters["self"] == "1" && dev.periy.bridge.BuildConfig.DEBUG
            call.respond(all.filter { it.id in online && it.name.startsWith(HELPER_PREFIX) && (self || !sameMachine(it, me, all)) }
                .map { LaptopDto(it.id, helperMachine(it.name).removeSuffix(" (website)")) }.distinctBy { it.name })
        }
        get("/api/laptops/view") {
            val me = call.device() ?: return@get
            val id = call.request.queryParameters["id"].orEmpty()
            if (id !in Control.online()) { call.respond(HttpStatusCode.NotFound, ApiResult(false, "That laptop's helper is not running")); return@get }
            val w = call.request.queryParameters["w"]?.toIntOrNull()?.coerceIn(320, 7680) ?: 1920
            val h = call.request.queryParameters["h"]?.toIntOrNull()?.coerceIn(240, 4320) ?: 1080
            // A page from afar (the tunnel, the website): the picture made for the internet, whatever way the laptop has.
            val far = call.viaTunnel() || call.viaSite()
            val vid = java.util.UUID.randomUUID().toString().take(12)
            val queue = java.util.concurrent.LinkedBlockingQueue<DisplayFeed.Feed>()
            DisplayFeed.views[vid] = queue
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondBytesWriter(ContentType("application", "x-l87-frames")) {
                EventBus.emitTo(id, "display", "start 0 $w $h 60 0 http view=$vid mirror" + (if (far) " far" else ""))
                // What has reached this page, told back to the helper every 50 ms, as the phone's own view
                // does: on a clock, not only as more comes (the helper waits for word before sending more).
                val ackSid = java.util.concurrent.atomic.AtomicReference("")
                val ackBytes = java.util.concurrent.atomic.AtomicLong(0)
                val acker = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
                    var last = -1L
                    while (true) {
                        kotlinx.coroutines.delay(50)
                        val n = ackBytes.get()
                        if (n != last && ackSid.get().isNotEmpty()) { EventBus.emitTo(id, "displayack", "${ackSid.get()} $n"); last = n }
                    }
                }
                try {
                    var first = true
                    while (true) {
                        // The first stream once the helper has started; another after it starts again (a monitor changed).
                        val feed = withContext(Dispatchers.IO) { queue.poll(if (first) 30L else 15L, java.util.concurrent.TimeUnit.SECONDS) } ?: break
                        first = false
                        var sent = 0L
                        ackSid.set(feed.sid); ackBytes.set(0)
                        val buf = ByteArray(64 * 1024)
                        try {
                            while (true) {
                                val n = withContext(Dispatchers.IO) { feed.input.read(buf) }
                                if (n < 0) break
                                writeFully(buf, 0, n)
                                flush()
                                sent += n
                                ackBytes.set(sent)
                            }
                        } finally {
                            runCatching { feed.input.close() }
                            feed.done.complete(Unit)
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    acker.cancel()
                    DisplayFeed.views.remove(vid)?.let { q -> while (true) { val f = q.poll() ?: break; runCatching { f.input.close() }; f.done.complete(Unit) } }
                    EventBus.emitTo(id, "display", "stop")
                }
            }
        }
        post("/api/laptops/input") {
            call.device() ?: return@post
            val id = call.request.queryParameters["id"].orEmpty()
            val body = call.receiveText()
            if (id !in Control.online() || body.length > 64_000) { call.respond(HttpStatusCode.NotFound, ApiResult(false, "That laptop's helper is not running")); return@post }
            body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && it.length < 4_000 }.forEach { Control.sendTo(id, it) }
            call.respond(ApiResult(true))
        }

        // ---- Messages on a laptop's page, as WhatsApp's: the phone's conversations (server/Messages.kt),
        // read and written there; the phone sends them on as it does its own. "messages" says one changed.
        get("/api/chats") {
            call.device() ?: return@get
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val m = messages()
            val keys = (m.threads.value.keys + m.groups.value.keys.map { Messages.GROUP + it }).distinct()
            val chats = keys.mapNotNull { k ->
                val g = m.group(k)
                val t = m.thread(k)
                if (g == null && t.isEmpty()) return@mapNotNull null
                val last = t.lastOrNull()
                ChatSummary(
                    k, m.title(k), g != null, g?.let { m.others(it) } ?: listOf(k), m.unread(k),
                    last?.at ?: g?.at ?: 0L, last?.let { chatLine(it) }.orEmpty(), last?.mine == true, last?.from.orEmpty(),
                )
            }.sortedByDescending { it.at }
            call.respond(ChatList(peers.deviceName(), chats, peers.dto().map { it.name }))
        }
        get("/api/chats/thread") {
            call.device() ?: return@get
            val k = call.request.queryParameters["key"].orEmpty()
            val m = messages()
            val g = m.group(k)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(ChatThread(k, m.title(k), g != null, g?.let { m.others(it) } ?: listOf(k), m.thread(k).map { x ->
                ChatItem(x.id, x.mine, x.text, x.at, x.state, x.kind, x.name, x.size, x.mime, x.durationMs, x.w, x.h, x.from,
                    x.file.isNotEmpty() && java.io.File(x.file).exists())
            }))
        }
        get("/api/chats/file") {
            call.device() ?: return@get
            val k = call.request.queryParameters["key"].orEmpty()
            val id = call.request.queryParameters["id"].orEmpty()
            val x = messages().thread(k).firstOrNull { it.id == id }
            val f = x?.file?.takeIf { it.isNotEmpty() }?.let { java.io.File(it) }?.takeIf { it.exists() }
            if (x == null || f == null) { call.respond(HttpStatusCode.NotFound, ApiResult(false, "Not here (yet)")); return@get }
            if (call.request.queryParameters["dl"] == "1") call.response.header(HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, x.name).toString())
            call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
            call.respond(io.ktor.server.http.content.LocalFileContent(f, runCatching { ContentType.parse(x.mime) }.getOrDefault(ContentType.Application.OctetStream)))
        }
        post("/api/chats/send") {
            call.device() ?: return@post
            val r = runCatching { call.receive<ChatSend>() }.getOrNull()
            if (r == null || r.key.isEmpty() || r.text.isBlank()) { call.respond(HttpStatusCode.BadRequest, ApiResult(false, "Nothing to send")); return@post }
            messages().send(r.key, r.text)
            call.respond(ApiResult(true))
        }
        post("/api/chats/read") {
            call.device() ?: return@post
            val r = runCatching { call.receive<ChatKey>() }.getOrNull()
            if (r != null && r.key.isNotEmpty()) messages().markRead(r.key)
            call.respond(ApiResult(true))
        }
        post("/api/chats/upload") {
            call.device() ?: return@post
            val q = call.request.queryParameters
            val k = q["key"].orEmpty()
            val kind = q["kind"].orEmpty().takeIf { it in Messages.FILE_KINDS } ?: "file"
            val name = q["name"].orEmpty().ifEmpty { "file" }.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)
            val mime = call.request.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim().orEmpty().ifEmpty { PhoneFiles.mimeOf(name) }
            if (k.isEmpty()) { call.respond(HttpStatusCode.BadRequest, ApiResult(false, "No conversation")); return@post }
            val tmp = java.io.File.createTempFile("upload", ".part", ctx.cacheDir)
            val ok = withContext(Dispatchers.IO) {
                call.receiveStream().use { i ->
                    tmp.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = i.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > Messages.MAX_FILE) return@withContext false
                            o.write(buf, 0, n)
                        }
                        true
                    }
                }
            }
            if (!ok) { tmp.delete(); call.respond(HttpStatusCode.PayloadTooLarge, ApiResult(false, "Too big: 40 MB at most")); return@post }
            messages().sendUpload(k, tmp, name, mime, kind, q["ms"]?.toLongOrNull() ?: 0L)
            call.respond(ApiResult(true))
        }
        post("/api/chats/group") {
            call.device() ?: return@post
            val r = runCatching { call.receive<ChatNewGroup>() }.getOrNull()
            val known = peers.dto().map { it.name }.toSet()
            val members = r?.members.orEmpty().filter { it in known }
            if (members.isEmpty()) { call.respond(HttpStatusCode.BadRequest, ApiResult(false, "Pick phones for it")); return@post }
            call.respond(ChatKey(messages().createGroup(r?.name.orEmpty(), members)))
        }

        // ---- Calls from a laptop's page: it rings there, answers, calls, and takes the call
        // with its own microphone, camera and speakers (server/Calls.kt, "a laptop's page").
        get("/api/call") {
            call.device() ?: return@get
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(PageCallDto(calls().forPage(), peers.dto().map { it.name }, (ctx.applicationContext as dev.periy.bridge.BridgeApp).container.callLog.calls.value))
        }
        post("/api/call/start") {
            call.device() ?: return@post
            val r = runCatching { call.receive<PageCallStart>() }.getOrNull()
            if (r == null || (r.name != TEST_CALL && peers.find(r.name) == null)) {
                call.respond(HttpStatusCode.BadRequest, ApiResult(false, "No such phone"))
                return@post
            }
            if (r.name == TEST_CALL) calls().testCall(r.video, camera = false) else calls().call(r.name, r.video, camera = false)
            call.respond(ApiResult(true))
        }
        // The page's list of recent calls was looked at: missed calls are not new any more.
        post("/api/call/seen") {
            call.device() ?: return@post
            (ctx.applicationContext as dev.periy.bridge.BridgeApp).container.callLog.seen()
            call.respond(ApiResult(true))
        }
        post("/api/call/answer") {
            call.device() ?: return@post
            calls().answer(false)
            call.respond(ApiResult(true))
        }
        post("/api/call/end") {
            call.device() ?: return@post
            calls().hangUp()
            call.respond(ApiResult(true))
        }
        post("/api/call/join") {
            val me = call.device() ?: return@post
            val r = runCatching { call.receive<PageCallSdp>() }.getOrNull()
            if (r == null || r.sdp.length > 64_000) {
                call.respond(HttpStatusCode.BadRequest, ApiResult(false, "Bad offer"))
                return@post
            }
            // Through the laptop helper (it hands its streams to this phone on the loopback): its relay is offered too.
            val raw = call.request.local.remoteAddress
            val relay = raw.startsWith("127.") || raw == "::1" || raw.startsWith("0:0:0:0:0:0:0:1")
            runCatching { calls().pageJoin(r.sdp, displayName(me), me.id, relay, r.relayOnly) }
                .onSuccess { call.respond(PageCallSdp(it)) }
                .onFailure { call.respond(HttpStatusCode.Conflict, ApiResult(false, it.message ?: "Could not join the call")) }
        }
        // The laptop helper's relay for a call on its page: this connection, once answered, carries
        // WebRTC over TCP, each packet with its length before it (RFC 4571), and hands each one to
        // the page's connection here as UDP (Calls.pageUdp), its answers back the same way: WebRTC
        // here sees one more way to the laptop, and needs nothing of TCP.
        get("/api/call/pipe") {
            call.device() ?: return@get
            val to = calls().pageUdp()
            if (to == null) { call.respond(HttpStatusCode.NotFound, ApiResult(false, "No call on a laptop")); return@get }
            call.respond(object : io.ktor.http.content.OutgoingContent.ProtocolUpgrade() {
                override val headers: io.ktor.http.Headers = io.ktor.http.Headers.build {
                    append(HttpHeaders.Upgrade, "l87-pipe")
                    append(HttpHeaders.Connection, "Upgrade")
                }
                override suspend fun upgrade(
                    input: io.ktor.utils.io.ByteReadChannel,
                    output: io.ktor.utils.io.ByteWriteChannel,
                    engineContext: kotlin.coroutines.CoroutineContext,
                    userContext: kotlin.coroutines.CoroutineContext,
                ): kotlinx.coroutines.Job = kotlinx.coroutines.CoroutineScope(engineContext + Dispatchers.IO).launch {
                    val udp = java.net.DatagramSocket()
                    try {
                        udp.connect(to)
                        // From the laptop: each packet, its length first, out as one datagram.
                        val up = launch {
                            val len = ByteArray(2)
                            val buf = ByteArray(65_536)
                            try {
                                while (true) {
                                    input.readFully(len, 0, 2)
                                    val n = ((len[0].toInt() and 0xFF) shl 8) or (len[1].toInt() and 0xFF)
                                    input.readFully(buf, 0, n)
                                    udp.send(java.net.DatagramPacket(buf, n))
                                }
                            } catch (_: Throwable) {
                            } finally {
                                // The laptop's side closed: the other way stops too.
                                runCatching { udp.close() }
                            }
                        }
                        // To the laptop: each datagram, its length first.
                        val buf = ByteArray(65_536 + 2)
                        val pkt = java.net.DatagramPacket(buf, 2, 65_536)
                        while (up.isActive) {
                            pkt.setData(buf, 2, 65_536)
                            udp.receive(pkt)
                            val n = pkt.length
                            buf[0] = (n shr 8).toByte(); buf[1] = n.toByte()
                            output.writeFully(buf, 0, n + 2)
                            output.flush()
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { udp.close() }
                        runCatching { output.flushAndClose() }
                    }
                }
            })
        }
        post("/api/call/media") {
            call.device() ?: return@post
            val r = runCatching { call.receive<PageCallMedia>() }.getOrNull() ?: PageCallMedia()
            calls().pageMedia(r.muted, r.camera)
            call.respond(ApiResult(true))
        }
        post("/api/call/leave") {
            call.device() ?: return@post
            calls().pageLeave()
            call.respond(ApiResult(true))
        }
        // Where a linked phone and its laptops are, sealed with the two phones' key (server/Where.kt).
        post("/api/peers/where") {
            val me = call.device() ?: return@post
            val from = peers.byDevice(me.id)?.name
            val w = runCatching { call.receive<CallWire>() }.getOrNull()
            if (from == null || w == null || w.c.length > 512_000) { call.respond(HttpStatusCode.BadRequest, ApiResult(false, "Bad position")); return@post }
            if (where().receive(from, me.id, w)) call.respond(ApiResult(true))
            else call.respond(HttpStatusCode.Forbidden, ApiResult(false, "That does not open here"))
        }
        post("/api/peers/msg/read") {
            val me = call.device() ?: return@post
            val from = peers.byDevice(me.id)?.name
            val r = runCatching { call.receive<MsgRead>() }.getOrNull()
            if (from == null || r == null) {
                call.respond(HttpStatusCode.BadRequest, ApiResult(false, "Bad receipt"))
                return@post
            }
            (ctx.applicationContext as dev.periy.bridge.BridgeApp).container.messages.readBy(from, r.upTo)
            call.respond(ApiResult(true))
        }
        post("/api/peers/clip") {
            val me = call.device() ?: return@post
            val c = runCatching { call.receive<PeerClip>() }.getOrNull()
            if (c == null || c.text.isEmpty() || c.text.length > ClipboardStore.MAX_CHARS) {
                call.respond(HttpStatusCode.BadRequest, ApiResult(false, "Bad copy"))
                return@post
            }
            val from = peers.byDevice(me.id)?.name ?: me.name
            if (peers.takeClip(from, c.at) { clipboard.set(c.text, c.at) }) mirrorToPhone()
            call.respond(ApiResult(true))
        }
        post("/api/peers/clip/blob") {
            val me = call.device() ?: return@post
            val declared = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: -1
            if (declared > ClipboardStore.MAX_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiResult(false, "Too large for the clipboard"))
                return@post
            }
            val at = call.request.queryParameters["at"]?.toLongOrNull() ?: 0
            val name = call.request.queryParameters["name"].orEmpty()
            val mime = call.request.header(HttpHeaders.ContentType)?.substringBefore(';')?.trim().orEmpty()
            val from = peers.byDevice(me.id)?.name ?: me.name
            val channel = call.receiveChannel()
            val took = withContext(Dispatchers.IO) {
                peers.takeClip(from, at) { clipboard.setBlob(name, mime, channel.toInputStream(), at) != null }
            }
            if (took) mirrorToPhone()
            call.respond(ApiResult(true))
        }

        get("/api/peers") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(peers.dto())
        }
        // A linked phone's files for the page, through this phone: "?peer=<name>" and the rest as on /api/fs.
        get("/api/peer/fs") { proxyToPeer(call, "/api/fs") }
        get("/api/peer/fs/{what}") {
            val what = call.parameters["what"].orEmpty()
            if (what !in PEER_FS) {
                call.respond(HttpStatusCode.NotFound, ApiResult(false, "No such thing"))
                return@get
            }
            proxyToPeer(call, "/api/fs/$what")
        }
    }

    /**
     * A request for a linked phone's files, passed on to it, and its answer streamed straight
     * back: status, type, length and byte ranges as the other phone gave them, nothing kept.
     */
    private suspend fun proxyToPeer(call: ApplicationCall, path: String) {
        val peer = peers.find(call.request.queryParameters["peer"].orEmpty())
        if (peer == null) {
            call.respond(HttpStatusCode.NotFound, ApiResult(false, "That phone is not linked to this one"))
            return
        }
        val query = call.request.queryParameters.entries()
            .filter { it.key != "peer" }
            .flatMap { e -> e.value.map { e.key to it } }
            .formUrlEncode()
        val conn = runCatching {
            withContext(Dispatchers.IO) {
                peers.openGet(peer, if (query.isEmpty()) path else "$path?$query", call.request.header(HttpHeaders.Range))
                    .also { it.responseCode }
            }
        }.getOrElse {
            call.respond(HttpStatusCode.BadGateway, ApiResult(false, "Could not reach ${peer.name}"))
            return
        }
        val code = conn.responseCode
        val passed = io.ktor.http.Headers.build {
            PASSED_HEADERS.forEach { h -> conn.getHeaderField(h)?.let { append(h, it) } }
        }
        call.respond(object : OutgoingContent.WriteChannelContent() {
            override val status = HttpStatusCode.fromValue(code)
            override val contentType = conn.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
            override val contentLength = conn.getHeaderField(HttpHeaders.ContentLength)?.toLongOrNull()
            override val headers = passed
            override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) {
                try {
                    withContext(Dispatchers.IO) {
                        (if (code < 400) conn.inputStream else conn.errorStream)?.use { ins ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                channel.writeFully(buf, 0, n)
                                Monitor.addOut(n)
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
        })
    }

    // ------------------------------------------------------------------ straight through

    /** See Pipes: a laptop's file passed on to another computer, here or on a linked phone, as it arrives. */
    private fun io.ktor.server.routing.Route.pipeRoutes() {
        // Where this computer can send besides this phone: computers open here, linked phones,
        // and the computers open on those. "?local=1" is a linked phone asking for this one's own.
        get("/api/targets") {
            val me = call.device() ?: return@get
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val local = localTargets(except = me)
            // The laptops already here by name (this page's own among them): one open on a linked
            // phone too is not offered again through it, and this page's own laptop never.
            val all = devices.devices.value
            val known = (local.mapNotNull { t -> devices.get(t.id.removePrefix("dev:"))?.let { machineOf(it, all) } } + listOfNotNull(machineOf(me, all))).toSet()
            val remote = if (call.request.queryParameters["local"] == "1") emptyList() else coroutineScope {
                peers.peers.value.map { p ->
                    async {
                        listOf(TargetDto("peer:${p.name}/phone", p.name, "phone")) +
                            peers.remoteTargets(p).filter { t -> t.name !in known }.map { t -> t.copy(id = "peer:${p.name}/${t.id}", via = p.name) }
                    }
                }.awaitAll().flatten()
            }
            // Who this page's clipboard reaches: the computers live here, and the linked phones while
            // the phone passes copies on to them.
            val clipWith = clipboardReach(
                devices.devices.value, devices.live.value, me,
                if (peers.sharesClipboard) peers.peers.value.map { it.name } else emptyList(),
            )
            call.respond(TargetsDto(config.deviceName, local + remote, clipWith))
        }

        post("/api/pipe") {
            val me = call.device() ?: return@post
            val o = runCatching { call.receive<PipeOffer>() }.getOrNull()
            if (o == null || o.size < 0 || o.name.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiResult(false, "Bad offer"))
                return@post
            }
            val from = o.from.ifBlank { displayName(me) }
            val sink: Pipes.Sink = when {
                o.to == "phone" -> {
                    if (!storage.hasDestination()) {
                        call.respond(HttpStatusCode.Conflict, ApiResult(false, "${config.deviceName} has no folder for received files yet"))
                        return@post
                    }
                    Pipes.Sink.Store
                }
                o.to.startsWith("dev:") -> {
                    val id = o.to.removePrefix("dev:")
                    if ((devices.live.value[id] ?: 0) <= 0) {
                        call.respond(HttpStatusCode.NotFound, ApiResult(false, "That computer does not have the page open right now"))
                        return@post
                    }
                    Pipes.Sink.Device(id)
                }
                o.to.startsWith("peer:") -> {
                    val rest = o.to.removePrefix("peer:")
                    val peer = peers.find(rest.substringBefore('/'))
                    if (peer == null) {
                        call.respond(HttpStatusCode.NotFound, ApiResult(false, "That phone is not linked to this one"))
                        return@post
                    }
                    val remote = withContext(Dispatchers.IO) {
                        peers.offerPipe(peer, o.copy(to = rest.substringAfter('/', "phone"), from = from))
                    }.getOrElse {
                        call.respond(HttpStatusCode.BadGateway, ApiResult(false, it.message ?: "Could not reach ${peer.name}"))
                        return@post
                    }
                    Pipes.Sink.Relay(peer, remote)
                }
                else -> {
                    call.respond(HttpStatusCode.BadRequest, ApiResult(false, "Send it where?"))
                    return@post
                }
            }
            val p = Pipes.open(sanitizeFilename(o.name), o.size, o.mime.ifBlank { "application/octet-stream" }, from, sink, me.id, peers.byDevice(me.id))
            if (sink is Pipes.Sink.Device) EventBus.emitTo(sink.id, "incoming", json.encodeToString(p.incoming()))
            call.respond(PipeDto(p.id))
        }

        // The sender waits on this until the other computer has taken it, or said no.
        get("/api/pipe/{id}") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val p = Pipes.get(call.parameters["id"].orEmpty())
            val sink = p?.sink
            val state = when {
                p == null -> "gone"
                sink is Pipes.Sink.Relay -> withContext(Dispatchers.IO) { peers.pipeState(sink.peer, sink.remote) } ?: "waiting"
                else -> p.state()
            }
            call.respond(PipeStateDto(state))
        }

        post("/api/pipe/{id}/decline") {
            val me = call.device()
            val p = Pipes.get(call.parameters["id"].orEmpty())
            if (p != null && (p.sink as? Pipes.Sink.Device)?.id == me?.id) {
                p.answer.complete(false)
                p.chunks.close()
            }
            call.respond(ApiResult(true))
        }

        // The receiving computer says yes, and that it will take it browser to browser if it can.
        post("/api/pipe/{id}/accept") {
            val me = call.device()
            val p = Pipes.get(call.parameters["id"].orEmpty())
            if (p == null || (p.sink as? Pipes.Sink.Device)?.id != me?.id) {
                call.respond(HttpStatusCode.NotFound, ApiResult(false, "Nothing is waiting for this computer"))
                return@post
            }
            p.direct = true
            p.answer.complete(true)
            call.respond(PipeStateDto(p.state()))
        }

        // The introductions for a direct send: offers and network candidates, passed between the
        // two pages. From the sender toward the receiver (on through a linked phone when it is
        // there), or back from the receiver toward the sender.
        post("/api/pipe/{id}/signal") {
            val me = call.device() ?: return@post
            val p = Pipes.get(call.parameters["id"].orEmpty())
            val msg = runCatching { json.parseToJsonElement(call.receiveText().take(SIGNAL_MAX)).toString() }.getOrNull()
            if (p == null || msg == null) {
                call.respond(HttpStatusCode.NotFound, ApiResult(false, "No such send"))
                return@post
            }
            val sink = p.sink
            val fromReceiver = sink is Pipes.Sink.Device && sink.id == me.id
            withContext(Dispatchers.IO) {
                if (fromReceiver) signalSender(p, msg)
                else when (sink) {
                    is Pipes.Sink.Device -> EventBus.emitTo(sink.id, "rtc", """{"id":"${p.id}","msg":$msg}""")
                    is Pipes.Sink.Relay -> peers.signal(sink.peer, sink.remote, msg)
                    Pipes.Sink.Store -> {}
                }
            }
            call.respond(ApiResult(true))
        }
        // A linked phone passing a receiver's introduction back, toward the computer that sent here.
        post("/api/pipe/back") {
            val me = call.device() ?: return@post
            val peer = peers.byDevice(me.id)
            val body = runCatching { json.parseToJsonElement(call.receiveText().take(SIGNAL_MAX)).jsonObject }.getOrNull()
            val remote = body?.get("remote")?.jsonPrimitive?.content.orEmpty()
            val p = if (peer != null) Pipes.byRelay(peer, remote) else null
            val msg = body?.get("msg")?.toString()
            if (p != null && msg != null) withContext(Dispatchers.IO) { signalSender(p, msg) }
            call.respond(ApiResult(p != null))
        }
        // A direct send is over: the pipe, here and on a linked phone, can go.
        post("/api/pipe/{id}/close") {
            val p = Pipes.get(call.parameters["id"].orEmpty())
            if (p != null && !p.sending && !p.receiving) {
                Pipes.close(p)
                (p.sink as? Pipes.Sink.Relay)?.let { r -> withContext(Dispatchers.IO) { peers.closePipe(r.peer, r.remote) } }
            }
            call.respond(ApiResult(true))
        }

        // The receiving computer's download. Asking for it is saying yes.
        get("/api/pipe/{id}/data") {
            val me = call.device()
            val p = Pipes.get(call.parameters["id"].orEmpty())
            if (p == null || (p.sink as? Pipes.Sink.Device)?.id != me?.id) {
                call.respond(HttpStatusCode.NotFound, ApiResult(false, "Nothing is waiting for this computer"))
                return@get
            }
            // Taking it is saying yes; after a yes to a direct send, this is its way through the phone instead.
            if (!p.answer.complete(true) && (p.state() != "direct" || p.receiving)) {
                call.respond(HttpStatusCode.Conflict, ApiResult(false, "That has been answered already"))
                return@get
            }
            p.receiving = true
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, p.name).toString(),
            )
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respond(object : OutgoingContent.WriteChannelContent() {
                // Always saved, never shown: whatever the file is, it cannot run as this page.
                override val contentType = ContentType.Application.OctetStream
                override val contentLength = p.size
                override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) {
                    var n = 0L
                    try {
                        while (n < p.size) {
                            val piece = withTimeoutOrNull(Pipes.IDLE_MS) { p.chunks.receiveCatching() }
                                ?: error("The sender stopped")
                            val b = piece.getOrNull() ?: break
                            channel.writeFully(b, 0, b.size)
                            n += b.size
                            Monitor.addOut(b.size)
                        }
                        channel.flush()
                    } finally {
                        p.delivered.complete(n == p.size)
                        if (n != p.size) p.chunks.close(java.io.IOException("The computer stopped taking it"))
                    }
                }
            })
        }

        // The sender's upload: into the receiving computer's download, on to a linked phone, or
        // into this phone's folder. It answers once the file has arrived wherever it was going.
        post("/api/pipe/{id}/data") {
            val p = Pipes.get(call.parameters["id"].orEmpty())
            if (p == null || p.sending) {
                call.respond(HttpStatusCode.Gone, ApiResult(false, "That send has closed; start it again"))
                return@post
            }
            p.sending = true
            val tid = "pipe-" + p.id
            Transfers.begin(tid, p.name + "  →  " + sinkName(p.sink), Direction.OUTBOUND, p.size)
            var ok = false
            try {
                when (val sink = p.sink) {
                    is Pipes.Sink.Relay -> {
                        val (code, msg) = withContext(Dispatchers.IO) {
                            peers.pushPipe(sink.peer, sink.remote, call.receiveChannel().toInputStream(), p.size) { Transfers.progress(tid, it) }
                        }
                        ok = code in 200..299
                        call.respond(HttpStatusCode.fromValue(code), ApiResult(ok, msg))
                    }
                    Pipes.Sink.Store -> {
                        val entry = withContext(Dispatchers.IO) { storeFromPipe(p, call.receiveChannel().toInputStream(), tid) }
                        ok = true
                        call.respond(ApiResult(true, entry.name))
                    }
                    is Pipes.Sink.Device -> {
                        val yes = withTimeoutOrNull(Pipes.ANSWER_MS) { p.answer.await() } ?: false
                        if (!yes) {
                            call.respond(HttpStatusCode.Gone, ApiResult(false, "They did not take it"))
                            return@post
                        }
                        val input = call.receiveChannel().toInputStream()
                        withContext(Dispatchers.IO) {
                            var sent = 0L
                            while (sent < p.size) {
                                val b = ByteArray(minOf(Pipes.PIECE.toLong(), p.size - sent).toInt())
                                var got = 0
                                while (got < b.size) {
                                    val n = input.read(b, got, b.size - got)
                                    if (n < 0) error("The upload stopped part way")
                                    got += n
                                }
                                p.chunks.send(b)
                                sent += got
                                Monitor.addIn(got)
                                Transfers.progress(tid, sent)
                            }
                        }
                        p.chunks.close()
                        ok = p.delivered.await()
                        call.respond(
                            if (ok) HttpStatusCode.OK else HttpStatusCode.BadGateway,
                            ApiResult(ok, if (ok) null else "The other computer stopped taking it"),
                        )
                    }
                }
            } finally {
                Transfers.finish(tid, ok)
                Pipes.close(p)
            }
        }
    }

    /** An introduction from a pipe's receiver, toward whoever sent it: a computer here, or a linked phone. */
    private fun signalSender(p: Pipes.Pipe, msg: String) {
        val back = p.senderPeer
        if (back != null) peers.signalBack(back, p.id, msg)
        else EventBus.emitTo(p.senderId, "rtc", """{"id":"${p.id}","msg":$msg}""")
    }

    /** A pipe's file into this phone's folder for received files. */
    private fun storeFromPipe(p: Pipes.Pipe, input: java.io.InputStream, tid: String): FileEntry {
        val slot = storage.newSlot(p.id)
        try {
            var got = 0L
            slot.writer(0).use { w ->
                val buf = ByteArray(Pipes.PIECE)
                while (got < p.size) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), p.size - got).toInt())
                    if (n < 0) error("The upload stopped part way")
                    w.write(buf, 0, n)
                    got += n
                    Monitor.addIn(n)
                    Transfers.progress(tid, got)
                }
                w.sync()
            }
            return slot.finish(p.name, p.mime, Origin.PC, emptyList()).also { index.add(it) }
        } catch (t: Throwable) {
            slot.discard()
            throw t
        }
    }

    /** A file arriving whole from a computer, [size] bytes or until it ends when not known, into the folder for received files. */
    private fun storeStream(id: String, name: String, mime: String, input: java.io.InputStream, size: Long, tid: String): FileEntry {
        val slot = storage.newSlot(id)
        try {
            var got = 0L
            slot.writer(0).use { w ->
                val buf = ByteArray(256 * 1024)
                while (size < 0 || got < size) {
                    val n = input.read(buf, 0, if (size < 0) buf.size else minOf(buf.size.toLong(), size - got).toInt())
                    if (n < 0) { if (size < 0) break else error("The file stopped part way") }
                    w.write(buf, 0, n)
                    got += n
                    Monitor.addIn(n)
                    Transfers.progress(tid, got)
                }
                w.sync()
            }
            return slot.finish(name, mime, Origin.PC, emptyList()).also { index.add(it) }
        } catch (t: Throwable) {
            slot.discard()
            throw t
        }
    }

    private fun sinkName(s: Pipes.Sink): String = when (s) {
        is Pipes.Sink.Device -> devices.get(s.id)?.let(::displayName) ?: "a computer"
        Pipes.Sink.Store -> config.deviceName
        is Pipes.Sink.Relay -> s.peer.name
    }

    /**
     * Computers with the page open here right now (not their helpers, not phones), by name. Not
     * [except]'s own machine (another browser on the same laptop is not somewhere else to send),
     * and not a browser on this phone itself: the phone is a place to send already.
     */
    private fun localTargets(except: PairedDevice): List<TargetDto> {
        val live = devices.live.value
        val all = devices.devices.value
        val own = dev.periy.bridge.net.NetInfo.addresses().map { plainIp(it.host) }.toSet()
        return all
            .filter { (live[it.id] ?: 0) > 0 && !it.name.startsWith(PHONE_PREFIX) && !it.name.startsWith(HELPER_PREFIX) }
            .filterNot { sameMachine(it, except, all) }
            .filterNot { it.name.endsWith(" on Android") && (it.lastIp.startsWith("127.") || it.lastIp == "::1" || plainIp(it.lastIp) in own) }
            // A laptop with the page open in two browsers is one place to send: the one used last.
            .sortedByDescending { it.lastSeenAt }
            .distinctBy { machineOf(it, all) ?: it.id }
            .map { TargetDto("dev:${it.id}", displayName(it), "computer") }
    }

    /**
     * A computer as a person knows it: a page by its machine's name when that machine's helper
     * is paired from the same address, else by its browser ("Edge on Windows").
     */
    private fun displayName(d: PairedDevice): String = shownName(d, devices.devices.value)

    private fun calls() = (ctx.applicationContext as dev.periy.bridge.BridgeApp).container.calls
    private fun messages() = (ctx.applicationContext as dev.periy.bridge.BridgeApp).container.messages
    private fun where() = (ctx.applicationContext as dev.periy.bridge.BridgeApp).container.where

    /** A message as one line, for the list of conversations. */
    private fun chatLine(x: ChatMsg): String = when (x.kind) {
        "image" -> if (x.text.isNotEmpty()) "Photo: " + x.text else "Photo"
        "voice" -> "Voice note"
        "file" -> x.name.ifEmpty { "File" }
        else -> x.text
    }

    /** Wi-Fi Direct, being tried as a faster way to host the direct link. Debug builds only. */
    private val p2p by lazy { dev.periy.bridge.net.P2pLink(ctx) }

    private fun io.ktor.server.routing.Route.p2pTrial() {
        if (!dev.periy.bridge.BuildConfig.DEBUG) return
        post("/api/direct/p2p") {
            val band = call.request.queryParameters["band"]?.toIntOrNull() ?: 0
            val freq = call.request.queryParameters["freq"]?.toIntOrNull() ?: 0
            val (info, err) = runCatching { p2p.start(band, freq) }.getOrElse { null to (it.message ?: "failed") }
            call.respond(if (info != null) ApiResult(true, json.encodeToString(info)) else ApiResult(false, err))
        }
        post("/api/direct/p2p/stop") {
            p2p.stop()
            call.respond(ApiResult(true))
        }
    }

    /** Makes the phone's own clipboard hold what the shared one does: text, a picture, a file, or nothing. */
    private fun mirrorToPhone() {
        val m = clipboard.meta.value
        val f = clipboard.blob()
        when {
            f != null -> SystemClipboard.writeFile(ctx, f, m.name, m.mime)
            m.kind == "text" -> SystemClipboard.write(ctx, m.text)
            else -> SystemClipboard.clear(ctx)
        }
    }

    /** The phone's address a request came in on, and the kind of link that is: the socket's own end says. */
    /** The caller's address, with an IPv4 client on the dual-stack socket shown as plain IPv4. */
    private fun ApplicationCall.remoteIp(): String =
        nearTunnel()?.remote?.takeIf { it.isNotEmpty() } ?: request.origin.remoteAddress.removePrefix("::ffff:").substringBefore('%')

    /**
     * True unless the caller came in over the internet. IPv4 cannot (carrier NAT); a global IPv6
     * is local only inside the /64 of one of the phone's own local links: the hotspot or USB
     * shares the mobile /64, but a laptop there has its address on that link.
     */
    private fun ApplicationCall.fromLocalNetwork(): Boolean {
        val ip = remoteIp()
        if (':' !in ip) return true
        val addr = runCatching { java.net.InetAddress.getByName(ip) }.getOrNull() ?: return false
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || (addr.address[0].toInt() and 0xfe) == 0xfc) return true
        val prefix = addr.address.copyOf(8)
        return dev.periy.bridge.net.NetInfo.addresses().any { a ->
            a.isIpv6 && a.kind != dev.periy.bridge.net.LinkKind.CELLULAR &&
                runCatching { java.net.InetAddress.getByName(a.host).address.copyOf(8).contentEquals(prefix) }.getOrDefault(false)
        }
    }

    /** The session cookie through the website: Secure, and for NAME and lan.NAME alike. */
    private fun siteCookie(deviceId: String, name: String) = Cookie(
        name = SESSION_COOKIE,
        value = Session.issue(config.sessionKey(), config.sessionTtlMs, deviceId),
        path = "/",
        domain = name,
        httpOnly = true,
        secure = true,
        maxAge = (config.sessionTtlMs / 1000).toInt(),
        extensions = mapOf("SameSite" to "Strict"),
    )

    /** How this request reached the phone: the link, what the website or tunnel ran over, IPv4 or IPv6. */
    private fun ApplicationCall.way(deviceId: String?): Way {
        val all = dev.periy.bridge.net.NetInfo.addresses()
        if (viaSite()) {
            val who = clientIp()
            return Way("website", over = localOver(who, all, site?.destFor(request.origin.remotePort).orEmpty()), ip = if (':' in who) "IPv6" else "IPv4")
        }
        val here = localAddr()
        if (viaTunnel()) {
            val far = tunnelFar(deviceId)
            val udp = far?.endsWith("(UDP)") == true
            return Way("tunnel", over = if (udp) "udp" else "tcp", ip = if (udp || far == null || ':' !in far) "IPv4" else "IPv6")
        }
        // adb's port forward arrives from the phone's own loopback.
        val from = remoteIp()
        if (from == "127.0.0.1" || from == "::1") return Way("adb")
        val via = when (all.firstOrNull { it.host == here }?.kind) {
            dev.periy.bridge.net.LinkKind.USB -> "usb"
            dev.periy.bridge.net.LinkKind.HOTSPOT -> "hotspot"
            dev.periy.bridge.net.LinkKind.WIFI -> "wifi"
            dev.periy.bridge.net.LinkKind.DIRECT -> "direct"
            else -> "other"
        }
        return Way(via, ip = if (':' in here) "IPv6" else "IPv4")
    }

    /**
     * Where a browser that came through the website is, judged by the phone's address it
     * connected to [dest]: in that address's own network (the same /64 for IPv6, the same /24 for
     * IPv4), it is on that link and nothing crossed the internet; otherwise it came over the
     * internet, even from a network the phone is also on (home Wi-Fi reaching the mobile-data
     * address goes out and back in). The hotspot and USB tethering hand out addresses from mobile
     * data's /64: a browser there is on the hotspot or the cable, whichever is on.
     */
    private fun localOver(client: String, all: List<dev.periy.bridge.net.Address>, dest: String): String {
        val c = runCatching { java.net.InetAddress.getByName(client).address }.getOrNull() ?: return "internet"
        val d = runCatching { java.net.InetAddress.getByName(dest).address }.getOrNull() ?: return "internet"
        val n = if (c.size == 16) 8 else 3
        if (d.size != c.size || !d.copyOf(n).contentEquals(c.copyOf(n))) return "internet"
        val match = all.firstOrNull { a ->
            runCatching { java.net.InetAddress.getByName(a.host).address }.getOrNull()?.contentEquals(d) == true
        } ?: return "internet"
        return when (match.kind) {
            dev.periy.bridge.net.LinkKind.USB -> "usb"
            dev.periy.bridge.net.LinkKind.HOTSPOT -> "hotspot"
            dev.periy.bridge.net.LinkKind.WIFI -> "wifi"
            dev.periy.bridge.net.LinkKind.DIRECT -> "hotspot"
            else -> {
                val hotspot = all.any { it.kind == dev.periy.bridge.net.LinkKind.HOTSPOT }
                val usb = all.any { it.kind == dev.periy.bridge.net.LinkKind.USB }
                when {
                    hotspot && !usb -> "hotspot"
                    usb && !hotspot -> "usb"
                    hotspot -> "tether"
                    else -> "internet"
                }
            }
        }
    }

    /**
     * For a browser that came through the website on one of the phone's own links: the phone's
     * plain address on that link, the old http://IP:PORT, which skips the certificate and the
     * door. [over] is the link, as [localOver] says; "wifi" also for a browser that came over the
     * internet from the phone's Wi-Fi (the page checks the Wi-Fi answers before it moves).
     * Over the internet: "".
     */
    private fun ApplicationCall.plainUrl(over: String): String {
        val all = dev.periy.bridge.net.NetInfo.addresses()
        val dest = site?.destFor(request.origin.remotePort).orEmpty()
        val kinds = when (over) {
            "usb" -> listOf(dev.periy.bridge.net.LinkKind.USB)
            "hotspot" -> listOf(dev.periy.bridge.net.LinkKind.HOTSPOT, dev.periy.bridge.net.LinkKind.DIRECT)
            "tether" -> listOf(dev.periy.bridge.net.LinkKind.HOTSPOT, dev.periy.bridge.net.LinkKind.USB)
            "wifi" -> listOf(dev.periy.bridge.net.LinkKind.WIFI)
            else -> return ""
        }
        // Came in on an IPv4 (lan.NAME's A record): that same address.
        val ip = if (dest.isNotEmpty() && ':' !in dest) dest
        else kinds.firstNotNullOfOrNull { k -> all.firstOrNull { !it.isIpv6 && it.kind == k }?.host } ?: return ""
        return "http://$ip:${config.port}"
    }

    private fun ApplicationCall.arrivedOn(
        all: List<dev.periy.bridge.net.Address> = dev.periy.bridge.net.NetInfo.addresses(),
    ): Pair<String, dev.periy.bridge.net.LinkKind> {
        val here = localAddr()
        return here to (all.firstOrNull { it.host == here }?.kind ?: dev.periy.bridge.net.LinkKind.OTHER)
    }

    private fun directDto(): DirectDto {
        if (config.laptopLink() == "hotspot" && direct.state.value !is dev.periy.bridge.net.DirectLink.State.On) return hotspotDto()
        return directLinkDto()
    }

    /**
     * The phone's ordinary hotspot, described like the direct link so a laptop helper joins
     * it the same way. The phone decides whether it is on; the app only sees whether its
     * interface is up.
     */
    private fun hotspotDto(): DirectDto {
        val (ssid, pass) = config.hotspot()
        val up = dev.periy.bridge.net.NetInfo.hotspotAddress()
        return when {
            ssid.isBlank() -> DirectDto("off", kind = "hotspot",
                message = "Add the hotspot's name and password in the phone's Settings")
            up == null -> DirectDto("off", kind = "hotspot", message = "Turn on the hotspot on the phone")
            else -> DirectDto(
                "on",
                dev.periy.bridge.net.DirectLink.Info(ssid, pass, up.host, config.port, "WPA2"),
                kind = "hotspot",
            )
        }
    }

    private fun directLinkDto(): DirectDto = when (val s = direct.state.value) {
        is dev.periy.bridge.net.DirectLink.State.On -> DirectDto("on", s.info, laptop = direct.forLaptop)
        is dev.periy.bridge.net.DirectLink.State.Failed -> DirectDto("failed", message = s.reason)
        dev.periy.bridge.net.DirectLink.State.Starting -> DirectDto("starting")
        dev.periy.bridge.net.DirectLink.State.Off -> DirectDto("off")
    }

    // ------------------------------------------------------------------ laptop control

    // ------------------------------------------------------------------ notifications

    private fun io.ktor.server.routing.Route.notificationRoutes() {
        get("/api/notifications") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(NotifList(Notifs.allowed(ctx), Notifs.list()))
        }
        get("/api/notifications/icon") {
            val bytes = Notifs.icon(ctx, call.request.queryParameters["pkg"].orEmpty())
            if (bytes == null) { call.respond(HttpStatusCode.NotFound); return@get }
            call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
            call.respondBytes(bytes, ContentType.Image.PNG)
        }
        post("/api/notifications/dismiss") {
            Notifs.dismiss(runCatching { call.receive<NotifKey>() }.getOrDefault(NotifKey()).key)
            call.respond(ApiResult(true))
        }
        post("/api/notifications/clear") {
            Notifs.dismissAll()
            call.respond(ApiResult(true))
        }
        post("/api/notifications/reply") {
            val r = runCatching { call.receive<NotifReply>() }.getOrDefault(NotifReply())
            val ok = r.text.isNotBlank() && Notifs.reply(ctx, r.key, r.text)
            call.respond(ApiResult(ok, if (ok) null else "This notification no longer takes a reply"))
        }
        post("/api/notifications/action") {
            val a = runCatching { call.receive<NotifAction>() }.getOrDefault(NotifAction())
            val ok = Notifs.press(a.key, a.index)
            call.respond(ApiResult(ok, if (ok) null else "That button is gone"))
        }
    }

    private fun io.ktor.server.routing.Route.controlRoutes() {
        // "Phone screen" on the page: the laptop helper opens a window with this phone's screen,
        // to watch and use with the laptop's mouse and keyboard (scrcpy, over adb).
        // "Use this phone as a second screen": opens the phone's second-screen view, which tells
        // the helper to start streaming once it is listening. Opening it from the background is
        // allowed because Localhost 8787 may draw over other apps (see ClipWatch).
        post("/api/display") {
            if (Control.connected.value.isEmpty()) {
                call.respond(ApiResult(false, "The laptop helper is not running"))
                return@post
            }
            runCatching {
                ctx.startActivity(
                    android.content.Intent().setClassName(ctx, "dev.periy.bridge.ui.SecondScreenActivity")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                call.respond(ApiResult(false, "Open it on the phone: Control, Second screen"))
                return@post
            }
            call.respond(ApiResult(true))
        }
        // The laptop's screen for the phone's screen view, from its helper: H.264 as ffmpeg writes it,
        // in one long upload, so it comes the same way as everything else from that laptop, the
        // tunnel included (server/DisplayFeed.kt). It lasts until the stream or the view ends.
        post("/api/display/stream") {
            // For another laptop's page (laptopRoutes): handed to its request, not the phone's view.
            val view = call.request.queryParameters["v"]?.let { DisplayFeed.views[it] }
            if (view != null) {
                val done = java.util.concurrent.CompletableFuture<Unit>()
                withContext(Dispatchers.IO) {
                    view.put(DisplayFeed.Feed(call.receiveStream(), done, call.request.queryParameters["s"].orEmpty(), true))
                    runCatching { done.get() }
                }
                call.respond(ApiResult(true))
                return@post
            }
            if (!DisplayFeed.open) {
                call.respond(HttpStatusCode.Conflict, ApiResult(false, "The screen view is not open on the phone"))
                return@post
            }
            val done = java.util.concurrent.CompletableFuture<Unit>()
            withContext(Dispatchers.IO) {
                val framed = call.request.headers[HttpHeaders.ContentType]?.startsWith("video/h264-frames") == true
                DisplayFeed.feeds.put(DisplayFeed.Feed(call.receiveStream(), done, call.request.queryParameters["s"].orEmpty(), framed))
                runCatching { done.get() }
            }
            call.respond(ApiResult(true))
        }
        // The laptop's sound with its screen, from its helper: AAC as ADTS frames, played as they come
        // while the screen view is open (server/DisplaySound.kt).
        post("/api/display/audio") {
            if (!DisplayFeed.open) {
                call.respond(HttpStatusCode.Conflict, ApiResult(false, "The screen view is not open on the phone"))
                return@post
            }
            withContext(Dispatchers.IO) { DisplaySound.play(call.receiveStream()) }
            call.respond(ApiResult(true))
        }
        // The laptop's files (server/LaptopFiles.kt): its helper's answer to "what is in this folder".
        post("/api/laptop/fs/answer") {
            val id = call.request.queryParameters["id"].orEmpty()
            val l = runCatching { call.receive<LaptopListing>() }.getOrNull()
            if (l != null) LaptopFiles.answer(id, l)
            call.respond(ApiResult(l != null))
        }
        // ...and a file it was asked for, saved here as one sent from a computer.
        post("/api/laptop/fs/file") {
            val id = call.request.queryParameters["id"].orEmpty()
            val name = call.request.queryParameters["name"].orEmpty().ifBlank { "file" }
            val size = call.request.queryParameters["size"]?.toLongOrNull() ?: -1L
            if (call.request.queryParameters["error"] != null) {
                LaptopFiles.saved(id, "!" + call.request.queryParameters["error"])
                call.respond(ApiResult(true))
                return@post
            }
            if (!storage.hasDestination()) {
                LaptopFiles.saved(id, "!This phone has no folder for received files yet")
                call.respond(HttpStatusCode.Conflict, ApiResult(false, "No folder for received files"))
                return@post
            }
            val mime = java.net.URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
            val tid = "laptop-" + id
            Transfers.begin(tid, name, Direction.INBOUND, size.coerceAtLeast(0))
            val r = runCatching {
                withContext(Dispatchers.IO) { storeStream(id, name, mime, call.receiveChannel().toInputStream(), size, tid) }
            }
            r.onSuccess { LaptopFiles.saved(id, it.name); Transfers.finish(tid, true) }
                .onFailure { LaptopFiles.saved(id, "!" + (it.message ?: "It stopped part way")); Transfers.finish(tid, false) }
            call.respond(ApiResult(r.isSuccess))
        }
        // A file this phone is sending to the laptop: the helper fetches it here.
        get("/api/laptop/fs/out/{id}") {
            val out = LaptopFiles.outgoing(call.parameters["id"].orEmpty())
            if (out == null) {
                call.respond(HttpStatusCode.NotFound, ApiResult(false, "Not on its way any more"))
                return@get
            }
            val input = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(out.uri) }.getOrNull() }
            if (input == null) {
                call.respond(HttpStatusCode.Gone, ApiResult(false, "The file could not be opened"))
                return@get
            }
            if (out.size > 0) call.response.header(HttpHeaders.ContentLength, out.size.toString())
            call.respondOutputStream(ContentType.Application.OctetStream) {
                input.use { i ->
                    val buf = ByteArray(256 * 1024)
                    while (true) { val n = i.read(buf); if (n < 0) break; write(buf, 0, n); Monitor.addOut(n, Lane.FILES) }
                }
            }
        }
        // The helper's word on it: the name it was saved as, or why not.
        post("/api/laptop/fs/put-done") {
            val id = call.request.queryParameters["id"].orEmpty()
            val err = call.request.queryParameters["error"]
            LaptopFiles.saved(id, if (err != null) "!$err" else call.request.queryParameters["name"].orEmpty())
            call.respond(ApiResult(true))
        }
        post("/api/mirror") {
            val mode = call.request.queryParameters["mode"] ?: "start"
            if (Control.connected.value.isEmpty()) {
                call.respond(ApiResult(false, "The laptop helper is not running"))
                return@post
            }
            EventBus.emit("mirror", mode)
            call.respond(ApiResult(true))
        }
        // The helper says what the laptop's volume is: on connecting, after each change from
        // the phone, and when it is changed on the laptop itself. The volume bar shows it.
        // The helper closing on purpose: not a laptop gone offline.
        post("/api/control/bye") {
            Control.byeAt = System.currentTimeMillis()
            call.respond(ApiResult(true))
        }
        post("/api/control/volume") {
            runCatching { call.receive<VolumeReport>() }.getOrNull()?.let { Control.reportVolume(it.level, it.muted) }
            call.respond(ApiResult(true))
        }
        // The laptop helper holds this open and turns each line into real input. Lines
        // that piled up while the socket was busy go out together in one flush, so a
        // slow moment costs one late batch rather than a growing delay.
        get("/api/control/stream") {
            val me = call.device()
            val name = me?.name ?: "Laptop"
            // The helper says every address its laptop has, so a page on that laptop is known as
            // it wherever it comes from (see machineOf), and is not offered itself as somewhere to send.
            val addrs = call.request.headers["Bridge-Addrs"]
            if (me != null && addrs != null && me.name.startsWith(HELPER_PREFIX)) {
                devices.setAddrs(me.id, addrs.split(',').map { it.trim() }.filter { it.isNotEmpty() && it.length <= 64 }.take(32).map(::plainIp).distinct())
            }
            // Helpers from this version on accept a slow keep-alive while the Control tab is
            // closed; older ones time out after ten seconds of silence, so they keep the fast one.
            val slowOk = call.request.headers["Bridge-Heartbeat"] == "slow"
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondBytesWriter(ContentType.Text.Plain) {
                val ch = Control.attach(name, me?.id)
                try {
                    writeStringUtf8("p\n")
                    flush()
                    while (true) {
                        val wait = if (Control.inUse || !slowOk) HEARTBEAT_CONTROL_MS else HEARTBEAT_IDLE_MS
                        val first = withTimeoutOrNull(wait) { ch.receive() } ?: "p"
                        writeStringUtf8(first)
                        writeStringUtf8("\n")
                        while (true) {
                            val next = ch.tryReceive().getOrNull() ?: break
                            writeStringUtf8(next)
                            writeStringUtf8("\n")
                        }
                        flush()
                    }
                } catch (_: Throwable) {
                    // The helper went away (laptop asleep, window closed). Normal.
                } finally {
                    Control.detach(ch)
                }
            }
        }
    }

    // ------------------------------------------------------------------ theme

    private fun io.ktor.server.routing.Route.themeRoutes() {
        // One shared setting: flipping it here changes the phone and every open page.
        post("/api/theme") {
            val body = runCatching { call.receive<ThemeRequest>() }.getOrDefault(ThemeRequest())
            config.setTheme(body.theme)
            call.respond(ApiResult(true))
        }
        post("/api/look") {
            val body = runCatching { call.receive<LookRequest>() }.getOrDefault(LookRequest())
            config.setLook(body.style, body.accent)
            call.respond(ApiResult(true))
        }
    }

    // ------------------------------------------------------------------ page + auth

    private fun io.ktor.server.routing.Route.page() {
        // The map of your phones and laptops (assets/map.html), for the app's map and the page's Map tab.
        get("/map.html") {
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondBytes(ctx.assets.open("map.html").use { it.readBytes() }, ContentType.Text.Html.withCharset(Charsets.UTF_8))
        }
        get("/") {
            // Kept by the browser and asked after each time: across the internet a visit costs one
            // round trip instead of the whole page again; a new app's page has a new tag.
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.response.header(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
            if (call.unchanged(pageTag)) return@get
            // Gzipped (lossless) for any browser that takes it: a quarter of the size, which is
            // what keeps the page from sitting blank for seconds across the internet.
            if (call.request.header(HttpHeaders.AcceptEncoding)?.contains("gzip") == true) {
                call.response.header(HttpHeaders.ContentEncoding, "gzip")
                call.respondBytes(pageGzip, ContentType.Text.Html.withCharset(Charsets.UTF_8))
            } else {
                call.respondBytes(ctx.assets.open("bridge.html").use { it.readBytes() }, ContentType.Text.Html.withCharset(Charsets.UTF_8))
            }
        }
        get("/favicon.ico") { call.respond(HttpStatusCode.NoContent) }
        // Lexend Deca (SIL OFL 1.1), the typeface of the page's player, as Namida is set in it.
        get("/fonts/{name}") {
            val name = call.parameters["name"].orEmpty()
            if (!FONT_FILE.matches(name)) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val bytes = withContext(Dispatchers.IO) { runCatching { ctx.assets.open("fonts/$name").use { it.readBytes() } }.getOrNull() }
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            call.respondBytes(bytes, ContentType.parse("font/woff2"))
        }
        // The laptop helpers, offered from the page itself so any paired laptop can get one:
        // Windows's, and one Python file for Linux and the Mac, named for each.
        for ((helper, asset) in listOf(
            "blazeit-pc.bat" to "blazeit-pc.bat",
            "blazeit-linux.py" to "blazeit-helper.py",
            "blazeit-mac.py" to "blazeit-helper.py",
        )) {
            get("/$helper") {
                var bytes = withContext(Dispatchers.IO) { ctx.assets.open(asset).use { it.readBytes() } }
                // Downloaded through the website by a signed-in browser: the website's name and a
                // one-time code go in, so the helper signs itself in when it starts (no PIN).
                val door = site
                if (call.viaSite() && door != null && call.device() != null) {
                    val name = door.state.value.name
                    val code = door.newCode()
                    val text = String(bytes)
                    bytes = (if (helper.endsWith(".bat")) text.replaceFirst(
                        "\$ErrorActionPreference = 'Stop'",
                        "\$ErrorActionPreference = 'Stop'\r\n\$env:L87_SITE = '$name'\r\n\$env:L87_CODE = '$code'",
                    ) else text.replaceFirst("SITE_SIGNIN = None", "SITE_SIGNIN = (\"$name\", \"$code\")")).toByteArray()
                }
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, helper).toString(),
                )
                call.respondBytes(bytes, ContentType.Application.OctetStream)
            }
        }
    }

    private fun io.ktor.server.routing.Route.auth() {
        get("/api/ping") {
            call.respond(PingDto(ok = true, paired = call.device() != null, device = config.deviceName, id = Session.phoneId(config.sessionKey())))
        }

        // What a paired device needs to come back from another network: its keys for the tunnel,
        // and where the phone is. Only here, on the phone's own networks (see the intercept).
        get("/api/tunnel") {
            val d = call.device()
            val door = remote
            if (d == null || door == null) {
                call.respond(HttpStatusCode.NotFound, ApiResult(false, "No tunnel"))
                return@get
            }
            call.respondText(door.forDevice(d.id), ContentType.Application.Json)
        }

        // A computer asks to be let in. The phone shows who is asking and a code.
        post("/api/pair") {
            // This phone asking itself (a link to its own address, or its own code): never.
            if (call.request.header(PHONE_ID_HEADER) == Session.phoneId(config.sessionKey())) {
                call.respond(HttpStatusCode.Conflict, ApiResult(false, "That is this phone"))
                return@post
            }
            val ip = call.clientIp()
            var name = describeUserAgent(call.request.header(HttpHeaders.UserAgent))
            if (call.viaSite()) {
                val why = site?.mayAsk(ip)
                if (why != null) {
                    call.respond(HttpStatusCode.TooManyRequests, ApiResult(false, why))
                    return@post
                }
                name += " (website)"
            }
            val req = pairing.request(name, ip)
            if (req == null) {
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ApiResult(false, "Too many connection requests right now. Try again in a minute."),
                )
                return@post
            }
            call.respond(PairStartDto(id = req.id, code = req.code, name = req.name))
        }

        // The computer polls for the answer. The request id is a random 128-bit value
        // only the requester knows, so only it can collect the cookie.
        get("/api/pair/{id}") {
            val req = pairing.status(call.parameters["id"].orEmpty())
            if (req == null) {
                call.respond(PairStatusDto("EXPIRED"))
                return@get
            }
            val deviceId = req.deviceId
            if (req.state == PairRequest.State.APPROVED && deviceId != null && call.viaSite() && site != null) {
                call.response.cookies.append(siteCookie(deviceId, site!!.state.value.name))
            } else if (req.state == PairRequest.State.APPROVED && deviceId != null) {
                call.response.cookies.append(
                    Cookie(
                        name = SESSION_COOKIE,
                        value = Session.issue(config.sessionKey(), config.sessionTtlMs, deviceId),
                        path = "/",
                        httpOnly = true,
                        // Secure through the website (HTTPS); not on the local network, served
                        // over plain HTTP, where a Secure cookie would never be sent back.
                        secure = call.viaSite(),
                        maxAge = (config.sessionTtlMs / 1000).toInt(),
                        extensions = mapOf("SameSite" to "Strict"),
                    )
                )
            }
            call.respond(PairStatusDto(req.state.name))
        }

        // How the page reached the phone, for its sign-in screen: through the website, a PIN.
        get("/api/site") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val door = site
            val via = call.viaSite()
            val name = door?.state?.value?.name.orEmpty()
            // A browser signed in through the website before the session covered lan.NAME too:
            // the same session again, for the whole name, so moving over keeps it signed in.
            val d = call.device()
            if (via && d != null && name.isNotEmpty()) call.response.cookies.append(siteCookie(d.id, name))
            val over = if (via) localOver(call.clientIp(), dev.periy.bridge.net.NetInfo.addresses(), door?.destFor(call.request.origin.remotePort).orEmpty()) else ""
            // Already on one of the phone's links (the hotspot counts as Wi-Fi to Android): not near, there.
            val near = via && door != null && over == "internet" && call.request.headers[HttpHeaders.Host]?.substringBefore(':') != door.lanHost && door.nearby(call.clientIp())
            call.respond(SiteDto(site = via, name = name, lan = door?.lanHost.orEmpty(), near = near, plain = if (via) call.plainUrl(if (near) "wifi" else over) else ""))
        }

        // Signed in through the website and on one of the phone's own links: a code the plain
        // address takes instead of asking the phone again. Once, within a minute.
        post("/api/site/handoff") {
            val d = call.device()
            if (!call.viaSite() || d == null) {
                call.respond(HttpStatusCode.Forbidden, ApiResult(false, "Only through the website."))
                return@post
            }
            val now = System.currentTimeMillis()
            handoffs.entries.removeIf { it.value.second < now }
            val code = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18).also { java.security.SecureRandom().nextBytes(it) })
            handoffs[code] = d.id to now + 60_000
            call.respond(HandoffDto(code))
        }

        // The plain address, opened by the website with a handoff code: the same computer, signed in.
        post("/api/site/handoff/use") {
            val code = runCatching { call.receive<HandoffDto>().code }.getOrNull().orEmpty()
            val (deviceId, until) = handoffs.remove(code) ?: (null to 0L)
            if (call.viaSite() || deviceId == null || until < System.currentTimeMillis() || devices.get(deviceId) == null) {
                call.respond(HttpStatusCode.Forbidden, ApiResult(false, "That code is used up or too old."))
                return@post
            }
            call.response.cookies.append(
                Cookie(
                    name = SESSION_COOKIE,
                    value = Session.issue(config.sessionKey(), config.sessionTtlMs, deviceId),
                    path = "/",
                    httpOnly = true,
                    maxAge = (config.sessionTtlMs / 1000).toInt(),
                    extensions = mapOf("SameSite" to "Strict"),
                )
            )
            call.respond(ApiResult(true))
        }

        // A helper downloaded through the website signs itself in with the one-time code baked into
        // it: a paired device like any other, and the tunnel's keys in the same answer.
        post("/api/site/enroll") {
            val door = site
            val code = runCatching { call.receive<SiteEnrollDto>().code }.getOrNull().orEmpty()
            if (door == null || !call.viaSite() || !door.useCode(code)) {
                call.respond(HttpStatusCode.Forbidden, ApiResult(false, "That sign-in code is used up or too old: sign in with the PIN."))
                return@post
            }
            val name = describeUserAgent(call.request.header(HttpHeaders.UserAgent))
            val device = devices.add(name, call.clientIp())
            call.response.cookies.append(siteCookie(device.id, door.state.value.name))
            val tunnel = remote?.forDevice(device.id) ?: "{}"
            call.respondText(tunnel, ContentType.Application.Json)
        }

        // Logging out also removes the computer from the phone's list, so the list only
        // ever shows computers that can actually still get in.
        post("/api/logout") {
            call.device()?.let { devices.remove(it.id) }
            call.response.cookies.append(Cookie(SESSION_COOKIE, "", path = "/", maxAge = 0))
            call.respond(ApiResult(true))
        }
    }

    // ------------------------------------------------------------------ api

    private fun io.ktor.server.routing.Route.api() {
        get("/api/state") {
            index.prune(storage::exists)
            call.respond(
                StateDto(
                    clipboard = clipboard.text,
                    files = index.entries,
                    deviceName = config.deviceName,
                    uploadStreams = config.uploadStreams(),
                    theme = config.theme(),
                    style = config.look().style,
                    accent = config.look().accent,
                    clipSync = config.clipSync(),
                    clip = clipboard.meta.value,
                )
            )
        }

        post("/api/clipboard") {
            val body = runCatching { call.receive<ClipboardRequest>() }.getOrDefault(ClipboardRequest())
            // The laptop helper's automatic copies are marked; with sync off they are ignored.
            if (call.request.header("Bridge-Auto") == "1" && !config.clipSync()) {
                call.respond(ApiResult(false, "Clipboard sync is off on the phone"))
                return@post
            }
            if (!clipboard.set(body.text)) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ApiResult(false, "Text exceeds ${ClipboardStore.MAX_CHARS} characters"),
                )
                return@post
            }
            // Mirror into the system clipboard so it is ready to paste on the phone.
            // Writing is always allowed; only reading is restricted on API 29+.
            mirrorToPhone()
            call.respond(ApiResult(true))
        }

        // What the shared clipboard holds: text, a picture, a file, or nothing.
        get("/api/clipboard") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(clipboard.meta.value)
        }

        // A picture or file copied on the laptop (pasted into the page, or copied in Windows
        // and sent by the helper). It lands on the phone's own clipboard too, ready to paste.
        post("/api/clipboard/blob") {
            if (call.request.header("Bridge-Auto") == "1" && !config.clipSync()) {
                call.respond(ApiResult(false, "Clipboard sync is off on the phone"))
                return@post
            }
            val declared = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: -1
            if (declared > ClipboardStore.MAX_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiResult(false, "Over ${ClipboardStore.MAX_BYTES / 1048576} MB; send it as a file instead"))
                return@post
            }
            val name = call.request.queryParameters["name"].orEmpty()
            val mime = call.request.header(HttpHeaders.ContentType)?.substringBefore(';')?.trim().orEmpty()
            val channel = call.receiveChannel()
            val meta = withContext(Dispatchers.IO) {
                clipboard.setBlob(name, mime, channel.toInputStream())
            }
            if (meta == null) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiResult(false, "Over ${ClipboardStore.MAX_BYTES / 1048576} MB; send it as a file instead"))
                return@post
            }
            mirrorToPhone()
            call.respond(meta)
        }

        // The picture or file itself, for the page's preview and download and the helper.
        get("/api/clipboard/blob") {
            val m = clipboard.meta.value
            val f = clipboard.blob()
            if (f == null) {
                call.respond(HttpStatusCode.NotFound, ApiResult(false, "The clipboard holds no picture or file"))
                return@get
            }
            val type = runCatching { ContentType.parse(m.mime) }.getOrDefault(ContentType.Application.OctetStream)
            // Pictures show inline; everything is sandboxed so no file can run as this page.
            val inline = m.kind == "image" && m.mime != "image/svg+xml"
            call.response.header("Content-Security-Policy", "sandbox")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            call.response.header(
                HttpHeaders.ContentDisposition,
                (if (inline) ContentDisposition.Inline else ContentDisposition.Attachment)
                    .withParameter(ContentDisposition.Parameters.FileName, m.name).toString(),
            )
            call.respond(FileRangeContent(call, f, type))
        }

        // Clear, from anywhere: the shared clipboard and the phone's own. The history stays.
        delete("/api/clipboard") {
            clipboard.set("")
            SystemClipboard.clear(ctx)
            call.respond(ApiResult(true))
        }

        // The history: recent items, newest first, like a keyboard's clipboard.
        get("/api/clipboard/history") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(clipboard.history.value)
        }
        get("/api/clipboard/history/{v}/blob") {
            val v = call.parameters["v"]?.toLongOrNull() ?: -1
            val m = clipboard.history.value.firstOrNull { it.v == v }
            val f = clipboard.historyBlob(v)
            if (m == null || f == null) {
                call.respond(HttpStatusCode.NotFound, ApiResult(false, "Not in the history"))
                return@get
            }
            call.response.header("Content-Security-Policy", "sandbox")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            call.response.header(
                HttpHeaders.ContentDisposition,
                (if (m.kind == "image" && m.mime != "image/svg+xml") ContentDisposition.Inline else ContentDisposition.Attachment)
                    .withParameter(ContentDisposition.Parameters.FileName, m.name).toString(),
            )
            call.respond(FileRangeContent(call, f, runCatching { ContentType.parse(m.mime) }.getOrDefault(ContentType.Application.OctetStream)))
        }
        // Puts an item from the history back on the clipboard, everywhere.
        post("/api/clipboard/history/{v}/use") {
            val m = clipboard.reuse(call.parameters["v"]?.toLongOrNull() ?: -1)
            if (m == null) {
                call.respond(HttpStatusCode.NotFound, ApiResult(false, "Not in the history"))
                return@post
            }
            mirrorToPhone()
            call.respond(m)
        }
        delete("/api/clipboard/history/{v}") {
            val v = call.parameters["v"]?.toLongOrNull() ?: -1
            val wasCurrent = clipboard.meta.value.v == v
            clipboard.forget(v)
            if (wasCurrent) SystemClipboard.clear(ctx)
            call.respond(ApiResult(true))
        }
        delete("/api/clipboard/history") {
            clipboard.forgetAll()
            SystemClipboard.clear(ctx)
            call.respond(ApiResult(true))
        }

        get("/api/files/{id}") {
            val id = call.parameters["id"].orEmpty()
            val entry = index.get(id)
            if (entry == null) {
                call.respond(HttpStatusCode.NotFound, ApiResult(false, "No such file"))
                return@get
            }
            val size = if (entry.size > 0) entry.size else storage.docSize(Uri.parse(entry.uri))
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, entry.name)
                    .toString(),
            )
            call.respond(
                DocumentContent(
                    scope = call,
                    resolver = ctx.contentResolver,
                    entry = entry,
                    length = size,
                    type = ContentType.parse(entry.mime.ifBlank { "application/octet-stream" }),
                )
            )
        }
        // There is deliberately no DELETE: shared files cannot be removed from the page or the
        // app. They go only when deleted on the phone itself, and the list drops them then.
    }

    // ------------------------------------------------------------------ sse

    private fun io.ktor.server.routing.Route.events() {
        sse("/events") {
            // The open event stream is what "live" means on the phone's device list: a
            // computer with this page open is here right now.
            val device = call.device()
            device?.let { devices.connected(it.id) }

            // Immediate snapshot so a reconnecting tab is correct before anything changes.
            send(data = json.encodeToString(index.entries), event = "files")
            send(data = clipboard.text, event = "clipboard")
            send(data = clipboard.metaJson(), event = "clip")
            send(data = clipboard.historyJson(), event = "cliphistory")
            send(data = config.theme(), event = "theme")
            send(data = config.look().json(), event = "look")
            // The phone's notifications too: any that came or went while this tab was away.
            send(data = Notifs.snapshotJson(ctx), event = "notifs")
            send(data = json.encodeToString(peers.dto()), event = "peers")
            // A file another computer is sending this one, asked for again after a reload.
            device?.let { d -> Pipes.waitingFor(d.id).forEach { send(data = json.encodeToString(it.incoming()), event = "incoming") } }

            val pump = CoroutineScope(coroutineContext).launch {
                EventBus.events.collect { if (it.to == null || it.to == device?.id) send(data = it.data, event = it.name) }
            }
            try {
                // Heartbeat. Without it the connection idles out and every browser tab
                // reconnects on its own schedule, which looks like flapping.
                while (isActive) {
                    kotlinx.coroutines.delay(HEARTBEAT_MS)
                    // Removed from the phone's list? Close the stream rather than keep
                    // feeding a computer that has just been locked out.
                    if (device != null && devices.get(device.id) == null) break
                    send(data = System.currentTimeMillis().toString(), event = "ping")
                }
            } finally {
                pump.cancel()
                device?.let { devices.disconnected(it.id) }
            }
        }
    }

    // ------------------------------------------------------------------ tus

    private fun io.ktor.server.routing.Route.tusRoutes() {
        options("/tus") { call.respondTusOptions() }
        options("/tus/{id}") { call.respondTusOptions() }

        // Plain tus Creation: one stream, standard semantics, works with any tus client.
        post("/tus") {
            call.response.header("Tus-Resumable", TUS_VERSION)
            val length = call.request.header("Upload-Length")?.toLongOrNull()
            if (length == null) {
                call.respond(HttpStatusCode.BadRequest, ApiResult(false, "Upload-Length is required"))
                return@post
            }
            val refusal = refuseUpload(length)
            if (refusal != null) {
                call.respond(refusal.first, ApiResult(false, refusal.second))
                return@post
            }
            val metadata = parseTusMetadata(call.request.header("Upload-Metadata"))
            val (_, streams) = tus.createGroup(length, metadata, 1)
            call.response.header(HttpHeaders.Location, "/tus/${streams.first().id}")
            call.respond(HttpStatusCode.Created)
        }

        /**
         * Parallel creation. Returns several ordinary tus upload URLs, each owning a
         * disjoint window of one already-preallocated file.
         *
         * This is an extension, not part of tus, which is why it lives on its own path
         * rather than overloading POST /tus -- a stock tus client hitting /tus still gets
         * exactly what the spec promises.
         */
        post("/tus/parallel") {
            call.response.header("Tus-Resumable", TUS_VERSION)
            val length = call.request.header("Upload-Length")?.toLongOrNull()
            if (length == null) {
                call.respond(HttpStatusCode.BadRequest, ApiResult(false, "Upload-Length is required"))
                return@post
            }
            val refusal = refuseUpload(length)
            if (refusal != null) {
                call.respond(refusal.first, ApiResult(false, refusal.second))
                return@post
            }
            val requested = call.request.header("Bridge-Streams")?.toIntOrNull()
                ?: config.uploadStreams()
            val metadata = parseTusMetadata(call.request.header("Upload-Metadata"))
            val (group, streams) = tus.createGroup(length, metadata, requested)
            call.respond(
                HttpStatusCode.Created,
                ParallelUploadDto(
                    groupId = group.id,
                    total = group.total,
                    streams = streams.map {
                        StreamDto("/tus/${it.id}", it.baseOffset, it.uploadLength, it.offset)
                    },
                ),
            )
        }

        head("/tus/{id}") {
            call.response.header("Tus-Resumable", TUS_VERSION)
            val info = tus.get(call.parameters["id"].orEmpty())
            if (info == null) {
                call.respond(HttpStatusCode.NotFound)
                return@head
            }
            call.response.header("Upload-Offset", info.offset.toString())
            call.response.header("Upload-Length", info.uploadLength.toString())
            // Mandated by the spec: a cached offset is a corrupted file.
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK)
        }

        patch("/tus/{id}") { call.tusPatch() }

        // The tus fallback for clients that cannot send PATCH: POST with an override header.
        post("/tus/{id}") {
            if (call.request.header("X-HTTP-Method-Override").equals("PATCH", ignoreCase = true)) call.tusPatch()
            else call.respond(HttpStatusCode.MethodNotAllowed, ApiResult(false, "Use PATCH"))
        }

        delete("/tus/{id}") {
            call.response.header("Tus-Resumable", TUS_VERSION)
            tus.terminate(call.parameters["id"].orEmpty())
            call.respond(HttpStatusCode.NoContent)
        }
    }

    /** Appends one PATCH body to an upload. Shared by PATCH and its POST fallback. */
    private suspend fun ApplicationCall.tusPatch() {
        response.header("Tus-Resumable", TUS_VERSION)

        val id = parameters["id"].orEmpty()
        val contentType = request.header(HttpHeaders.ContentType)
        if (contentType?.startsWith("application/offset+octet-stream") != true) {
            respond(HttpStatusCode.UnsupportedMediaType, ApiResult(false, "Wrong Content-Type"))
            return
        }
        val offset = request.header("Upload-Offset")?.toLongOrNull()
        if (offset == null) {
            respond(HttpStatusCode.BadRequest, ApiResult(false, "Upload-Offset is required"))
            return
        }

        when (val result = tus.append(id, offset, receiveChannel())) {
            is TusStore.AppendResult.Ok -> {
                response.header("Upload-Offset", result.offset.toString())
                respond(HttpStatusCode.NoContent)
            }
            is TusStore.AppendResult.Conflict -> {
                if (result.offset >= 0) response.header("Upload-Offset", result.offset.toString())
                respond(HttpStatusCode.Conflict, ApiResult(false, "Offset mismatch"))
            }
            TusStore.AppendResult.Busy -> respond(
                HttpStatusCode.Conflict,
                ApiResult(false, "Another request is already writing to this upload"),
            )
        }
    }

    /**
     * Everything that can refuse an upload before a single byte is sent. Shared by both
     * creation endpoints so the two cannot drift apart.
     */
    private fun refuseUpload(length: Long): Pair<HttpStatusCode, String>? {
        // No size limit: a file of any size is accepted as long as it fits in the free space.
        if (length < 0) return HttpStatusCode.BadRequest to "Upload-Length must be a size in bytes"
        if (!storage.hasDestination()) {
            return HttpStatusCode.ServiceUnavailable to
                "No destination folder has been chosen on the phone yet"
        }
        // Checked once, up front. Discovering it at 95% of a 10 GB upload is a much worse
        // way to find out -- and preallocation would fail there anyway.
        val free = storage.freeSpaceBytes()
        if (free in 1 until length + SPACE_HEADROOM) {
            return HttpStatusCode.InsufficientStorage to
                "Not enough free space: needs $length bytes, has $free"
        }
        return null
    }

    // ------------------------------------------------------------------ benchmark

    /**
     * Throughput probes that never touch storage.
     *
     * These exist because "the transfer is slow" has two completely different causes with
     * completely different fixes, and guessing between them wastes hours. Comparing a
     * real transfer against these numbers separates them immediately:
     *
     *  - real rate close to the probe rate  ->  the network is the ceiling. More streams,
     *    a better band, or a USB cable.
     *  - probe much faster than the real transfer  ->  storage is the ceiling. Look at
     *    whether the destination resolved to the direct-seek path, and at preallocation.
     */
    private fun io.ktor.server.routing.Route.bench() {
        get("/api/bench/download") {
            val want = (call.request.queryParameters["bytes"]?.toLongOrNull() ?: (128L * 1024 * 1024))
                .coerceIn(1, BENCH_MAX)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(ZeroContent(want))
        }

        post("/api/bench/upload") {
            val channel = call.receiveChannel()
            val buf = ByteArray(UPLOAD_BUFFER)
            var total = 0L
            while (true) {
                val n = channel.readAvailable(buf, 0, buf.size)
                if (n < 0) break
                total += n
                Monitor.addIn(n, Lane.TEST)
            }
            call.respond(ApiResult(true, "$total"))
        }
    }

    /**
     * A response body of a given length that is generated, not read. One buffer is
     * reused for every write, so this measures the socket and nothing else.
     *
     * Written straight into the response from the call's own coroutine, so the loop ends
     * at the first write after the client hangs up, and each chunk is counted only once
     * the flush has handed it on to the connection.
     */
    private class ZeroContent(private val length: Long) : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType get() = ContentType.Application.OctetStream
        override val contentLength: Long get() = length

        override suspend fun writeTo(channel: ByteWriteChannel) {
            val buf = ByteArray(DOWNLOAD_BUFFER)
            var sent = 0L
            try {
                while (sent < length) {
                    val n = minOf(buf.size.toLong(), length - sent).toInt()
                    channel.writeFully(buf, 0, n)
                    channel.flush()
                    Monitor.addOut(n, Lane.TEST)
                    sent += n
                }
            } catch (_: IOException) {
                // Client hung up mid-probe (the page's Measure always does); nothing to report.
            }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondTusOptions() {
        response.header("Tus-Resumable", TUS_VERSION)
        response.header("Tus-Version", TUS_VERSION)
        response.header("Tus-Extension", "creation,termination")
        respond(HttpStatusCode.NoContent)
    }

    // ------------------------------------------------------------------ download body

    /**
     * A SAF document served as a range-capable response body.
     *
     * `PartialContent` asks for `readFrom(range)` when the browser sends a `Range`
     * header, which is what makes a phone-to-PC download resumable. There is no `File`
     * behind a content:// URI, so the seek is done with `lseek` on the descriptor the
     * provider hands back -- the same trick, and the same caveat, as the upload path in
     * [Storage].
     *
     * The body is produced in a writer launched in [scope], the call it answers, so it
     * never outlives the request.
     */
    private inner class DocumentContent(
        private val scope: CoroutineScope,
        private val resolver: ContentResolver,
        private val entry: FileEntry,
        private val length: Long,
        private val type: ContentType,
    ) : OutgoingContent.ReadChannelContent() {

        override val contentType: ContentType get() = type
        override val contentLength: Long get() = length

        override fun readFrom(): ByteReadChannel = readFrom(0L until length)

        override fun readFrom(range: LongRange): ByteReadChannel {
            val first = range.first.coerceAtLeast(0)
            val last = range.last.coerceAtMost(length - 1)
            val count = (last - first + 1).coerceAtLeast(0)

            outbound.begin(entry, length)

            return scope.writer(Dispatchers.IO) {
                val pfd = resolver.openFileDescriptor(entry.uri.let(Uri::parse), "r")
                if (pfd == null) {
                    outbound.done(entry, first, 0, count)
                    return@writer
                }
                var sent = 0L
                try {
                    val input = FileInputStream(pfd.fileDescriptor)
                    // Files listed from the phone can come from any provider, and not
                    // every descriptor is seekable. lseek is the cheap path; skipping
                    // forward works on the pipes that refuse it, at the cost of reading
                    // and discarding the bytes before the range.
                    runCatching { Os.lseek(pfd.fileDescriptor, first, OsConstants.SEEK_SET) }
                        .onFailure {
                            var toSkip = first
                            while (toSkip > 0) {
                                val skipped = input.skip(toSkip)
                                if (skipped <= 0) break
                                toSkip -= skipped
                            }
                        }
                    val buf = ByteArray(DOWNLOAD_BUFFER)
                    var lastProgressAt = 0L
                    while (sent < count) {
                        val want = minOf(buf.size.toLong(), count - sent).toInt()
                        val n = input.read(buf, 0, want)
                        if (n <= 0) break
                        channel.writeFully(buf, 0, n)
                        channel.flush()
                        Monitor.addOut(n)
                        sent += n
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt >= 300) {
                            outbound.progress(entry, first, sent)
                            lastProgressAt = now
                        }
                    }
                } catch (t: Throwable) {
                    // The PC closing the tab mid-download lands here, and so does a
                    // cancelled parallel range. Not worth shouting about; the browser
                    // re-requests with a Range header.
                    Log.i(TAG, "Download ${entry.name} interrupted at $sent of $count: ${t.message}")
                } finally {
                    runCatching { pfd.close() }
                    outbound.done(entry, first, sent, count)
                }
            }.channel
        }
    }

    /**
     * Folds the browser's parallel range requests back into one visible transfer.
     *
     * Without this, a four-stream download shows up on the phone as four unrelated rows
     * that each claim to be a quarter of a file, with four separate rates. The user is
     * moving one file and wants one number.
     */
    private class OutboundTracker {
        private val parts = ConcurrentHashMap<String, ConcurrentHashMap<Long, Long>>()

        fun begin(entry: FileEntry, total: Long) {
            parts.computeIfAbsent(entry.id) {
                Transfers.begin("dl-${entry.id}", entry.name, Direction.OUTBOUND, total)
                ConcurrentHashMap()
            }
        }

        fun progress(entry: FileEntry, rangeStart: Long, sent: Long) {
            val m = parts[entry.id] ?: return
            m[rangeStart] = sent
            Transfers.progress("dl-${entry.id}", m.values.sum())
        }

        fun done(entry: FileEntry, rangeStart: Long, sent: Long, expected: Long) {
            val m = parts[entry.id] ?: return
            m[rangeStart] = sent
            val total = m.values.sum()
            Transfers.progress("dl-${entry.id}", total)
            when {
                total >= entry.size && entry.size > 0 -> {
                    parts.remove(entry.id)
                    Transfers.finish("dl-${entry.id}", ok = true)
                }
                // Only report a stall once nothing is still moving. One range finishing
                // short while its siblings are mid-flight is normal, not a failure.
                sent < expected && m.values.sum() < entry.size -> Transfers.stall("dl-${entry.id}")
            }
        }
    }

    private companion object {
        /** The name a page starts a test call with: its own voice and picture through a call and back. */
        const val TEST_CALL = "Test call"
        const val GRACE_MS = 500L
        const val TIMEOUT_MS = 2_000L
        const val HEARTBEAT_MS = 15_000L
        /** Short, so a dead laptop connection is noticed within seconds. */
        const val HEARTBEAT_CONTROL_MS = 3_000L
        const val HEARTBEAT_IDLE_MS = 25_000L

        /** Leave this much slack so finishing an upload cannot itself fill the disk. */
        const val SPACE_HEADROOM = 256L * 1024 * 1024

        /** Cap on a single throughput probe, so a stray query cannot run forever. */
        const val BENCH_MAX = 4L * 1024 * 1024 * 1024

        // map.html holds no positions: the app hands them in, the page asks /api/where (paired) for them.
        val PUBLIC_PATHS = setOf("/", "/api/ping", "/favicon.ico", "/map.html")

        /** The page's font files: Lexend Deca, by script subset and weight. */
        val FONT_FILE = Regex("""lexend-deca-(latin|latin-ext)-(400|500|600|700)-normal\.woff2""")
    }
}
