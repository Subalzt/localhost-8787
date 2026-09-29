package dev.periy.bridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.key
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
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
import kotlin.math.min

/*
 * The phone's Music, full screen, rebuilt to Namida's library (github.com/namidaco/namida):
 * the Tracks and Albums pages under Namida's bottom bar, an album's own page pushed over them,
 * a search over whichever is showing, set in Lexend Deca with the Iconsax icons, and coloured
 * from the song playing as Namida colours itself (NamidaStyle.kt). The sizes, spacing and
 * colours are Namida's own values; the code is this app's. Back returns to the app.
 */

/** Namida's bottom bar (a Material bar 64 high) and app bar (56). */
val MusicBarHeight = 64.dp
private val AppBarHeight = 56.dp
/** Namida's count bar over a page. */
private val CountBarHeight = 48.dp

/** Namida's page change: quick to leave, slow to arrive. */
private val PageEase = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * The iOS push Namida uses for a page (Flutter's linearToEaseOut). Its pop, easeInToLinear, is the
 * same curve played backwards, so going back is this one run from 1 to 0.
 */
private val LinearToEaseOut = CubicBezierEasing(0.35f, 0.91f, 0.33f, 0.97f)

/** Where the flying cover is, [p] of the way from the grid to the album's page. */
private fun heroRect(shelf: MusicShelf, p: Float): androidx.compose.ui.geometry.Rect {
    val a = shelf.heroFrom ?: androidx.compose.ui.geometry.Rect.Zero
    val b = shelf.heroTo ?: a
    return androidx.compose.ui.geometry.Rect(
        androidx.compose.ui.util.lerp(a.left, b.left, p), androidx.compose.ui.util.lerp(a.top, b.top, p),
        androidx.compose.ui.util.lerp(a.right, b.right, p), androidx.compose.ui.util.lerp(a.bottom, b.bottom, p),
    )
}

/**
 * The Music screen. [miniRoom] is what the mini player takes above the bar while something is
 * queued; [motion] sinks the bar away as the player opens, as Namida's does.
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
    val target = if (cur == null) NamidaDefaultColour else (if (cover != null) Covers.tint(cur.albumId) else null) ?: Covers.madeUp(cur.album)
    val tint by animateColorAsState(target, tween(600), label = "tint")
    val base = LocalPalette.current
    val nc = remember(tint, base.dark) { namidaColors(tint, base.dark) }

    val favourites = LocalContext.current.container.favourites
    val hearts by favourites.ids.collectAsState()
    val likedOrder by favourites.order.collectAsState()
    val acts = remember(player, shelf, favourites) { TrackActions(player, shelf, favourites) }

    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = shelf.page) { 3 }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { shelf.page = it } }
    LaunchedEffect(shelf.page) { if (pager.settledPage != shelf.page && !pager.isScrollInProgress) pager.scrollToPage(shelf.page) }
    val pagePos by remember { derivedStateOf { pager.currentPage + pager.currentPageOffsetFraction } }

    val top = statusTop + AppBarHeight
    val bottom = bottomInset + MusicBarHeight + (if (cur != null) miniRoom else 0.dp) + 8.dp

    // Namida pushes a page the iOS way: in from the right over 400 ms, quick to leave and slow
    // to arrive, the pages under it drawn a third of the way aside; back, the same in reverse.
    val push = remember { Animatable(if (shelf.open != null) 1f else 0f) }
    var shownAlbum by remember { mutableStateOf(shelf.open) }
    LaunchedEffect(shelf.open) {
        val target = shelf.open
        if (target != null) {
            val fly = shelf.heroKey == target.key && shelf.heroFrom != null
            shownAlbum = target
            shelf.heroFlying = fly
            push.animateTo(1f, tween(400, easing = LinearToEaseOut))
            shelf.heroFlying = false
        } else if (shownAlbum != null) {
            shelf.heroFlying = shelf.heroKey == shownAlbum?.key && shelf.heroFrom != null && shelf.heroTo != null
            // Back the same way round (Flutter plays the push backwards): quick to leave, slow to arrive.
            push.animateTo(0f, tween(400, easing = LinearToEaseOut))
            shelf.heroFlying = false
            shownAlbum = null
            shelf.heroKey = null
        }
    }
    val widthPx = remember { floatArrayOf(0f) }
    val origin = remember { floatArrayOf(0f, 0f) }
    // The pages come in one after another the first time they show, as Namida's do.
    LaunchedEffect(Unit) { shelf.trackEntrance.restart(); shelf.albumEntrance.restart(); shelf.likedEntrance.restart() }

    CompositionLocalProvider(
        LocalNamida provides nc,
        LocalPalette provides nc.asPalette(base),
        LocalTextStyle provides Nm.medium,
    ) {
        // As Namida's pages do, the screen sinks back a little (5%) as the player opens over it.
        Box(Modifier.fillMaxSize().background(nc.bg)
            .onGloballyPositioned { c -> widthPx[0] = c.size.width.toFloat(); val o = c.boundsInRoot(); origin[0] = o.left; origin[1] = o.top }
            .graphicsLayer {
                if (cur == null) return@graphicsLayer
                val k = 1f - 0.05f * motion.p.coerceIn(0f, 1f)
                scaleX = k; scaleY = k
            }) {
            // ---- Tracks, Albums and Liked, a swipe apart; a third aside while an album is pushed over them
            HorizontalPager(
                pager, Modifier.fillMaxSize().offset { IntOffset((-push.value * widthPx[0] / 3f).roundToInt(), 0) },
                key = { it },
            ) { page ->
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val off = abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                    alpha = 1f - 0.5f * off
                }) {
                    when (page) {
                        0 -> TracksPage(shelf, now, hearts, acts, top, bottom, requestMusic)
                        1 -> AlbumsPage(shelf, now, top, bottom, requestMusic, onPlay = { acts.play(it.tracks, 0) })
                        else -> LikedPage(shelf, now, likedOrder, hearts, acts, top, bottom, requestMusic)
                    }
                }
            }

            // ---- an album, pushed over them, with the iOS shadow along its left edge
            shownAlbum?.let { al ->
                key(al.key) {
                    Box(
                        Modifier.fillMaxSize()
                            .offset { IntOffset(((1f - push.value) * widthPx[0]).roundToInt(), 0) }
                            .drawBehind {
                                val w = 18.dp.toPx()
                                drawRect(
                                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.16f * push.value)), startX = -w, endX = 0f),
                                    topLeft = Offset(-w, 0f), size = Size(w, size.height),
                                )
                            },
                    ) {
                        AlbumPage(al, shelf, now, hearts, acts, top, bottom, pushOffset = { (1f - push.value) * widthPx[0] })
                    }
                }
            }

            // ---- the hero: the cover flying between the grid and the album's page
            // Always there while its album is open, and shown by the same flag, read at drawing, that
            // hides the covers it stands in for: so no frame shows both, or neither.
            val heroAlbum = (shelf.open ?: shownAlbum)?.takeIf { it.key == shelf.heroKey }
            if (heroAlbum != null) {
                val density = LocalDensity.current
                Box(
                    Modifier
                        .layout { m, _ ->
                            val r = heroRect(shelf, push.value)
                            val pl = m.measure(Constraints.fixed(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)))
                            layout(pl.width, pl.height) { pl.place((r.left - origin[0]).roundToInt(), (r.top - origin[1]).roundToInt()) }
                        }
                        .graphicsLayer {
                            alpha = if (shelf.heroFlying) 1f else 0f
                            shape = RoundedCornerShape(with(density) { androidx.compose.ui.util.lerp(10f, 12f, push.value).dp.toPx() })
                            clip = true
                        },
                ) { Cover(heroAlbum.coverId, heroAlbum.title, Modifier.fillMaxSize(), radius = 0.dp, big = true) }
            }

            // The app bar, with the page's count and what can be done to all of it on the left.
            val barPage by remember { derivedStateOf { pagePos.roundToInt().coerceIn(0, 2) } }
            AppBar(shelf, statusTop, barPage) { page ->
                when (page) {
                    0 -> {
                        val songs = remember(shelf.tracks, shelf.query) { shelf.songs() }
                        BarIcon(Iconsax.Shuffle, "Shuffle every song") { acts.shuffle(songs) }
                        Spacer(Modifier.width(10.dp))
                        BarIcon(Iconsax.Play, "Play every song") { acts.play(songs, 0) }
                        Spacer(Modifier.width(10.dp))
                        Text(count(songs.size, "Track"), style = Nm.medium, maxLines = 1)
                    }
                    1 -> {
                        val albums = remember(shelf.albums, shelf.query) { shelf.albumMatches() }
                        val quiet = Nm.c.onSecondaryContainer.copy(alpha = 0.8f)
                        Icon(Iconsax.Arrange, null, tint = quiet, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(count(albums.size, "Album"), style = Nm.medium.copy(color = quiet), maxLines = 1)
                    }
                    else -> {
                        val songs = remember(shelf.tracks, shelf.query, likedOrder) { shelf.liked(likedOrder) }
                        BarIcon(Iconsax.Shuffle, "Shuffle the liked songs") { acts.shuffle(songs) }
                        Spacer(Modifier.width(10.dp))
                        BarIcon(Iconsax.Play, "Play the liked songs") { acts.play(songs, 0) }
                        Spacer(Modifier.width(10.dp))
                        Text(count(songs.size, "Liked song"), style = Nm.medium, maxLines = 1)
                    }
                }
            }

            // ---- Namida's bottom bar: Tracks, Albums and Liked; it sinks away as the player opens
            NavBar(
                position = pagePos,
                bottomInset = bottomInset,
                modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer {
                    if (cur == null) return@graphicsLayer
                    val cp = motion.p.coerceIn(0f, 1f)
                    translationY = cp * size.height
                },
            ) { i ->
                shelf.open = null
                scope.launch { pager.animateScrollToPage(i, animationSpec = tween(420, easing = PageEase)) }
            }

            NamidaSnackHost(acts.snack, statusTop)
        }
    }
}

/** The app's palette in Namida's colours, for the few shared pieces drawn on these screens. */
internal fun NamidaColors.asPalette(base: Palette): Palette = base.copy(
    bg = bg, surface = card, surface2 = secondaryContainer,
    text = large, muted = small, faint = small.copy(alpha = small.alpha * 0.6f),
    outline = onSurface.copy(alpha = 0.12f), accent = primary, onAccent = if (dark) Color.Black else Color.White,
)

/** What a song's tile and the pages can do with songs. */
private class TrackActions(val player: PhonePlayer, val shelf: MusicShelf, val favourites: Favourites) {
    fun play(list: List<TrackDto>, i: Int) { if (list.isNotEmpty()) player.play(list, i) }
    fun shuffle(list: List<TrackDto>) { if (list.isNotEmpty()) player.play(list, list.indices.random(), shuffle = true) }
    fun next(t: TrackDto) = player.playNext(listOf(t))
    fun last(list: List<TrackDto>) = player.playLast(list)
    fun album(t: TrackDto) { shelf.albumOf[t.id]?.let { shelf.openAlbum(it) } }
    fun heart(t: TrackDto) = favourites.toggle(t.id)
    /** Namida's note along the top, for what was done out of sight (a song swiped to play next). */
    val snack = NamidaSnack()
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
    val list = shelf.trackList
    Column(Modifier.fillMaxSize().padding(top = top)) {
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(top = 6.dp, bottom = bottom)) {
            noteFor(shelf, songs.isEmpty())?.let { n -> item(key = "note") { LibraryNote(n, requestMusic) } }
            itemsIndexed(songs, key = { _, t -> t.id }) { i, t ->
                TrackTile(
                    t, shelf.coverOf(t), current = t.id == playingId, hearted = t.id in hearts, acts = acts,
                    modifier = Modifier.entrance(shelf.trackEntrance, i - list.firstVisibleItemIndex),
                ) { acts.play(songs, i) }
            }
        }
    }
}

/**
 * The songs with a heart, newest first: the same tiles as Tracks, with shuffle and play over
 * them. A heart taken off here takes the song off the list.
 */
@Composable
private fun LikedPage(
    shelf: MusicShelf,
    now: PhonePlayer.State,
    order: List<Long>,
    hearts: Set<Long>,
    acts: TrackActions,
    top: Dp,
    bottom: Dp,
    requestMusic: () -> Unit,
) {
    val songs = remember(shelf.tracks, shelf.query, order) { shelf.liked(order) }
    val playingId = now.current?.id
    val list = shelf.likedList
    Column(Modifier.fillMaxSize().padding(top = top)) {
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(top = 6.dp, bottom = bottom)) {
            val note = noteFor(shelf, songs.isEmpty() && shelf.query.isNotBlank())
                ?: if (songs.isEmpty()) "No liked songs yet. Tap the heart on a song to keep it here." else null
            note?.let { n -> item(key = "note") { LibraryNote(n, requestMusic) } }
            itemsIndexed(songs, key = { _, t -> t.id }) { i, t ->
                TrackTile(
                    t, shelf.coverOf(t), current = t.id == playingId, hearted = t.id in hearts, acts = acts,
                    modifier = Modifier.entrance(shelf.likedEntrance, i - list.firstVisibleItemIndex),
                ) { acts.play(songs, i) }
            }
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
    val grid = shelf.albumGrid
    Column(Modifier.fillMaxSize().padding(top = top)) {
        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.weight(1f), state = grid,
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 8.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            noteFor(shelf, albums.isEmpty())?.let { n -> item(key = "note", span = { GridItemSpan(maxLineSpan) }) { LibraryNote(n, requestMusic) } }
            itemsIndexed(albums, key = { _, a -> a.key }) { i, a ->
                // Namida's grid comes in by row and column, each card over 400/3 ms.
                val first = grid.firstVisibleItemIndex
                AlbumCard(
                    a, shelf, playing = a.key == playingKey, sounding = now.playing, onPlay = { onPlay(a) },
                    modifier = Modifier.entrance(shelf.albumEntrance, (i / 3 - first / 3) + i % 3, duration = 400 / 3),
                )
            }
        }
    }
}

/**
 * An album's page, as Namida's: the cover in its frame with the name, who it is by and the
 * year, shuffle and Play Last; then how many songs and how long, in the order chosen; then its
 * songs, each cover marked with the song's number.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumPage(
    a: Album,
    shelf: MusicShelf,
    now: PhonePlayer.State,
    hearts: Set<Long>,
    acts: TrackActions,
    top: Dp,
    bottom: Dp,
    pushOffset: () -> Float,
) {
    val list = remember(a.key) { LazyListState() }
    val songs = remember(a, shelf.albumSort, shelf.albumDesc, shelf.albumQuery) { shelf.albumTracks(a) }
    val playingId = now.current?.id
    // Under the app bar, so the tracks bar sticks just below it once scrolled to, as Namida's does.
    LazyColumn(Modifier.fillMaxSize().background(Nm.c.bg).padding(top = top), state = list, contentPadding = PaddingValues(bottom = bottom)) {
        item(key = "head") { AlbumHead(a, shelf, pushOffset, onShuffle = { acts.shuffle(a.tracks) }, onPlayLast = { acts.last(a.tracks) }) }
        stickyHeader(key = "bar") {
            CountBar(Modifier.background(Nm.c.bg)) {
                Icon(Iconsax.Note, null, tint = Nm.c.icon, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(count(a.tracks.size, "Track") + " - " + fmtMinutes(a.durationMs), style = Nm.medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                SortButton(shelf.albumSort) { shelf.albumSort = it }
                BarIcon(if (shelf.albumDesc) Iconsax.Up else Iconsax.Down, if (shelf.albumDesc) "Last first" else "First first", size = 20.dp) {
                    shelf.albumDesc = !shelf.albumDesc
                }
                Spacer(Modifier.width(6.dp))
                BarIcon(Iconsax.Search, "Search this album", size = 20.dp) { shelf.albumSearching = true }
            }
        }
        if (songs.isEmpty()) item(key = "none") { LibraryNote("Nothing matches", {}) }
        // In disc order, an album on more than one disc is split by disc, each under its header.
        val byDisc = shelf.albumSort == 0 && songs.map { it.disc }.distinct().size > 1
        songs.forEachIndexed { i, t ->
            if (byDisc && (i == 0 || songs[i - 1].disc != t.disc)) item(key = "disc-${t.disc}") {
                DiscHeader(t.disc, songs.filter { it.disc == t.disc }, Modifier.entrance(shelf.pageEntrance, i + 2))
            }
            item(key = t.id) {
                TrackTile(
                    t, a.coverId, current = t.id == playingId, hearted = t.id in hearts, acts = acts,
                    number = if (t.track > 0) t.track else a.tracks.indexOf(t) + 1, inAlbum = true,
                    modifier = Modifier.entrance(shelf.pageEntrance, i + 2),
                ) { acts.play(songs, i) }
            }
        }
    }
}

/**
 * Namida's header over one disc of an album: the disc on a tab of the secondary colour, and how
 * many songs are on it and how long they run, on the right.
 */
@Composable
private fun DiscHeader(disc: Int, tracks: List<TrackDto>, modifier: Modifier = Modifier) {
    val nc = Nm.c
    Row(modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Row(
            Modifier.clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                .background(nc.secondaryContainer.copy(alpha = 0.5f).compositeOver(nc.bg)).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Iconsax.Cd, "Disc", tint = nc.icon, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(4.dp))
            Text(" $disc", style = Nm.medium)
        }
        Text(
            count(tracks.size, "Track") + " \u2022 " + fmtMinutes(tracks.sumOf { it.durationMs }),
            style = Nm.small.copy(fontWeight = FontWeight.Medium), maxLines = 1,
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        )
    }
}

// ---------------------------------------------------------------------------- pieces

/** Namida's bar over a page: 48 high, 18 in from the left; what can be done to all, and how many. */
@Composable
private fun CountBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().height(CountBarHeight).padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Namida's icon button: the icon alone, in its icon colour, with a little room around it. */
@Composable
private fun BarIcon(icon: ImageVector, description: String, size: Dp = 18.dp, tint: Color = Nm.c.icon, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 2.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(size)) }
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
    val nc = Nm.c
    if (note == PERMISSION) Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(nc.card).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Iconsax.Note, null, tint = nc.icon, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text("Localhost 8787 needs to read your music", style = Nm.large)
        }
        Spacer(Modifier.height(6.dp))
        Text("Only music: not your photos or your other files. The same permission lets the laptop play it.", style = Nm.small)
        Spacer(Modifier.height(14.dp))
        NamidaButton("Allow music access", null, Modifier.fillMaxWidth(), onClick = requestMusic)
    }
    else Text(note, style = Nm.medium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(28.dp))
}

/**
 * A song, as Namida lists one: the cover, the title, who it is by, the album and year;
 * how long it is and its heart on the right; and the menu. On the tile colour; the song playing
 * is lit in the song's colour with white words, and its cover gives a little.
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val nc = Nm.c
    val white = Color.White
    // Lit and unlit over 300 ms, the cover giving a little over 400, as Namida's tiles change.
    val lit by animateFloatAsState(if (current) 1f else 0f, tween(300), label = "lit")
    val card = nc.card.copy(alpha = 0.9f)
    val bg = if (lit > 0f) Brush.linearGradient(0.6f to lerp(card, nc.main, lit), 1f to lerp(card, nc.tint.copy(alpha = 0.4f), lit))
    else SolidColor(card)
    val shrink by animateFloatAsState(if (current) 0.96f else 1f, tween(400, easing = FastOutSlowInEasing), label = "thumb")
    fun ink(normal: Color, a: Int) = lerp(normal, white.copy(alpha = a / 255f), lit)
    val heartTint = ink(nc.main.copy(alpha = 0.4f).compositeOver(nc.medium.copy(alpha = nc.medium.alpha * 140 / 255f * 0.4f)), 140)
    // Namida's swipe: pull the tile left and Play After shows from under it; let go past it and
    // the song plays after the one playing. A pull to the right is left for the pages' swipe.
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val dx = remember { Animatable(0f) }
    var widthPx by remember { mutableIntStateOf(0) }
    val reach = with(LocalDensity.current) { 96.dp.toPx() }
    Box(modifier.fillMaxWidth().padding(bottom = NamidaTileGap).onSizeChanged { widthPx = it.width }) {
        Box(Modifier.matchParentSize().background(nc.bg), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.padding(end = 8.dp).width(104.dp).fillMaxHeight().padding(vertical = 6.dp)
                    .graphicsLayer {
                        val f = -dx.value / reach
                        alpha = f.coerceIn(0f, 1f)
                        val s = 0.85f + 0.15f * f.coerceIn(0f, 1f)
                        scaleX = s; scaleY = s
                    }
                    .clip(RoundedCornerShape(12.dp)).background(nc.card),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Iconsax.Next, null, tint = nc.icon, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(6.dp))
                Text("Play After", style = Nm.small, maxLines = 1)
            }
        }
        Row(
            Modifier.fillMaxWidth().height(NamidaTileHeight)
                .offset { IntOffset(dx.value.roundToInt(), 0) }
                .pointerInput(t.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var start = 0f
                        val took = awaitHorizontalTouchSlopOrCancellation(down.id) { ch, over ->
                            if (over < 0f) { ch.consume(); start = over }
                        } ?: return@awaitEachGesture
                        var x = (dx.value + start).coerceAtMost(0f)
                        var armed = false
                        horizontalDrag(took.id) { ch ->
                            x = (x + ch.positionChange().x).coerceIn(-widthPx * 0.6f, 0f)
                            ch.consume()
                            scope.launch { dx.snapTo(x) }
                            val now = -x > reach
                            if (now != armed) {
                                armed = now
                                if (now) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                        if (armed) {
                            acts.next(t)
                            acts.snack.show("Up next: ${t.title}")
                        }
                        scope.launch { dx.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 420f)) }
                    }
                }
                .background(bg).clickable(onClick = onClick).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(12.dp))
            // Clipped to the cover's corners, so the number's chip sits flush in its corner.
            Box(Modifier.size(NamidaThumb).graphicsLayer { scaleX = shrink; scaleY = shrink; shape = RoundedCornerShape(8.dp); clip = true }) {
                Cover(coverId, t.album, Modifier.fillMaxSize(), radius = 8.dp)
                if (number != null) FrostedChip(
                    coverId, t.album, NamidaThumb, Alignment.BottomEnd, RoundedCornerShape(topStart = 4.dp),
                    androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 1.dp),
                    Modifier.align(Alignment.BottomEnd),
                ) { Text("$number", style = Nm.small) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(t.title, style = Nm.medium.copy(color = ink(nc.medium, 170)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist, style = Nm.small.copy(fontWeight = FontWeight.Medium, color = ink(nc.small, 140)),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(t.album, t.year.takeIf { it > 0 }?.toString()).joinToString(" • "),
                    style = Nm.small.copy(color = ink(nc.small, 130)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                val kind = remember(t.mime) { formatBadge(t.mime) }
                if (kind.isNotEmpty()) TypeLabel(kind, ink(if (kind in LOSSLESS) nc.primary else nc.small, 170))
                Text(fmtTime(t.durationMs), style = Nm.small.copy(fontWeight = FontWeight.Medium, color = ink(nc.small, 170)))
                Box(Modifier.size(28.dp).clip(CircleShape).clickable { acts.heart(t) }, contentAlignment = Alignment.Center) {
                    Icon(if (hearted) Iconsax.HeartOn else Iconsax.Heart, if (hearted) "Take the heart off" else "Give it a heart",
                        tint = heartTint, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(2.dp))
            TrackMenu(t, acts, inAlbum, ink(nc.icon, 160))
            Spacer(Modifier.width(4.dp))
        }
    }
}

/** What kind of file a song is (FLAC, MP3, OPUS...): a small outlined label, lossless ones in the lit colour. */
@Composable
private fun TypeLabel(kind: String, color: Color) {
    Text(
        kind, style = Nm.small.copy(fontSize = 9.nsp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp, color = color, lineHeight = 11.nsp),
        modifier = Modifier.padding(bottom = 2.dp).clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = color.alpha * 0.12f))
            .border(0.5.dp, color.copy(alpha = color.alpha * 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** The kinds of file that keep every bit of the recording. */
private val LOSSLESS = setOf("FLAC", "ALAC", "WAV", "AIFF", "APE", "WV", "DSD")

/** A song's menu, from Namida's "more" turned upright: play it next, play it last, or go to its album. */
@Composable
private fun TrackMenu(t: TrackDto, acts: TrackActions, inAlbum: Boolean, tint: Color) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.clip(RoundedCornerShape(4.dp)).clickable { open = true }.padding(6.dp), contentAlignment = Alignment.Center) {
            Icon(Iconsax.More, "More for ${t.title}", tint = tint, modifier = Modifier.size(18.dp).rotate(90f))
        }
        if (open) NamidaMenu(onDismiss = { open = false }) { close ->
            NamidaMenuItem(Iconsax.Next, "Play next", onClick = { close(); acts.next(t) })
            NamidaMenuItem(Iconsax.PlayLast, "Play last", onClick = { close(); acts.last(listOf(t)) })
            if (!inAlbum) NamidaMenuItem(Iconsax.Albums, "Go to album", onClick = { close(); acts.album(t) })
        }
    }
}

/**
 * An album in the grid, as Namida's card: on the card colour, rounded 12, with a soft shadow;
 * the cover with the year in a frosted corner and a play button in the other; under it the
 * name, who it is by, and how many songs and how long, sized to the card.
 */
@Composable
private fun AlbumCard(a: Album, shelf: MusicShelf, playing: Boolean, sounding: Boolean, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val nc = Nm.c
    // Where the cover is, for the hero when it is tapped; it hides while its hero is flying.
    val where = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    val onOpen = { shelf.openAlbum(a, where[0]) }
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(0.75f).padding(horizontal = 4.dp)) {
        val cardW = maxWidth
        val img = cardW
        val left = maxHeight - img
        val m = img.value * 0.015f
        fun font(k: Float) = min(left.value * k * 0.9f, 15f).nsp
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier.fillMaxSize().shadow(6.dp, shape, ambientColor = nc.shadow.copy(alpha = 50 / 255f), spotColor = nc.shadow.copy(alpha = 50 / 255f))
                .clip(shape).background(nc.cardColor.copy(alpha = 0.9f)).clickable(onClick = onOpen),
        ) {
            Box(Modifier.size(img).clip(RoundedCornerShape(10.dp))) {
                Cover(a.coverId, a.title, Modifier.fillMaxSize()
                    .onGloballyPositioned { where[0] = it.boundsInRoot() }
                    .graphicsLayer { alpha = if (shelf.heroFlying && shelf.heroKey == a.key) 0f else 1f }, radius = 10.dp)
                // The year, frosted into the cover's corner, as Namida's.
                if (a.year > 0) FrostedChip(
                    a.coverId, a.title, img, Alignment.TopEnd, RoundedCornerShape(bottomStart = 8.dp),
                    androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    Modifier.align(Alignment.TopEnd),
                ) { Text("${a.year}", style = Nm.small.copy(fontSize = font(0.18f), fontWeight = FontWeight.Bold), maxLines = 1) }
                val bgA = (img.value / 200f).coerceIn(0f, 1f)
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(end = (2f + m).dp, bottom = (2f + m).dp)
                        .shadow(3.dp, RoundedCornerShape(min(8f, img.value * 0.07f).dp), ambientColor = nc.cardColor, spotColor = nc.cardColor)
                        .clip(RoundedCornerShape(min(8f, img.value * 0.07f).dp)).background(nc.cardColor.copy(alpha = bgA))
                        .clickable(onClick = onPlay).padding((2.5f + m).dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val sz = (8.5f + 3f * m).dp
                    if (playing) PlayingBars(nc.icon, sounding, Modifier.size(sz))
                    else Icon(Iconsax.Play, "Play ${a.title}", tint = nc.icon, modifier = Modifier.size(sz))
                }
            }
            Column(Modifier.fillMaxWidth().height(left).padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center) {
                Text(a.title, style = Nm.medium.copy(fontSize = font(0.28f), color = if (playing) nc.primary else nc.medium), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                Text(a.artist, style = Nm.small.copy(fontSize = font(0.23f)), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                Text(count(a.tracks.size, "Track") + " • " + fmtMinutes(a.durationMs), style = Nm.small.copy(fontSize = font(0.23f)),
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The top of an album's page, as Namida's: the cover in its frame (a rim of the card colour,
 * rounded 18, with a shadow) and beside it the name, who it is by and the year, sized to the
 * room, then shuffle and Play Last.
 */
@Composable
private fun AlbumHead(a: Album, shelf: MusicShelf, pushOffset: () -> Float, onShuffle: () -> Unit, onPlayLast: () -> Unit) {
    val nc = Nm.c
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) {
        val imgMax = min(maxWidth.value * 0.4f, 260f)
        val infoW = maxWidth.value - imgMax
        fun font(p: Float, lo: Float, hi: Float) = (infoW * 0.2f * p).coerceIn(lo, hi).nsp
        Row(verticalAlignment = Alignment.CenterVertically) {
            val frame = RoundedCornerShape(18.dp)
            Box(
                Modifier.padding(horizontal = 12.dp).size((imgMax - 24f).dp)
                    .shadow(8.dp, frame, ambientColor = nc.shadow, spotColor = nc.shadow)
                    .clip(frame).background(nc.card.copy(alpha = 180 / 255f)).padding(3.dp),
            ) {
                // Where the hero lands: as it will be once the page has slid in. Hidden while it flies.
                Cover(a.coverId, a.title, Modifier.fillMaxSize()
                    .onGloballyPositioned { shelf.heroTo = it.boundsInRoot().translate(-pushOffset(), 0f) }
                    .graphicsLayer { alpha = if (shelf.heroFlying && shelf.heroKey == a.key) 0f else 1f }, radius = 12.dp, big = true)
            }
            Column(Modifier.weight(1f)) {
                Spacer(Modifier.height(18.dp))
                Text(a.title, style = Nm.large.copy(fontSize = font(0.4f, 10f, 32f)), maxLines = 1, softWrap = false,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp))
                Spacer(Modifier.height(2.dp))
                Text(a.artist, style = Nm.medium.copy(fontSize = font(0.28f, 10f, 24f)), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp))
                if (a.year > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text("${a.year}", style = Nm.small.copy(fontSize = font(0.25f, 10f, 22f)), maxLines = 1, modifier = Modifier.padding(start = 14.dp))
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.padding(start = 6.dp)) {
                    NamidaButton(null, Iconsax.Shuffle, onClick = onShuffle)
                    Spacer(Modifier.width(6.dp))
                    NamidaButton("Play Last", Iconsax.PlayLast, onClick = onPlayLast)
                }
            }
        }
    }
}

private val SORTS = listOf("Disc Number", "Title", "Duration", "Artist")
/** Namida's icons for the four. */
private val SORT_ICONS = listOf(Iconsax.Hashtag, Iconsax.Music, Iconsax.Clock, Iconsax.Microphone)

/** Namida's sort button: the order's name as a text button; a tap offers the others. */
@Composable
private fun SortButton(sort: Int, onSort: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            SORTS[sort], style = Nm.medium.copy(fontSize = (15f * 0.95f).nsp, color = Nm.c.primary), maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { open = true }.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        if (open) NamidaMenu(onDismiss = { open = false }) { close ->
            SORTS.forEachIndexed { i, s -> NamidaMenuItem(SORT_ICONS[i], s, selected = i == sort, onClick = { close(); onSort(i) }) }
        }
    }
}

/**
 * Namida's app bar: 56 high, a shade lighter than the page, so a line of light to dark runs
 * along its foot. On the left, the page's count and what can be done to all of it ([lead]);
 * the search on the right. Opening the search (or an album) slides the count out to the left
 * as the search box grows across, and back when it closes; inside an album, back on the left.
 */
@Composable
private fun AppBar(shelf: MusicShelf, statusTop: Dp, page: Int, lead: @Composable RowScope.(Int) -> Unit) {
    val album = shelf.open
    val searching = if (album != null) shelf.albumSearching else shelf.searching
    val nc = Nm.c
    val inAlbum = album != null
    // Namida's search: the box grows leftwards out of the search button over 400 ms, and back.
    val open by animateFloatAsState(if (searching) 1f else 0f, tween(400, easing = PageEase), label = "search")
    val back by animateFloatAsState(if (inAlbum || searching) 1f else 0f, tween(300), label = "back")
    val value = if (inAlbum) shelf.albumQuery else shelf.query
    val set: (String) -> Unit = { if (inAlbum) shelf.albumQuery = it else shelf.query = it }
    val away by animateFloatAsState(if (inAlbum || searching) 1f else 0f, tween(400, easing = PageEase), label = "lead")
    Box(Modifier.fillMaxWidth().background(nc.appBar).padding(top = statusTop).height(AppBarHeight)) {
        if (away < 0.999f) androidx.compose.animation.Crossfade(
            page, label = "count",
            animationSpec = tween(220),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp, end = AppBarHeight + 8.dp)
                .graphicsLayer {
                    translationX = -away * (size.width * 0.6f + 18.dp.toPx())
                    alpha = (1f - away * 1.4f).coerceIn(0f, 1f)
                },
        ) { pg -> Row(verticalAlignment = Alignment.CenterVertically) { lead(pg) } }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(AppBarHeight).graphicsLayer { alpha = back }) {
                if (back > 0f) AppBarIcon(Iconsax.Back, if (searching) "Close the search" else "Back") { shelf.back() }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                if (open > 0.01f) SearchBox(
                    value, set, when { inAlbum -> "Search this album"; shelf.page == 0 -> "Search tracks"; shelf.page == 1 -> "Search albums"; else -> "Search liked songs" },
                    Modifier.width(maxWidth * open).graphicsLayer { alpha = (open * 2f).coerceAtMost(1f) },
                )
            }
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.graphicsLayer { alpha = 1f - open; rotationZ = -90f * open }) {
                    AppBarIcon(Iconsax.Search, "Search") { if (inAlbum) shelf.albumSearching = true else shelf.searching = true }
                }
                if (open > 0f) Box(Modifier.graphicsLayer { alpha = open; rotationZ = 90f * (1f - open) }) {
                    AppBarIcon(Iconsax.Clear, "Clear the search") { if (value.isEmpty()) shelf.back() else set("") }
                }
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun AppBarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(AppBarHeight).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Nm.c.icon, modifier = Modifier.size(24.dp))
    }
}

/** Namida's search box: the card colour, the hint in its small style at 17, what is typed in its medium one. */
@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    val nc = Nm.c
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val box = if (nc.dark) nc.cardColor.copy(alpha = 200 / 255f) else nc.cardColor.copy(alpha = 200 / 255f).compositeOver(nc.onSurface.copy(alpha = 40 / 255f))
    Box(modifier.height(42.dp).clip(CircleShape).background(box).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, style = Nm.small.copy(fontSize = 17.nsp), maxLines = 1)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = Nm.medium,
            cursorBrush = SolidColor(nc.onSurface),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

/**
 * Namida's bottom bar, a Material bar 64 high with Tracks, Albums and Liked: the one showing has
 * its icon on a pill of the indicator colour, which grows from the middle as a swipe reaches it,
 * and its name under it; the other shows its icon alone.
 */
@Composable
private fun NavBar(position: Float, bottomInset: Dp, modifier: Modifier, onSelect: (Int) -> Unit) {
    val nc = Nm.c
    Row(
        modifier.fillMaxWidth().shadow(22.dp, RoundedCornerShape(0.dp), ambientColor = nc.shadow, spotColor = nc.shadow)
            .background(nc.bar).padding(bottom = bottomInset).height(MusicBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("Tracks" to Iconsax.Tracks, "Albums" to Iconsax.Albums, "Liked" to Iconsax.Heart).forEachIndexed { i, (label, icon) ->
            val on = (1f - abs(position - i)).coerceIn(0f, 1f)
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.graphicsLayer { translationY = (1f - on) * 9.dp.toPx() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(width = 64.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = on; alpha = if (on > 0.02f) 1f else 0f }.clip(RoundedCornerShape(16.dp)).background(nc.indicator))
                        Icon(icon, label, tint = lerp(nc.icon, Color.White.copy(alpha = 0.75f), on), modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(label, style = TextStyle(fontFamily = LexendDeca, fontSize = 13.nsp, fontWeight = FontWeight.Medium, color = nc.onSurface),
                        modifier = Modifier.graphicsLayer { alpha = on })
                }
            }
        }
    }
}
