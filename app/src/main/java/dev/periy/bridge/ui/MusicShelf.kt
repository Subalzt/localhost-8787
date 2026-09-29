package dev.periy.bridge.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.periy.bridge.server.MusicLibrary
import dev.periy.bridge.server.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's music library: every song, grouped into albums the way the page groups them,
 * and where in it the Music screen (MusicScreen.kt) is.
 */

/** An album as the page groups them: one per album, however the files split it up. */
@Stable
data class Album(
    val key: String,
    val coverId: String,
    val title: String,
    val artist: String,
    val tracks: List<TrackDto>,
    val year: Int,
) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs }
}

/** The library as the Music screen shows it, and where in it you are. */
@Stable
class MusicShelf {
    var granted by mutableStateOf(true)
    var loaded by mutableStateOf(false)
    var loading by mutableStateOf(false)
    /** Every song, A to Z by title, as the Tracks page lists them. */
    var tracks by mutableStateOf(emptyList<TrackDto>())
    var albums by mutableStateOf(emptyList<Album>())
    /** Which album each song is in, by the song's id. */
    var albumOf by mutableStateOf(emptyMap<Long, Album>())

    /** The Music screen is open, over the app. */
    var showing by mutableStateOf(false)
    /** 0: Tracks, 1: Albums, 2: Liked. */
    var page by mutableIntStateOf(0)
    /** The search over the page showing, and what is typed in it. */
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    /** The album open over the pages, and a search within it. */
    var open by mutableStateOf<Album?>(null)
    var albumSearching by mutableStateOf(false)
    var albumQuery by mutableStateOf("")
    /** How an album's songs are ordered: 0 disc number, 1 title, 2 duration, 3 artist; and reversed. */
    var albumSort by mutableIntStateOf(0)
    var albumDesc by mutableStateOf(false)

    /** Where each page is scrolled to, kept while the screen is closed. */
    val trackList = LazyListState()
    val albumGrid = LazyGridState()
    val likedList = LazyListState()

    /** Namida's count bar over each page: hidden while you scroll down, back as you scroll up. */
    var trackBar by mutableStateOf(true)
    var albumBar by mutableStateOf(true)
    var likedBar by mutableStateOf(true)

    /** When each page's items came on screen, for Namida's staggered entrance. */
    val trackEntrance = Entrance()
    val albumEntrance = Entrance()
    val likedEntrance = Entrance()
    val pageEntrance = Entrance()

    /**
     * Namida's hero: the album tapped in the grid, where its cover was on screen, where the
     * album's page puts it, and whether it is flying between the two.
     */
    var heroKey by mutableStateOf<String?>(null)
    var heroFrom by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    var heroTo: androidx.compose.ui.geometry.Rect? = null
    var heroFlying by mutableStateOf(false)

    suspend fun load(library: MusicLibrary, refresh: Boolean) {
        if (loading) return
        loading = true
        val (g, t) = withContext(Dispatchers.IO) { library.granted() to library.tracks(refresh) }
        val (grouped, sorted) = withContext(Dispatchers.Default) {
            groupAlbums(t) to t.sortedWith(compareBy<TrackDto, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.artist })
        }
        granted = g
        tracks = sorted
        albums = grouped
        albumOf = buildMap { grouped.forEach { a -> a.tracks.forEach { put(it.id, a) } } }
        open = open?.let { o -> grouped.firstOrNull { it.key == o.key } }
        loaded = true
        loading = false
    }

    fun matches(t: TrackDto, q: String) = q.isEmpty() || (t.title + " " + t.artist + " " + t.album).lowercase().contains(q)

    fun songs(): List<TrackDto> {
        val q = query.trim().lowercase()
        return if (q.isEmpty()) tracks else tracks.filter { matches(it, q) }
    }

    /** The liked songs on the phone, in the order given (newest heart first), through the search. */
    fun liked(order: List<Long>): List<TrackDto> {
        val byId = tracks.associateBy { it.id }
        val q = query.trim().lowercase()
        return order.mapNotNull { byId[it] }.filter { matches(it, q) }
    }

    fun albumMatches(): List<Album> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return albums
        return albums.filter { a -> (a.title + " " + a.artist).lowercase().contains(q) || a.tracks.any { matches(it, q) } }
    }

    /** An album's songs in the order chosen on its page, and only those matching its search. */
    fun albumTracks(a: Album): List<TrackDto> {
        val base = when (albumSort) {
            1 -> a.tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            2 -> a.tracks.sortedBy { it.durationMs }
            3 -> a.tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
            else -> a.tracks
        }
        val q = albumQuery.trim().lowercase()
        val ordered = if (albumDesc) base.reversed() else base
        return if (q.isEmpty()) ordered else ordered.filter { matches(it, q) }
    }

    /** The cover a song is shown with: its album's, however the files split the album up. */
    fun coverOf(t: TrackDto): String = albumOf[t.id]?.coverId ?: t.albumId

    /** Opens an album's page; [from] is where its cover was, when it was tapped in the grid, for the hero. */
    fun openAlbum(a: Album, from: androidx.compose.ui.geometry.Rect? = null) {
        heroKey = if (from != null) a.key else null
        heroFrom = from
        heroTo = null
        open = a
        albumSearching = false
        albumQuery = ""
        pageEntrance.restart()
    }

    /** One step back: a search closes, then the album, then the screen. */
    fun back() {
        when {
            open != null && albumSearching -> { albumSearching = false; albumQuery = "" }
            open != null -> open = null
            searching -> { searching = false; query = "" }
            else -> showing = false
        }
    }
}

/**
 * Groups songs into albums the way the page does (indexAlbums in bridge.html): by Android's
 * album, then pieces with the same name and an artist in common joined up, biggest first.
 */
fun groupAlbums(tracks: List<TrackDto>): List<Album> {
    val byId = LinkedHashMap<String, MutableList<TrackDto>>()
    tracks.forEach { byId.getOrPut(it.albumId) { mutableListOf() } += it }
    val ids = byId.keys.sortedByDescending { byId[it]!!.size }
    class Group(val ids: MutableList<String> = mutableListOf(), val tracks: MutableList<TrackDto> = mutableListOf(), val arts: MutableSet<String> = mutableSetOf())
    val byName = HashMap<String, MutableList<Group>>()
    val groups = mutableListOf<Group>()
    for (id in ids) {
        val ts = byId[id]!!
        val name = albumNameKey(ts[0].album)
        val arts = ts.flatMap { artistParts(it.albumArtist) + artistParts(it.artist) }.map { it.lowercase() }.toSet()
        val same = byName.getOrPut(name) { mutableListOf() }
        val g = same.firstOrNull { c -> arts.any { it in c.arts } } ?: Group().also { same += it; groups += it }
        g.ids += id
        g.tracks += ts
        g.arts += arts
    }
    return groups.map { g ->
        val ts = g.tracks.sortedWith(compareBy<TrackDto>({ it.disc }, { it.track }, { it.title.lowercase() }))
        Album(
            key = g.ids.sorted().first(),
            coverId = g.ids.first(),
            title = ts[0].album,
            artist = albumArtistOf(ts),
            tracks = ts,
            year = ts.maxOf { it.year },
        )
    }.sortedWith(compareByDescending<Album> { it.tracks.size }.thenBy { it.title.lowercase() })
}

private val ARTIST_SPLIT = Regex("""\s*(?:,|&|\+|/|;|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|\bx\b)\s*""", RegexOption.IGNORE_CASE)
private fun artistParts(s: String): List<String> = s.split(ARTIST_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
private fun albumNameKey(s: String): String = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

/**
 * Who an album is by: its album artist tag when the songs agree on one; else the artist on
 * most of its songs; else, for a soundtrack or a compilation, Various Artists.
 */
private fun albumArtistOf(ts: List<TrackDto>): String {
    fun best(pick: (TrackDto) -> List<String>): Pair<String, Int>? =
        ts.flatMap(pick).groupingBy { it }.eachCount().maxByOrNull { it.value }?.toPair()
    best { if (it.albumArtist.isNotEmpty()) listOf(it.albumArtist) else emptyList() }?.let { if (it.second >= ts.size / 2.0) return it.first }
    best { listOf(it.artist) }?.let { if (it.second >= ts.size * 0.6) return it.first }
    best { artistParts(it.artist) }?.let { if (it.second >= ts.size * 0.6) return it.first }
    return if (ts.size > 1) "Various Artists" else ts[0].artist
}

fun formatBadge(mime: String): String = dev.periy.bridge.server.formatName(mime).takeIf { it.isNotEmpty() && it != "*" }.orEmpty()

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** A length as Namida writes it on an album: 35min, 1h 12min. */
fun fmtMinutes(ms: Long): String {
    val min = ((ms + 30_000) / 60_000).toInt()
    return if (min >= 60) "${min / 60}h ${min % 60}min" else "${min}min"
}

/** Three bars rising and falling: this one is playing (still, while paused). */
@Composable
fun PlayingBars(color: Color, moving: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "eq")
    val phases = listOf(0, 180, 360).map { delay ->
        t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(520, delayMillis = 0, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse,
            initialStartOffset = androidx.compose.animation.core.StartOffset(delay)), label = "bar$delay")
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        phases.forEach { p ->
            val h = if (moving) p.value else 0.35f
            Box(Modifier.weight(1f).fillMaxHeight(h).clip(RoundedCornerShape(1.dp)).background(color))
        }
    }
}

/** Loads the library the first time Music is opened (or can first be read). */
@Composable
fun LoadMusicOnce(shelf: MusicShelf, library: MusicLibrary, open: Boolean, granted: Boolean, onLoaded: (List<TrackDto>) -> Unit) {
    LaunchedEffect(open, granted) {
        if ((open || granted) && (!shelf.loaded || shelf.granted != granted)) {
            shelf.load(library, refresh = false)
            onLoaded(shelf.tracks)
        }
    }
}

@Composable
fun rememberMusicShelf(): MusicShelf = remember { MusicShelf() }
