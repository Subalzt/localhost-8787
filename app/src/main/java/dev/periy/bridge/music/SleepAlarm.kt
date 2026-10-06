package dev.periy.bridge.music

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.periy.bridge.server.MusicLibrary
import dev.periy.bridge.server.TrackDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Calendar

/**
 * The sleep timer for the phone's music: after so many minutes, or at the end of this song, the
 * music fades out over its last [FADE_MS] and stops. Nothing to show for it but the Now Playing
 * button (no notification); the player's wake lock keeps it ticking with the screen off.
 */
class SleepTimer(private val player: PhonePlayer) {
    /** [endsAt]: elapsedRealtime it stops at (0: not by the clock); [endOfSong]: at the end of this song. */
    data class State(val endsAt: Long = 0, val endOfSong: Boolean = false) {
        val on: Boolean get() = endsAt > 0 || endOfSong
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val main = Handler(Looper.getMainLooper())
    private val tick = Runnable { step() }

    init { player.onStoppedAfter = { _state.value = State() } }

    fun start(minutes: Int) = startMs(minutes * 60_000L)

    fun startMs(ms: Long) {
        player.stopAfterThis = false
        _state.value = State(endsAt = SystemClock.elapsedRealtime() + ms)
        step()
    }

    /** Stops when this song ends: no fade, the song's own ending is the fade. */
    fun endOfSong() {
        main.removeCallbacks(tick)
        player.setFade(1f)
        player.stopAfterThis = true
        _state.value = State(endOfSong = true)
    }

    fun cancel() {
        main.removeCallbacks(tick)
        player.stopAfterThis = false
        player.setFade(1f)
        _state.value = State()
    }

    /** Milliseconds left, or -1 when it stops with the song. */
    fun leftMs(): Long {
        val s = _state.value
        return if (s.endsAt > 0) (s.endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) else -1
    }

    private fun step() {
        main.removeCallbacks(tick)
        val s = _state.value
        if (s.endsAt <= 0) return
        val left = s.endsAt - SystemClock.elapsedRealtime()
        if (left <= 0) {
            player.pause()
            player.setFade(1f)
            _state.value = State()
            Log.i(TAG, "sleep timer: stopped the music")
            return
        }
        // Down by ear: the square of the time left, so the last seconds are the quiet ones.
        val f = (left.toFloat() / FADE_MS).coerceIn(0f, 1f)
        player.setFade(f * f)
        main.postDelayed(tick, if (left <= FADE_MS) 250 else minOf(left - FADE_MS, 30_000))
    }

    companion object {
        private const val TAG = "SleepTimer"
        const val FADE_MS = 30_000L
    }
}

/**
 * An alarm: at [hour]:[minute] on the [days] set (bit 0 Monday to bit 6 Sunday; none, once), a
 * song of the user's own ([trackId]; -1, the favourites shuffled, or the whole library when
 * there are none) fading in over [fadeSec] on the alarm's volume.
 */
@Serializable
data class AlarmDto(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Int = 0,
    val on: Boolean = true,
    val trackId: Long = -1,
    val trackTitle: String = "",
    val fadeSec: Int = 60,
)

/**
 * The phone's alarms, rung by Android's own alarm clock (exact, through Doze, the clock in the
 * status bar): the next one is set with AlarmManager.setAlarmClock, whose firing opens the alarm
 * screen over the lock screen (ui/AlarmActivity.kt) straight away, so no notification is needed.
 * Set again after a reboot or an update (AlarmBoot) and whenever the app starts.
 */
class Alarms(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("alarms", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = ListSerializer(AlarmDto.serializer())

    private val _list = MutableStateFlow(
        prefs.getString(K_LIST, null)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() }.orEmpty()
    )
    val list: StateFlow<List<AlarmDto>> = _list

    /** A snooze waiting: when (wall clock) and which alarm; 0 when none. */
    private val _snoozeAt = MutableStateFlow(prefs.getLong(K_SNOOZE_AT, 0))
    val snoozeAt: StateFlow<Long> = _snoozeAt

    /** Adds or changes an alarm; its id. */
    fun put(a: AlarmDto): Int {
        val id = if (a.id > 0) a.id else (_list.value.maxOfOrNull { it.id } ?: 0) + 1
        save(_list.value.filter { it.id != id } + a.copy(id = id))
        return id
    }

    fun setOn(id: Int, on: Boolean) = save(_list.value.map { if (it.id == id) it.copy(on = on) else it })

    fun remove(id: Int) = save(_list.value.filter { it.id != id })

    private fun save(l: List<AlarmDto>) {
        val sorted = l.sortedWith(compareBy({ it.hour }, { it.minute }))
        _list.value = sorted
        prefs.edit().putString(K_LIST, json.encodeToString(ser, sorted)).apply()
        arm()
    }

    fun get(id: Int): AlarmDto? = _list.value.firstOrNull { it.id == id }

    /** When [a] next rings after [from] (wall clock ms), or null when it is off. */
    fun nextTime(a: AlarmDto, from: Long = System.currentTimeMillis()): Long? {
        if (!a.on) return null
        val c = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        for (i in 0..7) {
            if (c.timeInMillis > from && (a.days == 0 || a.days and (1 shl dayBit(c)) != 0)) return c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    /** The next to ring: the alarm (null for a snooze, which carries its own) and when. */
    fun next(): Pair<AlarmDto?, Long>? {
        val now = System.currentTimeMillis()
        val best = _list.value.mapNotNull { a -> nextTime(a, now)?.let { a to it } }.minByOrNull { it.second }
        val snooze = _snoozeAt.value.takeIf { it > now }
        if (snooze != null && (best == null || snooze < best.second)) return get(prefs.getInt(K_SNOOZE_ID, 0)) to snooze
        return best?.let { it.first to it.second }
    }

    /** Sets Android's alarm clock for the next one (or clears it). */
    fun arm() {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val n = next()
        val fire = firePending(n?.first?.id ?: 0, snooze = n != null && n.second == _snoozeAt.value)
        if (n == null) { am.cancel(fire); return }
        val show = PendingIntent.getActivity(
            app, 8702, Intent(app, dev.periy.bridge.ui.MainActivity::class.java).putExtra("openAlarms", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            Log.w(TAG, "exact alarms not allowed: inexact instead")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.second, fire)
            return
        }
        am.setAlarmClock(AlarmManager.AlarmClockInfo(n.second, show), fire)
        Log.i(TAG, "next alarm at ${java.util.Date(n.second)} (${n.first?.id ?: "snooze"})")
    }

    private fun firePending(id: Int, snooze: Boolean): PendingIntent {
        val i = Intent(app, dev.periy.bridge.ui.AlarmActivity::class.java)
            .putExtra(EXTRA_ID, id).putExtra(EXTRA_SNOOZE, snooze)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        // Android 14 on: the alarm opening its screen from the background is allowed because we say so.
        val opts = if (Build.VERSION.SDK_INT >= 34) android.app.ActivityOptions.makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
        return PendingIntent.getActivity(app, 8701, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, opts)
    }

    /** It has rung: a one-off alarm turns off; a snooze is used up; then the next is set. */
    fun rang(id: Int, snooze: Boolean) {
        if (snooze) { _snoozeAt.value = 0; prefs.edit().putLong(K_SNOOZE_AT, 0).apply() }
        val a = get(id)
        if (!snooze && a != null && a.days == 0) setOn(id, false) else arm()
    }

    fun snooze(id: Int, minutes: Int = SNOOZE_MIN) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        _snoozeAt.value = at
        prefs.edit().putLong(K_SNOOZE_AT, at).putInt(K_SNOOZE_ID, id).apply()
        arm()
    }

    /** Debug builds: a snooze at exactly [at], to try the alarm clock path in seconds. */
    fun snoozeAtForTest(at: Long) {
        _snoozeAt.value = at
        prefs.edit().putLong(K_SNOOZE_AT, at).apply()
        arm()
    }

    fun cancelSnooze() {
        _snoozeAt.value = 0
        prefs.edit().putLong(K_SNOOZE_AT, 0).apply()
        arm()
    }

    companion object {
        private const val TAG = "Alarms"
        private const val K_LIST = "list"
        private const val K_SNOOZE_AT = "snoozeAt"
        private const val K_SNOOZE_ID = "snoozeId"
        const val EXTRA_ID = "alarmId"
        const val EXTRA_SNOOZE = "alarmSnooze"
        const val SNOOZE_MIN = 10

        /** Monday 0 to Sunday 6, the bits of [AlarmDto.days]. */
        fun dayBit(c: Calendar): Int = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
    }
}

/**
 * The alarm sounding: the user's own song on the alarm's volume (so it rings with the phone
 * silent, as an alarm should), from silence up to full over the alarm's fade-in, on and on
 * (the next song, or the same one again) until Stop or Snooze; it gives up after [GIVE_UP_MS].
 * Music playing on the phone is paused first.
 */
class AlarmRinger(ctx: Context, private val music: MusicLibrary, private val favourites: Favourites, private val player: PhonePlayer) {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var queue: List<TrackDto> = emptyList()
    private var index = 0
    private var startedAt = 0L
    private var fadeMs = 60_000L

    /** Muted rings for testing over adb (the debug hook), so nobody is woken. */
    @Volatile var silent = false

    private val _ringing = MutableStateFlow<TrackDto?>(null)
    /** The song ringing now; null when quiet. */
    val ringing: StateFlow<TrackDto?> = _ringing

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val fadeTick = object : Runnable {
        override fun run() {
            val p = mp ?: return
            val t = SystemClock.elapsedRealtime() - startedAt
            if (t > GIVE_UP_MS) { stop(); return }
            val f = if (fadeMs <= 0) 1f else (t.toFloat() / fadeMs).coerceIn(0f, 1f)
            // Up by ear: the square, so the first seconds are a whisper.
            val v = if (silent) 0f else (0.02f + 0.98f * f * f)
            runCatching { p.setVolume(v, v) }
            main.postDelayed(this, 250)
        }
    }

    fun ring(a: AlarmDto?) {
        stop()
        if (player.state.value.playing) player.pause()
        val all = music.tracks()
        val chosen = a?.trackId?.takeIf { it > 0 }?.let { id -> all.firstOrNull { it.id == id } }
        val fav = favourites.order.value.mapNotNull { id -> all.firstOrNull { it.id == id } }
        queue = when {
            chosen != null -> listOf(chosen) + (fav - chosen).shuffled()
            fav.isNotEmpty() -> fav.shuffled()
            else -> all.shuffled().take(30)
        }
        index = 0
        fadeMs = (a?.fadeSec ?: 60) * 1000L
        startedAt = SystemClock.elapsedRealtime()
        if (queue.isEmpty()) { Log.w(TAG, "no music to ring with"); return }
        playAt(0)
        main.post(fadeTick)
    }

    private fun playAt(i: Int) {
        val t = queue.getOrNull(i % queue.size) ?: return
        index = i
        mp?.let { runCatching { it.release() } }
        val p = MediaPlayer().apply {
            setAudioAttributes(attrs)
            setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
            setVolume(0f, 0f)
        }
        mp = p
        p.setOnPreparedListener { if (p === mp) runCatching { it.start() } }
        p.setOnCompletionListener { if (p === mp) playAt(index + 1) }
        p.setOnErrorListener { _, _, _ -> if (p === mp) main.post { playAt(index + 1) }; true }
        runCatching { p.setDataSource(app, music.uri(t.id)); p.prepareAsync() }.onFailure { Log.w(TAG, "could not play ${t.title}", it) }
        _ringing.value = t
        Log.i(TAG, "ringing with ${t.title}")
    }

    /** Where the ringing song is, to carry on from it in the player. */
    fun positionMs(): Long = runCatching { mp?.currentPosition?.toLong() }.getOrNull() ?: 0L

    fun stop() {
        main.removeCallbacks(fadeTick)
        mp?.let { runCatching { it.stop() }; runCatching { it.release() } }
        mp = null
        _ringing.value = null
    }

    /** Stops ringing and plays the same song on, in the phone's own player, from where it is. */
    fun keepListening() {
        val t = _ringing.value ?: return
        val at = positionMs()
        val rest = queue.drop(index)
        stop()
        player.play(rest, 0)
        if (at > 0) player.seekTo(at)
        if (t.id != rest.firstOrNull()?.id) Log.w(TAG, "queue moved under us")
    }

    private companion object {
        const val TAG = "AlarmRinger"
        const val GIVE_UP_MS = 20 * 60_000L
    }
}
