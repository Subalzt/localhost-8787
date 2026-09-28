package dev.periy.bridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.periy.bridge.container
import dev.periy.bridge.music.Favourites
import dev.periy.bridge.music.PhonePlayer
import dev.periy.bridge.server.TrackDto
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * The phone's Music, full screen, after Namida's library (github.com/namidaco/namida): the
 * Tracks and Albums pages side by side under Namida's bottom bar, an album's own page slid in
 * over them, a search over whichever is showing, and all of it in the colour of the song
 * playing, as Namida's library takes it. Opened from the Music tab; Back returns to the app.
 * What is playing floats over the bar in the player (NowPlaying.kt).
 *
 * Written anew for Compose from how Namida's pages look; none of its code is used.
 */

/** Namida's own colour, for while nothing is playing to take one from. */
private val NamidaColour = Color(0xFF9C99C1)

/** Namida's bottom bar, over the navigation bar's inset. */
val MusicBarHeight = 62.dp
private val HeaderHeight = 56.dp

/** Namida's page change: quick to leave, slow to arrive. */
private val PageEase = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * The Music screen. [miniRoom] is what the mini player takes over the bar while something is
 * queued; [motion] sinks the bar away as the player opens, as the app's tabs do.
 */
@Composable
fun MusicScreen(
    shelf: MusicShelf,
    now: PhonePlayer.State,
    player: PhonePlayer,
    motion: PlayerMotion,
    statusTop: Dp,
    bottomInset: Dp,
    miniRoom: Dp,
    requestMusic: () -> Unit,
) {
    // The song playing's colour, as the player takes it; Namida's own while nothing plays.
    val cur = now.current
    val cover = if (cur != null) rememberCover(cur.albumId) else null
    val target = if (cur == null) NamidaColour else (if (cover != null) Covers.tint(cur.albumId) else null) ?: Covers.madeUp(cur.album)
    val tint by animateColorAsState(target, tween(600), label = "tint")
    val base = LocalPalette.current
    val palette = remember(tint, base) { playerPalette(base, tint) }

    val favourites = LocalContext.current.container.favourites
    val hearts by favourites.ids.collectAsState()
    val acts = remember(player, shelf, favourites) { TrackActions(player, shelf, favourites) }

    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = shelf.page) { 2 }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { shelf.page = it } }
    LaunchedEffect(shelf.page) { if (pager.settledPage != shelf.page && !pager.isScrollInProgress) pager.scrollToPage(shelf.page) }
    val pagePos by remember { derivedStateOf { pager.currentPage + pager.currentPageOffsetFraction } }

    val top = statusTop + HeaderHeight
    val bottom = bottomInset + MusicBarHeight + if (cur != null) miniRoom else 0.dp

    CompositionLocalProvider(LocalPalette provides palette, LocalTextStyle provides TextStyle(color = palette.text)) {
        Box(Modifier.fillMaxSize().background(Bridge.Bg)) {
            // ---- Tracks and Albums, a swipe apart
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { page ->
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val off = abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                    val s = 1f - 0.05f * off
                    scaleX = s; scaleY = s
                    alpha = 1f - 0.5f * off
                }) {
                    if (page == 0) TracksPage(shelf, now, hearts, acts, top, bottom, requestMusic)
                    else AlbumsPage(shelf, now, top, bottom, requestMusic, onPlay = { acts.play(it.tracks, 0) })
                }
            }

            // ---- an album, slid in over them
            AnimatedVisibility(
                shelf.open != null,
                enter = slideInHorizontally(tween(420, easing = PageEase)) { it / 4 } + fadeIn(tween(260)),
                exit = slideOutHorizontally(tween(300, easing = PageEase)) { it / 4 } + fadeOut(tween(200)),
            ) {
                // Kept while it slides out, after it has been closed.
                val kept = remember { shelf.open }
                (shelf.open ?: kept)?.let { AlbumPage(it, shelf, now, hearts, acts, top, bottom) }
            }

            MusicHeader(shelf, statusTop)

            // ---- Namida's bottom bar: Tracks and Albums; it sinks away as the player opens
            MusicBar(
                position = pagePos,
                bottomInset = bottomInset,
                modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer {
                    if (cur == null) return@graphicsLayer
                    val cp = motion.p.coerceIn(0f, 1f)
                    translationY = cp * size.height
                    alpha = 1f - cp
                },
            ) { i ->
                shelf.open = null
                scope.launch { pager.animateScrollToPage(i, animationSpec = tween(420, easing = PageEase)) }
            }
        }
    }
}

/** What a song's tile and the pages can do with songs. */
private class TrackActions(val player: PhonePlayer, val shelf: MusicShelf, val favourites: Favourites) {
    fun play(list: List<TrackDto>, i: Int) { if (list.isNotEmpty()) player.play(list, i) }
    fun shuffle(list: List<TrackDto>) { if (list.isNotEmpty()) player.play(list, list.indices.random(), shuffle = true) }
    fun next(t: TrackDto) = player.playNext(listOf(t))
    fun last(list: List<TrackDto>) = player.playLast(list)
    fun album(t: TrackDto) { shelf.albumOf[t.id]?.let { shelf.openAlbum(it) } }
    fun heart(t: TrackDto) = favourites.toggle(t.id)
}

// ---------------------------------------------------------------------------- the pages

@Composable
private fun TracksPage(
    shelf: MusicShelf,
    now: PhonePlayer.State,
    hearts: Set<Long>,
    acts: TrackActions,
    top: Dp,
    bottom: Dp,
    requestMusic: () -> Unit,
) {
    val songs = remember(shelf.tracks, shelf.query) { shelf.songs() }
    val playingId = now.current?.id
    LazyColumn(Modifier.fillMaxSize(), state = shelf.trackList, contentPadding = PaddingValues(top = top, bottom = bottom + 8.dp)) {
        item(key = "bar") {
            CountBar {
                BarButton(PlayerIcons.Shuffle, "Shuffle every song") { acts.shuffle(songs) }
                BarButton(PlayerIcons.Play, "Play every song") { acts.play(songs, 0) }
                Spacer(Modifier.width(6.dp))
                CountText(count(songs.size, "Track"))
            }
        }
        noteFor(shelf, songs.isEmpty())?.let { n -> item(key = "note") { LibraryNote(n, requestMusic) } }
        itemsIndexed(songs, key = { _, t -> t.id }) { i, t ->
            TrackTile(t, shelf.coverOf(t), current = t.id == playingId, hearted = t.id in hearts, acts = acts) { acts.play(songs, i) }
        }
    }
}

@Composable
private fun AlbumsPage(
    shelf: MusicShelf,
    now: PhonePlayer.State,
    top: Dp,
    bottom: Dp,
    requestMusic: () -> Unit,
    onPlay: (Album) -> Unit,
) {
    val albums = remember(shelf.albums, shelf.query) { shelf.albumMatches() }
    val playingKey = now.current?.let { shelf.albumOf[it.id]?.key }
    LazyVerticalGrid(
        GridCells.Fixed(3), Modifier.fillMaxSize(), state = shelf.albumGrid,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = top, bottom = bottom + 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "bar", span = { GridItemSpan(maxLineSpan) }) {
            CountBar(Modifier.padding(start = 2.dp)) { CountText(count(albums.size, "Album")) }
        }
        noteFor(shelf, albums.isEmpty())?.let { n -> item(key = "note", span = { GridItemSpan(maxLineSpan) }) { LibraryNote(n, requestMusic) } }
        items(albums, key = { it.key }) { a ->
            AlbumCard(a, playing = a.key == playingKey, sounding = now.playing, onOpen = { shelf.openAlbum(a) }, onPlay = { onPlay(a) })
        }
    }
}

/**
 * An album's page, as Namida's: the cover in a frame with the name, who it is by and the
 * year, shuffle and Play Last; then how many songs and how long, in the order chosen; then
 * its songs, each cover marked with the song's number.
 */
@Composable
private fun AlbumPage(
    a: Album,
    shelf: MusicShelf,
    now: PhonePlayer.State,
    hearts: Set<Long>,
    acts: TrackActions,
    top: Dp,
    bottom: Dp,
) {
    val list = remember(a.key) { LazyListState() }
    val songs = remember(a, shelf.albumSort, shelf.albumDesc, shelf.albumQuery) { shelf.albumTracks(a) }
    val playingId = now.current?.id
    LazyColumn(Modifier.fillMaxSize().background(Bridge.Bg), state = list, contentPadding = PaddingValues(top = top, bottom = bottom + 8.dp)) {
        item(key = "band") { AlbumBand(a, onShuffle = { acts.shuffle(a.tracks) }, onPlayLast = { acts.last(a.tracks) }) }
        item(key = "bar") {
            Row(Modifier.fillMaxWidth().height(50.dp).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(PlayerIcons.Note, null, tint = Bridge.Text, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    count(a.tracks.size, "Track") + " - " + fmtMinutes(a.durationMs),
                    style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                SortChip(shelf.albumSort) { shelf.albumSort = it }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { shelf.albumDesc = !shelf.albumDesc },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PlayerIcons.Order, if (shelf.albumDesc) "Last first" else "First first", tint = Bridge.Text,
                        modifier = Modifier.size(21.dp).graphicsLayer { scaleY = if (shelf.albumDesc) -1f else 1f })
                }
            }
        }
        if (songs.isEmpty()) item(key = "none") { LibraryNote("Nothing matches", {}) }
        itemsIndexed(songs, key = { _, t -> t.id }) { i, t ->
            TrackTile(
                t, a.coverId, current = t.id == playingId, hearted = t.id in hearts, acts = acts,
                number = if (t.track > 0) t.track else a.tracks.indexOf(t) + 1, inAlbum = true,
            ) { acts.play(songs, i) }
        }
    }
}

// ---------------------------------------------------------------------------- pieces

/** The row over a page: how many there are, with what can be done to all of them. */
@Composable
private fun CountBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().height(50.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun CountText(text: String) {
    Text(text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Text, maxLines = 1)
}

@Composable
private fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).pressable(CircleShape, scaleTo = 0.88f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Bridge.Text, modifier = Modifier.size(22.dp))
    }
}

private fun count(n: Int, one: String) = "%,d %s".format(n, if (n == 1) one else one + "s")

private const val PERMISSION = "permission"

/** What a page shows in place of songs: the permission to ask for, a search with no match, an empty phone. */
private fun noteFor(shelf: MusicShelf, nothing: Boolean): String? = when {
    !shelf.granted -> PERMISSION
    !shelf.loaded -> "Looking for music…"
    shelf.tracks.isEmpty() -> "No music found on the phone"
    nothing -> "Nothing matches"
    else -> null
}

@Composable
private fun LibraryNote(note: String, requestMusic: () -> Unit) {
    if (note == PERMISSION) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).card().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(PlayerIcons.Note, Bridge.Accent)
            Spacer(Modifier.width(12.dp))
            Text("Localhost 8787 needs to read your music", style = TitleStyle, color = Bridge.Text)
        }
        RowNote("Only music: not your photos or your other files. The same permission lets the laptop play it.")
        Spacer(Modifier.height(14.dp))
        BridgeButton("Allow music access", Modifier.fillMaxWidth(), onClick = requestMusic)
    }
    else Text(note, style = BodyStyle, color = Bridge.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(28.dp))
}

/**
 * A song, as Namida lists one: the cover, the name, who it is by and the album and year under
 * it; how long it is and its heart on the right; and a menu. The song playing is lit with a
 * wash of the colour. On an album's page, the cover carries the song's [number].
 */
@Composable
private fun TrackTile(
    t: TrackDto,
    coverId: String,
    current: Boolean,
    hearted: Boolean,
    acts: TrackActions,
    number: Int? = null,
    inAlbum: Boolean = false,
    onClick: () -> Unit,
) {
    val lit = Bridge.Accent
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(76.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(if (current) Modifier.background(Brush.horizontalGradient(listOf(lit.copy(alpha = 0.30f), lit.copy(alpha = 0.06f)))) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(58.dp)) {
            Cover(coverId, t.album, Modifier.fillMaxSize(), radius = 10.dp)
            if (number != null) Text(
                "$number",
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
                color = Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, style = TextStyle(fontSize = 13.sp), color = Bridge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(t.album, t.year.takeIf { it > 0 }?.toString()).joinToString(" • "),
                style = TextStyle(fontSize = 12.sp), color = Bridge.Muted.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(fmtTime(t.durationMs), style = TextStyle(fontSize = 12.5.sp, fontFeatureSettings = "tnum"), color = Bridge.Muted)
            Box(Modifier.size(30.dp).clip(CircleShape).clickable { acts.heart(t) }, contentAlignment = Alignment.CenterEnd) {
                Icon(if (hearted) PlayerIcons.HeartOn else PlayerIcons.Heart, if (hearted) "Take the heart off" else "Give it a heart",
                    tint = if (hearted) lit else Bridge.Muted.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
            }
        }
        TrackMenu(t, acts, inAlbum)
    }
}

/** A song's menu: play it next, play it last, or go to its album. */
@Composable
private fun TrackMenu(t: TrackDto, acts: TrackActions, inAlbum: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(width = 38.dp, height = 56.dp).clip(CircleShape).clickable { open = true }, contentAlignment = Alignment.Center) {
            Icon(PlayerIcons.Dots, "More for ${t.title}", tint = Bridge.Muted, modifier = Modifier.size(20.dp))
        }
        if (open) PopupMenu(onDismiss = { open = false }) {
            MenuRow(PlayerIcons.AddNext, "Play next") { open = false; acts.next(t) }
            MenuRow(PlayerIcons.AddLast, "Play last") { open = false; acts.last(listOf(t)) }
            if (!inAlbum) MenuRow(PlayerIcons.Disc, "Go to album") { open = false; acts.album(t) }
        }
    }
}

@Composable
private fun PopupMenu(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, with(LocalDensity.current) { 44.dp.roundToPx() }),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.width(210.dp).floating(RoundedCornerShape(16.dp)).background(Bridge.Surface).padding(vertical = 6.dp)) { content() }
    }
}

@Composable
private fun MenuRow(icon: ImageVector?, label: String, on: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = Bridge.Text, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(label, style = TextStyle(fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
            color = if (on) Bridge.Accent else Bridge.Text, modifier = Modifier.weight(1f))
    }
}

/**
 * An album in the grid, as Namida's: the cover with the year in a chip at its corner and a
 * play button at the other, and under it the name, who it is by, and how many songs and how long.
 */
@Composable
private fun AlbumCard(a: Album, playing: Boolean, sounding: Boolean, onOpen: () -> Unit, onPlay: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().pressable(shape, scaleTo = 0.96f, onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Cover(a.coverId, a.title, Modifier.fillMaxSize(), radius = 12.dp)
            if (a.year > 0) Text(
                "${a.year}", style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.42f)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Box(
                Modifier.align(Alignment.BottomEnd).padding(5.dp).size(28.dp)
                    .pressable(CircleShape, scaleTo = 0.86f, onClick = onPlay)
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center,
            ) {
                if (playing) PlayingBars(Color.White, sounding, Modifier.size(12.dp))
                else Icon(PlayerIcons.Play, "Play ${a.title}", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(a.title, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
            color = if (playing) Bridge.Accent else Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(a.artist, style = TextStyle(fontSize = 11.5.sp), color = Bridge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count(a.tracks.size, "Track") + " • " + fmtMinutes(a.durationMs),
            style = TextStyle(fontSize = 11.sp), color = Bridge.Muted.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The top of an album's page: the cover in its frame, what it is, and shuffle and Play Last. */
@Composable
private fun AlbumBand(a: Album, onShuffle: () -> Unit, onPlayLast: () -> Unit) {
    val glow = Covers.tint(a.coverId) ?: Covers.madeUp(a.title)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(lerp(Bridge.Surface, glow, 0.16f), Bridge.Surface)))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val frame = RoundedCornerShape(20.dp)
        Box(
            Modifier.size(136.dp).shadow(14.dp, frame, ambientColor = glow.copy(alpha = 0.5f), spotColor = glow)
                .clip(frame).background(Bridge.Chip).padding(6.dp),
        ) { Cover(a.coverId, a.title, Modifier.fillMaxSize(), radius = 14.dp, big = true) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, lineHeight = 23.sp),
                color = Bridge.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(a.artist, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp), color = Bridge.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (a.year > 0) Text("${a.year}", style = TextStyle(fontSize = 13.sp, fontFeatureSettings = "tnum"), color = Bridge.Muted.copy(alpha = 0.8f))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.height(36.dp).width(48.dp).pressable(ButtonShape, scaleTo = 0.92f, onClick = onShuffle).background(Bridge.Chip),
                    contentAlignment = Alignment.Center) {
                    Icon(PlayerIcons.Shuffle, "Shuffle ${a.title}", tint = Bridge.Text, modifier = Modifier.size(20.dp))
                }
                Row(Modifier.height(36.dp).pressable(ButtonShape, scaleTo = 0.94f, onClick = onPlayLast).background(Bridge.Chip).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Play Last", style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Text, maxLines = 1)
                }
            }
        }
    }
}

private val SORTS = listOf("Disc Number", "Title", "Duration", "Artist")

/** How the album's songs are ordered, in a chip; a tap offers the others. */
@Composable
private fun SortChip(sort: Int, onSort: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(32.dp).clip(ButtonShape).background(Bridge.Chip).clickable { open = true }.padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(SORTS[sort], style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Text, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Icon(PlayerIcons.Down, null, tint = Bridge.Text, modifier = Modifier.size(14.dp))
        }
        if (open) PopupMenu(onDismiss = { open = false }) {
            SORTS.forEachIndexed { i, s -> MenuRow(null, s, on = i == sort) { open = false; onSort(i) } }
        }
    }
}

/**
 * The top of the screen: the search on the right (and, on an album, back on the left); with
 * the search open, the field across it, filtering the page under it as you type.
 */
@Composable
private fun MusicHeader(shelf: MusicShelf, statusTop: Dp) {
    val album = shelf.open
    val searching = if (album != null) shelf.albumSearching else shelf.searching
    Box(Modifier.fillMaxWidth().background(Bridge.Bg).padding(top = statusTop).height(HeaderHeight)) {
        AnimatedContent(
            Triple(album != null, searching, album?.key),
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(140)) },
            label = "header",
        ) { (inAlbum, on, _) ->
            Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (on) {
                    HeaderButton(PlayerIcons.Back, "Close the search") { shelf.back() }
                    val value = if (inAlbum) shelf.albumQuery else shelf.query
                    val set: (String) -> Unit = { if (inAlbum) shelf.albumQuery = it else shelf.query = it }
                    SearchBox(value, set, when { inAlbum -> "Search this album"; shelf.page == 0 -> "Search tracks"; else -> "Search albums" }, Modifier.weight(1f))
                    if (value.isNotEmpty()) HeaderButton(PlayerIcons.Close, "Clear the search") { set("") }
                } else {
                    if (inAlbum) HeaderButton(PlayerIcons.Back, "Back") { shelf.back() }
                    Spacer(Modifier.weight(1f))
                    HeaderButton(PlayerIcons.Search, "Search") { if (inAlbum) shelf.albumSearching = true else shelf.searching = true }
                }
            }
        }
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).pressable(CircleShape, scaleTo = 0.88f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Bridge.Text, modifier = Modifier.size(23.dp))
    }
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(modifier.height(42.dp).clip(RoundedCornerShape(14.dp)).background(Bridge.Chip).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, style = TextStyle(fontSize = 15.sp), color = Bridge.Muted.copy(alpha = 0.7f), maxLines = 1)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = Bridge.Text),
            cursorBrush = SolidColor(Bridge.Accent),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

/**
 * Namida's bottom bar, with only Tracks and Albums: each an icon over its name, the one showing
 * with a pill of the colour behind its icon, which grows from the middle as a swipe reaches it.
 */
@Composable
private fun MusicBar(position: Float, bottomInset: Dp, modifier: Modifier, onSelect: (Int) -> Unit) {
    Row(
        modifier.fillMaxWidth().background(Bridge.Surface).padding(bottom = bottomInset).height(MusicBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("Tracks" to PlayerIcons.Note, "Albums" to PlayerIcons.Disc).forEachIndexed { i, (label, icon) ->
            val on = (1f - abs(position - i)).coerceIn(0f, 1f)
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(width = 60.dp, height = 30.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxSize().graphicsLayer { scaleX = 0.4f + 0.6f * on; alpha = on }.clip(ButtonShape).background(Bridge.Accent.copy(alpha = 0.24f)))
                    Icon(icon, label, tint = lerp(Bridge.Muted, Bridge.Text, on), modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(label, style = TextStyle(fontSize = 12.sp, fontWeight = if (on > 0.5f) FontWeight.SemiBold else FontWeight.Medium),
                    color = lerp(Bridge.Muted, Bridge.Text, on))
            }
        }
    }
}
