package dev.periy.bridge.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What a laptop is showing or playing, as its helper tells it (blazeit-pc.bat HandoffNow): the
 * page's address and title, and what plays in it and where ([pos], seconds; -1 when nothing does).
 */
@Serializable
data class LaptopHandoff(
    val url: String = "", val title: String = "", val app: String = "", val artist: String = "",
    val playing: Boolean = false, val pos: Double = -1.0, val media: String = "", val laptop: String = "", val error: String = "",
)

/** The phone's music for a page to carry on: the queue's songs, which one, and where (ms) at [at] (wall clock). */
@Serializable
data class MusicHandoff(val id: String = "", val ids: List<Long> = emptyList(), val index: Int = 0, val posMs: Long = 0, val at: Long = 0, val from: String = "")

/**
 * Handoff between the phone and the laptops, Apple's way: carry on with what the other one was
 * in the middle of, at the same second.
 *
 * - Laptop to phone: the phone asks a laptop's helper what it is showing or playing ("handoff ID"
 *   on its event stream; Ctrl+Alt+P on the laptop sends it unasked), the laptop pauses it, and the
 *   phone opens the page, a YouTube video at the same second, in its own app.
 * - Phone to laptop: a link shared to "Open on laptop" opens in the laptop's browser (a YouTube
 *   video playing on the phone at the second it was at, paused here); the phone's music goes on in
 *   a laptop's page from the same moment.
 */
object Handoff {
    private const val TAG = "Handoff"
    private val waits = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    // ------------------------------------------------------------------ laptop to phone

    /** Asks laptop [laptopId] what it is in the middle of (it pauses it). */
    suspend fun pull(laptopId: String): LaptopHandoff {
        if (laptopId !in Control.online()) return LaptopHandoff(error = "The laptop helper is not running")
        val id = UUID.randomUUID().toString()
        val wait = CompletableDeferred<String>()
        waits[id] = wait
        EventBus.emitTo(laptopId, "handoff", id)
        val r = try { withTimeoutOrNull(12_000) { wait.await() } } finally { waits.remove(id) }
            ?: return LaptopHandoff(error = "The laptop did not answer")
        return parse(r)
    }

    fun parse(body: String): LaptopHandoff = runCatching { json.decodeFromString(LaptopHandoff.serializer(), body) }.getOrElse { LaptopHandoff(error = "The laptop's answer made no sense") }

    fun answer(id: String, body: String) { waits.remove(id)?.complete(body) }

    /**
     * Opens what a laptop handed over on this phone: the page in its own app (YouTube, Prime
     * Video, Spotify's web player...) or the browser, a YouTube video at the same second. What
     * happened, in words.
     */
    fun open(ctx: Context, d: LaptopHandoff): String {
        if (d.error.isNotEmpty()) return d.error
        if (d.url.isEmpty()) return if (d.media.isNotEmpty()) "${d.laptop.ifEmpty { "The laptop" }} is playing ${d.media} in ${d.app}, which has no web address" else "Nothing open on the laptop to carry on"
        val url = withTime(d.url, if (d.pos >= 0) d.pos else null)
        return runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "opened ${Uri.parse(url).host} from the laptop")
            "Carrying on: " + d.title.ifEmpty { Uri.parse(url).host.orEmpty() }
        }.getOrElse { "Could not open it on the phone: ${it.message}" }
    }

    /** [url] to start at [sec]: YouTube's own t= (other sites keep their address; Prime and Netflix remember the place themselves). */
    fun withTime(url: String, sec: Double?): String {
        if (sec == null || sec < 5) return url
        val u = Uri.parse(url)
        val host = u.host.orEmpty().removePrefix("www.").removePrefix("m.")
        val yt = host == "youtube.com" || host == "music.youtube.com" || host == "youtu.be"
        if (!yt) return url
        val b = u.buildUpon().clearQuery()
        for (k in u.queryParameterNames) if (k != "t" && k != "start") for (v in u.getQueryParameters(k)) b.appendQueryParameter(k, v)
        return b.appendQueryParameter("t", "${sec.toInt()}s").build().toString()
    }

    // ------------------------------------------------------------------ phone to laptop

    /**
     * Opens [link] in a laptop's browser (the one [laptopId], else every laptop whose helper
     * runs). A YouTube link playing on the phone right now goes at the second it is at, and the
     * phone pauses it. The laptop's name it went to, or null when no helper runs.
     */
    fun toLaptop(ctx: Context, link: String, laptopId: String? = null): String? {
        val laptops = Control.laptops()
        val target = laptops.firstOrNull { it.first == laptopId } ?: laptops.firstOrNull() ?: return null
        var url = link
        val host = Uri.parse(link).host.orEmpty()
        if ("youtube.com" in host || "youtu.be" in host) playingOn(ctx, "com.google.android.youtube", pause = true)?.let { url = withTime(link, it) }
        EventBus.emitTo(target.first, "openurl", Base64.encodeToString(url.toByteArray(), Base64.NO_WRAP))
        Log.i(TAG, "sent ${Uri.parse(url).host} to ${target.second}")
        return target.second
    }

    /**
     * Where the app [pkg] is in what it plays (seconds), from Android's media sessions, which the
     * notification reader may see; [pause]: paused there too. Null when it plays nothing.
     */
    fun playingOn(ctx: Context, pkg: String, pause: Boolean): Double? = runCatching {
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        val c: MediaController = msm.getActiveSessions(ComponentName(ctx, NotifyListener::class.java)).firstOrNull { it.packageName == pkg } ?: return null
        val st = c.playbackState ?: return null
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
            if (pause) c.transportControls.pause()
        }
        pos / 1000.0
    }.onFailure { Log.w(TAG, "media sessions: ${it.message}") }.getOrNull()

    // ------------------------------------------------------------------ music, phone to page

    @Volatile private var pending: MusicHandoff? = null

    /**
     * The phone's music, for a laptop's page to carry on: offered to every page open ("handoff"
     * event); the first to claim it plays it, and the phone stops. With no page open, the
     * helper opens one ([laptopId]'s, or any), which claims it as it loads.
     */
    fun musicToLaptop(ids: List<Long>, index: Int, posMs: Long, from: String, laptopId: String?): Boolean {
        val m = MusicHandoff(UUID.randomUUID().toString().take(12), ids, index, posMs, System.currentTimeMillis(), from)
        pending = m
        val line = json.encodeToString(MusicHandoff.serializer(), m)
        EventBus.emit("handoffmusic", line)
        // A moment for an open page to claim it; else the laptop opens the page to.
        Thread {
            Thread.sleep(2_500)
            if (pending?.id != m.id) return@Thread
            val laptops = Control.laptops()
            val target = laptops.firstOrNull { it.first == laptopId } ?: laptops.firstOrNull() ?: return@Thread
            EventBus.emitTo(target.first, "openurl", Base64.encodeToString("http://localhost:8787/#handoff=${m.id}".toByteArray(), Base64.NO_WRAP))
        }.start()
        return Control.laptops().isNotEmpty()
    }

    /** A page takes the music offered: it is its to play (once); null when another took it, or it is old. */
    fun claim(id: String, onClaimed: () -> Unit): MusicHandoff? = synchronized(this) {
        val m = pending?.takeIf { it.id == id || id == "latest" } ?: return null
        if (System.currentTimeMillis() - m.at > 120_000) return null
        pending = null
        onClaimed()
        m
    }
}
