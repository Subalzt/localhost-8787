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
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondOutputStream
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

    @Volatile
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    val isRunning: Boolean get() = engine != null

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
                connector {
                    host = "0.0.0.0"
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
        beacon.start()
        Monitor.start(ctx)
        Log.i(TAG, "Listening on :${config.port}")
    }

    fun stop() {
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
            val path = call.request.path()
            if (path in PUBLIC_PATHS || path.startsWith("/api/pair")) return@intercept
            val device = call.device()
            if (device != null) {
                devices.touch(device.id, call.request.origin.remoteAddress)
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
            val dto = withContext(Dispatchers.IO) {
                MusicDto(granted = music.granted(), tracks = music.tracks(refresh))
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(dto)
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

        // A song's lyrics, as a page (or the phone itself) found them online and left here; see LyricsStore.
        get("/api/music/lyrics/{id}") {
            val track = call.parameters["id"]?.toLongOrNull()?.let { withContext(Dispatchers.IO) { music.find(it) } }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val doc = track?.let { withContext(Dispatchers.IO) { lyrics.get(it) } }
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
            val info = call.parameters["id"]?.toLongOrNull()?.let { withContext(Dispatchers.IO) { music.info(it) } }
            if (info == null) {
                call.respond(HttpStatusCode.NotFound)
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

        get("/api/music/art/{albumId}") {
            val albumId = call.parameters["albumId"]?.toLongOrNull()
            val bytes = albumId?.let { withContext(Dispatchers.IO) { music.cover(it) } }
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.response.header(HttpHeaders.CacheControl, "private, max-age=604800")
            call.respondBytes(bytes, ContentType.Image.JPEG)
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
            val all = dev.periy.bridge.net.NetInfo.addresses()
            val (here, via) = call.arrivedOn(all)
            val usb = all.firstOrNull { it.kind == dev.periy.bridge.net.LinkKind.USB && !it.isIpv6 }
            call.respond(
                RouteDto(
                    via = via.name.lowercase(),
                    host = here,
                    usb = usb?.host?.takeIf { via != dev.periy.bridge.net.LinkKind.USB },
                    usbMbps = if (via == dev.periy.bridge.net.LinkKind.USB) Monitor.laptopUsbMbps() else 0,
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
            peers.hello(h, call.request.origin.remoteAddress, me.id)
            call.respond(ApiResult(true, config.deviceName))
        }
        post("/api/peers/bye") {
            call.device()?.let { peers.bye(it.id) }
            call.respond(ApiResult(true))
        }
        // A copy on a linked phone. Taken only when newer than what is here (see PeerManager.takeClip).
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
            val local = localTargets(except = me.id)
            val remote = if (call.request.queryParameters["local"] == "1") emptyList() else coroutineScope {
                peers.peers.value.map { p ->
                    async {
                        listOf(TargetDto("peer:${p.name}/phone", p.name, "phone")) +
                            peers.remoteTargets(p).map { t -> t.copy(id = "peer:${p.name}/${t.id}", via = p.name) }
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

    private fun sinkName(s: Pipes.Sink): String = when (s) {
        is Pipes.Sink.Device -> devices.get(s.id)?.let(::displayName) ?: "a computer"
        Pipes.Sink.Store -> config.deviceName
        is Pipes.Sink.Relay -> s.peer.name
    }

    /** Computers with the page open here right now (not their helpers, not phones), by name. */
    private fun localTargets(except: String): List<TargetDto> {
        val live = devices.live.value
        return devices.devices.value
            .filter { (live[it.id] ?: 0) > 0 && it.id != except && !it.name.startsWith(PHONE_PREFIX) && !it.name.startsWith(HELPER_PREFIX) }
            .map { TargetDto("dev:${it.id}", displayName(it), "computer") }
    }

    /**
     * A computer as a person knows it: a page by its machine's name when that machine's helper
     * is paired from the same address, else by its browser ("Edge on Windows").
     */
    private fun displayName(d: PairedDevice): String = shownName(d, devices.devices.value)

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
    private fun ApplicationCall.arrivedOn(
        all: List<dev.periy.bridge.net.Address> = dev.periy.bridge.net.NetInfo.addresses(),
    ): Pair<String, dev.periy.bridge.net.LinkKind> {
        val here = request.local.localAddress.removePrefix("::ffff:").substringBefore('%')
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
        post("/api/control/volume") {
            runCatching { call.receive<VolumeReport>() }.getOrNull()?.let { Control.reportVolume(it.level, it.muted) }
            call.respond(ApiResult(true))
        }
        // The laptop helper holds this open and turns each line into real input. Lines
        // that piled up while the socket was busy go out together in one flush, so a
        // slow moment costs one late batch rather than a growing delay.
        get("/api/control/stream") {
            val name = call.device()?.name ?: "Laptop"
            // Helpers from this version on accept a slow keep-alive while the Control tab is
            // closed; older ones time out after ten seconds of silence, so they keep the fast one.
            val slowOk = call.request.headers["Bridge-Heartbeat"] == "slow"
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondBytesWriter(ContentType.Text.Plain) {
                val ch = Control.attach(name)
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
        get("/") {
            val html = ctx.assets.open("bridge.html").use { it.readBytes() }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondBytes(html, ContentType.Text.Html.withCharset(Charsets.UTF_8))
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
                val bytes = withContext(Dispatchers.IO) { ctx.assets.open(asset).use { it.readBytes() } }
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
            call.respond(PingDto(ok = true, paired = call.device() != null, device = config.deviceName))
        }

        // A computer asks to be let in. The phone shows who is asking and a code.
        post("/api/pair") {
            val ip = call.request.origin.remoteAddress
            val name = describeUserAgent(call.request.header(HttpHeaders.UserAgent))
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
            if (req.state == PairRequest.State.APPROVED && deviceId != null) {
                call.response.cookies.append(
                    Cookie(
                        name = SESSION_COOKIE,
                        value = Session.issue(config.sessionKey(), config.sessionTtlMs, deviceId),
                        path = "/",
                        httpOnly = true,
                        // Not Secure: this is served over plain HTTP on the local network,
                        // and a Secure cookie would never be sent back.
                        secure = false,
                        maxAge = (config.sessionTtlMs / 1000).toInt(),
                        extensions = mapOf("SameSite" to "Strict"),
                    )
                )
            }
            call.respond(PairStatusDto(req.state.name))
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

        val PUBLIC_PATHS = setOf("/", "/api/ping", "/favicon.ico")

        /** The page's font files: Lexend Deca, by script subset and weight. */
        val FONT_FILE = Regex("""lexend-deca-(latin|latin-ext)-(400|500|600|700)-normal\.woff2""")
    }
}
