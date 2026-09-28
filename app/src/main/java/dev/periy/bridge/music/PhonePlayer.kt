package dev.periy.bridge.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.periy.bridge.server.MusicLibrary
import dev.periy.bridge.server.SyncPlay
import dev.periy.bridge.server.TrackDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Music played on the phone itself, from the Music tab: its own speaker or headphones, in the
 * background, with the controls in the notification and on the lock screen (MusicService).
 *
 * The queue is kept in the order it plays: shuffle reorders it behind the song playing (and
 * off again puts it back), so what the queue shows is what comes next. Each song plays in a
 * MediaPlayer straight from where it lives on the phone; the next one is prepared while the
 * current one plays and handed over the instant it ends, so an album that runs on plays on.
 *
 * Everything here runs on the main thread, where MediaPlayer calls back.
 */
class PhonePlayer(ctx: Context, private val music: MusicLibrary) {

    enum class Repeat { OFF, ALL, ONE }

    data class State(
        val queue: List<TrackDto> = emptyList(),
        val index: Int = -1,
        val playing: Boolean = false,
        val shuffle: Boolean = false,
        val repeat: Repeat = Repeat.OFF,
        /** Where the song was at [at] (elapsedRealtime); while [playing] it has moved on since. */
        val positionMs: Long = 0,
        val at: Long = 0,
        val durationMs: Long = 0,
        /** Bumped by every change, so a notification or a screen can tell a new state from an old one. */
        val seq: Long = 0,
    ) {
        val current: TrackDto? get() = queue.getOrNull(index)
        val hasNext: Boolean get() = index + 1 < queue.size || (repeat == Repeat.ALL && queue.isNotEmpty())
        fun positionNow(now: Long = SystemClock.elapsedRealtime()): Long {
            val p = if (playing) positionMs + (now - at) else positionMs
            return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
        }
    }

    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val prefs = app.getSharedPreferences("player", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** The queue as it was before shuffle, to go back to. */
    private var unshuffled: List<TrackDto> = emptyList()

    private var mp: MediaPlayer? = null
    private var prepared = false
    /** The song after, prepared and chained to [mp]; for [nextFor]. */
    private var nextMp: MediaPlayer? = null
    private var nextFor: Long = -1
    private var nextReady = false
    /** A seek asked for before the song was ready. */
    private var pendingSeek = -1L
    private var wantPlay = false
    private var restored = false

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    // ------------------------------------------------------------------ focus and headphones

    /** Paused by a call or another app for a moment: carry on when it gives the sound back. */
    private var resumeOnGain = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attrs)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { if (_state.value.playing) { resumeOnGain = true; pause(keepFocus = true) } }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mp?.setVolume(DUCK, DUCK)
                AudioManager.AUDIOFOCUS_GAIN -> {
                    mp?.setVolume(1f, 1f)
                    if (resumeOnGain) { resumeOnGain = false; resume() }
                }
            }
        }, main)
        .build()
    private var hasFocus = false

    /** Headphones pulled out: stop at once rather than play out loud. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }
    private var noisyRegistered = false

    // ------------------------------------------------------------------ the queue

    /** Plays [list] from [start]; with [shuffle], that song first and the rest shuffled behind it. */
    fun play(list: List<TrackDto>, start: Int, shuffle: Boolean = false) {
        if (list.isEmpty()) return
        val from = start.coerceIn(0, list.lastIndex)
        unshuffled = list
        val (q, i) = if (shuffle) shuffled(list, from) else list to from
        restored = true
        set { it.copy(queue = q, index = i, shuffle = shuffle, positionMs = 0, durationMs = q[i].durationMs) }
        load(autoplay = true)
    }

    /** Plays the song at [index] of the queue now. */
    fun skipTo(index: Int) {
        val s = _state.value
        if (index !in s.queue.indices) return
        set { it.copy(index = index, positionMs = 0, durationMs = s.queue[index].durationMs) }
        load(autoplay = true)
    }

    fun next() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        val n = when {
            s.index + 1 < s.queue.size -> s.index + 1
            s.repeat == Repeat.ALL -> 0
            else -> return
        }
        skipTo(n)
    }

    /** Early in a song, the one before; later, this one from the start, as every player does. */
    fun previous() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        if (s.positionNow() > RESTART_AFTER_MS || (s.index == 0 && s.repeat != Repeat.ALL)) { seekTo(0); return }
        skipTo(if (s.index > 0) s.index - 1 else s.queue.lastIndex)
    }

    /** Whether previous goes to another song, rather than back to the start of this one. */
    fun previousChangesSong(): Boolean {
        val s = _state.value
        return s.queue.size > 1 && s.positionNow() <= RESTART_AFTER_MS && (s.index > 0 || s.repeat == Repeat.ALL)
    }

    fun toggle() { if (_state.value.playing) pause() else resume() }

    fun resume() {
        val s = _state.value
        if (s.current == null) return
        if (mp == null) { load(autoplay = true, at = s.positionMs); return }
        wantPlay = true
        if (!prepared) return
        if (!gainFocus()) return
        runCatching { mp?.start() }
        set { it.copy(playing = true, positionMs = currentPos(), at = now()) }
    }

    fun pause(keepFocus: Boolean = false) {
        wantPlay = false
        if (prepared) runCatching { mp?.pause() }
        set { it.copy(playing = false, positionMs = currentPos(), at = now()) }
        if (!keepFocus) dropFocus()
        unregisterNoisy()
        save()
    }

    fun seekTo(ms: Long) {
        val s = _state.value
        val target = if (s.durationMs > 0) ms.coerceIn(0, s.durationMs) else ms.coerceAtLeast(0)
        if (prepared) runCatching { mp?.seekTo(target, MediaPlayer.SEEK_CLOSEST) }
        else pendingSeek = target
        set { it.copy(positionMs = target, at = now()) }
    }

    fun setRepeat(r: Repeat) {
        set { it.copy(repeat = r) }
        chainNext()
    }

    fun cycleRepeat() = setRepeat(when (_state.value.repeat) { Repeat.OFF -> Repeat.ALL; Repeat.ALL -> Repeat.ONE; Repeat.ONE -> Repeat.OFF })

    /** Shuffles what comes after the song playing; off again, the queue goes back to its order, from this song. */
    fun setShuffle(on: Boolean) {
        val s = _state.value
        val cur = s.current ?: return
        if (on == s.shuffle) return
        if (on) {
            unshuffled = s.queue
            val (q, i) = shuffled(s.queue, s.index)
            set { it.copy(queue = q, index = i, shuffle = true) }
        } else {
            val back = unshuffled.ifEmpty { s.queue }
            val i = back.indexOfFirst { it.id == cur.id }.coerceAtLeast(0)
            set { it.copy(queue = back, index = i, shuffle = false) }
        }
        chainNext()
    }

    /** Takes a song off the queue; the one playing goes on to the next. */
    fun remove(index: Int) {
        val s = _state.value
        if (index !in s.queue.indices) return
        val gone = s.queue[index]
        val q = s.queue.toMutableList().apply { removeAt(index) }
        unshuffled = unshuffled.filterNot { it.id == gone.id && it === gone }
        if (q.isEmpty()) { clear(); return }
        when {
            index < s.index -> set { it.copy(queue = q, index = s.index - 1) }
            index > s.index -> set { it.copy(queue = q) }
            else -> {
                val i = index.coerceAtMost(q.lastIndex)
                set { it.copy(queue = q, index = i, positionMs = 0, durationMs = q[i].durationMs) }
                load(autoplay = s.playing)
                return
            }
        }
        chainNext()
    }

    /** Moves a song within the queue; the one playing keeps playing, wherever it lands. */
    fun move(from: Int, to: Int) {
        val s = _state.value
        if (from !in s.queue.indices || to !in s.queue.indices || from == to) return
        val q = s.queue.toMutableList()
        q.add(to, q.removeAt(from))
        val i = when (s.index) {
            from -> to
            in (minOf(from, to)..maxOf(from, to)) -> if (from < to) s.index - 1 else s.index + 1
            else -> s.index
        }
        set { it.copy(queue = q, index = i) }
        chainNext()
    }

    /** Plays these next, after the song playing. */
    fun playNext(tracks: List<TrackDto>) {
        val s = _state.value
        if (s.current == null) { play(tracks, 0); return }
        val q = s.queue.toMutableList().apply { addAll(s.index + 1, tracks) }
        unshuffled = unshuffled + tracks
        set { it.copy(queue = q) }
        chainNext()
    }

    /** Stops and empties the queue: the mini player goes. */
    fun clear() {
        wantPlay = false
        releasePlayers()
        dropFocus()
        unregisterNoisy()
        unshuffled = emptyList()
        set { State(repeat = it.repeat, seq = it.seq) }
        prefs.edit().remove(K_IDS).remove(K_INDEX).remove(K_POS).apply()
    }

    /**
     * The queue from last time, paused where it stopped, once the library is readable. The
     * player comes back as it was left, with nothing playing until play is pressed.
     */
    fun restore(library: List<TrackDto>) {
        if (restored || _state.value.current != null) return
        restored = true
        val ids = prefs.getString(K_IDS, null)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return
        val byId = library.associateBy { it.id }
        val q = ids.mapNotNull { byId[it] }
        if (q.isEmpty()) return
        val i = prefs.getInt(K_INDEX, 0).coerceIn(0, q.lastIndex)
        unshuffled = q
        set {
            it.copy(
                queue = q, index = i, playing = false,
                shuffle = prefs.getBoolean(K_SHUFFLE, false),
                repeat = runCatching { Repeat.valueOf(prefs.getString(K_REPEAT, "OFF")!!) }.getOrDefault(Repeat.OFF),
                positionMs = prefs.getLong(K_POS, 0).coerceIn(0, q[i].durationMs.coerceAtLeast(0)),
                durationMs = q[i].durationMs,
            )
        }
    }

    // ------------------------------------------------------------------ playing

    private fun load(autoplay: Boolean, at: Long = 0) {
        val s = _state.value
        val t = s.current ?: return
        wantPlay = autoplay
        pendingSeek = if (at > 0) at else -1
        // The song after is already prepared: take it over rather than open the file again.
        val handover = nextMp?.takeIf { nextFor == t.id && nextReady && at <= 0 }
        releaseCurrent()
        if (handover != null) {
            nextMp = null; nextFor = -1
            mp = handover
            handover.setNextMediaPlayer(null)
            wire(handover, t)
            prepared = true
            onReady(t)
            return
        }
        releaseNext()
        val p = newPlayer()
        mp = p
        prepared = false
        wire(p, t)
        runCatching {
            p.setDataSource(app, music.uri(t.id))
            p.prepareAsync()
        }.onFailure { failed(t, it) }
        if (autoplay) set { it.copy(playing = true, positionMs = 0, at = now()) }
    }

    private fun newPlayer() = MediaPlayer().apply {
        setAudioAttributes(attrs)
        setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
    }

    private fun wire(p: MediaPlayer, t: TrackDto) {
        p.setOnPreparedListener { if (p === mp) { prepared = true; onReady(t) } }
        p.setOnCompletionListener { if (p === mp) completed() }
        p.setOnErrorListener { _, what, extra ->
            if (p === mp) failed(t, IllegalStateException("MediaPlayer error $what/$extra"))
            true
        }
    }

    private fun onReady(t: TrackDto) {
        val p = mp ?: return
        val dur = runCatching { p.duration.toLong() }.getOrDefault(0L).takeIf { it > 0 } ?: t.durationMs
        if (pendingSeek > 0) { runCatching { p.seekTo(pendingSeek, MediaPlayer.SEEK_CLOSEST) }; pendingSeek = -1 }
        if (wantPlay && gainFocus()) {
            runCatching { p.start() }
            set { it.copy(playing = true, durationMs = dur, positionMs = currentPos(), at = now()) }
        } else {
            set { it.copy(playing = false, durationMs = dur, positionMs = currentPos(), at = now()) }
        }
        chainNext()
        save()
    }

    /**
     * The current song ended. With the next one chained, it is already sounding: only the
     * books move on. Otherwise, the next is loaded, or it stops at the end of the queue.
     */
    private fun completed() {
        val s = _state.value
        if (s.repeat == Repeat.ONE) {
            runCatching { mp?.seekTo(0); mp?.start() }
            set { it.copy(positionMs = 0, at = now(), playing = true) }
            return
        }
        val n = when {
            s.index + 1 < s.queue.size -> s.index + 1
            s.repeat == Repeat.ALL -> 0
            else -> -1
        }
        if (n < 0) {
            wantPlay = false
            set { it.copy(playing = false, positionMs = 0, at = now()) }
            runCatching { mp?.seekTo(0) }
            dropFocus()
            unregisterNoisy()
            save()
            return
        }
        val chained = nextMp?.takeIf { nextFor == s.queue[n].id && nextReady }
        if (chained != null) {
            // MediaPlayer has started it by itself, at the very end of the one before.
            val old = mp
            mp = chained
            nextMp = null; nextFor = -1
            wire(chained, s.queue[n])
            prepared = true
            old?.let { o -> runCatching { o.setNextMediaPlayer(null) }; runCatching { o.release() } }
            val dur = runCatching { chained.duration.toLong() }.getOrDefault(s.queue[n].durationMs)
            set { it.copy(index = n, playing = true, positionMs = 0, at = now(), durationMs = dur) }
            chainNext()
            save()
        } else {
            set { it.copy(index = n, positionMs = 0, durationMs = s.queue[n].durationMs) }
            load(autoplay = true)
        }
    }

    /** Prepares the song after the current one and chains it on, for a handover without a gap. */
    private fun chainNext() {
        val cur = mp ?: return
        if (!prepared) return
        val s = _state.value
        val n = when {
            s.repeat == Repeat.ONE -> -1
            s.index + 1 < s.queue.size -> s.index + 1
            s.repeat == Repeat.ALL && s.queue.size > 1 -> 0
            else -> -1
        }
        val want = s.queue.getOrNull(n)
        if (want == null) { releaseNext(); runCatching { cur.setNextMediaPlayer(null) }; return }
        if (want.id == nextFor && nextMp != null) {
            if (nextReady) runCatching { cur.setNextMediaPlayer(nextMp) }
            return
        }
        releaseNext()
        runCatching { cur.setNextMediaPlayer(null) }
        val p = newPlayer()
        nextMp = p
        nextFor = want.id
        nextReady = false
        p.setOnPreparedListener {
            if (p !== nextMp) return@setOnPreparedListener
            nextReady = true
            if (mp === cur && prepared) runCatching { cur.setNextMediaPlayer(p) }
        }
        p.setOnErrorListener { _, _, _ -> if (p === nextMp) releaseNext(); true }
        runCatching { p.setDataSource(app, music.uri(want.id)); p.prepareAsync() }.onFailure { releaseNext() }
    }

    private fun failed(t: TrackDto, e: Throwable) {
        Log.w(TAG, "Cannot play ${t.title}", e)
        releaseCurrent()
        // A file the phone cannot play is skipped, as the page skips one the browser cannot.
        main.postDelayed({
            val s = _state.value
            if (s.current?.id == t.id && s.index + 1 < s.queue.size) next()
            else if (s.current?.id == t.id) set { it.copy(playing = false) }
        }, 600)
    }

    private fun currentPos(): Long =
        if (prepared) runCatching { mp?.currentPosition?.toLong() }.getOrNull() ?: _state.value.positionMs
        else _state.value.positionMs

    private fun releaseCurrent() {
        mp?.let { runCatching { it.setNextMediaPlayer(null) }; runCatching { it.release() } }
        mp = null
        prepared = false
    }

    private fun releaseNext() {
        nextMp?.let { runCatching { it.release() } }
        nextMp = null
        nextFor = -1
        nextReady = false
    }

    private fun releasePlayers() { releaseCurrent(); releaseNext() }

    private fun gainFocus(): Boolean {
        // The phone's speaker cannot play in a group and on its own at once: it leaves the group.
        SyncPlay.state.takeIf { "phone" in it.members }?.let { SyncPlay.set(it.copy(members = it.members - "phone")) }
        registerNoisy()
        MusicService.ensure(app)
        if (hasFocus) return true
        hasFocus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun dropFocus() {
        if (!hasFocus) return
        audio.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        app.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { app.unregisterReceiver(noisy) }
        noisyRegistered = false
    }

    // ------------------------------------------------------------------ bookkeeping

    private inline fun set(f: (State) -> State) {
        val next = f(_state.value)
        _state.value = next.copy(seq = _state.value.seq + 1)
    }

    /** The queue and where it is, for next time. */
    private fun save() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        prefs.edit()
            .putString(K_IDS, s.queue.take(MAX_SAVED).joinToString(",") { it.id.toString() })
            .putInt(K_INDEX, s.index.coerceAtMost(MAX_SAVED - 1))
            .putLong(K_POS, s.positionNow())
            .putBoolean(K_SHUFFLE, s.shuffle)
            .putString(K_REPEAT, s.repeat.name)
            .apply()
    }

    private fun shuffled(list: List<TrackDto>, keep: Int): Pair<List<TrackDto>, Int> {
        val first = list[keep]
        val rest = list.filterIndexed { i, _ -> i != keep }.shuffled()
        return (listOf(first) + rest) to 0
    }

    private fun now() = SystemClock.elapsedRealtime()

    private companion object {
        const val TAG = "PhonePlayer"
        const val DUCK = 0.25f
        const val RESTART_AFTER_MS = 3_000L
        const val MAX_SAVED = 2_000
        const val K_IDS = "queue"
        const val K_INDEX = "index"
        const val K_POS = "pos"
        const val K_SHUFFLE = "shuffle"
        const val K_REPEAT = "repeat"
    }
}
