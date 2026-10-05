package dev.periy.bridge.net

import android.content.Context
import android.os.PowerManager

/**
 * Keeps the CPU awake while the server is answering something: with the screen off the phone
 * otherwise sleeps between packets, and a page's requests then wait for it to wake. Held while any
 * request is being served (a long one, a song or a screen, as long as it lasts), and for half a
 * minute after the last, so the next request does not find it asleep; never for a connection that
 * is only open (a laptop helper's link, a page's event stream), so an idle phone sleeps as it should.
 * The screen stays as it is.
 */
object KeepAwake {
    private const val LINGER_MS = 30_000L
    private var lock: PowerManager.WakeLock? = null
    private var app: Context? = null
    private var open = 0
    /** Until when a page's last request keeps it awake (a helper's request then lets go only after). */
    private var lingerUntil = 0L

    /** Once, with the app's context. */
    @Synchronized
    fun init(ctx: Context) { app = ctx.applicationContext }

    /** Debug builds: what is being answered now, by path, for `adb logcat -s KeepAwake` (logged each minute while held). */
    private val paths = HashMap<String, Int>()
    private var told = 0L

    @Synchronized
    fun start(path: String = "") {
        if (dev.periy.bridge.BuildConfig.DEBUG) {
            paths[path] = (paths[path] ?: 0) + 1
            val now = System.currentTimeMillis()
            if (now - told > 60_000) { told = now; android.util.Log.i("KeepAwake", "Answering: $paths") }
        }
        open++
        val l = lock ?: (app?.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneBridge:remote")?.apply { setReferenceCounted(false) }
            ?.also { lock = it })
        // An hour at most per hold: a connection that never says goodbye cannot keep it forever.
        l?.acquire(3_600_000L)
    }

    @Synchronized
    fun end(path: String = "", linger: Boolean = true) {
        if (dev.periy.bridge.BuildConfig.DEBUG) { val n = (paths[path] ?: 1) - 1; if (n <= 0) paths.remove(path) else paths[path] = n }
        open = maxOf(0, open - 1)
        if (open > 0) return
        val now = System.currentTimeMillis()
        if (linger) lingerUntil = now + LINGER_MS
        // Held on until a page's last request is half a minute old; a helper's alone lets go at once.
        lock?.let { if (it.isHeld) { val left = lingerUntil - now; if (left > 0) it.acquire(left) else it.release() } }
    }
}
