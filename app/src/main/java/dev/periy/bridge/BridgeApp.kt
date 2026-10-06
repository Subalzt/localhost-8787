package dev.periy.bridge

import android.app.Application
import android.content.Context
import android.os.Build
import dev.periy.bridge.server.BridgeServer
import dev.periy.bridge.server.ClipboardStore
import dev.periy.bridge.server.DeviceRegistry
import dev.periy.bridge.server.EventBus
import dev.periy.bridge.server.MusicLibrary
import dev.periy.bridge.server.FileIndex
import dev.periy.bridge.server.PairingManager
import dev.periy.bridge.server.PeerManager
import dev.periy.bridge.server.SESSION_COOKIE
import dev.periy.bridge.server.Session
import dev.periy.bridge.server.ServerConfig
import dev.periy.bridge.server.Storage
import dev.periy.bridge.server.TusStore
import dev.periy.bridge.util.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hand-rolled singleton graph. A DI framework would earn its keep at ten times this size;
 * at this size it would only add a build step and an annotation processor.
 */
class Container(ctx: Context) {
    private val app = ctx.applicationContext

    val prefs = Prefs(app)
    val storage = Storage(app, prefs)
    val index = FileIndex(app)
    val clipboard = ClipboardStore(app)
    val tus = TusStore(app, storage, index)
    val devices = DeviceRegistry(app)
    val pairing = PairingManager(app, devices)
    val music = MusicLibrary(app) { prefs.coverLookup }
    /** The phone's own music player, and how loud each moment of a song is, for its seek bar and cover. */
    val player = dev.periy.bridge.music.PhonePlayer(app, music)
    val loudness = dev.periy.bridge.music.Loudness(app, music)
    val favourites = dev.periy.bridge.music.Favourites(app)
    /** The sleep timer, the alarms (Android's alarm clock) and the alarm sounding with your own music. */
    val sleep = dev.periy.bridge.music.SleepTimer(player)
    val alarms = dev.periy.bridge.music.Alarms(app)
    val ringer = dev.periy.bridge.music.AlarmRinger(app, music, favourites, player)
    /** Plays counted, each song's sound measured, and the mixes made from them. */
    val plays = dev.periy.bridge.music.PlayStats(app)
    val mixes = dev.periy.bridge.music.SmartMixes(app, music, plays)
    /** The equalizer, the same for the phone's player and every page's. */
    val eq = dev.periy.bridge.music.EqStore(app)
    /**
     * Lyrics: kept on the phone for the page and the phone's player alike, and looked up by the
     * phone itself (LRCLIB) a moment after each song it plays starts, when it has none yet.
     */
    val lyrics = dev.periy.bridge.server.LyricsFinder(dev.periy.bridge.server.LyricsStore(app) { music.file(it.id) })
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    init {
        // Android forgets alarms when the app is updated or killed hard: the next one, set again.
        runCatching { alarms.arm() }
        background.launch(kotlinx.coroutines.Dispatchers.Main) { eq.state.collect { player.applyEq(it) } }
        // A song counts as played once half of it, or 30 s, has played (again after it starts over).
        background.launch(kotlinx.coroutines.Dispatchers.Main) {
            var counted = -1L
            player.state.collectLatest { s ->
                if (!s.playing) return@collectLatest
                while (true) {
                    val st = player.state.value
                    val t = st.current ?: break
                    val pos = st.positionNow()
                    if (pos < 3_000) counted = -1
                    val enough = minOf(dev.periy.bridge.music.PlayStats.ENOUGH_MS, (st.durationMs.takeIf { it > 0 } ?: t.durationMs) / 2)
                    if (t.id != counted && enough > 2_000 && pos >= enough) { plays.played(t.id); counted = t.id }
                    kotlinx.coroutines.delay(4_000)
                }
            }
        }
        // The library's sound, measured in the background a while after starting (new songs too).
        background.launch { kotlinx.coroutines.delay(90_000); if (music.granted()) mixes.scan() }
        background.launch {
            player.state.map { it.current }.distinctUntilChangedBy { it?.id }.collectLatest { t ->
                if (t == null) return@collectLatest
                // Not for songs skipped straight past.
                kotlinx.coroutines.delay(LYRICS_AFTER_MS)
                runCatching { lyrics.forTrack(t) }
            }
        }
    }

    val direct = dev.periy.bridge.net.DirectLink(app)
    /** How two laptops' pages learn their addresses, to send to each other directly. */
    val stun = dev.periy.bridge.net.StunServer()
    // Phone to phone goes over the network both are on; the direct-link option was taken off the Devices tab.
    val peers = PeerManager(
        app, ::deviceName, { prefs.uploadStreams }, direct, useDirect = { false },
        access = object : PeerManager.Access {
            override val port: Int get() = prefs.port
            override fun grant(name: String, ip: String): String = devices.add("Phone: $name", ip).id
            override fun cookie(deviceId: String): String? = devices.get(deviceId)?.let {
                SESSION_COOKIE + "=" + Session.issue(prefs.sessionKey(), PeerManager.SESSION_TTL_MS, it.id)
            }
            override fun revoke(deviceId: String) = devices.remove(deviceId)
            // Always: on the phone's own networks the tunnel is how linked phones talk, sealed, even with From other networks off.
            override fun tunnelFor(deviceId: String): String = door.forDevice(deviceId)
            override fun ensureTunnel() = turnOnTunnel()
            override fun linked() {
                val code = tunnelKeys.openCode() ?: return
                // A moment more, so the phone that just linked is done with the way the code opened.
                main.postDelayed({ if (tunnelKeys.openCode() == code) closeLinkCode() }, 30_000)
            }
            override val selfId: String get() = Session.phoneId(prefs.sessionKey())
            override fun signedHere(cookie: String): String? = Session.signedHere(prefs.sessionKey(), cookie)
            override fun ownCode(): String? = tunnelKeys.openCode()
        },
        storage = storage, index = index, clipboard = clipboard, clipSync = { prefs.clipSync },
    )

    /** Voice calls with linked phones, straight between the two (server/Calls.kt). */
    val calls by lazy {
        dev.periy.bridge.server.Calls(app, peers) { tunnelKeys.psk(it) }
            .also { c -> c.onChange = { s -> dev.periy.bridge.service.CallService.update(app, s) }; c.log = callLog }
    }

    /** The calls made and had, and missed calls said in a notification. */
    val callLog = dev.periy.bridge.server.CallLog(app)

    /** Says when the laptop with the helper drops off. */

    /** Where your phones and laptops are (server/Where.kt). */
    val where: dev.periy.bridge.server.Where by lazy { dev.periy.bridge.server.Where(app, peers) { tunnelKeys.psk(it) }.also { w -> w.lostNow = { findMe.lost.value.on } } }

    /** Ringing this phone and lost mode, from the map (server/FindMe.kt). */
    val findMe: dev.periy.bridge.server.FindMe by lazy { dev.periy.bridge.server.FindMe(app).also { f -> f.onLost = { where.lostChanged() } } }

    /** Messages with linked phones, each phone the other's server (server/Messages.kt). */
    /** Phones as security cameras (server/Cameras.kt). */
    val cameras: dev.periy.bridge.server.Cameras by lazy {
        dev.periy.bridge.server.Cameras(app, peers, { tunnelKeys.psk(it) }) {
            val online = dev.periy.bridge.server.Control.online()
            devices.devices.value.filter { it.id in online && it.name.startsWith(dev.periy.bridge.server.HELPER_PREFIX) }
                .map { it.id to dev.periy.bridge.server.helperMachine(it.name).removeSuffix(" (website)") }.distinctBy { it.second }
        }
    }

    val messages by lazy { dev.periy.bridge.server.Messages(app, peers) { tunnelKeys.psk(it) } }

    /** The phone as a website, NAME.dedyn.io:8443 with a PIN (docs/website.md). */
    val site = dev.periy.bridge.net.Site(app) { prefs.port }.also { s -> dev.periy.bridge.net.Punch.zone = { s.boardZone() } }

    /** From other networks: paired devices reach the phone through its tunnel (docs/tunnel-protocol.md). */
    val tunnelKeys = dev.periy.bridge.net.TunnelKeys(app.filesDir)
    val tunnel = dev.periy.bridge.net.TunnelServer(
        tunnelKeys,
        // While a link code is open, the tunnel also answers for it (net/LinkCode.kt).
        deviceIds = { devices.devices.value.map { it.id } + listOfNotNull(tunnelKeys.openCode()?.let { dev.periy.bridge.net.LinkCode.ID }) },
        pagePort = { prefs.port },
        info = ::tunnelInfo,
        farAllowed = { prefs.remote },
    )

    /** The tunnel as the server sees it: whether it is on, and each device's details. */
    val door by lazy { dev.periy.bridge.server.RemoteDoor(tunnel, tunnelKeys, { prefs.remote }, ::tunnelInfo) }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Turns on From other networks (a phone linked from afar comes back through it). */
    fun turnOnTunnel() {
        prefs.remote = true
        if (server?.isRunning == true) door.set(true)
    }

    /**
     * A code another phone can link with from any network, open for five minutes or until a phone
     * links (net/LinkCode.kt). From other networks goes on with it.
     */
    fun openLinkCode(): String {
        val code = dev.periy.bridge.net.LinkCode.new()
        val secret = dev.periy.bridge.net.LinkCode.newSecret()
        tunnelKeys.openCode(code, secret)
        linkTriesSpent.value = false
        linkDoor?.stop()
        // Answers a phone that types the code, three tries at most (net/LinkPake.kt).
        linkDoor = dev.periy.bridge.net.LinkPake.Door(code, secret, System.currentTimeMillis() + dev.periy.bridge.net.LinkCode.TTL_MS) {
            linkTriesSpent.value = true
        }.also { it.start() }
        turnOnTunnel()
        tunnel.prune()
        main.postDelayed({ if (tunnelKeys.openCode() == null) tunnel.prune() }, dev.periy.bridge.net.LinkCode.TTL_MS + 1_000)
        return code
    }

    /** The open code's door to the typing phone; and whether it has had its three tries. */
    private var linkDoor: dev.periy.bridge.net.LinkPake.Door? = null
    val linkTriesSpent = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun closeLinkCode() {
        linkDoor?.stop()
        linkDoor = null
        tunnelKeys.closeCode()
        tunnel.prune()
    }

    /** What the tunnel tells a device about this phone: its name, and where to find it next time. */
    fun tunnelInfo(): String = kotlinx.serialization.json.buildJsonObject {
        put("name", kotlinx.serialization.json.JsonPrimitive(deviceName()))
        put("v", kotlinx.serialization.json.JsonPrimitive(1))
        put("port", kotlinx.serialization.json.JsonPrimitive(dev.periy.bridge.net.TunnelProto.PORT))
        // The page's own port, for a phone that has only a link code to go on.
        put("page", kotlinx.serialization.json.JsonPrimitive(prefs.port))
        put("addrs", kotlinx.serialization.json.JsonArray(dev.periy.bridge.net.NetInfo.publicAddresses(app).map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }.toString()

    private val _theme = MutableStateFlow(prefs.theme)

    /** The shared appearance: "system", "light" or "dark". The app and every page follow it. */
    val theme: StateFlow<String> = _theme

    fun setTheme(value: String) {
        val v = value.takeIf { it in THEMES } ?: return
        prefs.theme = v
        _theme.value = v
        EventBus.emit("theme", v)
    }

    private val _look = MutableStateFlow(Look(styleName(prefs.style) ?: "theatre", prefs.accent.takeIf { it in ACCENT_NAMES } ?: "auto"))

    init {
        // A removed style must not remain in preferences after the next app launch.
        if (prefs.style != _look.value.style) prefs.style = _look.value.style
    }

    /** The look and the colour: shared like the theme. */
    val look: StateFlow<Look> = _look

    /** Either or both; an unknown value leaves that half as it is. */
    fun setLook(style: String? = null, accent: String? = null) {
        val v = Look(
            style?.let(::styleName) ?: _look.value.style,
            accent?.takeIf { it in ACCENT_NAMES } ?: _look.value.accent,
        )
        prefs.style = v.style
        prefs.accent = v.accent
        _look.value = v
        EventBus.emit("look", v.json())
    }

    @Volatile
    var server: BridgeServer? = null
        private set

    fun deviceName(): String = listOfNotNull(
        Build.MANUFACTURER?.replaceFirstChar { it.uppercase() },
        Build.MODEL,
    ).distinct().joinToString(" ").ifBlank { "Android device" }

    /** Builds a server against the current port and key. Stops any previous one. */
    fun newServer(): BridgeServer {
        server?.stop()
        val config = ServerConfig(
            port = prefs.port,
            sessionKey = { prefs.sessionKey() },
            uploadStreams = { prefs.uploadStreams },
            theme = { _theme.value },
            setTheme = ::setTheme,
            look = { _look.value },
            setLook = { s, a -> setLook(s, a) },
            laptopLink = { prefs.laptopLink },
            hotspot = { prefs.hotspotSsid to prefs.hotspotPass },
            clipSync = { prefs.clipSync },
            deviceName = deviceName(),
        )
        return BridgeServer(app, config, storage, tus, index, clipboard, devices, pairing, music, direct, peers, loudness, favourites, lyrics)
            .also { it.remote = door; it.site = site }
            .also { server = it }
    }

    fun stopServer() {
        server?.stop()
        server = null
    }
}

/** How long a song plays on the phone before its lyrics are looked up. */
private const val LYRICS_AFTER_MS = 1500L

val THEMES = setOf("system", "light", "dark")

/**
 * The style, beyond light and dark. There is one now, "theatre"; pages and the laptop helper
 * still read the name, so it is still sent.
 */
val STYLES = setOf("theatre")

/** "auto" is the style's own colour; the rest are the system colours (Theme.kt, ACCENTS). */
/** Colours no longer offered (pink, indigo, graphite, black) fall back to "auto". */
val ACCENT_NAMES = setOf("auto", "red", "orange", "yellow", "green", "mint", "blue", "purple")

/** A style by its name: every one there has been (Studio, Glass, the first names) is now Theatre. */
fun styleName(s: String): String? = when (s) {
    "studio", "music", "glass", "signal", "tv", "theatre" -> "theatre"
    else -> null
}

data class Look(val style: String = "theatre", val accent: String = "auto") {
    fun json() = """{"style":"$style","accent":"$accent"}"""
}

class BridgeApp : Application() {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
    }
}

val Context.container: Container
    get() = (applicationContext as BridgeApp).container
