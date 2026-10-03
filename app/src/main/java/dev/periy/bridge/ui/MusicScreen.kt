package dev.periy.bridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInParent
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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.rememberUpdatedState
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
 * The phone's Music, full screen, with Namida's library underneath (github.com/namidaco/namida):
 * its pages a swipe apart, an album's own page pushed over them with the cover flying, the
 * search over whichever is showing, and every one of its motions; drawn clean and black (white
 * in the light theme), in the phone's own type, with no colour but the app's (cleanColors in
 * NamidaStyle.kt). The page in the browser is drawn the same way. Back returns to the app.
 */

/** The app bar, with the switch between the pages in it. */
private val AppBarHeight = 56.dp

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
    // The clean look: black (or white), nothing tinted by the song; what plays lit in the app's
    // colour, or in the type colour while that is Automatic.
    val cur = now.current
    val base = LocalPalette.current
    val nc = rememberCleanColors()

    val favourites = LocalContext.current.container.favourites
    val hearts by favourites.ids.collectAsState()
    val likedOrder by favourites.order.collectAsState()
    val acts = remember(player, shelf, favourites) { TrackActions(player, shelf, favourites) }

    val scope = rememberCoroutineScope()
    // Laid out Albums, Songs, Liked, as the switch in the bar reads; shelf.page keeps its own
    // numbering (0 songs, 1 albums, 2 liked), which the rest of the app goes by.
    val pager = rememberPagerState(initialPage = PAGE_ORDER.indexOf(shelf.page).coerceAtLeast(0)) { 3 }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { shelf.page = PAGE_ORDER[it] } }
    LaunchedEffect(shelf.page) {
        val want = PAGE_ORDER.indexOf(shelf.page)
        if (want >= 0 && pager.settledPage != want && !pager.isScrollInProgress) pager.scrollToPage(want)
    }
    val pagePos by remember { derivedStateOf { pager.currentPage + pager.currentPageOffsetFraction } }

    // The pages and an album's page alike start under the bar.
    val albumTop = statusTop + AppBarHeight
    val top = albumTop
    val bottom = bottomInset + (if (cur != null) miniRoom else 0.dp) + 16.dp

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
    // Back from an album follows the finger (Android 14 on): the page slides off to the right as
    // it is swiped, the pages under it coming back from a third aside; let go, and the rest of the
    // way plays out as the push backwards; swiped back out, it slides home again. (The player,
    // open over it, takes back first; so does a search on the album's page.)
    androidx.activity.compose.PredictiveBackHandler(
        enabled = shelf.showing && shelf.open != null && !shelf.albumSearching && motion.p < 0.5f,
    ) { events ->
        try {
            events.collect { e -> push.snapTo(1f - e.progress) }
            shelf.open = null
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            scope.launch { push.animateTo(1f, tween(300, easing = LinearToEaseOut)) }
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
            // ---- Albums, Songs and Liked, a swipe apart; a third aside while an album is pushed over them
            HorizontalPager(
                pager, Modifier.fillMaxSize().offset { IntOffset((-push.value * widthPx[0] / 3f).roundToInt(), 0) },
                key = { it },
            ) { index ->
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val off = abs((pager.currentPage - index) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                    alpha = 1f - 0.5f * off
                }) {
                    when (PAGE_ORDER[index]) {
                        0 -> TracksPage(shelf, now, hearts, acts, top, bottom + 72.dp, requestMusic)
                        1 -> AlbumsPage(shelf, now, top, bottom, requestMusic)
                        else -> LikedPage(shelf, now, likedOrder, hearts, acts, top, bottom + 72.dp, requestMusic)
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
                        AlbumPage(al, shelf, now, hearts, acts, albumTop, bottom, pushOffset = { (1f - push.value) * widthPx[0] })
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

            // The app bar: the switch between the three in its middle, how many on each beside its
            // name; the lit pill follows a swipe between the pages, and a tap slides to one.
            AppBar(shelf, statusTop) {
                val counts = listOf(
                    remember(shelf.albums, shelf.query) { shelf.albumMatches() }.size,
                    remember(shelf.tracks, shelf.query) { shelf.songs() }.size,
                    remember(shelf.tracks, shelf.query, likedOrder) { shelf.liked(likedOrder) }.size,
                )
                PageSwitch(counts, { pagePos }, Modifier.fillMaxWidth()) { i ->
                    shelf.open = null
                    scope.launch { pager.animateScrollToPage(i, animationSpec = tween(420, easing = PageEase)) }
                }
            }

            // The mini player's foot: the page's own colour from just above it to the bottom of the
            // screen, the list fading into it, so no song shows (or is swiped) around the player.
            // It takes the touches that land on it, outside the player.
            if (cur != null) Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottomInset + miniRoom)
                    .background(Brush.verticalGradient(0f to nc.bg.copy(alpha = 0f), 0.14f to nc.bg, 1f to nc.bg))
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
            )

            // Shuffle-play, floating at the bottom right over Songs and Liked (whichever shows,
            // and for what it lists), above the mini player; they shrink away towards the albums
            // or under an album's page.
            val songsNow = remember(shelf.tracks, shelf.query) { shelf.songs() }
            val likedNow = remember(shelf.tracks, shelf.query, likedOrder) { shelf.liked(likedOrder) }
            FloatingShuffle(
                shown = {
                    val p = pagePos
                    val k = maxOf(1f - abs(p - 1f), 1f - abs(p - 2f)).coerceIn(0f, 1f)
                    val list = if (p >= 1.5f) likedNow else songsNow
                    if (list.isEmpty()) 0f else k * (1f - push.value)
                },
                onShuffle = { val l = if (pagePos >= 1.5f) likedNow else songsNow; acts.shuffle(l) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottom - 4.dp),
            )

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
) {
    val albums = remember(shelf.albums, shelf.query) { shelf.albumMatches() }
    val playingKey = now.current?.let { shelf.albumOf[it.id]?.key }
    val grid = shelf.albumGrid
    val cols = shelf.albumCols
    val view = LocalView.current
    Column(Modifier.fillMaxSize().padding(top = top)) {
        LazyVerticalGrid(
            GridCells.Fixed(cols),
            Modifier.weight(1f)
                // Two fingers pinch the grid: apart for bigger covers (fewer across), together for
                // more of them; one step each time the pinch goes a quarter further. One finger
                // is left to scroll.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var scale = 1f
                        while (true) {
                            val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            if (ev.changes.none { it.pressed }) break
                            if (ev.changes.count { it.pressed } < 2) continue
                            scale *= ev.calculateZoom()
                            ev.changes.forEach { it.consume() }
                            val step = when {
                                scale > 1.25f && shelf.albumCols > 2 -> -1
                                scale < 0.8f && shelf.albumCols < 4 -> 1
                                else -> 0
                            }
                            if (step != 0) {
                                shelf.albumCols += step
                                scale = 1f
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                    }
                },
            state = grid,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = bottom),
            horizontalArrangement = Arrangement.spacedBy(if (cols == 2) 14.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (cols == 2) 18.dp else 14.dp),
        ) {
            noteFor(shelf, albums.isEmpty())?.let { n -> item(key = "note", span = { GridItemSpan(maxLineSpan) }) { LibraryNote(n, requestMusic) } }
            itemsIndexed(albums, key = { _, a -> a.key }) { i, a ->
                // Namida's grid comes in by row and column, each card over 400/3 ms; a change in
                // how many across slides each card to its new place.
                val first = grid.firstVisibleItemIndex
                AlbumCard(
                    a, shelf, playing = a.key == playingKey, sounding = now.playing, small = cols > 2,
                    modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = tween(320, easing = PageEase))
                        .entrance(shelf.albumEntrance, (i / cols - first / cols) + i % cols, duration = 400 / 3),
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
    LazyColumn(Modifier.fillMaxSize().background(Nm.c.bg).padding(top = top), state = list, contentPadding = PaddingValues(bottom = bottom)) {
        item(key = "head") { AlbumHead(a, shelf, pushOffset, acts) }
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
                    number = if (t.track > 0) t.track else a.tracks.indexOf(t) + 1, inAlbum = true, sounding = now.playing,
                    modifier = Modifier.entrance(shelf.pageEntrance, i + 2),
                ) { acts.play(songs, i) }
            }
        }
        item(key = "foot") {
            Text(
                count(a.tracks.size, "song") + ", " + fmtMinutes(a.durationMs),
                style = Nm.small.copy(fontSize = 13.sp), modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp),
            )
        }
    }
}

/** The head of one disc of an album: "Disc 2" in small dim capitals, with how long it runs. */
@Composable
private fun DiscHeader(disc: Int, tracks: List<TrackDto>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("DISC $disc", style = Nm.small.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp), modifier = Modifier.weight(1f))
        Text(fmtMinutes(tracks.sumOf { it.durationMs }), style = Nm.small.copy(fontSize = 12.sp))
    }
}

// ---------------------------------------------------------------------------- pieces

/** The pages left to right, by the shelf's numbers for them: Albums, Songs, Liked. */
private val PAGE_ORDER = listOf(1, 0, 2)

/**
 * The switch between Albums, Songs and Liked: three equal parts across a shallow track, each its
 * icon, its name and how many; the one showing lifted onto a pill of its own. The pill is drawn
 * at [position] (the pager's, so it slides with a swipe). Its type keeps its size whatever the
 * phone's text size, so the three always fit.
 */
@Composable
private fun PageSwitch(counts: List<Int>, position: () -> Float, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val nc = Nm.c
    val track = nc.onSurface.copy(alpha = if (nc.dark) 0.10f else 0.06f)
    val pill = if (nc.dark) Color(0xFF2C2C2E) else Color.White
    val edge = nc.onSurface.copy(alpha = if (nc.dark) 0.10f else 0.06f)
    val d = LocalDensity.current
    val labelSize = with(d) { 13.dp.toSp() }
    val countSize = with(d) { 11.dp.toSp() }
    val items = listOf(Triple("Albums", Iconsax.Albums, counts[0]), Triple("Songs", Iconsax.Music, counts[1]), Triple("Liked", Iconsax.Heart, counts[2]))
    val inset = 3.dp
    Row(
        modifier.height(38.dp).clip(CircleShape).background(track)
            .drawBehind {
                val ins = inset.toPx()
                val part = (size.width - 2 * ins) / 3f
                val p = position().coerceIn(0f, 2f)
                val r = androidx.compose.ui.geometry.CornerRadius((size.height - 2 * ins) / 2f)
                val top = Offset(ins + p * part, ins)
                val sz = Size(part, size.height - 2 * ins)
                // A soft shadow under the pill in the light theme; a hairline round it in both.
                if (!nc.dark) drawRoundRect(Color.Black.copy(alpha = 0.06f), top + Offset(0f, 1.dp.toPx()), sz, r)
                drawRoundRect(pill, top, sz, r)
                drawRoundRect(edge, top, sz, r, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
            }
            .padding(horizontal = inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, (label, icon, n) ->
            val on by remember { derivedStateOf { (1f - abs(position() - i)).coerceIn(0f, 1f) } }
            val ink = lerp(nc.onSurface.copy(alpha = 0.55f), nc.onSurface, on)
            Row(
                Modifier.weight(1f).fillMaxHeight()
                    .clip(CircleShape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (i == 2 && on > 0.5f) Iconsax.HeartOn else icon, null, tint = ink, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(label, style = TextStyle(fontFamily = MusicType, fontSize = labelSize, fontWeight = if (on > 0.5f) FontWeight.SemiBold else FontWeight.Medium, color = ink),
                    maxLines = 1, softWrap = false)
                Spacer(Modifier.width(4.dp))
                Text("$n", style = TextStyle(fontFamily = MusicType, fontSize = countSize, fontWeight = FontWeight.Medium, color = ink.copy(alpha = ink.alpha * 0.6f), fontFeatureSettings = "tnum"),
                    maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * Shuffle-play, floating over a list: one round button solid in the type colour, with the shuffle
 * on it. [shown] (0 to 1) grows and fades it in and out.
 */
@Composable
private fun FloatingShuffle(shown: () -> Float, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    val nc = Nm.c
    // Gone altogether while hidden, so it never takes a touch meant for what is under it.
    val now = rememberUpdatedState(shown)
    val present by remember { derivedStateOf { now.value() > 0.02f } }
    if (!present) return
    Box(
        modifier
            .graphicsLayer {
                val k = shown()
                alpha = k
                val sc = 0.6f + 0.4f * k
                scaleX = sc; scaleY = sc
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)
            }
            .size(56.dp).shadow(12.dp, CircleShape, ambientColor = nc.shadow, spotColor = nc.shadow)
            .clip(CircleShape).background(nc.onSurface)
            .pressable(CircleShape, scaleTo = 0.9f) { if (shown() > 0.5f) onShuffle() },
        contentAlignment = Alignment.Center,
    ) { Icon(Iconsax.Shuffle, "Shuffle play", tint = nc.bg, modifier = Modifier.size(22.dp)) }
}

/** The hairline between rows and around covers. */
@Composable
private fun hairline() = Nm.c.onSurface.copy(alpha = 0.12f)

/**
 * Play and Shuffle, side by side and the same width: Play a solid pill in the type colour, Shuffle
 * the same pill drawn as an outline.
 */
@Composable
private fun PlayShuffle(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val nc = Nm.c
    val ink = nc.onSurface
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).height(46.dp).clip(CircleShape).background(ink).clickable(onClick = onPlay),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(BlazeIcons.Play, null, tint = nc.bg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Play", style = TextStyle(fontFamily = MusicType, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = nc.bg))
        }
        Row(
            Modifier.weight(1f).height(46.dp).clip(CircleShape).border(1.dp, ink.copy(alpha = 0.35f), CircleShape).clickable(onClick = onShuffle),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Iconsax.Shuffle, null, tint = ink, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text("Shuffle", style = TextStyle(fontFamily = MusicType, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ink))
        }
        trailing?.invoke()
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
    sounding: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val nc = Nm.c
    // Lit and unlit over 300 ms, the cover giving a little over 400, as Namida's tiles change:
    // here the words take the lit colour rather than the tile.
    val lit by animateFloatAsState(if (current) 1f else 0f, tween(300), label = "lit")
    val shrink by animateFloatAsState(if (current) 0.94f else 1f, tween(400, easing = FastOutSlowInEasing), label = "thumb")
    val line = hairline()
    // Namida's swipe: pull the tile left and Play After shows from under it; let go past it and
    // the song plays after the one playing. A pull to the right is left for the pages' swipe.
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val dx = remember { Animatable(0f) }
    var widthPx by remember { mutableIntStateOf(0) }
    val reach = with(LocalDensity.current) { 96.dp.toPx() }
    Box(modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }) {
        Box(Modifier.matchParentSize().background(nc.bg), contentAlignment = Alignment.CenterEnd) {
            Row(
                Modifier.padding(end = 20.dp)
                    .graphicsLayer {
                        val f = -dx.value / reach
                        alpha = f.coerceIn(0f, 1f)
                        val s = 0.85f + 0.15f * f.coerceIn(0f, 1f)
                        scaleX = s; scaleY = s
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Iconsax.Next, null, tint = nc.icon, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Play next", style = Nm.small.copy(fontSize = 14.sp, color = nc.onSurface), maxLines = 1)
            }
        }
        Row(
            Modifier.fillMaxWidth().height(if (number != null) 50.dp else 62.dp)
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
                .background(nc.bg)
                // The song playing sits on a band of the song's colour, with a bar down its left.
                .drawBehind { playingBand(nc.primary, lit) }
                // A long press shows the song's album (from the songs and the liked; on an album's
                // own page it is already there).
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (inAlbum) null else ({
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        acts.album(t)
                    }),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(16.dp))
            if (number != null) {
                // In an album: the song's number, bold in the song's colour on the one playing.
                Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                    Text("$number", style = Nm.small.copy(fontSize = 14.sp, fontFeatureSettings = "tnum",
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, color = if (current) nc.primary else Nm.small.color), maxLines = 1)
                }
            } else Box(
                Modifier.size(46.dp).graphicsLayer { scaleX = shrink; scaleY = shrink }
                    .clip(RoundedCornerShape(6.dp)).border(0.5.dp, line, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Cover(coverId, t.album, Modifier.fillMaxSize(), radius = 6.dp)
            }
            Spacer(Modifier.width(14.dp))
            // The words, with the hairline under them (not under the cover), as a list in iOS.
            Row(
                Modifier.weight(1f).fillMaxHeight().drawBehind {
                    if (lit < 0.5f) drawLine(line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1f)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = MusicType, fontSize = 16.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                            color = lerp(nc.large, nc.primary, lit)),
                    )
                    if (!inAlbum) Text(t.artist, style = Nm.small.copy(fontSize = 14.sp, color = lerp(nc.small, nc.large.copy(alpha = 0.75f), lit)),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (hearted) Box(Modifier.size(30.dp).clip(CircleShape).clickable { acts.heart(t) }, contentAlignment = Alignment.Center) {
                    Icon(Iconsax.HeartOn, "Take the heart off", tint = nc.small, modifier = Modifier.size(14.dp))
                }
                Text(fmtTime(t.durationMs), style = Nm.small.copy(fontSize = 13.sp, fontFeatureSettings = "tnum"), modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.width(16.dp))
            }
        }
    }
}

/**
 * An album in the grid: the cover, square with a hairline round it, and under it the name and who
 * it is by. The one playing has a ring of the song's colour round its cover and its name lit.
 */
@Composable
private fun AlbumCard(a: Album, shelf: MusicShelf, playing: Boolean, sounding: Boolean, small: Boolean = false, modifier: Modifier = Modifier) {
    val nc = Nm.c
    val line = hairline()
    // Where the cover is, for the hero when it is tapped; it hides while its hero is flying.
    val where = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    val onOpen = { shelf.openAlbum(a, where[0]) }
    Column(modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen)) {
        val shape = RoundedCornerShape(8.dp)
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Cover(a.coverId, a.title, Modifier.fillMaxSize()
                .onGloballyPositioned { where[0] = it.boundsInRoot() }
                .graphicsLayer { alpha = if (shelf.heroFlying && shelf.heroKey == a.key) 0f else 1f }
                .border(if (playing) 3.dp else 0.5.dp, if (playing) nc.primary else line, shape), radius = 8.dp)
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.title, style = TextStyle(fontFamily = MusicType, fontSize = if (small) 12.sp else 14.sp,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium, color = if (playing) nc.primary else nc.large),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(a.artist, style = Nm.small.copy(fontSize = if (small) 12.sp else 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The top of an album's page: the cover in the middle with a hairline round it, the name under it,
 * who it is by, and a line of what it is (genre, year, and the file: FLAC · 16-bit · 44.1 kHz);
 * then Play and Shuffle, and the album's menu.
 */
@Composable
private fun AlbumHead(a: Album, shelf: MusicShelf, pushOffset: () -> Float, acts: TrackActions) {
    val nc = Nm.c
    val line = hairline()
    // What the album's files are, read from its first song off the main thread.
    val library = LocalContext.current.container.music
    val first = a.tracks.firstOrNull()
    val info by androidx.compose.runtime.produceState<dev.periy.bridge.server.TrackInfoDto?>(null, first?.id) {
        val id = first?.id ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { library.info(id) }.getOrNull() }
    }
    val genre = remember(a) { a.tracks.firstNotNullOfOrNull { t -> t.genre.takeIf { it.isNotBlank() } }.orEmpty() }
    val meta = listOfNotNull(
        genre.takeIf { it.isNotEmpty() },
        a.year.takeIf { it > 0 }?.toString(),
        info?.let { formatLine(it, first?.mime.orEmpty(), kbps = false) }?.takeIf { it.isNotEmpty() }
            ?: first?.let { formatBadge(it.mime) }?.takeIf { it.isNotEmpty() },
    ).joinToString(" · ")
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)) {
        val img = min(maxWidth.value - 96f, 270f).dp
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val shape = RoundedCornerShape(10.dp)
            Box(Modifier.size(img).shadow(18.dp, shape, ambientColor = nc.shadow, spotColor = nc.shadow)) {
                // Where the hero lands: as it will be once the page has slid in. Hidden while it flies.
                Cover(a.coverId, a.title, Modifier.fillMaxSize()
                    .onGloballyPositioned { shelf.heroTo = it.boundsInRoot().translate(-pushOffset(), 0f) }
                    .graphicsLayer { alpha = if (shelf.heroFlying && shelf.heroKey == a.key) 0f else 1f }
                    .border(0.5.dp, line, shape), radius = 10.dp, big = true)
            }
            Spacer(Modifier.height(16.dp))
            Text(a.title, style = TextStyle(fontFamily = MusicType, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = nc.large),
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(a.artist, style = TextStyle(fontFamily = MusicType, fontSize = 19.sp, color = nc.primary.takeIf { it != nc.large } ?: nc.small),
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(meta, style = Nm.small.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), textAlign = TextAlign.Center, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(18.dp))
            PlayShuffle({ acts.play(a.tracks, 0) }, { acts.shuffle(a.tracks) }) { AlbumMenu(a, shelf, acts) }
        }
    }
}

private val SORTS = listOf("Disc Number", "Title", "Duration", "Artist")
/** Namida's icons for the four. */
private val SORT_ICONS = listOf(Iconsax.Hashtag, Iconsax.Music, Iconsax.Clock, Iconsax.Microphone)

/**
 * The album's "···": play it next or last, search it, and the order of its songs (Namida's four,
 * the one chosen ticked; choosing it again turns it round).
 */
@Composable
private fun AlbumMenu(a: Album, shelf: MusicShelf, acts: TrackActions) {
    var open by remember { mutableStateOf(false) }
    val nc = Nm.c
    Box {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(nc.onSurface.copy(alpha = 0.09f)).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) { Icon(BlazeIcons.Dots, "More for ${a.title}", tint = nc.onSurface, modifier = Modifier.size(20.dp)) }
        if (open) NamidaMenu(onDismiss = { open = false }) { close ->
            NamidaMenuItem(Iconsax.Next, "Play next", onClick = { close(); acts.player.playNext(a.tracks) })
            NamidaMenuItem(Iconsax.PlayLast, "Play last", onClick = { close(); acts.last(a.tracks) })
            NamidaMenuItem(Iconsax.Search, "Search this album", onClick = { close(); shelf.albumSearching = true })
            SORTS.forEachIndexed { i, s ->
                val on = i == shelf.albumSort
                NamidaMenuItem(SORT_ICONS[i], if (on) s + if (shelf.albumDesc) "  ↓" else "  ↑" else s, selected = on, onClick = {
                    close()
                    if (on) shelf.albumDesc = !shelf.albumDesc else { shelf.albumSort = i; shelf.albumDesc = false }
                })
            }
        }
    }
}

/**
 * The app bar: the switch between the pages across it, and the search at its right end, both on
 * one line 16 in from the edges as the pages are. Opening the search (or an album) fades the
 * switch out first, then the search box grows leftwards out of its button over 400 ms, and back;
 * inside an album, or searching, back on the left.
 */
@Composable
private fun AppBar(shelf: MusicShelf, statusTop: Dp, lead: @Composable () -> Unit) {
    val album = shelf.open
    val searching = if (album != null) shelf.albumSearching else shelf.searching
    val nc = Nm.c
    val inAlbum = album != null
    val open by animateFloatAsState(if (searching) 1f else 0f, tween(400, easing = PageEase), label = "search")
    val back by animateFloatAsState(if (inAlbum || searching) 1f else 0f, tween(300), label = "back")
    val value = if (inAlbum) shelf.albumQuery else shelf.query
    val set: (String) -> Unit = { if (inAlbum) shelf.albumQuery = it else shelf.query = it }
    val away by animateFloatAsState(if (inAlbum || searching) 1f else 0f, tween(400, easing = PageEase), label = "lead")
    Box(Modifier.fillMaxWidth().background(nc.appBar).padding(top = statusTop).height(AppBarHeight)) {
        // The switch, from 16 in on the left to just short of the search button; gone in the
        // first third of the search opening, so the two are never seen over each other.
        if (away < 0.999f) Box(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp + BarButton + 8.dp)
                .graphicsLayer {
                    val k = 1f - 0.06f * away
                    scaleX = k; scaleY = k
                    alpha = (1f - away * 3f).coerceIn(0f, 1f)
                },
            contentAlignment = Alignment.Center,
        ) { lead() }
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(BarButton).graphicsLayer { alpha = back }) {
                if (back > 0f) AppBarIcon(Iconsax.Back, if (searching) "Close the search" else "Back") { shelf.back() }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 6.dp), contentAlignment = Alignment.CenterEnd) {
                if (open > 0.01f) SearchBox(
                    value, set, when { inAlbum -> "Search this album"; shelf.page == 0 -> "Search songs"; shelf.page == 1 -> "Search albums"; else -> "Search liked songs" },
                    Modifier.width(maxWidth * open).graphicsLayer { alpha = (open * 2f).coerceAtMost(1f) },
                )
            }
            // Search turns into clear: the one goes out before the other comes in.
            Box(Modifier.size(BarButton), contentAlignment = Alignment.Center) {
                val outA = (1f - open * 2f).coerceIn(0f, 1f)
                val inA = (open * 2f - 1f).coerceIn(0f, 1f)
                if (outA > 0f) Box(Modifier.graphicsLayer { alpha = outA; rotationZ = -45f * open }) {
                    AppBarIcon(BlazeIcons.Search, "Search") { if (inAlbum) shelf.albumSearching = true else shelf.searching = true }
                }
                if (inA > 0f) Box(Modifier.graphicsLayer { alpha = inA; rotationZ = 45f * (1f - open) }) {
                    AppBarIcon(Iconsax.Clear, "Clear the search") { if (value.isEmpty()) shelf.back() else set("") }
                }
            }
        }
    }
}

/** The bar's round buttons: back, search and clear. */
private val BarButton = 44.dp

@Composable
private fun AppBarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(BarButton).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Nm.c.icon, modifier = Modifier.size(22.dp))
    }
}

/** The search box: the same shallow track as the switch, the hint dim, what is typed in the type colour. */
@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    val nc = Nm.c
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val style = TextStyle(fontFamily = MusicType, fontSize = 15.sp, color = nc.onSurface)
    Box(modifier.height(38.dp).clip(CircleShape).background(nc.onSurface.copy(alpha = if (nc.dark) 0.10f else 0.06f)).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, style = style.copy(color = nc.onSurface.copy(alpha = 0.45f)), maxLines = 1)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(nc.onSurface),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}
