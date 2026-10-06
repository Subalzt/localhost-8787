package dev.periy.bridge.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import dev.periy.bridge.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** What the map asks of a phone: ring, stop, lost (with a message and a number to call), found. */
@Serializable
data class FindCmd(val action: String = "", val message: String = "", val contact: String = "", val target: String = "")

/** A phone's lost mode, as it shows it (and the map). */
@Serializable
data class LostState(val on: Boolean = false, val message: String = "", val contact: String = "", val since: Long = 0)

/**
 * Finding this phone, from the map on any of your devices: it rings at full alarm volume (through
 * silent mode, as an alarm does) with vibration, for two minutes or until stopped; in lost mode it
 * shows your message and a number to call over the lock screen, rings, and says where it is more
 * often. The lock screen itself cannot be written on by an app, so the message comes as a call-like
 * screen that shows over it (ui/LostActivity.kt), and as a notification that stays.
 */
class FindMe(ctx: Context) {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("find-me", Context.MODE_PRIVATE)
    private var player: MediaPlayer? = null
    private var volumeWas = -1

    private val _ringing = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = _ringing.asStateFlow()

    private val _lost = MutableStateFlow(
        LostState(prefs.getBoolean("on", false), prefs.getString("message", "").orEmpty(), prefs.getString("contact", "").orEmpty(), prefs.getLong("since", 0)),
    )
    val lost: StateFlow<LostState> = _lost.asStateFlow()

    fun handle(c: FindCmd) = main.post {
        when (c.action) {
            "ring" -> ring(true)
            "stop" -> ring(false)
            "lost" -> setLost(LostState(true, c.message.take(300), c.contact.take(40), System.currentTimeMillis()))
            "found" -> { setLost(LostState()); ring(false) }
        }
    }

    /** Rings at the alarm stream's full volume, looping, for two minutes at most. */
    private fun ring(on: Boolean) {
        if (!on) {
            player?.let { runCatching { it.stop(); it.release() } }
            player = null
            val am = app.getSystemService(AudioManager::class.java)
            if (volumeWas >= 0) runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, volumeWas, 0) }
            volumeWas = -1
            runCatching { app.getSystemService(VibratorManager::class.java).defaultVibrator.cancel() }
            _ringing.value = false
            return
        }
        if (player != null) return
        val am = app.getSystemService(AudioManager::class.java)
        runCatching {
            volumeWas = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(app, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        runCatching {
            app.getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0))
        }
        _ringing.value = true
        main.postDelayed({ ring(false) }, RING_MS)
    }

    /** Told when lost mode goes on or off (the map, linked phones). */
    var onLost: () -> Unit = {}

    private fun setLost(s: LostState) {
        _lost.value = s
        onLost()
        prefs.edit().putBoolean("on", s.on).putString("message", s.message).putString("contact", s.contact).putLong("since", s.since).apply()
        if (!s.on) return
        ring(true)
        // Its screen over the lock screen, straight away (the app may open over others).
        runCatching { app.startActivity(Intent(app, dev.periy.bridge.ui.LostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    companion object {
        private const val RING_MS = 120_000L
    }
}
