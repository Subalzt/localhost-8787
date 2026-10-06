package dev.periy.bridge.util

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.edit
import dev.periy.bridge.BuildConfig
import java.security.SecureRandom

/**
 * Small typed wrapper over SharedPreferences. Not encrypted: the values here (the
 * session HMAC key) are only as valuable as access to the device itself, and pulling in
 * Jetpack Security for a personal LAN tool buys very little. If this ever handles other
 * people's devices, that changes.
 */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    var treeUri: Uri?
        get() = sp.getString(K_TREE, null)?.let(Uri::parse)
        set(v) = sp.edit { if (v == null) remove(K_TREE) else putString(K_TREE, v.toString()) }

    var port: Int
        get() = sp.getInt(K_PORT, BuildConfig.DEFAULT_PORT)
        set(v) = sp.edit { putInt(K_PORT, v) }

    var autoStartOnBoot: Boolean
        get() = sp.getBoolean(K_AUTOSTART, false)
        set(v) = sp.edit { putBoolean(K_AUTOSTART, v) }

    /** Forces the staged-copy storage path even when the direct-seek probe passes. */
    var forceStagedCopy: Boolean
        get() = sp.getBoolean(K_FORCE_STAGE, false)
        set(v) = sp.edit { putBoolean(K_FORCE_STAGE, v) }

    /**
     * Parallel connections per transfer.
     *
     * Four is the default because it captures most of the available gain on Wi-Fi while
     * keeping the interleaved write pattern tame. Two is enough on a wired USB link,
     * where a single stream already runs close to capacity; eight helps on a busy or
     * distant Wi-Fi link where any one connection spends most of its time in recovery.
     */
    /**
     * Appearance, shared by the app and every connected page: "system", "light" or "dark".
     * Earlier versions stored an OLED switch; that choice carries over as dark.
     */
    var theme: String
        get() = sp.getString(K_THEME, null) ?: if (sp.getBoolean(K_OLED, false)) "dark" else "system"
        set(v) = sp.edit { putString(K_THEME, v) }

    /**
     * The style, shared with every page: "theatre", the only one (older names are mapped by
     * styleName in BridgeApp).
     */
    var style: String
        get() = sp.getString(K_STYLE, null) ?: "theatre"
        set(v) = sp.edit { putString(K_STYLE, v) }

    /** The colour, shared with every page: "auto" (the style's own) or a system colour's name. */
    var accent: String
        get() = sp.getString(K_ACCENT, null) ?: "auto"
        set(v) = sp.edit { putString(K_ACCENT, v) }

    /**
     * How the laptop reaches the phone at speed: "direct" (the phone's own offline network,
     * fastest, the laptop has no internet while on it) or "hotspot" (the phone's ordinary
     * hotspot, which shares the phone's internet, so the laptop stays online).
     */
    var laptopLink: String
        get() = sp.getString(K_LAPTOP_LINK, "direct").takeIf { it == "hotspot" } ?: "direct"
        set(v) = sp.edit { putString(K_LAPTOP_LINK, if (v == "hotspot") "hotspot" else "direct") }

    /**
     * The phone's hotspot name and password, typed in once. Android does not let an app read
     * them, and the laptop helper needs them to join (unless the laptop already knows the network).
     */
    var hotspotSsid: String
        get() = sp.getString(K_HOTSPOT_SSID, "").orEmpty()
        set(v) = sp.edit { putString(K_HOTSPOT_SSID, v.trim()) }

    var hotspotPass: String
        get() = sp.getString(K_HOTSPOT_PASS, "").orEmpty()
        set(v) = sp.edit { putString(K_HOTSPOT_PASS, v) }

    /**
     * Clipboard follows between the phone and a laptop running the helper without pressing
     * Send: copy on the laptop, paste on the phone, and the phone's latest copy goes over
     * whenever Localhost 8787 opens or its quick-settings tile is tapped.
     */
    var clipSync: Boolean
        get() = sp.getBoolean(K_CLIP_SYNC, true)
        set(v) = sp.edit { putBoolean(K_CLIP_SYNC, v) }

    /** A notification when the laptop with the helper drops off (asleep, off the internet). */
    /** Location was asked for once, on the first start (the map, server/Where.kt). */
    var locationAsked: Boolean
        get() = sp.getBoolean("location_asked", false)
        set(v) = sp.edit { putBoolean("location_asked", v) }


    /** Paired laptops and phones may reach this phone from other networks, through the tunnel. */
    var remote: Boolean
        get() = sp.getBoolean(K_REMOTE, true)
        set(v) = sp.edit { putBoolean(K_REMOTE, v) }

    /** New screenshots go on the shared clipboard by themselves, Localhost 8787 open or not. */
    var screenshotClip: Boolean
        get() = sp.getBoolean(K_SHOT_CLIP, true)
        set(v) = sp.edit { putBoolean(K_SHOT_CLIP, v) }

    /** Whether the live monitor floats over the app's screens. */
    var showMonitor: Boolean
        get() = sp.getBoolean(K_MONITOR, false)
        set(v) = sp.edit { putBoolean(K_MONITOR, v) }

    /** Albums with no cover of their own are looked up in Apple's public catalogue (CoverFinder). */
    var coverLookup: Boolean
        get() = sp.getBoolean(K_COVER_LOOKUP, true)
        set(v) = sp.edit { putBoolean(K_COVER_LOOKUP, v) }

    /** The phone's player shows the song's lyrics over its cover (Namida's lyrics button). */
    var lyricsShown: Boolean
        get() = sp.getBoolean(K_LYRICS_SHOWN, true)
        set(v) = sp.edit { putBoolean(K_LYRICS_SHOWN, v) }

    var uploadStreams: Int
        get() = sp.getInt(K_STREAMS, 4).coerceIn(1, 8)
        set(v) = sp.edit { putInt(K_STREAMS, v.coerceIn(1, 8)) }

    /**
     * HMAC key for session cookies. Rotating it invalidates every browser that has
     * already paired, which is exactly what "unpair everything" should mean.
     */
    fun sessionKey(): ByteArray {
        sp.getString(K_KEY, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        return rotateSessionKey()
    }

    fun rotateSessionKey(): ByteArray {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        sp.edit { putString(K_KEY, Base64.encodeToString(key, Base64.NO_WRAP)) }
        return key
    }

    private companion object {
        const val K_TREE = "tree_uri"
        const val K_PORT = "port"
        const val K_KEY = "session_key"
        const val K_AUTOSTART = "autostart"
        const val K_FORCE_STAGE = "force_staged_copy"
        const val K_STREAMS = "upload_streams"
        const val K_OLED = "oled"
        const val K_STYLE = "style"
        const val K_ACCENT = "accent"
        const val K_THEME = "theme"
        const val K_LAPTOP_LINK = "laptop_link"
        const val K_CLIP_SYNC = "clip_sync"
        const val K_REMOTE = "remote"
        const val K_SHOT_CLIP = "screenshot_clip"
        const val K_HOTSPOT_SSID = "hotspot_ssid"
        const val K_HOTSPOT_PASS = "hotspot_pass"
        const val K_MONITOR = "show_monitor"
        const val K_COVER_LOOKUP = "cover_lookup"
        const val K_LYRICS_SHOWN = "lyrics_shown"
    }
}
