package dev.periy.bridge.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import dev.periy.bridge.R
import dev.periy.bridge.container
import dev.periy.bridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the phone's music playing with Localhost 8787 in the background, and puts what is playing
 * where Android shows it: the notification, the lock screen, a headset's buttons, a watch.
 *
 * It lives as long as there is a queue. Playing, it is a foreground service; paused, the
 * notification stays (and can be swiped away) but no longer holds the phone awake.
 */
class MusicService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession
    private val player get() = container.player
    private var foreground = false

    /** The cover for the notification and the lock screen: by album, loaded once. */
    private var artFor: String? = null
    private var art: Bitmap? = null
    private var artJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        session = MediaSession(this, "Localhost 8787").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = player.resume()
                override fun onPause() = player.pause()
                override fun onSkipToNext() = player.next()
                override fun onSkipToPrevious() = player.previous()
                override fun onSeekTo(pos: Long) = player.seekTo(pos)
                override fun onStop() = player.pause()
            })
            setSessionActivity(openApp())
            isActive = true
        }
        // Android gives a started service five seconds to say what it is showing.
        goForeground(build(player.state.value))
        scope.launch { player.state.collect { show(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> player.toggle()
            ACTION_NEXT -> player.next()
            ACTION_PREV -> player.previous()
            ACTION_DISMISS -> { player.pause(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    /** Swiped out of recents while paused: nothing is playing, so nothing needs to stay. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.state.value.playing) stopSelf()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        session.isActive = false
        session.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun show(s: PhonePlayer.State) {
        val t = s.current
        if (t == null) { stopSelf(); return }
        if (artFor != t.albumId) loadArt(t.albumId)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs)
                .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
                )
                .setState(
                    if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    s.positionMs, if (s.playing) s.speed else 0f, s.at,
                )
                .build()
        )
        val n = build(s)
        if (s.playing) goForeground(n)
        else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
            if (foreground) { stopForeground(STOP_FOREGROUND_DETACH); foreground = false }
        }
    }

    private fun goForeground(n: Notification) {
        // Resumed from the lock screen or a headset, Android allows it; should a phone refuse,
        // the music still plays and the notification still shows, only without the guarantee.
        runCatching { startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
            .onSuccess { foreground = true }
            .onFailure { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
    }

    private fun build(s: PhonePlayer.State): Notification {
        val t = s.current
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(t?.title ?: "Music")
            .setContentText(t?.let { it.artist + " · " + it.album } ?: "")
            .setLargeIcon(art)
            .setContentIntent(openApp())
            .setDeleteIntent(action(ACTION_DISMISS, 4))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(s.playing)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_music_prev), "Previous", action(ACTION_PREV, 1)).build())
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, if (s.playing) R.drawable.ic_music_pause else R.drawable.ic_music_play),
                    if (s.playing) "Pause" else "Play", action(ACTION_TOGGLE, 2),
                ).build()
            )
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_music_next), "Next", action(ACTION_NEXT, 3)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun loadArt(albumId: String) {
        artFor = albumId
        art = null
        artJob?.cancel()
        artJob = scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                albumId.toLongOrNull()?.let { container.music.cover(it) }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            if (artFor != albumId) return@launch
            art = bmp
            show(player.state.value)
        }
    }

    private fun action(name: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, MusicService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Tapping what is playing opens the app with the player open. */
    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).setAction(ACTION_OPEN_PLAYER).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_music_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_music_desc)
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "dev.periy.bridge.OPEN_PLAYER"
        private const val ACTION_TOGGLE = "dev.periy.bridge.music.TOGGLE"
        private const val ACTION_NEXT = "dev.periy.bridge.music.NEXT"
        private const val ACTION_PREV = "dev.periy.bridge.music.PREV"
        private const val ACTION_DISMISS = "dev.periy.bridge.music.DISMISS"
        private const val CHANNEL_ID = "music"
        private const val NOTIFICATION_ID = 8788

        @Volatile private var running = false

        /** Started as playing starts; it goes by itself when the queue does. */
        fun ensure(ctx: Context) {
            if (running) return
            runCatching { ctx.startForegroundService(Intent(ctx, MusicService::class.java)) }
        }
    }
}
