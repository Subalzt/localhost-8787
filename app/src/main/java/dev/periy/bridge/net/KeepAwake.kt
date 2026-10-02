package dev.periy.bridge.net

import android.content.Context
import android.os.PowerManager

/**
 * Keeps the CPU awake while someone is connected from far away (the website, a tunnel): with the
 * screen off the phone otherwise sleeps between packets, and every request then waits seconds for
 * it to wake. Held while any connection is open, and for half a minute after the last one, so a
 * page's next request does not find it asleep. The screen stays as it is.
 */
object KeepAwake {
    private const val LINGER_MS = 30_000L
    private var lock: PowerManager.WakeLock? = null
    private var app: Context? = null
    private var open = 0

    /** Once, with the app's context. */
    @Synchronized
    fun init(ctx: Context) { app = ctx.applicationContext }

    @Synchronized
    fun start() {
        open++
        val l = lock ?: (app?.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneBridge:remote")?.apply { setReferenceCounted(false) }
            ?.also { lock = it })
        // An hour at most per hold: a connection that never says goodbye cannot keep it forever.
        l?.acquire(3_600_000L)
    }

    @Synchronized
    fun end() {
        open = maxOf(0, open - 1)
        if (open == 0) lock?.let { if (it.isHeld) it.acquire(LINGER_MS) }
    }
}
