package dev.periy.bridge.server

import kotlinx.serialization.Serializable

/** Where a file came from. Drives the icon in the browser list, nothing more. */
enum class Origin { PC, PHONE }

@Serializable
data class FileEntry(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val addedAt: Long,
    /** SAF document URI (content://) once finalised into the user's chosen folder. */
    val uri: String,
    val origin: String,
    /**
     * Real filesystem path, when one could be derived. Used only to hand the media
     * scanner something to index -- we never open it ourselves, because on API 29+
     * without storage permission we would not be allowed to.
     */
    val scanPath: String? = null,
    /**
     * True when this app created the file, so removing it from the list should delete it.
     * False for a file the user merely pointed us at -- deleting that would destroy their
     * own photo, which is never what "remove from the list" should mean.
     */
    val owned: Boolean = true,
)

@Serializable
data class StateDto(
    val clipboard: String,
    val files: List<FileEntry>,
    val deviceName: String,
    /** How many parallel connections the page should open. See TusStore's class comment. */
    val uploadStreams: Int = 4,
    /** Shared appearance, "system", "light" or "dark": changing it anywhere changes all of them. */
    val theme: String = "system",
    /** The style (always "theatre" now) and the colour; shared like the theme. */
    val style: String = "theatre",
    val accent: String = "auto",
    /** Namida's look over the whole page, not only its music. */
    val namida: Boolean = false,
    /** Clipboard follows between phone and laptop helper without pressing Send. */
    val clipSync: Boolean = true,
    /** What the shared clipboard holds: text, a picture, a file, or nothing. */
    val clip: ClipMeta = ClipMeta(),
    /** Smallest file worth splitting; below this the round trips cost more than they save. */
    val parallelThreshold: Long = 16L * 1024 * 1024,
)

@Serializable data class ClipboardRequest(val text: String = "")
@Serializable data class ApiResult(val ok: Boolean, val message: String? = null, val retryAfterMs: Long = 0)

/**
 * Everything the HTTP layer needs to know.
 *
 * `sessionKey` is a provider rather than a snapshot on purpose. Unpairing every
 * computer has to take effect on the very next request -- a
 * revocation that only applies after the user remembers to restart the server is not a
 * revocation. `port` is a plain value because changing it genuinely does require
 * rebinding the socket.
 */
class ServerConfig(
    val port: Int,
    val sessionKey: () -> ByteArray,
    val uploadStreams: () -> Int,
    val theme: () -> String,
    val setTheme: (String) -> Unit,
    /** The style and the colour, shared like the theme. */
    val look: () -> dev.periy.bridge.Look = { dev.periy.bridge.Look() },
    val setLook: (String?, String?, Boolean?) -> Unit = { _, _, _ -> },
    /** "direct" or "hotspot", and the hotspot's name and password; see Prefs.laptopLink. */
    val laptopLink: () -> String = { "direct" },
    val hotspot: () -> Pair<String, String> = { "" to "" },
    val clipSync: () -> Boolean = { true },
    val sessionTtlMs: Long = 30L * 24 * 60 * 60 * 1000,
    val deviceName: String,
)

/**
 * Unauthenticated probe the page calls before showing anything, to decide between the
 * pairing screen and the main UI. Deliberately reveals nothing but whether this browser is
 * already paired and what device it is talking to.
 */
@Serializable
data class PingDto(val ok: Boolean, val paired: Boolean, val device: String)

/** One offset window of a parallel upload, as handed to the browser. */
@Serializable
data class StreamDto(
    val url: String,
    val base: Long,
    val length: Long,
    val offset: Long,
)

/**
 * Response to a parallel upload creation. Each stream is an ordinary tus upload URL --
 * HEAD and PATCH work on it exactly as the spec says -- but all of them write into
 * disjoint windows of one already-preallocated file, so there is no concatenation step.
 */
@Serializable
data class ParallelUploadDto(
    val groupId: String,
    val total: Long,
    val streams: List<StreamDto>,
)

/** A network interface the PC could reach this phone on, with a speed expectation. */
@Serializable
data class LinkDto(
    val iface: String,
    val url: String,
    val kind: String,
    val hint: String,
    val preferred: Boolean,
)

/** Returned when a computer asks to connect: the code it should display. */
@Serializable
data class PairStartDto(val id: String, val code: String, val name: String)

/** PENDING, APPROVED, DENIED or EXPIRED. */
@Serializable
data class PairStatusDto(val state: String)

@Serializable data class ThemeRequest(val theme: String = "system")

/** From a page's Settings: the style, the colour, or both, to use everywhere. */
@Serializable data class LookRequest(val style: String? = null, val accent: String? = null, val namida: Boolean? = null)


/** The phone's notifications, and whether Localhost 8787 may see them at all. */
@Serializable data class NotifList(val allowed: Boolean, val items: List<NotifDto>)

/** The laptop's master volume, from its helper. */
@Serializable data class VolumeReport(val level: Float = 0f, val muted: Boolean = false)

/** A browser reporting the round trip it measured to this phone. */
@Serializable data class RttReport(val ms: Int = -1)

/**
 * Which of the phone's links a request arrived on, so the page can say how it is really
 * connected (through the laptop helper it only sees localhost), and whether a faster one
 * is sitting unused.
 */
@Serializable
data class RouteDto(
    /** usb, direct, hotspot, wifi, cellular or other. */
    val via: String,
    /** The phone's address that answered. */
    val host: String,
    /** The phone's address on a USB cable, when there is one and this request did not use it. */
    val usb: String? = null,
    /**
     * Over the cable, the speed the laptop helper's USB adapter reports: under 600 means the
     * cable came up at USB 2 (about 40 MB/s instead of 250). 0 when unknown.
     */
    val usbMbps: Int = 0,
)

/** The phone's own offline network, for a laptop helper or another phone to join. */
@Serializable
data class DirectDto(
    /** off, starting, on or failed. */
    val state: String,
    val info: dev.periy.bridge.net.DirectLink.Info? = null,
    val message: String? = null,
    /** False when it was started for a phone-to-phone send: laptops should not join it. */
    val laptop: Boolean = true,
    /**
     * "direct" for the phone's own offline network, "hotspot" for its ordinary hotspot,
     * which shares internet. Laptops join either the same way.
     */
    val kind: String = "direct",
)
