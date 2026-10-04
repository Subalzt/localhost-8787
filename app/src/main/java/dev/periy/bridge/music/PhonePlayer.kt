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

    /**
     * Namida's repeat modes, in its menu's order: stop after the last song; this song over and
     * over; this song [State.repeatTimes] more times, then on; the whole queue; the whole queue,
     * shuffled again each time round.
     */
    enum class Repeat { OFF, ONE, TIMES, ALL, ALL_SHUFFLE;
        /** Round again after the last song. */
        val loops: Boolean get() = this == ALL || this == ALL_SHUFFLE
        /** This song again when it ends. */
        val holds: Boolean get() = this == ONE || this == TIMES
    }

    data class State(
        val queue: List<TrackDto> = emptyList(),
        val index: Int = -1,
        val playing: Boolean = false,
        val shuffle: Boolean = false,
        val repeat: Repeat = Repeat.OFF,
        /** How many more times [Repeat.TIMES] plays this song before it goes on. */
        val repeatTimes: Int = 1,
        /** Where the song was at [at] (elapsedRealtime); while [playing] it has moved on since. */
        val positionMs: Long = 0,
        val at: Long = 0,
        val durationMs: Long = 0,
        /** The sound controls: how fast, how high and how loud it plays (1 is as it was made). */
        val speed: Float = 1f,
        val pitch: Float = 1f,
        val volume: Float = 1f,
        /** Bumped by every change, so a notification or a screen can tell a new state from an old one. */
        val seq: Long = 0,
    ) {
        val current: TrackDto? get() = queue.getOrNull(index)
        val hasNext: Boolean get() = index + 1 < queue.size || (repeat.loops && queue.isNotEmpty())
        fun positionNow(now: Long = SystemClock.elapsedRealtime()): Long {
            val p = if (playing) positionMs + ((now - at) * speed).toLong() else positionMs
            return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
        }
    }

    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val prefs = app.getSharedPreferences("player", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(State(
        speed = prefs.getFloat(K_SPEED, 1f),
        pitch = prefs.getFloat(K_PITCH, 1f),
        volume = prefs.getFloat(K_VOLUME, 1f),
    ))
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

    /** One audio session for every player made here, so the equalizer on it carries from song to song. */
    private val session = audio.generateAudioSessionId()
    private val eq = EqEngine(session)

    /** The equalizer's curve (EqStore), applied to the phone's music from now on. */
    fun applyEq(s: EqState) { if (session > 0) eq.apply(s) }

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
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { ducked = true; applyVolume() }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    ducked = false; applyVolume()
                    if (resumeOnGain) { resumeOnGain = false; resume() }
                }
            }
        }, main)
        .build()
    private var hasFocus = false
    private var ducked = false

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
            s.repeat.loops -> { roundAgain(); 0 }
            else -> return
        }
        skipTo(n)
    }

    /** Early in a song, the one before; later, this one from the start, as every player does. */
    fun previous() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        if (s.positionNow() > RESTART_AFTER_MS || (s.index == 0 && !s.repeat.loops)) { seekTo(0); return }
        skipTo(if (s.index > 0) s.index - 1 else s.queue.lastIndex)
    }

    /** Whether previous goes to another song, rather than back to the start of this one. */
    fun previousChangesSong(): Boolean {
        val s = _state.value
        return s.queue.size > 1 && s.positionNow() <= RESTART_AFTER_MS && (s.index > 0 || s.repeat.loops)
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
        applyParams()
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

    /** Picks a repeat mode; [times] sets how many more times [Repeat.TIMES] plays this song. */
    fun setRepeat(r: Repeat, times: Int? = null) {
        set { it.copy(repeat = r, repeatTimes = (times ?: it.repeatTimes).coerceIn(1, MAX_REPEATS)) }
        chainNext()
        save()
    }

    /** The count for [Repeat.TIMES], changed from its menu without picking it. */
    fun setRepeatTimes(times: Int) {
        set { it.copy(repeatTimes = times.coerceIn(1, MAX_REPEATS)) }
        save()
    }

    /** The next mode along, in Namida's order. */
    fun cycleRepeat() = setRepeat(Repeat.entries[(_state.value.repeat.ordinal + 1) % Repeat.entries.size])

    /**
     * Round the queue again. Shuffling each round, the first song stays where it is (it is the
     * one already shown next, and may already be sounding); the rest are dealt anew.
     */
    private fun roundAgain() {
        val s = _state.value
        if (s.repeat != Repeat.ALL_SHUFFLE || s.queue.size < 3) return
        val q = listOf(s.queue[0]) + s.queue.drop(1).shuffled()
        set { it.copy(queue = q) }
    }

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
        if (tracks.isEmpty()) return
        val s = _state.value
        if (s.current == null) { play(tracks, 0); return }
        val q = s.queue.toMutableList().apply { addAll(s.index + 1, tracks) }
        unshuffled = unshuffled + tracks
        set { it.copy(queue = q) }
        chainNext()
    }

    /** Takes off every song before the one playing (Namida's queue broom). */
    fun removeBefore() {
        val s = _state.value
        if (s.index <= 0) return
        val q = s.queue.drop(s.index)
        unshuffled = unshuffled.filter { t -> q.any { it.id == t.id } }
        set { it.copy(queue = q, index = 0) }
        chainNext()
    }

    /** Takes off every song after the one playing. */
    fun removeAfter() {
        val s = _state.value
        if (s.index < 0 || s.index >= s.queue.lastIndex) return
        val q = s.queue.take(s.index + 1)
        unshuffled = unshuffled.filter { t -> q.any { it.id == t.id } }
        set { it.copy(queue = q) }
        chainNext()
    }

    /** Shuffles what comes after the song playing, once, as the queue's Shuffle button does. */
    fun shuffleUpcoming() {
        val s = _state.value
        if (s.index < 0 || s.index >= s.queue.lastIndex) return
        val q = s.queue.take(s.index + 1) + s.queue.drop(s.index + 1).shuffled()
        set { it.copy(queue = q) }
        chainNext()
    }

    /** Plays these after everything queued. */
    fun playLast(tracks: List<TrackDto>) {
        if (tracks.isEmpty()) return
        if (_state.value.current == null) { play(tracks, 0); return }
        unshuffled = unshuffled + tracks
        set { it.copy(queue = it.queue + tracks) }
        chainNext()
    }

    /** Stops and empties the queue: the mini player goes. */
    fun clear() {
        wantPlay = false
        releasePlayers()
        dropFocus()
        unregisterNoisy()
        unshuffled = emptyList()
        set { State(repeat = it.repeat, repeatTimes = it.repeatTimes, speed = it.speed, pitch = it.pitch, volume = it.volume, seq = it.seq) }
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
                repeatTimes = prefs.getInt(K_REPEAT_TIMES, 1).coerceIn(1, MAX_REPEATS),
                positionMs = prefs.getLong(K_POS, 0).coerceIn(0, q[i].durationMs.coerceAtLeast(0)),
                durationMs = q[i].durationMs,
            )
        }
    }

    // ------------------------------------------------------------------ the sound controls

    /**
     * How fast, how high and how loud it plays, as Namida's sound controls set them. Kept for
     * next time. Speed and pitch only reach a song that is playing (setting them on a paused
     * MediaPlayer would start it); a paused one takes them as it starts.
     */
    fun setSound(speed: Float = _state.value.speed, pitch: Float = _state.value.pitch, volume: Float = _state.value.volume) {
        val sp = speed.coerceIn(SPEED_MIN, SPEED_MAX)
        val pi = pitch.coerceIn(PITCH_MIN, PITCH_MAX)
        val vo = volume.coerceIn(0f, 1f)
        // Where the song is, at the old speed, before the new one takes over the reckoning.
        set { it.copy(positionMs = it.positionNow(), at = now(), speed = sp, pitch = pi, volume = vo) }
        prefs.edit().putFloat(K_SPEED, sp).putFloat(K_PITCH, pi).putFloat(K_VOLUME, vo).apply()
        applyVolume()
        applyParams()
    }

    /** Namida's two choices in the sound dialog: pitch counted in semitones, and speed carrying pitch with it. */
    var pitchInSemitones: Boolean
        get() = prefs.getBoolean(K_SEMITONES, false)
        set(v) { prefs.edit().putBoolean(K_SEMITONES, v).apply() }
    var speedCarriesPitch: Boolean
        get() = prefs.getBoolean(K_LINK_PITCH, false)
        set(v) { prefs.edit().putBoolean(K_LINK_PITCH, v).apply() }

    private fun outVolume() = _state.value.volume * (if (ducked) DUCK else 1f)

    private fun applyVolume() {
        val v = outVolume()
        runCatching { mp?.setVolume(v, v) }
        runCatching { nextMp?.setVolume(v, v) }
    }

    private fun applyParams() {
        val p = mp ?: return
        val s = _state.value
        if (!prepared || !s.playing && !wantPlay) return
        runCatching {
            val now = p.playbackParams
            if (now.speed != s.speed || now.pitch != s.pitch) p.playbackParams = android.media.PlaybackParams().setSpeed(s.speed).setPitch(s.pitch)
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
        if (session > 0) audioSessionId = session
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
        applyVolume()
        if (wantPlay && gainFocus()) {
            runCatching { p.start() }
            applyParams()
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
        if (s.repeat.holds) {
            runCatching { mp?.seekTo(0); mp?.start() }
            applyParams()
            // Counting down: the last of the times, it goes on as it would with repeat off.
            set {
                if (it.repeat != Repeat.TIMES) it.copy(positionMs = 0, at = now(), playing = true)
                else if (it.repeatTimes > 1) it.copy(repeatTimes = it.repeatTimes - 1, positionMs = 0, at = now(), playing = true)
                else it.copy(repeat = Repeat.OFF, repeatTimes = 1, positionMs = 0, at = now(), playing = true)
            }
            chainNext()
            save()
            return
        }
        val n = when {
            s.index + 1 < s.queue.size -> s.index + 1
            s.repeat.loops -> 0
            else -> -1
        }
        if (n == 0 && s.index == s.queue.lastIndex) roundAgain()
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
            applyParams()
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
            s.repeat.holds -> -1
            s.index + 1 < s.queue.size -> s.index + 1
            s.repeat.loops && s.queue.size > 1 -> 0
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
            runCatching { p.setVolume(outVolume(), outVolume()) }
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
            .putInt(K_REPEAT_TIMES, s.repeatTimes)
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
        const val K_REPEAT_TIMES = "repeatTimes"
        const val MAX_REPEATS = 99
        const val K_SPEED = "speed"
        const val K_PITCH = "pitch"
        const val K_VOLUME = "volume"
        const val K_SEMITONES = "pitchInSemitones"
        const val K_LINK_PITCH = "speedCarriesPitch"
        // The dialog's sliders run from 0 to 2, as Namida's; the sound itself stops short of 0.
        const val SPEED_MIN = 0.25f
        const val SPEED_MAX = 2f
        const val PITCH_MIN = 0.25f
        const val PITCH_MAX = 2f
    }
}
