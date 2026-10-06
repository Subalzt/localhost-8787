package dev.periy.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.periy.bridge.R
import dev.periy.bridge.container
import dev.periy.bridge.net.NetInfo
import dev.periy.bridge.server.Transfers
import dev.periy.bridge.server.canReadPhotos
import dev.periy.bridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

private const val TAG = "BridgeService"

/**
 * Hosts [dev.periy.bridge.server.BridgeServer] for as long as the bridge is switched on.
 *
 * The service exists to keep the process alive and the radio awake; it owns no protocol
 * logic. Two things it does own are easy to get wrong:
 *
 * - **The wake locks are refcounted against live transfers, not against the service.**
 *   Holding a partial wake lock for an idle listener would flatten the battery for
 *   nothing. Holding none during a 90-minute upload would let the CPU idle out mid-write.
 * - **The locks linger briefly after the last transfer.** Between two tus PATCH requests
 *   there is a gap of a few hundred milliseconds; releasing and re-acquiring across every
 *   gap is both pointless and measurably worse than holding.
 */
class BridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var releaseJob: Job? = null
    private var lastNotifiedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    /** New screenshots onto the shared clipboard, while the server runs, app open or closed. */
    private var screenshots: dev.periy.bridge.server.ScreenshotWatcher? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Notification access can be allowed yet not running (after an update, or once HyperOS has
        // stopped it); ask Android to start it again so the laptop gets the phone's notifications.
        if (dev.periy.bridge.server.Notifs.service == null) runCatching {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(this, dev.periy.bridge.server.NotifyListener::class.java)
            )
        }
    }

    private fun watchScreenshots() {
        if (screenshots != null) return
        val app = applicationContext
        screenshots = dev.periy.bridge.server.ScreenshotWatcher(
            app, container.clipboard,
            enabled = { container.prefs.clipSync && container.prefs.screenshotClip && canReadPhotos(app) },
            onNew = {
                // The phone's own clipboard too, ready to paste in any app here as well.
                val c = container.clipboard
                val m = c.meta.value
                c.blob()?.let { dev.periy.bridge.server.SystemClipboard.writeFile(app, it, m.name, m.mime) }
            },
        ).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            // Allow / Deny tapped on a connection-request notification. The service is
            // already in the foreground whenever a request can exist, so this is handled
            // and returned from without touching the foreground state.
            ACTION_APPROVE, ACTION_DENY -> {
                val id = intent.getStringExtra(EXTRA_REQUEST)
                if (id != null) {
                    if (intent.action == ACTION_APPROVE) container.pairing.approve(id)
                    else container.pairing.deny(id)
                }
                if (container.server?.isRunning != true) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
        }

        startForegroundCompat(buildNotification())
        container.where.start()
        // Camera mode left on: back on (from the background it wakes the phone a moment to be let).
        if (container.cameras.state.value.on) container.cameras.startService()

        if (container.server?.isRunning != true) {
            scope.launch {
                runCatching {
                    container.newServer().start()
                    // Let other phones running Localhost 8787 find this one on the network.
                    container.peers.advertise(container.prefs.port)
                    // Links to other phones made one way only are completed, and old ones renewed.
                    container.peers.online()
                    container.stun.start()
                }
                    .onFailure {
                        Log.e(TAG, "Server failed to start", it)
                        stopEverything()
                    }
                _running.value = container.server?.isRunning == true
                if (_running.value) {
                    watchScreenshots()
                    // After an update Android does not always reconnect the notification
                    // listener by itself; asking costs nothing when it already is.
                    if (!dev.periy.bridge.server.Notifs.connected) runCatching {
                        android.service.notification.NotificationListenerService.requestRebind(
                            android.content.ComponentName(applicationContext, dev.periy.bridge.server.NotifyListener::class.java)
                        )
                    }
                    dev.periy.bridge.server.ClipWatch.ensure(applicationContext, container.prefs.clipSync)
                }
                refreshNotification()
            }
        }

        observeTransfers()
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        screenshots?.stop()
        screenshots = null
        dev.periy.bridge.server.ClipWatch.stop(applicationContext)
        container.direct.stop()
        container.peers.stopAdvertising()
        container.stun.stop()
        container.stopServer()
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Android 15+ calls this when a timed foreground service type runs out of budget.
     *
     * With the specialUse type this should never fire. It is implemented anyway because
     * the alternative to a clean shutdown is a system-generated ANR, and because a build
     * switched back to dataSync (see the manifest comment) will hit it after six hours.
     */
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "Foreground service budget exhausted; shutting down cleanly")
        stopEverything()
    }

    private fun stopEverything() {
        _running.value = false
        container.direct.stop()
        container.peers.stopAdvertising()
        container.stun.stop()
        container.stopServer()
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ foreground

    private fun startForegroundCompat(notification: Notification) {
        // On API 34+ start as specialUse only, so the running service is never charged
        // against the dataSync budget even though the manifest declares both types.
        // Below 34 there are no FGS timeouts at all, so dataSync is free to use and is
        // the value those platform versions actually understand.
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        // With location allowed, location too: the phone's position keeps coming, precisely, with the
        // app in the background (server/Where.kt).
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && container.where.allowed()) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else base
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
        }.onFailure {
            Log.e(TAG, "startForeground rejected", it)
            stopSelf()
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_server_name),
            // MIN: Android requires it while the server runs; folded away, no icon, never a sound.
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.channel_server_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
        // Notifications of earlier versions this one no longer posts, or posts quieter: gone.
        OLD_CHANNELS.forEach { runCatching { nm.deleteNotificationChannel(it) } }
    }

    private fun buildNotification(): Notification {
        val prefs = container.prefs
        val address = NetInfo.preferred()
        val url = address?.url(prefs.port) ?: "Waiting for a network"
        val active = Transfers.flow.value.filter {
            it.state == dev.periy.bridge.server.TransferState.ACTIVE
        }

        val title = if (active.isEmpty()) "Localhost 8787 is ready" else {
            val rate = active.sumOf { it.bytesPerSec }
            "Transferring ${active.size} file${if (active.size == 1) "" else "s"} - ${formatRate(rate)}"
        }
        val connected = container.devices.live.value.size
        val body = buildString {
            append(url)
            if (connected > 0) append("\n$connected computer${if (connected == 1) "" else "s"} connected")
            container.storage.destinationLabel?.let { append("\nSaving to ").append(it) }
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(url)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .also { builder ->
                active.singleOrNull()?.let { t ->
                    if (t.total > 0) {
                        builder.setProgress(1000, (t.fraction * 1000).toInt(), false)
                    }
                }
            }
            .build()
    }

    private fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTIF_ID, buildNotification()) }
    }

    // ------------------------------------------------------------------ locks

    /** Started once: every start command calls this, and each call used to add two more collectors. */
    private var observingTransfers = false

    private fun observeTransfers() {
        if (observingTransfers) return
        observingTransfers = true
        scope.launch {
            Transfers.activeCount.distinctUntilChanged().collect { count ->
                if (count > 0) acquireLocks() else scheduleRelease()
            }
        }
        scope.launch {
            Transfers.flow.collect {
                // Rate-limit notification updates. Posting on every progress tick is a
                // measurable amount of binder traffic during a sustained transfer.
                val now = System.currentTimeMillis()
                if (now - lastNotifiedAt >= NOTIFY_EVERY_MS) {
                    lastNotifiedAt = now
                    refreshNotification()
                }
            }
        }
    }

    private fun acquireLocks() {
        releaseJob?.cancel()
        releaseJob = null

        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneBridge:transfer")
                ?.apply { setReferenceCounted(false); acquire(MAX_LOCK_MS) }
        }
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            // HIGH_PERF is deprecated in favour of LOW_LATENCY, but LOW_LATENCY optimises
            // for latency at the cost of throughput. Bulk transfer wants the opposite,
            // and the deprecated mode is still honoured.
            @Suppress("DEPRECATION")
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PhoneBridge:transfer")
                ?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(LOCK_LINGER_MS)
            releaseLocks()
            refreshNotification()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        private const val CHANNEL_ID = "bridge_server_quiet"
        private val OLD_CHANNELS = listOf(
            "bridge_server", "camera-mode", "calls", "messages", "calls-missed", "laptop-offline", "camera-alerts", "find-me", "places",
        )
        private const val NOTIF_ID = 1001
        private const val ACTION_STOP = "dev.periy.bridge.STOP"
        const val ACTION_APPROVE = "dev.periy.bridge.PAIR_APPROVE"
        const val ACTION_DENY = "dev.periy.bridge.PAIR_DENY"
        const val EXTRA_REQUEST = "request_id"
        private const val NOTIFY_EVERY_MS = 1_000L
        private const val LOCK_LINGER_MS = 30_000L

        /**
         * Safety net on the wake lock. A 10 GB transfer over a slow link can legitimately
         * run for hours, so this is generous; it exists only so a leaked lock cannot
         * outlive the day.
         */
        private const val MAX_LOCK_MS = 6L * 60 * 60 * 1000

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(ctx: Context) {
            val intent = Intent(ctx, BridgeService::class.java)
            ctx.startForegroundService(intent)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_STOP))
        }
    }
}

fun formatRate(bytesPerSec: Long): String = when {
    bytesPerSec <= 0 -> "-"
    bytesPerSec < 1024 -> "$bytesPerSec B/s"
    bytesPerSec < 1024 * 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
    else -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024))
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
}
