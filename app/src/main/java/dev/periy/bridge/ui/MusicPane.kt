package dev.periy.bridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.periy.bridge.music.PhonePlayer
import dev.periy.bridge.server.MusicLibrary
import dev.periy.bridge.server.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's Music tab: the same library the page shows (albums first, or every song, and a
 * search over both), played here on the phone. What is playing floats along the bottom in
 * the Namida-style player (NowPlaying.kt).
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

/** The library as the Music tab shows it, and where in it you are. */
@Stable
class MusicShelf {
    var granted by mutableStateOf(true)
    var loaded by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var tracks by mutableStateOf(emptyList<TrackDto>())
    var albums by mutableStateOf(emptyList<Album>())
    /** Which album each song is in, by the song's id. */
    var albumOf by mutableStateOf(emptyMap<Long, Album>())
    /** 0: albums, 1: songs. */
    var view by mutableIntStateOf(0)
    var query by mutableStateOf("")
    /** The album open in the tab, over the library. */
    var open by mutableStateOf<Album?>(null)
    var songsShown by mutableIntStateOf(SONGS_PAGE)

    suspend fun load(library: MusicLibrary, refresh: Boolean) {
        if (loading) return
        loading = true
        val (g, t) = withContext(Dispatchers.IO) { library.granted() to library.tracks(refresh) }
        val grouped = withContext(Dispatchers.Default) { groupAlbums(t) }
        granted = g
        tracks = t
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

    fun albumMatches(): List<Album> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return albums
        return albums.filter { a -> (a.title + " " + a.artist).lowercase().contains(q) || a.tracks.any { matches(it, q) } }
    }

    companion object {
        const val SONGS_PAGE = 300
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

fun formatBadge(mime: String): String {
    val m = mime.lowercase()
    return when {
        "flac" in m -> "FLAC"
        "mpeg" in m || "mp3" in m -> "MP3"
        "wav" in m -> "WAV"
        "ogg" in m -> "OGG"
        "opus" in m -> "OPUS"
        "mp4" in m || "aac" in m || "m4a" in m -> "AAC"
        else -> ""
    }
}

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun fmtLong(ms: Long): String {
    val min = (ms / 60_000).toInt()
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

// ---------------------------------------------------------------------------- the tab

@Composable
fun MusicPane(
    shelf: MusicShelf,
    library: MusicLibrary,
    now: PhonePlayer.State,
    list: LazyListState,
    top: Dp,
    bottom: Dp,
    requestMusic: () -> Unit,
    onPlay: (tracks: List<TrackDto>, start: Int, shuffle: Boolean) -> Unit,
    onRescan: () -> Unit,
) {
    val playingId = now.current?.id
    AnimatedContent(
        shelf.open,
        transitionSpec = {
            if (targetState != null) (slideInHorizontally(tween(300)) { it / 3 } + fadeIn(tween(220))) togetherWith fadeOut(tween(160))
            else fadeIn(tween(220)) togetherWith (slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(200)))
        },
        label = "album",
    ) { album ->
        if (album != null) AlbumPage(album, playingId, now.playing, top, bottom, onBack = { shelf.open = null }, onPlay = onPlay)
        else LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = top, bottom = 28.dp + bottom)) {
            item(key = "tools") { MusicTools(shelf, onRescan) }
            if (!shelf.granted) item(key = "perm") {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp).panel().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(BlazeIcons.Music, Bridge.Pink)
                        Spacer(Modifier.width(12.dp))
                        Text("Localhost 8787 needs to read your music", style = TitleStyle, color = Bridge.Text)
                    }
                    RowNote("Only music: not your photos or your other files. The same permission lets the laptop play it.")
                    Spacer(Modifier.height(14.dp))
                    BridgeButton("Allow music access", Modifier.fillMaxWidth(), onClick = requestMusic)
                }
            }
            else if (shelf.loaded && shelf.tracks.isEmpty()) item(key = "empty") {
                SettingRow("No music found on the phone", first = true, titleColor = Bridge.Muted)
            }
            else if (shelf.view == 0) {
                val albums = shelf.albumMatches()
                if (shelf.loaded && albums.isEmpty()) item(key = "none") { NothingMatches() }
                items(albums.chunked(2), key = { "row-" + it[0].key }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { a ->
                            AlbumTile(a, playing = playingId != null && a.tracks.any { it.id == playingId }, sounding = now.playing,
                                modifier = Modifier.weight(1f), onOpen = { shelf.open = a }, onPlay = { onPlay(a.tracks, 0, false) })
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                val songs = shelf.songs()
                if (shelf.loaded && songs.isEmpty()) item(key = "none") { NothingMatches() }
                val shown = songs.take(shelf.songsShown)
                itemsIndexed(shown, key = { _, t -> "s" + t.id }) { i, t ->
                    SongRow(
                        t, first = i == 0, last = i == shown.lastIndex,
                        detail = t.artist + " · " + t.album,
                        playing = t.id == playingId, sounding = now.playing,
                        lead = { Cover(shelf.albumOf[t.id]?.coverId ?: t.albumId, t.album, Modifier.size(46.dp), radius = 8.dp) },
                    ) { onPlay(songs, songs.indexOf(t), false) }
                }
                if (songs.size > shown.size) item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) {
                        SoftButton("Show ${minOf(MusicShelf.SONGS_PAGE, songs.size - shown.size)} more of ${songs.size}") {
                            shelf.songsShown += MusicShelf.SONGS_PAGE
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NothingMatches() {
    Text("Nothing matches", style = BodyStyle, color = Bridge.Muted, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(28.dp))
}

/** The search, Albums or Songs with how many, and look again for new music. */
@Composable
private fun MusicTools(shelf: MusicShelf, onRescan: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchField(shelf.query, { shelf.query = it; shelf.songsShown = MusicShelf.SONGS_PAGE }, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            val spin = rememberInfiniteTransition(label = "rescan")
            val turn by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = androidx.compose.animation.core.LinearEasing)), label = "turn")
            Box(Modifier.rotate(if (shelf.loading) turn else 0f)) {
                IconChip(BlazeIcons.Refresh, "Look for new music on the phone", tint = Bridge.Muted, size = 44.dp, onClick = onRescan)
            }
        }
        Spacer(Modifier.height(10.dp))
        val ready = shelf.loaded && shelf.granted && shelf.tracks.isNotEmpty()
        SegmentedRow(
            listOf(
                "Albums" + if (ready) "  " + "%,d".format(shelf.albumMatches().size) else "",
                "Songs" + if (ready) "  " + "%,d".format(shelf.songs().size) else "",
            ),
            shelf.view,
        ) { shelf.view = it; shelf.open = null }
    }
}

/** A search box: a well with a magnifier, and a cross to clear it once something is typed. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Search your library") {
    val text = Bridge.Text
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(Bridge.Chip).padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BlazeIcons.Search, null, tint = Bridge.Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = BodyStyle.copy(fontSize = 15.sp), color = Bridge.Faint, maxLines = 1)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = BodyStyle.copy(fontSize = 15.sp, color = text),
                cursorBrush = SolidColor(Bridge.Blue),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) Box(
            Modifier.size(32.dp).clip(CircleShape).clickable { onChange("") },
            contentAlignment = Alignment.Center,
        ) { Icon(BlazeIcons.Close, "Clear the search", tint = Bridge.Muted, modifier = Modifier.size(16.dp)) }
    }
}

/** An album: its cover with a play button on it, the name and who it is by under it. */
@Composable
private fun AlbumTile(a: Album, playing: Boolean, sounding: Boolean, modifier: Modifier, onOpen: () -> Unit, onPlay: () -> Unit) {
    val glow = Covers.tint(a.coverId) ?: Covers.madeUp(a.title)
    Column(modifier.pressable(RoundedCornerShape(12.dp), scaleTo = 0.97f, onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).depth(glow, RoundedCornerShape(12.dp), 10.dp)) {
            Cover(a.coverId, a.title, Modifier.fillMaxSize(), radius = 12.dp)
            Box(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).size(34.dp)
                    .pressable(CircleShape, scaleTo = 0.88f, onClick = onPlay)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                if (playing) PlayingBars(Color.White, sounding, Modifier.size(14.dp))
                else Icon(BlazeIcons.Play, "Play ${a.title}", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(a.title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
            color = if (playing) Bridge.Lit else Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(a.artist + " · " + a.tracks.size + if (a.tracks.size == 1) " song" else " songs",
            style = CaptionStyle, color = Bridge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A song in a list, as a slice of one long card: rounded at the first and the last. */
@Composable
fun SongRow(
    t: TrackDto,
    first: Boolean,
    last: Boolean,
    detail: String?,
    playing: Boolean,
    sounding: Boolean,
    lead: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(topStart = if (first) cardRadius else 0.dp, topEnd = if (first) cardRadius else 0.dp,
        bottomStart = if (last) cardRadius else 0.dp, bottomEnd = if (last) cardRadius else 0.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(shape).background(Bridge.Surface)) {
        if (!first) Box(Modifier.fillMaxWidth().padding(start = 16.dp + 46.dp + 12.dp).height(0.5.dp).background(Bridge.Outline))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                lead()
                if (playing) Box(Modifier.matchParentSize().clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    PlayingBars(Color.White, sounding, Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = TextStyle(fontSize = 16.sp, letterSpacing = (-0.2).sp, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (playing) Bridge.Lit else Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!detail.isNullOrEmpty()) Text(detail, style = CaptionStyle, color = Bridge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            val badge = formatBadge(t.mime)
            if (badge.isNotEmpty()) {
                Text(badge, style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp), color = Bridge.Muted,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Bridge.Chip).padding(horizontal = 5.dp, vertical = 2.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(fmtTime(t.durationMs), style = CaptionStyle.copy(fontFeatureSettings = "tnum"), color = Bridge.Muted)
        }
    }
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

// ---------------------------------------------------------------------------- an album

@Composable
private fun AlbumPage(
    a: Album,
    playingId: Long?,
    sounding: Boolean,
    top: Dp,
    bottom: Dp,
    onBack: () -> Unit,
    onPlay: (List<TrackDto>, Int, Boolean) -> Unit,
) {
    val state = rememberLazyListState()
    val discs = a.tracks.any { it.disc > 1 }
    val glow = Covers.tint(a.coverId) ?: Covers.madeUp(a.title)
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(top = top, bottom = 28.dp + bottom)) {
        item(key = "back") {
            Row(Modifier.padding(start = 10.dp, top = 2.dp, bottom = 6.dp).clip(ButtonShape).clickable(onClick = onBack).padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { Icon(BlazeIcons.Back, "Back", tint = Bridge.Text, modifier = Modifier.size(22.dp)) }
                Text("Albums", style = TitleStyle.copy(fontSize = 16.sp), color = Bridge.Text)
            }
        }
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth(0.66f).aspectRatio(1f).depth(glow, RoundedCornerShape(14.dp), 26.dp)) {
                    Cover(a.coverId, a.title, Modifier.fillMaxSize(), radius = 14.dp, big = true)
                }
                Spacer(Modifier.height(18.dp))
                Text(a.title, style = HeadlineStyle.copy(fontSize = 23.sp), color = Bridge.Text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(a.artist, style = TitleStyle.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium), color = Bridge.Lit, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(a.year.takeIf { it > 0 }?.toString(), "${a.tracks.size} " + if (a.tracks.size == 1) "song" else "songs", fmtLong(a.durationMs)).joinToString(" · "),
                    style = CaptionStyle, color = Bridge.Muted,
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BridgeButton("Play", Modifier.weight(1f), icon = BlazeIcons.Play) { onPlay(a.tracks, 0, false) }
                    BridgeButton("Shuffle", Modifier.weight(1f), color = Bridge.Chip, textColor = Bridge.Text, icon = BlazeIcons.Shuffle) {
                        onPlay(a.tracks, (a.tracks.indices).random(), true)
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
        itemsIndexed(a.tracks, key = { _, t -> "t" + t.id }) { i, t ->
            val n = if (t.track > 0) t.track else i + 1
            SongRow(
                t, first = i == 0, last = i == a.tracks.lastIndex,
                detail = t.artist.takeIf { it != a.artist },
                playing = t.id == playingId, sounding = sounding,
                lead = {
                    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                        Text(if (discs) "${t.disc}-$n" else "$n", style = TitleStyle.copy(fontSize = 15.sp, fontFeatureSettings = "tnum"), color = Bridge.Muted)
                    }
                },
            ) { onPlay(a.tracks, i, false) }
        }
    }
}

/** Loads the library the first time the tab is opened, and again on Rescan. */
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
