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
    /**
     * Lyrics: kept on the phone for the page and the phone's player alike, and looked up by the
     * phone itself (LRCLIB) a moment after each song it plays starts, when it has none yet.
     */
    val lyrics = dev.periy.bridge.server.LyricsFinder(dev.periy.bridge.server.LyricsStore(app) { music.file(it.id) })
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    init {
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
        },
        storage = storage, index = index, clipboard = clipboard, clipSync = { prefs.clipSync },
    )

    private val _theme = MutableStateFlow(prefs.theme)

    /** The shared appearance: "system", "light" or "dark". The app and every page follow it. */
    val theme: StateFlow<String> = _theme

    fun setTheme(value: String) {
        val v = value.takeIf { it in THEMES } ?: return
        prefs.theme = v
        _theme.value = v
        EventBus.emit("theme", v)
    }

    private val _look = MutableStateFlow(Look(styleName(prefs.style) ?: "theatre", prefs.accent.takeIf { it in ACCENT_NAMES } ?: "auto", prefs.namidaUi))

    init {
        // A removed style must not remain in preferences after the next app launch.
        if (prefs.style != _look.value.style) prefs.style = _look.value.style
    }

    /** The look and the colour: shared like the theme. */
    val look: StateFlow<Look> = _look

    /** Any of them; an unknown or missing value leaves that part as it is. */
    fun setLook(style: String? = null, accent: String? = null, namida: Boolean? = null) {
        val v = Look(
            style?.let(::styleName) ?: _look.value.style,
            accent?.takeIf { it in ACCENT_NAMES } ?: _look.value.accent,
            namida ?: _look.value.namida,
        )
        prefs.style = v.style
        prefs.accent = v.accent
        prefs.namidaUi = v.namida
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
            setLook = { s, a, n -> setLook(s, a, n) },
            laptopLink = { prefs.laptopLink },
            hotspot = { prefs.hotspotSsid to prefs.hotspotPass },
            clipSync = { prefs.clipSync },
            deviceName = deviceName(),
        )
        return BridgeServer(app, config, storage, tus, index, clipboard, devices, pairing, music, direct, peers, loudness, favourites, lyrics)
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

/**
 * The shared look: the style, the colour, and [namida], Namida's surfaces, shapes, font and icons
 * over everything outside Music (which is Namida's always).
 */
data class Look(val style: String = "theatre", val accent: String = "auto", val namida: Boolean = false) {
    fun json() = """{"style":"$style","accent":"$accent","namida":$namida}"""
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
