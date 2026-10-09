package dev.periy.bridge.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.periy.bridge.container
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.widthIn
import dev.periy.bridge.net.DirectLink
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import dev.periy.bridge.net.LinkKind
import dev.periy.bridge.net.Reach
import dev.periy.bridge.server.Direction
import dev.periy.bridge.server.FileEntry
import dev.periy.bridge.server.Monitor
import dev.periy.bridge.server.NearbyPhone
import dev.periy.bridge.server.PairRequest
import dev.periy.bridge.server.PairedDevice
import dev.periy.bridge.server.Peer
import dev.periy.bridge.server.PeerStatus
import dev.periy.bridge.server.Storage
import dev.periy.bridge.server.SystemClipboard
import dev.periy.bridge.server.Transfer
import dev.periy.bridge.server.TransferState
import dev.periy.bridge.server.Transfers
import dev.periy.bridge.service.BridgeService
import dev.periy.bridge.service.formatBytes
import dev.periy.bridge.service.formatRate

class MainActivity : ComponentActivity() {

    companion object {
        /** A message notification was tapped: the conversation with this phone opens. */
        const val EXTRA_CHAT = "chat"
        /** A call notification: "answer" answers it, anything else shows it. */
        const val EXTRA_CALL = "call"
    }

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            val look by vm.look.collectAsStateWithLifecycle()
            BlazeTheme(theme, look) {
                BlazeItUi(vm)
                if (AlarmsUi.open) AlarmsDialog { AlarmsUi.open = false }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
        // On screen now, so Android can ask (once) to let Localhost 8787 read the log the watch needs.
        if (BridgeService.running.value) dev.periy.bridge.server.ClipWatch.ensure(this, container.prefs.clipSync)
    }

    /** With clipboard sync on, opening Localhost 8787 sends what was last copied on the phone. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val said = ClipSync.sendFromPhone(this)
        if (said.endsWith("sent to the laptop") || said == "Sent to the laptop") {
            Toast.makeText(this, if (said == "Sent to the laptop") "Clipboard sent to the laptop" else said, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Accepts content handed over by the share sheet.
     *
     * The action is cleared afterwards so a configuration change or a return to the task
     * cannot import the same files a second time.
     */
    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        // Tapping the music notification opens the player.
        if (intent.action == dev.periy.bridge.music.MusicService.ACTION_OPEN_PLAYER) { openPlayer.value = true; intent.action = null; return }
        // The alarm clock in the status bar opens the alarms.
        if (intent.getBooleanExtra("openAlarms", false)) { AlarmsUi.open = true; intent.removeExtra("openAlarms") }
        // Tapping a message opens its conversation.
        intent.getStringExtra(EXTRA_CHAT)?.let { openChat.value = it; intent.removeExtra(EXTRA_CHAT) }
        // A call: shown over the lock screen too, and answered when that was the button pressed.
        intent.getStringExtra(EXTRA_CALL)?.let { what ->
            setShowWhenLocked(true); setTurnScreenOn(true)
            if (what == "answer") answerCall.value = true
            intent.removeExtra(EXTRA_CALL)
        }
        // Debug builds: `adb shell am start ... --ez serve true` starts the server for testing.
        if (dev.periy.bridge.BuildConfig.DEBUG && intent.getBooleanExtra("serve", false)) BridgeService.start(this)
        // Debug builds: `--es style theatre` and `--es accent blue` switch the look, for screenshots.
        if (dev.periy.bridge.BuildConfig.DEBUG) {
            intent.getStringExtra("accent")?.let { vm.setAccent(it) }
            intent.getStringExtra("theme")?.let { vm.setTheme(it) }
            intent.getIntExtra("tab", -1).let { if (it >= 0) debugTab.value = it }
            // `--ez sitestaging true|false` points the website's certificate at Let's Encrypt's test
            // server (looser limits) or back; `--ez siteretry true` tries again at once.
            if (intent.hasExtra("sitestaging")) (application as dev.periy.bridge.BridgeApp).container.site.setStaging(intent.getBooleanExtra("sitestaging", false))
            if (intent.getBooleanExtra("siteretry", false)) (application as dev.periy.bridge.BridgeApp).container.site.start()
            // `--ez directtest true|false` starts or stops the direct link without a laptop joining it.
            if (intent.hasExtra("directtest")) (application as dev.periy.bridge.BridgeApp).container.direct.let {
                if (intent.getBooleanExtra("directtest", false)) it.start(8787, laptop = false) else it.stop()
            }
            // Phone to phone without touching the screen: `--es peerconnect <address>` links with the phone
            // there (never with this one itself: that is refused), `--ez approvepairs true` says Allow to every request waiting,
            // `--es peerbrowse <name>` opens that phone's files, `--ez forgetpeers true` unlinks them all.
            val c = (application as dev.periy.bridge.BridgeApp).container
            intent.getStringExtra("peerconnect")?.let { a ->
                val host = a.substringBefore(':')
                c.peers.connect(dev.periy.bridge.server.NearbyPhone(host, host, a.substringAfter(':', "").toIntOrNull() ?: c.prefs.port))
            }
            if (intent.getBooleanExtra("approvepairs", false)) c.pairing.pending.value.forEach { c.pairing.approve(it.id) }
            intent.getStringExtra("peerbrowse")?.let { debugBrowse.value = it }
            // Messages and link codes without touching the screen: `--es msgto <name> --es msg <text>`
            // sends, `--ez linkcode true` opens a code (logged), `--es linkwith <code>` links with one.
            intent.getStringExtra("msgto")?.let { to -> c.messages.send(to, intent.getStringExtra("msg").orEmpty()) }
            if (intent.getBooleanExtra("linkcode", false)) android.util.Log.i("LinkCode", "Open: " + c.openLinkCode())
            intent.getStringExtra("linkwith")?.let { c.peers.linkByCode(it) }
            // `--ez paketest true`: a code's key exchange with itself through the board, right and wrong (logged as LinkPake).
            if (intent.getBooleanExtra("paketest", false)) Thread {
                val code = dev.periy.bridge.net.LinkCode.new()
                val secret = dev.periy.bridge.net.LinkCode.newSecret()
                val door = dev.periy.bridge.net.LinkPake.Door(code, secret, System.currentTimeMillis() + 60_000) { android.util.Log.i("LinkPake", "test: tries spent") }
                door.start()
                Thread.sleep(2_000)
                val t0 = System.currentTimeMillis()
                val right = runCatching { dev.periy.bridge.net.LinkPake.fetch(code) }
                android.util.Log.i("LinkPake", "test right: ${right.map { it.contentEquals(secret) }.getOrElse { "failed: ${it.message}" }} in ${System.currentTimeMillis() - t0} ms")
                val wrong = code.dropLast(1) + ((code.last() - '0' + 1) % 10)
                val bad = runCatching { dev.periy.bridge.net.LinkPake.fetch(wrong) }
                android.util.Log.i("LinkPake", "test wrong: ${bad.exceptionOrNull()?.message ?: "GOT A SECRET"}")
                door.stop()
            }.start()
            // Alarms without a sound or a tap: `--ez alarmsilent true` mutes rings (false undoes it),
            // `--ei alarmsnoozesec N` rings through Android's alarm clock in N seconds, `--ez alarmstop true`.
            if (intent.hasExtra("alarmsilent")) c.ringer.silent = intent.getBooleanExtra("alarmsilent", false)
            if (intent.hasExtra("alarmsnoozesec")) c.alarms.snoozeAtForTest(System.currentTimeMillis() + intent.getIntExtra("alarmsnoozesec", 30) * 1000L)
            if (intent.getBooleanExtra("alarmstop", false)) c.ringer.stop()
            // Sleep timer: `--ei sleepsec N` stops the music in N seconds (fading over the last 30).
            if (intent.hasExtra("sleepsec")) c.sleep.startMs(intent.getIntExtra("sleepsec", 60) * 1000L)
            // Calls: `--es callto <name>`, `--ez callanswer true`, `--ez callend true`.
            intent.getStringExtra("callto")?.let { c.calls.call(it, intent.getBooleanExtra("video", false)) }
            intent.getStringExtra("calladd")?.let { c.calls.add(it) }
            // `--ez calltest true [--ez video true]`: a test call, this phone through a call and back.
            // `--ez callpipeonly true|false` first: the test goes through the phones'-link pipe only.
            if (intent.hasExtra("callpipeonly")) c.calls.pipeOnly = intent.getBooleanExtra("callpipeonly", false)
            if (intent.getBooleanExtra("calltest", false)) c.calls.testCall(intent.getBooleanExtra("video", false))
            if (intent.getBooleanExtra("callstate", false)) c.calls.logState()
            // `--ez dnsboardtest true`: how long a punch note takes to reach the website's nameservers (logcat DnsBoard).
            if (intent.getBooleanExtra("dnsboardtest", false)) c.site.boardZone()?.let { (n, t) -> dev.periy.bridge.net.DnsBoard.timeIt(n, t) }
            // `--es webcamtest LAPTOP_ID`: a laptop's webcam watched here for 25 s (logcat WebcamTest: pictures, gaps).
            intent.getStringExtra("webcamtest")?.let { id ->
                Thread {
                    val t0 = System.currentTimeMillis()
                    val input = java.io.DataInputStream(dev.periy.bridge.server.LaptopCams.open(id, far = false, listen = false))
                    var pics = 0; var bytes = 0L; var last = 0L; var first = -1L
                    try {
                        while (System.currentTimeMillis() - t0 < 25_000) {
                            val n = input.readInt() and Int.MAX_VALUE; input.readInt()
                            val b = ByteArray(n); input.readFully(b)
                            val now = System.currentTimeMillis()
                            if (first < 0) { first = now - t0; android.util.Log.e("WebcamTest", "first picture after $first ms") }
                            if (last > 0 && now - last > 1000) android.util.Log.e("WebcamTest", "${now - last} ms with no picture at ${(now - t0) / 1000} s")
                            last = now; pics++; bytes += n
                            if (pics % 30 == 0) android.util.Log.e("WebcamTest", "$pics pictures, ${bytes * 8 / maxOf(1, now - t0 - first)} kbit/s, at ${(now - t0) / 1000} s")
                        }
                    } catch (e: Exception) { android.util.Log.e("WebcamTest", "ended: ${e.message} at ${(System.currentTimeMillis() - t0) / 1000} s") }
                    android.util.Log.e("WebcamTest", "done: $pics pictures in 25 s")
                    runCatching { input.close() }
                }.start()
            }
            // `--ez natprobe true`: how this network's NAT treats UDP (logcat NatProbe), for 3 minutes.
            if (intent.getBooleanExtra("natprobe", false)) dev.periy.bridge.net.NatProbe.run(intent.getStringExtra("natsend"))
            // Cameras: `--ez camon true|false` (this phone's camera mode), `--ez camrec true|false`.
            if (intent.hasExtra("camon")) c.cameras.set(dev.periy.bridge.server.CamSet(on = intent.getBooleanExtra("camon", false)))
            if (intent.hasExtra("camrec")) c.cameras.set(dev.periy.bridge.server.CamSet(recordNow = intent.getBooleanExtra("camrec", false)))
            // `--ez laptopscreen true`: the laptop's screen view, as Control's row opens it.
            if (intent.getBooleanExtra("laptopscreen", false)) startActivity(android.content.Intent(this, SecondScreenActivity::class.java))
            if (intent.getBooleanExtra("closescreen", false)) SecondScreenActivity.openView?.finish()
            // `--es laptopput <folder>`: a small test file from this phone into that folder on the laptop (logged).
            intent.getStringExtra("laptopput")?.let { folder ->
                val f = java.io.File(cacheDir, "Sent from the phone.txt").apply { writeText("Hello from the phone, sent " + java.util.Date()) }
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    val r = dev.periy.bridge.server.LaptopFiles.put(android.net.Uri.fromFile(f), f.name, f.length(), folder)
                    android.util.Log.i("LaptopPut", r.fold({ "Saved as $it" }, { "Failed: ${it.message}" }))
                }
            }
            if (intent.getBooleanExtra("callanswer", false)) c.calls.answer(intent.getBooleanExtra("video", false))
            if (intent.getBooleanExtra("callend", false)) c.calls.hangUp()
            if (intent.getBooleanExtra("opencontrol", false)) debugControl.value = true
            // `--es forgetpeer <name>` unlinks just that phone (and a test link to itself, its own way in).
            intent.getStringExtra("forgetpeer")?.let { n ->
                c.peers.find(n)?.let(c.peers::forget)
                if (n == c.deviceName()) c.devices.devices.value.filter { it.name == "Phone: $n" }.forEach { c.devices.remove(it.id) }
            }
            if (intent.getBooleanExtra("forgetpeers", false)) {
                c.peers.forgetAll()
                // A test link to itself leaves its own way in behind; other phones' are left alone.
                c.devices.devices.value.filter { it.name == "Phone: " + c.deviceName() }.forEach { c.devices.remove(it.id) }
            }
        }
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    uri != null -> vm.acceptSharedFiles(listOf(uri))
                    !text.isNullOrEmpty() -> vm.acceptSharedText(text)
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (!uris.isNullOrEmpty()) vm.acceptSharedFiles(uris)
            }

            else -> return
        }
        // Sharing into the app implies wanting the computer to be able to fetch it.
        BridgeService.start(this)
        intent.action = null
    }
}

/**
 * The tabs along the bottom. Music is not a page beside the others: it opens full screen
 * (MusicScreen.kt). Control opens from Devices (the computers); the linked phones, their
 * conversations and calls, and adding a phone have Phones to themselves.
 */
private val TABS = listOf(
    "Home" to BlazeIcons.Home,
    "Devices" to BlazeIcons.Laptop,
    "Phones" to BlazeIcons.Phones,
    "Music" to BlazeIcons.Music,
    "Settings" to BlazeIcons.Sliders,
)
private const val PILL_MUSIC = 3
private const val PILL_PHONES = 2

/** The pages a swipe moves between: every tab but Music. */
private val PAGES = TABS.filterIndexed { i, _ -> i != PILL_MUSIC }
private fun pageOfPill(pill: Int) = if (pill > PILL_MUSIC) pill - 1 else pill

/** Where the pill sits in the tabs for a place in the pages (2.5 is halfway from Control to Settings). */
private fun pillOfPage(page: Float) = if (page <= PILL_MUSIC - 1) page else page + 1f
private const val TAB_HOME = 0
private val debugTab = kotlinx.coroutines.flow.MutableStateFlow(-1)

/** The music notification was tapped: the player opens. */
private val openPlayer = kotlinx.coroutines.flow.MutableStateFlow(false)

/** Debug builds: Control opens (`--ez opencontrol true`). */
private val debugControl = kotlinx.coroutines.flow.MutableStateFlow(false)

/** A message notification was tapped: that phone's conversation opens. */
private val openChat = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

/** Answer was pressed on a call's notification: the call screen answers it. */
private val answerCall = kotlinx.coroutines.flow.MutableStateFlow(false)

/** Debug builds: a linked phone whose files to open (`--es peerbrowse <name>`). */
private val debugBrowse = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
private const val TAB_DEVICES = 1
private const val TAB_PHONES = 2

/** The header at the top of every screen: its large title and the monitor switch, over the content. */
private val HeaderHeight = 60.dp

/** The tabs along the bottom: the capsule, and the room around it. */
private val TabsHeight = 74.dp

/** The mini player (Namida's, 82 high), and the gap over it: what every page leaves free at its foot while music is queued. */
private val MiniRoom = 82.dp + 20.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlazeItUi(vm: MainViewModel) {
    val ctx = LocalContext.current
    val peers = ctx.container.peers
    val state by vm.state.collectAsStateWithLifecycle()
    val running by BridgeService.running.collectAsStateWithLifecycle()
    val transfers by Transfers.flow.collectAsStateWithLifecycle()
    val shared by vm.clipboard.collectAsStateWithLifecycle()
    val clipStatus by vm.clipStatus.collectAsStateWithLifecycle()
    val files by vm.files.collectAsStateWithLifecycle()
    val sendStatus by vm.sendStatus.collectAsStateWithLifecycle()
    val requests by vm.pairRequests.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val live by vm.liveDevices.collectAsStateWithLifecycle()
    val monitor by Monitor.snapshot.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val look by vm.look.collectAsStateWithLifecycle()
    val direct by vm.direct.collectAsStateWithLifecycle()
    val laptopLink by vm.laptopLink.collectAsStateWithLifecycle()
    val nearby by peers.nearby.collectAsStateWithLifecycle()
    val paired by peers.peers.collectAsStateWithLifecycle()
    val peerStatus by peers.status.collectAsStateWithLifecycle()
    val routes by peers.route.collectAsStateWithLifecycle()
    // The phone's own music: the library as the Music tab shows it, and the player.
    val player = ctx.container.player
    val now by player.state.collectAsStateWithLifecycle()
    val motion = rememberPlayerMotion()
    val shelf = rememberMusicShelf()
    val playerOpen by remember { derivedStateOf { motion.p > 0.5f } }

    var tab by remember { mutableIntStateOf(TAB_HOME) }
    // Swiping moves between the tabs. Each tab's list keeps its place while it is off screen.
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = TAB_HOME) { PAGES.size }
    val lists = List(PAGES.size) { androidx.compose.foundation.lazy.rememberLazyListState() }
    // A tap on a tab slides there, from wherever the pages are: it takes over a slide still
    // moving or a swipe still settling, so a tap is never lost and the pages never stop halfway.
    // (A slide waited on before would refuse a tap made during one, and cancel itself.)
    val navScope = rememberCoroutineScope()
    val goTo: (Int) -> Unit = { i ->
        tab = i
        shelf.showing = false
        navScope.launch { pager.animateScrollToPage(i, animationSpec = spring(dampingRatio = 0.95f, stiffness = 650f)) }
    }
    // A tap on a tab: Music opens full screen over the app; the rest slide there.
    val onPill: (Int) -> Unit = { i -> if (i == PILL_MUSIC) shelf.showing = true else goTo(pageOfPill(i)) }
    // Where the pages are right now, in tabs, for the bars to follow a swipe.
    val pagePos by remember { derivedStateOf { pager.currentPage + pager.currentPageOffsetFraction } }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { tab = it } }
    // The tab lit in the bar: mid-swipe, the one the swipe is heading to.
    val shown = if (pager.isScrollInProgress) pager.targetPage else tab
    var showOem by remember { mutableStateOf(false) }
    val oemSteps = remember { OemBatterySetup.steps(ctx) }
    var sendTarget by remember { mutableStateOf<Peer?>(null) }
    // A linked phone whose files are open, over everything else.
    var browsePeer by remember { mutableStateOf<Peer?>(null) }
    // The conversation open over the app, by the linked phone's name.
    var chatWith by remember { mutableStateOf<String?>(null) }
    // The trackpad and keys for a computer, open over the app.
    var controlOpen by remember { mutableStateOf(false) }
    // The laptop's files, open over the app.
    var laptopFilesOpen by remember { mutableStateOf(false) }
    var healthOpen by remember { mutableStateOf(false) }
    // Where your phones and laptops are, on a map, open over the app.
    var mapOpen by remember { mutableStateOf(false) }
    // Location, asked for once on the first start (then all the time, as Android has it asked
    // separately), so this phone is on the map from the beginning.
    val askAlways = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ctx.container.where.start(); runCatching { dev.periy.bridge.service.BridgeService.start(ctx) }
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { got ->
        if (got.values.any { it }) {
            ctx.container.where.start()
            runCatching { dev.periy.bridge.service.BridgeService.start(ctx) }
            if (Build.VERSION.SDK_INT >= 29 && !ctx.container.where.allowedAlways()) askAlways.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    LaunchedEffect(Unit) {
        val prefs = ctx.container.prefs
        if (!prefs.locationAsked && !ctx.container.where.allowed()) {
            prefs.locationAsked = true
            askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    val threads by ctx.container.messages.threads.collectAsStateWithLifecycle()
    val recentCalls by ctx.container.callLog.calls.collectAsStateWithLifecycle()
    val chatGroups by ctx.container.messages.groups.collectAsStateWithLifecycle()
    val startCall = rememberStartCall()
    var showMonitor by remember { mutableStateOf(ctx.container.prefs.showMonitor) }
    val setMonitor = { on: Boolean -> showMonitor = on; ctx.container.prefs.showMonitor = on }

    // Back: whatever is open closes first (a linked phone's files and the clipboard's history
    // take their own), then any tab goes back to Home. At Home the app goes to the background,
    // as Home does for any app; it is never closed, and the server keeps running either way.
    //
    // Back follows the finger (Android 14 on): whatever it would close slides aside and fades as
    // the swipe goes, what is under it coming back; let go and the rest plays out quickly; swipe
    // back out before letting go and it all settles back. (Music's albums and the full-page
    // lyrics do the same in their own screens.)
    val oemSwipe = rememberBackSwipe(enabled = showOem) { showOem = false }
    // A tab goes back to Home: the pages slide towards it with the swipe, a little under half way.
    androidx.activity.compose.PredictiveBackHandler(enabled = !showOem && browsePeer == null && chatWith == null && !controlOpen && !laptopFilesOpen && !healthOpen && !mapOpen && tab != TAB_HOME && !shelf.showing) { events ->
        val from = pager.currentPage
        val toward = if (from > TAB_HOME) -1 else 1
        try {
            events.collect { e -> pager.scrollToPage(from, toward * 0.45f * e.progress) }
            goTo(TAB_HOME)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            navScope.launch { pager.animateScrollToPage(from, animationSpec = spring(dampingRatio = 0.95f, stiffness = 650f)) }
        }
    }
    // At Home, from Android 12 on the system takes back itself (and, from 14, shows it coming):
    // the app goes to the background, as it does below 12 here.
    androidx.activity.compose.BackHandler(enabled = !showOem && browsePeer == null && chatWith == null && !controlOpen && !laptopFilesOpen && !healthOpen && !mapOpen && tab == TAB_HOME && Build.VERSION.SDK_INT < 31) {
        (ctx as? android.app.Activity)?.moveTaskToBack(true)
    }
    // Music, open: a search closes, then an album (in Music's own screen), then Music itself, back
    // to the app, following the finger. The player, open over it, goes back down a step first.
    androidx.activity.compose.BackHandler(enabled = !showOem && browsePeer == null && chatWith == null && !controlOpen && !laptopFilesOpen && !healthOpen && !mapOpen && shelf.showing) { shelf.back() }
    val musicSwipe = rememberBackSwipe(
        enabled = !showOem && browsePeer == null && chatWith == null && !controlOpen && !laptopFilesOpen && !healthOpen && !mapOpen && shelf.showing && shelf.open == null && !shelf.searching && !playerOpen,
    ) { shelf.showing = false }
    // A linked phone's files, at their top folder (a folder inside goes up a folder first, in its screen).
    val peerSwipe = rememberBackSwipe(enabled = browsePeer != null) { browsePeer = null }
    val chatSwipe = rememberBackSwipe(enabled = chatWith != null) { chatWith = null }
    val controlSwipe = rememberBackSwipe(enabled = controlOpen) { controlOpen = false }
    // The player: down to the mini player (or from the queue back to the player) with the finger.
    androidx.activity.compose.PredictiveBackHandler(enabled = now.current != null && playerOpen) { events ->
        val from = motion.p
        val to = if (from > 1.5f) 1f else 0f
        try {
            events.collect { e -> motion.backPreview(from, to, e.progress) }
            if (to == 1f) motion.expand() else motion.collapse()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            if (from > 1.5f) motion.toQueue() else motion.expand()
        }
    }
    // Whether a back swipe is under way over the battery steps, for the pages to be there under them.
    val oemPeek by remember { derivedStateOf { oemSwipe.progress.value > 0f } }

    // Who the clipboard reaches right now: every computer live here, once each (by its machine's
    // name where the laptop helper gives one), and the linked phones while copies go on to them.
    val sharedWith = remember(devices, live, paired) {
        dev.periy.bridge.server.clipboardReach(devices, live, null, if (peers.sharesClipboard) paired.map { it.name } else emptyList())
    }

    // Home runs its hero under the status bar and the title, and the hero is always dark.
    // By the page mostly on screen, so the header changes look halfway through a swipe, not at its start.
    val heroUnderBar = kotlin.math.round(pagePos).toInt() == TAB_HOME && !showOem
    // The header is drawn for the dark hero only while the hero is still under it; scrolled
    // past, it takes the page's own colours, or its tabs would vanish on light cards.
    val headerBottom = with(androidx.compose.ui.platform.LocalDensity.current) {
        (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HeaderHeight).toPx()
    }
    val homeList = lists[TAB_HOME]
    val heroShowing by remember(headerBottom) {
        derivedStateOf {
            val first = homeList.layoutInfo.visibleItemsInfo.firstOrNull()
            first == null || (first.key == "hero" && first.offset + first.size > headerBottom)
        }
    }
    val overHero = heroUnderBar && heroShowing && !(now.current != null && playerOpen) && !shelf.showing

    // Status and navigation bar icons follow the palette, whichever way it was chosen.
    val dark = Bridge.Dark
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    // A computer asking to connect is waiting on you, so jump to where the answer is.
    LaunchedEffect(requests.size) { if (requests.isNotEmpty()) { goTo(TAB_HOME); showOem = false } }
    // Debug builds: `--ei tab 0` opens that tab (3 is Music), for screenshots without touching the screen.
    LaunchedEffect(Unit) { debugTab.collect { if (it >= 0) { onPill(it); debugTab.value = -1 } } }
    LaunchedEffect(Unit) { debugBrowse.collect { n -> if (n != null) { browsePeer = peers.find(n); debugBrowse.value = null } } }
    LaunchedEffect(Unit) { openPlayer.collect { if (it) { showOem = false; browsePeer = null; motion.expand(); openPlayer.value = false } } }
    LaunchedEffect(Unit) { debugControl.collect { if (it) { chatWith = null; controlOpen = true; debugControl.value = false } } }
    LaunchedEffect(Unit) { openChat.collect { n -> if (n != null) { showOem = false; browsePeer = null; shelf.showing = false; goTo(TAB_PHONES); controlOpen = false; chatWith = n; openChat.value = null } } }
    // The library loads once it can be read (the queue from last time comes back with it), and
    // covers the catalogue finds later are drawn when they arrive.
    LoadMusicOnce(shelf, ctx.container.music, open = shelf.showing, granted = state.musicGranted) { player.restore(it) }
    // Each time Music opens, songs added since are looked for (the library's own cache keeps this cheap).
    LaunchedEffect(shelf.showing) { if (shelf.showing && shelf.loaded) shelf.load(ctx.container.music, refresh = false) }
    LaunchedEffect(Unit) { dev.periy.bridge.server.EventBus.events.collect { if (it.name == "cover") Covers.forget(it.data) } }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.offerPickedFiles(uris)
    }
    val pickForPhone = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val to = sendTarget
        if (to != null && uris.isNotEmpty()) {
            peers.sendFiles(to, uris)
            Toast.makeText(ctx, "Sending ${uris.size} to ${to.name}", Toast.LENGTH_SHORT).show()
        }
    }
    val pickSendFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.offerPickedFolder(it) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::setDestination)
    }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    val openSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refresh() }
    val requestMusic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    val requestPhotos = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.setScreenshotClip(true)
        vm.refresh()
    }
    // The direct link needs "nearby devices" (Android 13+) or location (older) to start a hotspot.
    val requestNearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startDirect() else Toast.makeText(ctx, "Localhost 8787 needs Nearby devices to start the direct link", Toast.LENGTH_LONG).show()
    }
    // In hotspot mode only the phone's own settings can switch the hotspot on or off.
    val openHotspot = {
        vm.tetherSettingsIntent()?.let { runCatching { openSettings.launch(it) } }
        Unit
    }
    val toggleDirect = {
        if (laptopLink.mode == "hotspot" && direct !is DirectLink.State.On) openHotspot()
        else when (direct) {
            is DirectLink.State.On, DirectLink.State.Starting -> vm.stopDirect()
            else -> {
                val perm = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.NEARBY_WIFI_DEVICES
                else android.Manifest.permission.ACCESS_FINE_LOCATION
                if (ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) vm.startDirect()
                else requestNearby.launch(perm)
            }
        }
    }
    val toggleServer = { if (running) BridgeService.stop(ctx) else BridgeService.start(ctx) }

    // Missed calls are seen once Phones is open.
    LaunchedEffect(tab, recentCalls.count { it.fresh }) { if (tab == TAB_PHONES) ctx.container.callLog.seen() }
    // Other phones are looked for only while the Phones tab is open.
    if (tab == TAB_PHONES) {
        DisposableEffect(Unit) {
            peers.startDiscovery()
            onDispose { peers.stopDiscovery() }
        }
    }

    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val imeUp = WindowInsets.isImeVisible
    // The tabs float along the bottom; every page leaves room under it for them, and for the
    // mini player over them while music is queued.
    val hasPlayer = now.current != null && !showOem && !imeUp
    val underBar = (if (imeUp) 0.dp else TabsHeight + bottomInset) + if (hasPlayer) MiniRoom else 0.dp
    // Room for the monitor pill, so by default it covers nothing.
    val monitorRoom = if (showMonitor) 48.dp else 0.dp
    val headerTop = statusTop + HeaderHeight + monitorRoom
    val contentTop = if (!heroUnderBar) headerTop else 0.dp

    Box(Modifier.fillMaxSize().background(Bridge.Bg)) {
        Box(Modifier.fillMaxSize().then(if (shelf.showing) SharedAxisBack.under(musicSwipe) else Modifier).then(if (browsePeer != null) SharedAxisBack.under(peerSwipe) else Modifier).then(if (chatWith != null) SharedAxisBack.under(chatSwipe) else Modifier).then(if (controlOpen) SharedAxisBack.under(controlSwipe) else Modifier)) {
            StyleBackground()
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).imePadding()) {
                    // The trackpad keeps its own touches; swipe on the keys under it instead.
                    if (!showOem || oemPeek) androidx.compose.foundation.pager.HorizontalPager(
                        pager, Modifier.fillMaxSize().then(if (showOem) SharedAxisBack.under(oemSwipe) else Modifier), key = { it },
                        // Settles with a soft spring rather than a hard stop.
                        flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                            pager, snapPositionalThreshold = 0.18f,
                            snapAnimationSpec = spring(dampingRatio = 0.86f, stiffness = 420f),
                        ),
                    ) { page ->
                        val top = if (page != TAB_HOME) headerTop else 0.dp
                        // A page on its way out sinks back a little and dims; the one coming in rises to meet you.
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            val off = kotlin.math.abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                            val s = 1f - 0.07f * off
                            scaleX = s; scaleY = s
                            alpha = 1f - 0.45f * off
                        }) {
                        LazyColumn(Modifier.fillMaxSize(), state = lists[page], contentPadding = PaddingValues(top = top, bottom = 28.dp + underBar)) {
                            when (page) {
                                TAB_HOME -> homeTab(
                                    state, running, direct, laptopLink.mode, shared, clipStatus, requests, vm, sharedWith,
                                    transfers, files, sendStatus,
                                    heroTop = statusTop,
                                    heroGap = monitorRoom,
                                    monitorOn = showMonitor,
                                    onMonitor = { setMonitor(!showMonitor) },
                                    toggleDirect = toggleDirect,
                                    pickFiles = { pickFiles.launch(arrayOf("*/*")) },
                                    pickSendFolder = { pickSendFolder.launch(null) },
                                    openTether = openHotspot,
                                    onToggle = toggleServer,
                                )
                                TAB_DEVICES -> devicesTab(
                                    running, transfers, paired, devices, live, vm,
                                    openControl = { controlOpen = true },
                                    openLaptopFiles = { laptopFilesOpen = true },
                                    openHealth = { healthOpen = true },
                                    openMap = { mapOpen = true },
                                )
                                TAB_PHONES -> phonesTab(
                                    running, nearby, paired, peerStatus, routes, threads, recentCalls, chatGroups,
                                    connect = peers::connect,
                                    sendFilesTo = { sendTarget = it; pickForPhone.launch(arrayOf("*/*")) },
                                    browse = { browsePeer = it },
                                    chat = { chatWith = it },
                                    startCall = startCall,
                                )
                                else -> settingsTab(
                                    state, vm, theme, look, laptopLink, direct, toggleDirect,
                                    pickFolder = { pickFolder.launch(null) },
                                    requestNotifications = { requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                                    requestMusic = { requestMusic.launch(musicPermission()) },
                                    requestPhotos = {
                                        requestPhotos.launch(
                                            if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
                                            else android.Manifest.permission.READ_EXTERNAL_STORAGE
                                        )
                                    },
                                    openSettings = { openSettings.launch(it) },
                                    showOem = { showOem = true },
                                )
                            }
                        }
                        }
                    }

                    if (showOem) OemScreen(oemSteps, Modifier.padding(top = contentTop).then(SharedAxisBack.over(oemSwipe)), onOpen = { intent ->
                            runCatching { openSettings.launch(intent) }.onFailure {
                                Toast.makeText(ctx, "This phone would not open that screen", Toast.LENGTH_SHORT).show()
                            }
                        }, onDone = { showOem = false })

                    // Over Home's hero the hero carries the title itself; the header comes in once it has gone.
                    androidx.compose.animation.AnimatedVisibility(
                        !overHero,
                        enter = androidx.compose.animation.fadeIn(tween(160)),
                        exit = androidx.compose.animation.fadeOut(tween(120)),
                    ) {
                        AppHeader(PAGES[shown].first, false, showMonitor) { setMonitor(!showMonitor) }
                    }

                    // The tabs, a phone's own tab bar along the bottom, a hairline along its top. As the
                    // player opens they sink away under it, and come back as it closes.
                    val lineColor = Bridge.Outline
                    if (!imeUp && !showOem) androidx.compose.foundation.layout.BoxWithConstraints(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .graphicsLayer {
                                if (now.current == null) return@graphicsLayer
                                val cp = motion.p.coerceIn(0f, 1f)
                                translationY = cp * size.height
                                alpha = 1f - cp
                            }
                            .background(Bridge.Bg.copy(alpha = 0.94f))
                            .then(Modifier.drawBehind { drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1f) })
                            .padding(bottom = bottomInset),
                        contentAlignment = Alignment.Center,
                    ) {
                        BarTabs(
                            TABS, pillOfPage(shown.toFloat()).toInt(), position = pillOfPage(pagePos),
                            // Messages not yet read and calls missed, on Phones, where the conversations are: only
                            // those it lists (a linked phone's, a group's), never one nobody can open to read.
                            badges = mapOf(PILL_PHONES to threads.filterKeys { k -> k.startsWith(dev.periy.bridge.server.Messages.GROUP) || paired.any { it.name == k } }
                                .values.sumOf { l -> l.count { !it.mine && it.state == "new" } } + recentCalls.count { it.fresh }),
                        ) { onPill(it); showOem = false }
                    }
                }

                if (showOem && !imeUp) Spacer(Modifier.height(bottomInset))
            }
        }

        // Music, full screen over the app: Albums, Songs and Favourites.
        androidx.compose.animation.AnimatedVisibility(
            shelf.showing && !showOem,
            enter = androidx.compose.animation.fadeIn(tween(240)) + androidx.compose.animation.scaleIn(tween(340, easing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)), initialScale = 0.94f),
            exit = androidx.compose.animation.fadeOut(tween(200)) + androidx.compose.animation.scaleOut(tween(240), targetScale = 0.96f),
        ) {
            Box(SharedAxisBack.over(musicSwipe)) { MusicScreen(
                shelf, now, player, motion,
                statusTop = statusTop, bottomInset = bottomInset, miniRoom = MiniRoom,
                requestMusic = { requestMusic.launch(musicPermission()) },
            ) }
        }

        // What is playing: the mini player over the tabs, or close to the foot in Music, on a foot
        // of its own there (it glides between the two), dragged up to full screen and on to the queue.
        val miniLift by androidx.compose.animation.core.animateDpAsState(
            // In Music: lifted 16 off the system's bottom (it sat 4 off, nearly on the edge), as a mini player floats.
            if (shelf.showing) bottomInset + 16.dp else bottomInset + 66.dp, tween(320), label = "lift",
        )
        if (hasPlayer) NowPlaying(
            player, now, motion, ctx.container.loudness,
            statusTop = statusTop, navBottom = bottomInset, lift = miniLift,
            onOpenAlbum = { t -> shelf.albumOf[t.id]?.let { shelf.openAlbum(it) }; shelf.showing = true },
        )

        // A linked phone's files slide in over the app, as a folder does in Files.
        androidx.compose.animation.AnimatedVisibility(
            browsePeer != null,
            enter = androidx.compose.animation.slideInHorizontally(tween(300)) { it } + androidx.compose.animation.fadeIn(tween(200)),
            exit = androidx.compose.animation.slideOutHorizontally(tween(260)) { it } + androidx.compose.animation.fadeOut(tween(200)),
        ) {
            // Kept while it slides out, after browsePeer has gone.
            val shownPeer = remember { browsePeer }
            (browsePeer ?: shownPeer)?.let { Box(SharedAxisBack.over(peerSwipe)) { PeerFilesScreen(it) { browsePeer = null } } }
        }

        // Control slides in over the app: the trackpad and keys for the computer with the helper.
        androidx.compose.animation.AnimatedVisibility(
            controlOpen,
            enter = androidx.compose.animation.slideInHorizontally(tween(300)) { it } + androidx.compose.animation.fadeIn(tween(200)),
            exit = androidx.compose.animation.slideOutHorizontally(tween(260)) { it } + androidx.compose.animation.fadeOut(tween(200)),
        ) {
            Box(SharedAxisBack.over(controlSwipe)) {
                androidx.activity.compose.BackHandler { controlOpen = false }
                Column(Modifier.fillMaxSize().background(Bridge.Bg).padding(top = statusTop)) {
                    BackBar("Devices", "Control") { controlOpen = false }
                    ControlPane(
                        running = running,
                        onStart = { BridgeService.start(ctx) },
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = bottomInset),
                        active = true,
                    )
                }
            }
        }

        // The laptop's files slide in over the app, as a linked phone's do.
        androidx.compose.animation.AnimatedVisibility(
            laptopFilesOpen,
            enter = androidx.compose.animation.slideInHorizontally(tween(300)) { it } + androidx.compose.animation.fadeIn(tween(200)),
            exit = androidx.compose.animation.slideOutHorizontally(tween(260)) { it } + androidx.compose.animation.fadeOut(tween(200)),
        ) {
            LaptopFilesScreen { laptopFilesOpen = false }
        }

        // How the laptops are doing, the same way in.
        androidx.compose.animation.AnimatedVisibility(
            healthOpen,
            enter = androidx.compose.animation.slideInHorizontally(tween(300)) { it } + androidx.compose.animation.fadeIn(tween(200)),
            exit = androidx.compose.animation.slideOutHorizontally(tween(260)) { it } + androidx.compose.animation.fadeOut(tween(200)),
        ) {
            LaptopHealthScreen { healthOpen = false }
        }

        // The map of your phones and laptops rises in over the app.
        androidx.compose.animation.AnimatedVisibility(
            mapOpen,
            enter = androidx.compose.animation.slideInVertically(tween(320)) { it / 3 } + androidx.compose.animation.fadeIn(tween(220)),
            exit = androidx.compose.animation.slideOutVertically(tween(260)) { it / 3 } + androidx.compose.animation.fadeOut(tween(200)),
        ) {
            MapScreen { mapOpen = false }
        }

        // A conversation slides in over the app, as a linked phone's files do.
        androidx.compose.animation.AnimatedVisibility(
            chatWith != null,
            enter = androidx.compose.animation.slideInHorizontally(tween(300)) { it } + androidx.compose.animation.fadeIn(tween(200)),
            exit = androidx.compose.animation.slideOutHorizontally(tween(260)) { it } + androidx.compose.animation.fadeOut(tween(200)),
        ) {
            val shownChat = remember { chatWith }
            (chatWith ?: shownChat)?.let { Box(SharedAxisBack.over(chatSwipe)) { ChatScreen(it) { chatWith = null } } }
        }

        if (showMonitor) MonitorOverlay(monitor, running) { setMonitor(false) }

        // A call, over everything.
        val call by ctx.container.calls.state.collectAsStateWithLifecycle()
        val answerNow by answerCall.collectAsStateWithLifecycle()
        // It rises in from the foot and sinks away when the call is over.
        androidx.compose.animation.AnimatedVisibility(
            call != null,
            enter = androidx.compose.animation.slideInVertically(tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it / 3 } + androidx.compose.animation.fadeIn(tween(260)),
            exit = androidx.compose.animation.slideOutVertically(tween(320)) { it / 3 } + androidx.compose.animation.fadeOut(tween(260)),
        ) { CallScreen(answerNow) { answerCall.value = false } }
    }
}

/** The permission that covers reading the music library on this Android version. */
private fun musicPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) android.Manifest.permission.READ_MEDIA_AUDIO
    else android.Manifest.permission.READ_EXTERNAL_STORAGE

// ---------------------------------------------------------------------- chrome

@Composable
private fun MonitorButton(on: Boolean, toggle: () -> Unit) {
    IconChip(
        BlazeIcons.Pulse, if (on) "Hide monitor" else "Show monitor",
        tint = if (on) Bridge.OnAccent else Bridge.Text,
        bg = if (on) Bridge.Accent else Bridge.Chip,
        size = 36.dp,
        onClick = toggle,
    )
}

/**
 * The top of every screen: its name in a large title, as Apple's apps begin a screen, and the
 * monitor switch on the right. It floats over the content: over Home's hero it is drawn in the
 * dark palette, as the hero is dark; on the other tabs the content fades out under it.
 */
@Composable
private fun AppHeader(title: String, overHero: Boolean, monitorOn: Boolean, onMonitor: () -> Unit) {
    val palette = if (overHero) TheatreDark else LocalPalette.current
    CompositionLocalProvider(LocalPalette provides palette) {
        val bg = Bridge.Bg
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (overHero) Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent))
                    else Brush.verticalGradient(0.75f to bg, 1f to bg.copy(alpha = 0f))
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(HeaderHeight)
                .padding(start = 20.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.animation.Crossfade(title, Modifier.weight(1f), animationSpec = tween(160), label = "title") { t ->
                Text(t, style = LargeTitleStyle.copy(shadow = if (overHero) OnArt else null), color = Bridge.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MonitorButton(monitorOn, onMonitor)
        }
    }
}

// ---------------------------------------------------------------------- tab: home

private fun LazyListScope.homeTab(
    state: UiState,
    running: Boolean,
    direct: DirectLink.State,
    linkMode: String,
    shared: String,
    clipStatus: String,
    requests: List<PairRequest>,
    vm: MainViewModel,
    sharedWith: List<String>,
    transfers: List<Transfer>,
    files: List<FileEntry>,
    sendStatus: String,
    heroTop: Dp,
    heroGap: Dp,
    monitorOn: Boolean,
    onMonitor: () -> Unit,
    toggleDirect: () -> Unit,
    pickFiles: () -> Unit,
    pickSendFolder: () -> Unit,
    openTether: () -> Unit,
    onToggle: () -> Unit,
) {
    // The Theatre hero comes first whatever happens: it is the top of the screen.
    item(key = "hero") { Hero(state, running, heroTop, heroGap, monitorOn, onMonitor, onToggle, openTether, linkMode, direct, toggleDirect) }

    // Someone is asking to connect. It goes first: it is the one thing waiting on you.
    items(requests, key = { it.id }) { req -> RequestCard(req, vm) }

    // The clipboard is what Home is opened for most, so it sits right under the hero.
    item(key = "clip") { Column { ClipboardPanel(shared, clipStatus, vm, sharedWith) } }

    // Sending: the other half of what the app is for.
    item(key = "send") { SendCard(sendStatus, pickFiles, pickSendFolder) }


    // The hotspot's details live in the phone's own settings, a tap on its tile away.
    if (direct is DirectLink.State.On) item(key = "direct") { DirectCard(direct.info, toggleDirect) }

    transfersSection(transfers)

    // Clearing takes files off this list (and the laptop's), never off the phone.
    item {
        SectionBar("On the phone") {
            if (files.isNotEmpty()) Text(
                "Clear", style = LabelStyle.copy(fontWeight = FontWeight.SemiBold), color = Bridge.Danger,
                modifier = Modifier.clip(ButtonShape).clickable { vm.clearFiles() }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
    item {
        GroupCard {
            if (files.isEmpty()) SettingRow("Nothing yet", first = true, titleColor = Bridge.Muted)
            files.forEachIndexed { i, f ->
                val fromPhone = f.origin == "PHONE"
                MediaRow(
                    f.name,
                    formatBytes(f.size) + " · " + (if (fromPhone) "from this phone" else "received") +
                        (if (!f.owned) " · original" else ""),
                    icon = if (fromPhone) BlazeIcons.Upload else BlazeIcons.Download,
                    color = if (fromPhone) Color(0xFF30D158) else Color(0xFFFF9F0A),
                    first = i == 0,
                ) {
                    IconChip(BlazeIcons.Close, "Take ${f.name} off the list", tint = Bridge.Muted, size = 32.dp) { vm.forgetFile(f.id) }
                }
            }
        }
    }

}

/** A computer or phone asking to connect: who, the code to compare, Allow or Deny. */
@Composable
private fun RequestCard(req: PairRequest, vm: MainViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).panel().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(BlazeIcons.Laptop, Bridge.Purple)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${req.name} wants to connect", style = TitleStyle, color = Bridge.Text)
                Text(req.ip, style = CaptionStyle, color = Bridge.Muted)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            req.code,
            style = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = 10.sp, fontFeatureSettings = "tnum"),
            color = Bridge.Text,
        )
        Text("Allow only if the other screen shows this code.", style = BodyStyle, color = Bridge.Muted)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BridgeButton("Allow", Modifier.weight(1f)) { vm.approve(req.id) }
            BridgeButton("Deny", Modifier.weight(1f), color = Bridge.Chip, textColor = Bridge.Text) { vm.deny(req.id) }
        }
    }
}

/**
 * What the hero says in words: the state, and the address it shows: the phone's website when it
 * is on (it works from anywhere), else the address of the connection picked. Never the mobile
 * network's own 192.0.0.x, which nothing outside the phone can reach.
 */
private class HeroText(state: UiState, running: Boolean, val url: String?) {
    val address = url?.removePrefix("http://")?.removePrefix("https://")?.removeSuffix("/")
    val headline = when {
        !running -> "Localhost 8787 is off"
        state.storageMode == Storage.Mode.NO_DESTINATION -> "Choose a folder"
        url == null && state.onlyCellular -> "On mobile data"
        url == null -> "No network"
        else -> "Open on your computer"
    }
    val note: String? = when {
        !running -> "Turn on to connect a computer or phone."
        state.storageMode == Storage.Mode.NO_DESTINATION -> "Set it in Settings, Receiving."
        url == null && state.onlyCellular -> "Turn on Website in Settings to open it from any browser, or connect USB, Wi-Fi or the hotspot."
        url == null -> "Connect USB, Wi-Fi or the hotspot."
        else -> null
    }
    val showAddress = running && address != null
}

/** One way a computer reaches this phone, as the hero's picker shows it. */
private class LinkOption(
    val kind: LinkKind,
    val name: String,
    val icon: ImageVector,
    /** Measured between this phone and the laptop; the router's depends on the router. */
    val speed: String,
    /** Null while this connection is not up. */
    val url: String?,
    /** What a tap does while it is not up: the setting that brings it up. */
    val setUp: () -> Unit,
)

/**
 * The connections a computer can use, fastest first: the USB cable, the Wi-Fi both are on,
 * and the laptop link chosen in Settings (the phone's hotspot, or the direct link).
 */
private fun linkOptions(
    state: UiState, running: Boolean, linkMode: String, direct: DirectLink.State,
    openTether: () -> Unit, openWifi: () -> Unit, toggleDirect: () -> Unit,
): List<LinkOption> {
    fun urlOf(k: LinkKind) = if (!running) null
    else state.addresses.firstOrNull { !it.isIpv6 && it.kind == k && it.reach == Reach.LAN_ONLY }?.url(state.port)
    val laptop = if (linkMode == "direct") LinkOption(
        LinkKind.DIRECT, "Direct", BlazeIcons.Bolt,
        if (direct == DirectLink.State.Starting) "Starting" else "100 MB/s",
        urlOf(LinkKind.DIRECT), toggleDirect,
    ) else LinkOption(LinkKind.HOTSPOT, "Hotspot", BlazeIcons.Hotspot, "65 MB/s", urlOf(LinkKind.HOTSPOT), openTether)
    return listOf(
        LinkOption(LinkKind.USB, "USB", BlazeIcons.Usb, "250 MB/s", urlOf(LinkKind.USB), openTether),
        laptop,
        LinkOption(LinkKind.WIFI, "Wi-Fi", BlazeIcons.Wifi, "Router", urlOf(LinkKind.WIFI), openWifi),
    )
}

/**
 * The app's switch, big and plain, the knob carrying the power sign; green when on. It is
 * the first thing on Home, in both styles.
 */
@Composable
private fun PowerSwitch(on: Boolean, onToggle: () -> Unit) {
    val activeColor = Color(0xFF34C759)
    val switchShape = ButtonShape
    val knobShape = CircleShape
    val x by androidx.compose.animation.core.animateDpAsState(
        if (on) 30.dp else 0.dp, androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 600f), label = "power",
    )
    val track by androidx.compose.animation.animateColorAsState(if (on) activeColor
        else Bridge.Chip, tween(180), label = "track")
    Box(
        Modifier.width(72.dp).height(42.dp)
            .pressable(switchShape, scaleTo = 0.94f, onClick = onToggle)
            .background(track)
            .padding(3.dp)
    ) {
        Box(
            Modifier.offset(x = x).size(36.dp).shadow(4.dp, knobShape)
                .clip(knobShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                BlazeIcons.Power, if (on) "Turn Localhost 8787 off" else "Turn Localhost 8787 on",
                tint = if (on) activeColor else Color(0xFF8E8E93), modifier = Modifier.size(19.dp),
            )
        }
    }
}

/**
 * The connections as one segmented control: each with its speed, the picked one lit white and
 * its address in large type above. One that is not up is dimmed, and a tap on it opens what
 * brings it up (USB tethering, Wi-Fi, the hotspot, or the direct link).
 */
@Composable
private fun LinkPicker(options: List<LinkOption>, chosen: LinkOption?, note: String?, onPick: (LinkOption) -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(Bridge.Chip)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { o ->
                val on = o === chosen
                val up = o.url != null
                val bg by androidx.compose.animation.animateColorAsState(if (on) Bridge.Surface
                    else Color.Transparent, tween(160), label = "seg")
                val fg = if (on) Bridge.Text
                    else Bridge.Text.copy(alpha = if (up) 0.75f else 0.4f)
                Column(
                    Modifier.weight(1f)
                        .pressable(RoundedCornerShape(12.dp), scaleTo = 0.96f) { if (up) onPick(o) else o.setUp() }
                        .background(bg)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(o.icon, null, tint = fg, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(o.name, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
                    }
                    Text(
                        if (up) o.speed else "Off",
                        style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                        color = if (on) Bridge.Muted else Bridge.Muted.copy(alpha = if (up) 0.85f else 0.5f),
                        maxLines = 1,
                    )
                }
            }
        }
        if (note != null) Text(
            note, style = BodyStyle.copy(fontSize = 13.sp), color = Bridge.Muted,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
    }
}

/**
 * The app's state at the top of Home, edge to edge under the status bar and the title, and kept
 * short, so the clipboard shows under it: the address in large type, the switch big on the
 * right, and under them the connection picker, fastest first.
 */
@Composable
private fun Hero(
    state: UiState, running: Boolean, top: Dp, gap: Dp, monitorOn: Boolean, onMonitor: () -> Unit,
    onToggle: () -> Unit, openTether: () -> Unit,
    linkMode: String, direct: DirectLink.State, toggleDirect: () -> Unit,
) {
    val ctx = LocalContext.current
    var showQr by remember { mutableStateOf(false) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val openWifi = {
        runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) }
        Unit
    }
    val options = linkOptions(state, running, linkMode, direct, openTether, openWifi, toggleDirect)
    // The one picked while it is up, else the fastest that is.
    val chosen = options.firstOrNull { it.kind.name == picked && it.url != null } ?: options.firstOrNull { it.url != null }
    // The website, when it is up, is the address to give anyone: it works from anywhere.
    val site by (ctx.applicationContext as dev.periy.bridge.BridgeApp).container.site.state.collectAsStateWithLifecycle()
    val siteUrl = if (site.on && site.serving && site.name.isNotEmpty()) "https://${site.name}:${dev.periy.bridge.net.Site.PORT}" else null
    val localUrl = chosen?.url ?: state.primaryUrl?.takeUnless { state.onlyCellular }
    val h = HeroText(state, running, siteUrl ?: localUrl)
    val copy = {
        if (h.url != null) {
            SystemClipboard.write(ctx, h.url)
            Toast.makeText(ctx, "Address copied", Toast.LENGTH_SHORT).show()
        }
    }
    val kicker = if (!running) "OFF" else "LIVE" + (if (siteUrl != null) " · WEBSITE" else chosen?.let { " · " + HeroNames[it.kind].orEmpty() } ?: "")
    // Why the laptop link is not up, when that is known.
    val linkNote = when {
        !running -> null
        linkMode == "direct" && direct is DirectLink.State.Failed -> direct.reason
        linkMode == "direct" && state.hotspotActive && direct !is DirectLink.State.On ->
            "The phone's hotspot is on; the direct link needs it off."
        else -> null
    }
    val pick = { o: LinkOption -> picked = o.kind.name }
    Column(Modifier.fillMaxWidth().animateContentSize(tween(220))) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = top, bottom = 4.dp)) {
            // The title and the monitor switch, as in the header of the other tabs; they scroll
            // away with the card rather than float over it.
            Row(Modifier.fillMaxWidth().height(HeaderHeight).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Home", style = LargeTitleStyle, color = Bridge.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                MonitorButton(monitorOn, onMonitor)
            }
            Spacer(Modifier.height(gap))
            // The phone's own card: its address, the switch, and the way the laptop is linked.
            Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
                Text(kicker, style = KickerStyle.copy(fontSize = 11.sp), color = Bridge.Muted)
                Spacer(Modifier.height(6.dp))
                HeroBody(
                    h, kicker, running, onToggle, copy, showQr, { showQr = !showQr },
                    // Under the website, the address on the link the phone is on now, for a browser there.
                    localUrl?.takeIf { siteUrl != null }?.let { u ->
                        u.removePrefix("http://").removeSuffix("/") + (chosen?.let { "  ·  " + it.name } ?: "")
                    },
                    TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, fontFeatureSettings = "tnum"),
                ) { LinkPicker(options, chosen, linkNote, pick) }
            }
        }
        HeroExtras(state, running, h, showQr, openTether, siteUrl, localUrl, Modifier.padding(horizontal = 20.dp))
    }
}

/**
 * Send files: a deep green card with the app's hard offset shadow (the music covers' and titles'),
 * the title large with its own hard shadow, and two plain buttons: Files, and Folder, which sends
 * everything in it.
 */
@Composable
private fun SendCard(status: String, onFiles: () -> Unit, onFolder: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
            Text("Send files", style = TitleStyle, color = Bridge.Text)
            if (status.isNotEmpty()) Text(status, style = CaptionStyle.copy(fontSize = 13.sp), color = Bridge.Muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SendChoice(BlazeIcons.Upload, "Files", primary = true, Modifier.weight(1f), onFiles)
                SendChoice(BlazeIcons.Folder, "Folder", primary = false, Modifier.weight(1f), onFolder)
            }
        }
    }
}

/** One of the send card's two buttons: white (files) or clear with a white edge (a folder). */
@Composable
private fun SendChoice(icon: ImageVector, label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ink = if (primary) Bridge.Bg else Bridge.Text
    Row(
        modifier.height(44.dp)
            .pressable(RoundedCornerShape(12.dp), scaleTo = 0.96f, onClick = onClick)
            .background(if (primary) Bridge.Text else Bridge.Chip)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * What the hero holds: the whole address large across the top, a tap copies it; under it
 * copy, the QR code and the switch; then the connection picker.
 */
@Composable
private fun HeroBody(
    h: HeroText, kicker: String, running: Boolean, onToggle: () -> Unit, copy: () -> Unit,
    showQr: Boolean, toggleQr: () -> Unit, sub: String?, big: TextStyle, picker: @Composable () -> Unit,
) {
    if (h.showAddress) {
        val host = h.address!!.substringBeforeLast(':')
        val port = h.address.substringAfterLast(':', "")
        FitText(
            androidx.compose.ui.text.buildAnnotatedString {
                append(host)
                if (port.isNotEmpty()) { pushStyle(androidx.compose.ui.text.SpanStyle(color = Bridge.Muted)); append(":$port"); pop() }
            },
            big.copy(color = Bridge.Text), max = 34.sp, min = 18.sp,
            Modifier.fillMaxWidth().clickable(onClickLabel = "Copy the address", onClick = copy),
        )
        if (sub != null) {
            val ctx = LocalContext.current
            Text(
                sub, style = MonoStyle.copy(fontSize = 14.sp), color = Bridge.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp).clickable(onClickLabel = "Copy the address") {
                    SystemClipboard.write(ctx, sub.substringBefore("  ·"))
                    Toast.makeText(ctx, "Address copied", Toast.LENGTH_SHORT).show()
                },
            )
        }
    } else {
        Text(h.headline, style = big, color = Bridge.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        h.note?.let { Text(it, style = BodyStyle.copy(fontSize = 14.sp), color = Bridge.Muted, maxLines = 3) }
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (h.showAddress) {
            IconChip(BlazeIcons.Copy, "Copy the address", tint = Bridge.Text, bg = Bridge.Chip, size = 40.dp, onClick = copy)
            Spacer(Modifier.width(10.dp))
            IconChip(
                BlazeIcons.Qr, if (showQr) "Hide code" else "QR code", tint = if (showQr) Bridge.Bg else Bridge.Text,
                bg = if (showQr) Bridge.Text else Bridge.Chip, size = 40.dp, onClick = toggleQr,
            )
        }
        Spacer(Modifier.weight(1f))
        PowerSwitch(running, onToggle)
    }
    if (running) picker()
}

/**
 * One line of large type at the biggest size that fits the width, down to [min], with the
 * music page's hard shadow: offset, unblurred, like a lit sleeve's lettering.
 */
@Composable
private fun FitText(text: androidx.compose.ui.text.AnnotatedString, style: TextStyle, max: androidx.compose.ui.unit.TextUnit, min: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val size = remember(text, width, style) {
            var s = max.value
            while (s > min.value &&
                measurer.measure(text, style.copy(fontSize = s.sp, shadow = null), maxLines = 1, softWrap = false).size.width > width
            ) s -= 1f
            s
        }
        val px = with(density) { size.sp.toPx() }
        Text(
            text,
            style = style.copy(fontSize = size.sp, shadow = null),
            maxLines = 1, softWrap = false,
        )
    }
}

/** One way in: what it is, the address (a tap copies it), and when it works. */
@Composable
private fun WayIn(label: String, value: String, note: String) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Copy") {
            SystemClipboard.write(ctx, value)
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        }.padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(label, style = CaptionStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Text)
                Spacer(Modifier.width(8.dp))
                Text(note, style = CaptionStyle.copy(fontSize = 12.sp), color = Bridge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(value, style = MonoStyle.copy(fontSize = 13.sp), color = Bridge.Text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        Icon(BlazeIcons.Copy, null, tint = Bridge.Faint, modifier = Modifier.size(16.dp))
    }
}

/** The kicker's name for each connection. */
private val HeroNames = mapOf(
    LinkKind.USB to "USB CABLE", LinkKind.WIFI to "WI-FI", LinkKind.HOTSPOT to "HOTSPOT", LinkKind.DIRECT to "DIRECT LINK",
)

/**
 * Under the hero: every way in, each a tap to copy (the website, the helper's localhost, the same
 * network, the IPv6 the helpers' tunnel uses); then what needs doing, and the QR code.
 */
@Composable
private fun HeroExtras(
    state: UiState, running: Boolean, h: HeroText, showQr: Boolean, openTether: () -> Unit,
    siteUrl: String?, localUrl: String?, modifier: Modifier,
) {
    if (!running) return
    Column(modifier.fillMaxWidth().padding(top = 6.dp)) {
        if (siteUrl != null) WayIn("Any browser", siteUrl.removePrefix("https://"), "anywhere with IPv6")
        WayIn("With the helper", "localhost:8787", "the fastest way")
        // Each link the phone is on, for a browser on the same one: the hotspot, Wi-Fi, the cable.
        state.addresses.filter { !it.isIpv6 && !it.host.startsWith("192.0.0.") }.distinctBy { it.host }.forEach { a ->
            WayIn(a.kind.label, a.url(state.port).removePrefix("http://").removeSuffix("/"), "a browser on the same " + when (a.kind) {
                dev.periy.bridge.net.LinkKind.USB -> "cable"
                dev.periy.bridge.net.LinkKind.HOTSPOT -> "hotspot"
                dev.periy.bridge.net.LinkKind.DIRECT -> "direct link"
                else -> "Wi-Fi"
            })
        }
        // The cable is in, but it carries nothing until USB tethering is on; Android lets only
        // the phone's own settings switch that.
        if (state.cableNoTether) {
            RowNote("Cable in. Turn on USB tethering for full speed.", Bridge.Text)
            Spacer(Modifier.height(8.dp))
            SoftButton("USB tethering", icon = BlazeIcons.Bolt, onClick = openTether)
        }
        state.fasterLink?.let { usb ->
            RowNote("Faster over USB: " + usb.url(state.port).removePrefix("http://").removeSuffix("/"), Bridge.Text)
        }
        val url = h.url
        if (showQr && url != null) {
            val qr = remember(url) { QrCode.render(url, 520) }
            if (qr != null) {
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = qr,
                        contentDescription = "QR code for the address",
                        modifier = Modifier.size(200.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(10.dp),
                    )
                }
            }
            // Where the helpers' tunnel finds the phone from other networks (docs/tunnel-protocol.md);
            // they learn it by themselves, so it lives here rather than on the page.
            state.ipv6?.takeIf { state.remote }?.let { WayIn("IPv6", it, "the helpers' tunnel, from other networks") }
        }
    }
}

/**
 * The direct link while it is on: the network's name and password, the code a phone camera
 * joins from, and what the laptop does by itself.
 */
@Composable
private fun DirectCard(info: DirectLink.Info, stop: () -> Unit) {
    var showQr by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).panel().animateContentSize(tween(180)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(BlazeIcons.Bolt, Bridge.Blue)
            Spacer(Modifier.width(12.dp))
            Text("Direct link", style = TitleStyle, color = Bridge.Text, modifier = Modifier.weight(1f))
            SoftButton("Stop", onClick = stop)
        }
        Spacer(Modifier.height(16.dp))
        CopyField("Network", info.ssid)
        Spacer(Modifier.height(8.dp))
        CopyField("Password", info.passphrase, shown = groupsOfFour(info.passphrase))
        if (info.host.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            CopyField("Then open", "${info.host}:${info.port}")
        }
        Spacer(Modifier.height(12.dp))
        SoftButton(if (showQr) "Hide code" else "QR code", icon = BlazeIcons.Qr) { showQr = !showQr }
        if (showQr) {
            val qr = remember(info.qr) { QrCode.render(info.qr, 520) }
            if (qr != null) {
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = qr, contentDescription = "QR code to join the direct link",
                        modifier = Modifier.size(190.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(10.dp),
                    )
                }
            }
        }
    }
}

/**
 * Android makes up the direct link's password and an app cannot pick a shorter one, so it
 * is at least shown in groups of four: "exrb a9cy rsny ab8" is easy to read out and type.
 */
private fun groupsOfFour(s: String): String = s.chunked(4).joinToString(" ")

/** A label and a value on a well; tap to copy the value (as it is, without display spacing). */
@Composable
private fun CopyField(label: String, value: String, shown: String = value) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Bridge.Chip)
            .clickable {
                SystemClipboard.write(ctx, value)
                Toast.makeText(ctx, "$label copied", Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = LabelStyle, color = Bridge.Muted, modifier = Modifier.width(84.dp))
        Text(shown, style = MonoStyle.copy(fontSize = 15.sp), color = Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(BlazeIcons.Copy, "Copy", tint = Bridge.Faint, modifier = Modifier.size(17.dp))
    }
}

/** Anything moving right now, either way, with progress: rows like songs, with a scrubber under each. */
private fun LazyListScope.transfersSection(transfers: List<Transfer>) {
    if (transfers.isEmpty()) return
    item {
        SectionBar("Moving") {
            Text(
                "Clear finished", style = LabelStyle.copy(fontSize = 15.sp),
                color = Bridge.Blue,
                modifier = Modifier.clip(ButtonShape).clickable { Transfers.clearFinished() }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
    item { GroupCard { transfers.forEachIndexed { i, t -> TransferRow(t, first = i == 0) } } }
}

/** A picture decoded no larger than [px] on its long side: a phone photo is too big to hold whole. */
private fun decodeSampled(path: String, px: Int): android.graphics.Bitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
    android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

/** How long the clipboard's history stays open untouched before the clipboard comes back. */
private const val HISTORY_IDLE_MS = 10_000L

/** Names as a sentence says them: "A", "A and B", "A, B and C". */
private fun namesInLine(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/**
 * The shared clipboard: one thing at a time, the same on the phone and the computer. Typing
 * here reaches the computer by itself a moment after you stop; a picture or file copied on
 * either side shows here too, and what the computer copies is on this phone's own clipboard
 * already, so there are no Paste and Copy buttons. Clear empties it everywhere; the clock opens
 * the history of recent items, to put one back or remove it.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ClipboardPanel(shared: String, status: String, vm: MainViewModel, sharedWith: List<String>) {
    val meta by vm.clipMeta.collectAsStateWithLifecycle()
    val history by vm.clipHistory.collectAsStateWithLifecycle()
    var showHistory by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showHistory) { showHistory = false }
    // The history goes back to the clipboard by itself: once left untouched for a while, and as
    // soon as it has nothing in it.
    var historyTouched by remember { mutableIntStateOf(0) }
    LaunchedEffect(showHistory, historyTouched) {
        if (!showHistory) return@LaunchedEffect
        kotlinx.coroutines.delay(HISTORY_IDLE_MS)
        showHistory = false
    }
    LaunchedEffect(history.isEmpty()) { if (history.isEmpty()) showHistory = false }
    // With nothing in it, the clock does not open an empty list: "Nothing to clear" shows in red
    // beside it for a moment instead.
    var nothingAt by remember { mutableIntStateOf(0) }
    var nothingShown by remember { mutableStateOf(false) }
    LaunchedEffect(nothingAt) {
        if (nothingAt == 0) return@LaunchedEffect
        nothingShown = true
        kotlinx.coroutines.delay(1800)
        nothingShown = false
    }
    val nothingAlpha by androidx.compose.animation.core.animateFloatAsState(if (nothingShown) 1f else 0f, tween(220), label = "nothing")
    var draft by remember { mutableStateOf(shared) }
    // True between a keystroke and the moment it is published; incoming text waits till then.
    var pending by remember { mutableStateOf(false) }
    LaunchedEffect(shared) { if (!pending) draft = shared }
    LaunchedEffect(draft, pending) {
        if (!pending) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        vm.sendClipboard(draft)
        pending = false
    }
    val picture by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, meta.v, meta.kind) {
        value = if (meta.kind != "image") null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            vm.clipFile()?.let { f -> decodeSampled(f.path, 1080)?.asImageBitmap() }
        }
    }

    // A quiet clock by the title opens the history under it; Clear is a small cross in the box,
    // there only while it holds something.
    SectionBar("Clipboard") {
        // Only while the history is open: clearing it, at the top, beside the clock that closes it.
        if (showHistory && history.isNotEmpty()) {
            Text(
                "Clear history", style = LabelStyle.copy(fontWeight = FontWeight.SemiBold), color = Bridge.Danger,
                modifier = Modifier.clip(ButtonShape).clickable { vm.forgetAllClips() }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        if (nothingAlpha > 0f) {
            Text(
                "Nothing to clear", style = LabelStyle.copy(fontWeight = FontWeight.SemiBold), color = Bridge.Danger,
                modifier = Modifier.alpha(nothingAlpha).padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        IconChip(
            BlazeIcons.History, if (showHistory) "Hide history" else "History",
            tint = if (showHistory) Bridge.Text else Bridge.Muted, bg = if (showHistory) Bridge.Chip else Color.Transparent, size = 34.dp,
        ) {
            when {
                showHistory -> showHistory = false
                history.isEmpty() -> nothingAt++
                else -> showHistory = true
            }
        }
    }
    // Who it is shared with, live.
    Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp).offset(y = (-4).dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (sharedWith.isNotEmpty()) Bridge.Good else Bridge.Faint))
        Spacer(Modifier.width(7.dp))
        Text(
            if (sharedWith.isNotEmpty()) "Shared with " + namesInLine(sharedWith) else "No computer connected",
            style = CaptionStyle, color = Bridge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    // Hold a picture or file to keep it: Android's own Save as picks the folder and the name.
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var saving by remember { mutableStateOf<java.io.File?>(null) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val target = r.data?.data
        val f = saving
        saving = null
        if (r.resultCode == android.app.Activity.RESULT_OK && target != null && f != null) vm.saveClipTo(f, target)
    }
    val askWhere = { f: java.io.File?, name: String, mime: String ->
        if (f != null) {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            saving = f
            saveAs.launch(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime.ifBlank { "application/octet-stream" }).putExtra(Intent.EXTRA_TITLE, name)
            )
        }
    }
    val save = { askWhere(vm.clipFile(), meta.name, meta.mime) }
    Column(Modifier.fillMaxWidth().panel().padding(16.dp)) {
        if (showHistory) {
            // Any touch in the history (a tap, a scroll) keeps it open a while longer.
            Box(
                Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            historyTouched++
                        }
                    }
                },
            ) { ClipHistory(history, current = meta.v, vm = vm) { m -> askWhere(vm.historyFile(m.v), m.name, m.mime) } }
            Spacer(Modifier.height(12.dp))
        }
        when (meta.kind) {
            // What is on the clipboard, in a well of its own: the box holds it, it is not a row of
            // its own beside Send files. A long press keeps it on this phone.
            "image", "file" -> Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Bridge.Chip)
                    .combinedClickable(onClick = {}, onLongClick = save).padding(12.dp)
            ) {
                picture?.let {
                    Image(
                        it, meta.name,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (picture == null) {
                        AppIcon(if (meta.kind == "image") BlazeIcons.Image else BlazeIcons.File, if (meta.kind == "image") Bridge.Purple else Bridge.Orange)
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(meta.name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((if (meta.kind == "image") "Picture" else "File") + " · " + formatBytes(meta.size),
                            style = CaptionStyle, color = Bridge.Muted)
                    }
                }
            }
            else -> Box(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 4.dp, vertical = 2.dp)) {
                if (draft.isEmpty()) Text("Type or paste anything", style = BodyStyle.copy(fontSize = 16.sp), color = Bridge.Faint)
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it; pending = true },
                    textStyle = BodyStyle.copy(fontSize = 16.sp, lineHeight = 22.sp, color = Bridge.Text),
                    cursorBrush = SolidColor(Bridge.Blue),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val hint = if (meta.kind == "image" || meta.kind == "file") "Hold to save it" else ""
        if (status.isNotEmpty() || hint.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(status.ifEmpty { hint }, style = LabelStyle, color = if (status.isNotEmpty()) Bridge.Good else Bridge.Faint,
                modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/**
 * Recent clipboard items, newest first, like a keyboard's clipboard: tap one to put it back
 * (on the computer too), the pin to keep it however much is copied after, the cross to remove it.
 * Pinned ones come first; a search box narrows the list to what has some words in it.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ClipHistory(
    items: List<dev.periy.bridge.server.ClipMeta>, current: Long, vm: MainViewModel,
    save: (dev.periy.bridge.server.ClipMeta) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (items.isEmpty()) {
            Text("Nothing copied yet.", style = BodyStyle, color = Bridge.Muted, modifier = Modifier.padding(4.dp))
            return@Column
        }
        var query by remember { mutableStateOf("") }
        Box(
            Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(10.dp)).background(Bridge.Chip)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (query.isEmpty()) Text("Search copies", style = BodyStyle.copy(fontSize = 15.sp), color = Bridge.Faint)
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = BodyStyle.copy(fontSize = 15.sp, color = Bridge.Text), cursorBrush = SolidColor(Bridge.Blue),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val needle = query.trim()
        val shown = items.filter { needle.isEmpty() || (if (it.kind == "text") it.text else it.name).contains(needle, ignoreCase = true) }
        if (shown.isEmpty()) Text("Nothing copied has that in it.", style = BodyStyle, color = Bridge.Muted, modifier = Modifier.padding(6.dp))
        val pinned = shown.filter { it.pinned }
        val rest = shown.filterNot { it.pinned }
        val kicker = @Composable { t: String -> Text(t.uppercase(), style = KickerStyle, color = Bridge.Muted, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)) }
        (pinned + rest).forEachIndexed { i, m ->
            if (i == 0 && pinned.isNotEmpty()) kicker("Pinned")
            if (i == pinned.size && pinned.isNotEmpty() && rest.isNotEmpty()) kicker("Recent")
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (m.v == current) Bridge.Accent.copy(alpha = 0.16f) else Bridge.Chip)
                    .combinedClickable(
                        onClick = { vm.reuseClip(m.v) },
                        onLongClick = { if (m.kind != "text") save(m) },
                    ).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (m.kind) {
                    "image" -> ClipThumb(vm, m)
                    "file" -> AppIcon(BlazeIcons.File, Bridge.Orange, size = 34.dp)
                    else -> {}
                }
                if (m.kind != "text") Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (m.kind == "text") m.text.trim().replace(Regex("\\s+"), " ") else m.name,
                        style = BodyStyle.copy(fontSize = 14.sp), color = Bridge.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        (if (m.kind == "text") "" else formatBytes(m.size) + " · ") + ago(m.at) + (if (m.v == current) " · now on the clipboard" else ""),
                        style = BodyStyle.copy(fontSize = 12.sp), color = Bridge.Muted,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(30.dp).clip(CircleShape).clickable { vm.pinClip(m.v, !m.pinned) }, contentAlignment = Alignment.Center) {
                    Icon(BlazeIcons.Pushpin, if (m.pinned) "Unpin" else "Pin", tint = if (m.pinned) Bridge.Accent else Bridge.Faint, modifier = Modifier.size(17.dp))
                }
                Box(Modifier.size(30.dp).clip(CircleShape).clickable { vm.forgetClip(m.v) }, contentAlignment = Alignment.Center) {
                    Icon(BlazeIcons.Close, "Remove", tint = Bridge.Muted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** A small square of a picture in the history. */
@Composable
private fun ClipThumb(vm: MainViewModel, m: dev.periy.bridge.server.ClipMeta) {
    val bmp by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, m.v) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            vm.historyFile(m.v)?.let { f -> decodeSampled(f.path, 160)?.asImageBitmap() }
        }
    }
    val b = bmp
    if (b == null) AppIcon(BlazeIcons.Image, Bridge.Purple, size = 34.dp)
    else Image(b, m.name, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)))
}

/** "just now", "5 min ago", "3 h ago", or the date. */
private fun ago(at: Long): String {
    if (at <= 0) return ""
    val s = (System.currentTimeMillis() - at) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(at))
    }
}

// ---------------------------------------------------------------------- tab: devices

/**
 * The computers this phone talks to, each with how it is connected now (the cable, the hotspot,
 * Wi-Fi, the tunnel, the website), Control for the one with the helper, what is moving, and
 * unpairing. The linked phones have Phones to themselves.
 */
private fun LazyListScope.devicesTab(
    running: Boolean,
    transfers: List<Transfer>,
    paired: List<Peer>,
    devices: List<PairedDevice>,
    live: Map<String, Int>,
    vm: MainViewModel,
    openControl: () -> Unit,
    openLaptopFiles: () -> Unit,
    openHealth: () -> Unit,
    openMap: () -> Unit,
) {
    // Linked phones are on Phones: their ways in here (the one each was let in by, and the one it
    // asked with) are not shown as computers.
    val linkedIds = paired.map { it.deviceId }.filter { it.isNotEmpty() }.toSet()
    val computers = devices.filter { it.id !in linkedIds && !it.name.startsWith(dev.periy.bridge.server.PHONE_PREFIX) }

    // Where your phones and laptops are (server/Where.kt), on a map.
    item {
        GroupCard(Modifier.padding(top = 4.dp)) {
            SettingRow("Where they are", "Your phones and laptops on a map, precisely", first = true, icon = BlazeIcons.Pin, onClick = openMap)
            // Phones as security cameras (server/Cameras.kt): the control centre, over the lock screen too.
            val camCtx = LocalContext.current
            SettingRow("Cameras", "Your phones as security cameras, live, with motion alerts", icon = BlazeIcons.Video,
                onClick = { camCtx.startActivity(android.content.Intent(camCtx, CamerasActivity::class.java)) })
        }
    }
    item {
        SectionBar("Computers") {
            val n = computers.count { (live[it.id] ?: 0) > 0 }
            if (n > 0) Text("$n live", style = LabelStyle.copy(fontWeight = FontWeight.SemiBold), color = Bridge.Lit)
        }
    }
    item { ComputersCard(computers, devices, live) { vm.removeDevice(it) } }
    item { Spacer(Modifier.height(12.dp)) }
    item { ControlRow(running, openControl, openLaptopFiles, openHealth) }

    transfersSection(transfers)

    // Last, and apart: it signs out every computer and phone at once.
    item { SectionBar("Pairing") }
    item {
        GroupCard {
            SettingRow("Sign out all devices", first = true, titleColor = Bridge.Danger, onClick = { vm.unpairAll() })
        }
    }
}

// ---------------------------------------------------------------------- tab: phones

/**
 * Phone to phone: the linked phones, each a conversation (with calls), its files and a send; then
 * adding a phone, nearby on the same Wi-Fi, by address, or by a code from anywhere.
 */
private fun LazyListScope.phonesTab(
    running: Boolean,
    nearby: List<NearbyPhone>,
    paired: List<Peer>,
    peerStatus: Map<String, PeerStatus>,
    routes: Map<String, String>,
    threads: Map<String, List<dev.periy.bridge.server.ChatMsg>>,
    recentCalls: List<dev.periy.bridge.server.CallRecord>,
    chatGroups: Map<String, dev.periy.bridge.server.ChatGroup>,
    connect: (NearbyPhone) -> Unit,
    sendFilesTo: (Peer) -> Unit,
    browse: (Peer) -> Unit,
    chat: (String) -> Unit,
    startCall: (String, Boolean) -> Unit,
) {
    val pairedNames = paired.map { it.name }.toSet()
    val unpaired = nearby.filter { it.name !in pairedNames }

    if (chatGroups.isNotEmpty() || paired.size >= 2) {
        item { SectionBar("Groups", Modifier.padding(top = 4.dp)) }
        item { GroupsCard(chatGroups, threads, paired, chat) }
    }
    item { SectionBar("Linked phones", Modifier.padding(top = 4.dp)) }
    item { PhonesCard(paired, nearby, threads, routes, chat, browse, sendFilesTo) }
    if (paired.isNotEmpty()) item {
        Text(
            "Tap a phone to message it. Only the two phones can read it.",
            style = CaptionStyle, color = Bridge.Muted,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
        )
    }
    if (recentCalls.isNotEmpty()) {
        item { SectionBar("Recent calls") }
        item { RecentCalls(recentCalls.take(8), startCall) }
    }
    item { Spacer(Modifier.height(12.dp)) }
    item { TestCallRow() }

    item { SectionBar("Add a phone") }
    item { Searching(running, found = unpaired.size) }
    if (unpaired.isNotEmpty()) {
        item { Spacer(Modifier.height(12.dp)) }
        item {
            GroupCard {
                unpaired.forEachIndexed { i, n ->
                    val st = peerStatus[n.host]
                    MediaRow(
                        n.name,
                        when (st) {
                            is PeerStatus.Waiting -> "Allow it on ${n.name} · code ${st.code}"
                            is PeerStatus.Failed -> st.message
                            null -> "Nearby · same Wi-Fi"
                        },
                        BlazeIcons.Wifi, Color(0xFF30D158),
                        first = i == 0,
                    ) {
                        if (st !is PeerStatus.Waiting) SoftButton("Link") { connect(n) }
                    }
                }
            }
        }
    }
    // Attempts made by address are not in the discovered list, so their progress shows here.
    val listed = unpaired.map { it.host }.toSet() + dev.periy.bridge.server.PeerManager.LINK_KEY
    peerStatus.filterKeys { it !in listed }.forEach { (host, st) ->
        item {
            Text(
                when (st) {
                    is PeerStatus.Waiting -> "$host: allow it on the other phone. Code ${st.code}"
                    is PeerStatus.Failed -> "$host: ${st.message}"
                },
                style = BodyStyle,
                color = if (st is PeerStatus.Failed) Bridge.Danger else Bridge.Text,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
            )
        }
    }
    item { Spacer(Modifier.height(12.dp)) }
    item { LinkFromAnywhere(peerStatus[dev.periy.bridge.server.PeerManager.LINK_KEY]) }

}

/** A way in, as an icon: the cable, the hotspot, Wi-Fi, the direct link, the website, the tunnel. */
private fun wayIcon(via: String?): androidx.compose.ui.graphics.vector.ImageVector? = when (via) {
    "usb", "adb" -> BlazeIcons.Usb
    "hotspot" -> BlazeIcons.Hotspot
    "wifi" -> BlazeIcons.Wifi
    "direct" -> BlazeIcons.Bolt
    "website" -> BlazeIcons.Lock
    "tunnel" -> BlazeIcons.Link
    null -> null
    else -> BlazeIcons.Wifi
}

/** How long a way in still counts as how a device is connected now. */
private const val WAY_FRESH_MS = 120_000L

/** The minute, for lists that say "now": they look again every ten seconds. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(10_000); now = System.currentTimeMillis() } }
    return now
}

/**
 * The computers allowed in: each one's icon is how it is connected right now, and under its name
 * that way in words (Same Wi-Fi, USB cable, Internet tunnel, Website over the internet), whether
 * it is the helper or a browser, and then what that way runs over and how fast it is. Quiet ones
 * say when they were last here.
 */
@Composable
private fun ComputersCard(computers: List<PairedDevice>, all: List<PairedDevice>, live: Map<String, Int>, remove: (String) -> Unit) {
    val ways by dev.periy.bridge.server.Ways.now.collectAsStateWithLifecycle()
    val now = rememberNow()
    GroupCard {
        if (computers.isEmpty()) SettingRow("None yet", detail = "Open the address on Home in a browser, or run the helper", first = true, titleColor = Bridge.Muted)
        computers.sortedWith(compareByDescending<PairedDevice> { (live[it.id] ?: 0) > 0 }.thenByDescending { it.lastSeenAt })
            .forEachIndexed { i, d ->
                val isLive = (live[d.id] ?: 0) > 0
                val w = ways[d.id]?.takeIf { now - it.at < WAY_FRESH_MS }
                val role = when {
                    d.name.startsWith(dev.periy.bridge.server.HELPER_PREFIX) -> "Helper"
                    d.name.startsWith(dev.periy.bridge.server.PHONE_PREFIX) -> "Phone"
                    else -> "Browser"
                }
                MediaRow(
                    dev.periy.bridge.server.shownName(d, all),
                    if (w != null) "${w.name} · $role" else "$role · ${lastSeen(d.lastSeenAt)}",
                    icon = wayIcon(w?.via) ?: BlazeIcons.Laptop,
                    color = Color(0xFF5E5CE6),
                    dim = !isLive && w == null,
                    first = i == 0,
                    below = {
                        Text(
                            w?.detail ?: d.lastIp,
                            style = CaptionStyle.copy(fontSize = 12.sp), color = Bridge.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    },
                ) {
                    IconChip(BlazeIcons.Close, "Remove ${d.name}", tint = Bridge.Muted, size = 32.dp) { remove(d.id) }
                }
            }
    }
}

/** Control: the trackpad and keys for the computer running the helper, on a screen of its own. */
@Composable
private fun ControlRow(running: Boolean, open: () -> Unit, openFiles: () -> Unit, openHealth: () -> Unit) {
    val laptops by dev.periy.bridge.server.Control.connected.collectAsStateWithLifecycle()
    GroupCard {
        SettingRow(
            "Control",
            detail = when {
                !running -> "Turn Localhost 8787 on from Home first"
                laptops.isEmpty() -> "Needs the helper on the computer"
                else -> "Trackpad and keys for " + dev.periy.bridge.server.helperMachine(laptops.first())
            },
            first = true, icon = BlazeIcons.Trackpad, iconColor = Color(0xFF5E5CE6), onClick = open,
        ) { Icon(BlazeIcons.Chevron, null, tint = Bridge.Faint, modifier = Modifier.size(18.dp)) }
        // Its files, from anywhere, through the same helper.
        SettingRow(
            "Files on the laptop",
            detail = if (laptops.isEmpty()) "Needs the helper on the computer" else "Browse and save to this phone",
            icon = BlazeIcons.Folder, iconColor = Color(0xFF0A84FF), onClick = openFiles,
        ) { Icon(BlazeIcons.Chevron, null, tint = Bridge.Faint, modifier = Modifier.size(18.dp)) }
        // Handoff: what the laptop is showing or playing, carried on here (paused there).
        val hctx = LocalContext.current
        SettingRow(
            "Continue from laptop",
            detail = if (laptops.isEmpty()) "Needs the helper on the computer" else "The page or video it has open, at the same second · Ctrl+Alt+P sends it",
            icon = BlazeIcons.Laptop, iconColor = Color(0xFFFF9F0A),
            onClick = { hctx.startActivity(android.content.Intent(hctx, HandoffActivity::class.java)) },
        ) { Icon(BlazeIcons.Chevron, null, tint = Bridge.Faint, modifier = Modifier.size(18.dp)) }
        // The phone as the laptop's webcam (tools/vcam): added once, then any app can pick it.
        SettingRow(
            "Phone as webcam",
            detail = if (laptops.isEmpty()) "Needs the helper on the computer" else "Add once (one admin prompt on the laptop), then pick \"Localhost 8787 Phone Camera\" in any app",
            icon = BlazeIcons.Video, iconColor = Color(0xFF64D2FF),
        ) {
            if (laptops.isNotEmpty()) Text(
                "Add", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Accent,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                    dev.periy.bridge.server.Control.laptops().forEach { dev.periy.bridge.server.EventBus.emitTo(it.first, "webcam", "install") }
                    android.widget.Toast.makeText(hctx, "Approve the prompt on the laptop", android.widget.Toast.LENGTH_LONG).show()
                }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        // How it is doing: battery, CPU, GPU, temperatures, disks.
        SettingRow(
            "Laptop health",
            detail = if (laptops.isEmpty()) "Needs the helper on the computer" else "Battery, CPU, GPU, temperatures and disks",
            icon = BlazeIcons.Pulse, iconColor = Color(0xFF30D158), onClick = openHealth,
        ) { Icon(BlazeIcons.Chevron, null, tint = Bridge.Faint, modifier = Modifier.size(18.dp)) }
    }
}

/**
 * The linked phones: each is a conversation (a tap opens it), with its last message, how many are
 * unread, and how it is reached now (nearby on the same Wi-Fi, or through its tunnel from another
 * network); its files and a send are on the row.
 */
@Composable
private fun PhonesCard(
    paired: List<Peer>, nearby: List<NearbyPhone>, threads: Map<String, List<dev.periy.bridge.server.ChatMsg>>,
    routes: Map<String, String>, chat: (String) -> Unit, browse: (Peer) -> Unit, sendFilesTo: (Peer) -> Unit,
) {
    val ways by dev.periy.bridge.server.Ways.now.collectAsStateWithLifecycle()
    val now = rememberNow()
    GroupCard {
        if (paired.isEmpty()) SettingRow("None linked yet", detail = "Link one below: nearby, by address, or with a code from anywhere", first = true, titleColor = Bridge.Muted)
        paired.sortedByDescending { threads[it.name]?.lastOrNull()?.at ?: 0L }.forEachIndexed { i, p ->
            val here = nearby.any { it.name == p.name }
            val w = ways[p.deviceId]?.takeIf { now - it.at < WAY_FRESH_MS }
            val l = threads[p.name].orEmpty()
            val last = l.lastOrNull()
            val unread = l.count { !it.mine && it.state == "new" }
            val how = routes[p.name] ?: when {
                !p.mutual -> "One way only · update Localhost 8787 on it"
                here -> "Nearby · same Wi-Fi"
                w != null -> w.name + if (w.via == "tunnel") " · " + (if (w.over == "udp") "punched over IPv4" else "IPv6") else ""
                p.tunnel.isNotEmpty() -> "Anywhere · through its tunnel"
                else -> "On the same Wi-Fi only"
            }
            MediaRow(
                p.name,
                when {
                    last == null -> "No messages yet"
                    last.mine && last.state == "waiting" -> "Waiting: " + preview(last)
                    last.mine -> "You: " + preview(last)
                    else -> preview(last)
                },
                icon = if (here) BlazeIcons.Wifi else BlazeIcons.Phones,
                color = Color(0xFF30D158),
                first = i == 0,
                onClick = { chat(p.name) },
                below = { Text(how, style = CaptionStyle.copy(fontSize = 12.sp), color = Bridge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            ) {
                if (unread > 0) {
                    Box(
                        Modifier.clip(CircleShape).background(Bridge.Text).padding(horizontal = 7.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("$unread", style = LabelStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Bg) }
                    Spacer(Modifier.width(8.dp))
                }
                IconChip(BlazeIcons.Folder, "Files on ${p.name}", tint = Bridge.Text, size = 34.dp) { browse(p) }
                Spacer(Modifier.width(6.dp))
                IconChip(BlazeIcons.Upload, "Send files to ${p.name}", tint = Bridge.Text, size = 34.dp) { sendFilesTo(p) }
            }
        }
    }
}

/** What a message says, for a list: its words, or what it is. */
private fun preview(m: dev.periy.bridge.server.ChatMsg): String = when (m.kind) {
    "image" -> "Photo" + (if (m.text.isNotEmpty()) ": " + m.text else "")
    "voice" -> "Voice note"
    "file" -> "File: " + m.name
    "event" -> m.text
    "deleted" -> if (m.mine) "You deleted this message" else "This message was deleted"
    else -> m.text
}

/**
 * The group conversations, newest first, each with its last line and who wrote it; and New group,
 * which picks linked phones and a name. This phone keeps the groups it makes and passes each
 * message on, so only it needs to be linked with everyone in them.
 */
@Composable
private fun GroupsCard(
    groups: Map<String, dev.periy.bridge.server.ChatGroup>,
    threads: Map<String, List<dev.periy.bridge.server.ChatMsg>>,
    paired: List<Peer>,
    chat: (String) -> Unit,
) {
    val c = LocalContext.current.container
    var making by remember { mutableStateOf(false) }
    GroupCard {
        val sorted = groups.values.sortedByDescending { g -> threads[dev.periy.bridge.server.Messages.GROUP + g.id]?.lastOrNull()?.at ?: g.at }
        sorted.forEachIndexed { i, g ->
            val key = dev.periy.bridge.server.Messages.GROUP + g.id
            val l = threads[key].orEmpty()
            val last = l.lastOrNull()
            val unread = l.count { !it.mine && it.state == "new" }
            MediaRow(
                g.name,
                when {
                    last == null -> c.messages.others(g).joinToString(", ")
                    last.mine -> "You: " + preview(last)
                    last.from.isNotEmpty() -> last.from + ": " + preview(last)
                    else -> preview(last)
                },
                BlazeIcons.Phones, Color(0xFF5E5CE6), first = i == 0, onClick = { chat(key) },
            ) {
                if (unread > 0) Box(
                    Modifier.clip(CircleShape).background(Bridge.Text).padding(horizontal = 7.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("$unread", style = LabelStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Bg) }
            }
        }
        if (paired.size >= 2) SettingRow("New group", first = sorted.isEmpty(), icon = BlazeIcons.Plus, iconColor = Color(0xFF5E5CE6), onClick = { making = true }) {
            Icon(BlazeIcons.Chevron, null, tint = Bridge.Faint, modifier = Modifier.size(18.dp))
        }
    }
    if (making) NewGroupDialog(paired.map { it.name }, onDismiss = { making = false }) { name, members ->
        making = false
        chat(c.messages.createGroup(name, members))
    }
}

/** A group's name and its phones, picked from the linked ones. */
@Composable
private fun NewGroupDialog(phones: List<String>, onDismiss: () -> Unit, onCreate: (String, List<String>) -> Unit) {
    var name by remember { mutableStateOf("") }
    val picked = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group") },
        text = {
            Column {
                BridgeTextField(name, { name = it.take(60) }, placeholder = "Name (optional)", minHeight = 46.dp, mono = false)
                Spacer(Modifier.height(10.dp))
                phones.forEach { p ->
                    val on = p in picked
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { if (on) picked.remove(p) else picked.add(p) }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChatAvatar(p, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(p, style = BodyStyle, color = Bridge.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).background(if (on) Bridge.Accent else Bridge.Chip),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(BlazeIcons.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = picked.size >= 2, onClick = { onCreate(name, picked.toList()) }) {
                Text("Create", color = if (picked.size >= 2) Bridge.Accent else Bridge.Muted)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = Bridge.Text) } },
        containerColor = Bridge.Surface, titleContentColor = Bridge.Text, textContentColor = Bridge.Muted,
    )
}

/** Over a screen opened from a tab: back to [back], and the screen's [title]. */
@Composable
private fun BackBar(back: String, title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(BlazeIcons.Chevron, "Back", tint = Bridge.Text, modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = 180f })
            Text(back, style = TextStyle(fontSize = 17.sp), color = Bridge.Text)
        }
        Text(title, style = TitleStyle, color = Bridge.Text, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(72.dp))
    }
}

/** Phones nearby: what is going on, with a soft pulse while it looks. */
@Composable
private fun Searching(running: Boolean, found: Int) {
    val pulse by rememberInfiniteTransition(label = "look").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot",
    )
    Row(
        Modifier.fillMaxWidth().panel().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(BlazeIcons.Phones, Color(0xFF30D158), Modifier.size(44.dp), radius = 10.dp, glyph = 22.dp, center = true)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    !running -> "Localhost 8787 is off"
                    found == 0 -> "Looking for phones"
                    found == 1 -> "1 phone"
                    else -> "$found phones"
                },
                style = TextStyle(fontSize = 16.sp, letterSpacing = (-0.2).sp), color = Bridge.Text,
            )
            Text(
                if (!running) "Turn it on from Home" else "On this Wi-Fi",
                style = CaptionStyle, color = Bridge.Muted,
            )
        }
        if (running) Box(Modifier.size(10.dp).alpha(pulse).clip(CircleShape).background(Bridge.Lit))
    }
}

/**
 * The last calls: who (red when missed), which way, voice or video, how long or how it ended, and
 * when; the button on each calls back the same way.
 */
@Composable
private fun RecentCalls(calls: List<dev.periy.bridge.server.CallRecord>, startCall: (String, Boolean) -> Unit) {
    rememberNow()
    GroupCard {
        calls.forEachIndexed { i, r ->
            val missed = r.outcome == "missed"
            val way = when (r.outcome) {
                "answered" -> (if (r.outgoing) "Outgoing" else "Incoming") + " · " + (r.durationMs / 1000).let { if (it >= 3600) "%d:%02d:%02d".format(it / 3600, it / 60 % 60, it % 60) else "%d:%02d".format(it / 60, it % 60) }
                "missed" -> "Missed"
                "no answer" -> "No answer"
                "declined" -> if (r.outgoing) "Declined" else "You declined"
                "busy" -> "Busy"
                "cancelled" -> "Cancelled"
                else -> "Did not connect"
            }
            MediaRow(
                r.names.joinToString(", ").ifEmpty { "Call" },
                way + " · " + ago(r.at),
                if (r.video) BlazeIcons.Video else BlazeIcons.Call,
                if (missed) Bridge.Danger else Color(0xFF34C759),
                first = i == 0,
                titleColor = if (missed) Bridge.Danger else null,
            ) {
                val who = r.names.firstOrNull()
                if (who != null) IconChip(if (r.video) BlazeIcons.Video else BlazeIcons.Call, "Call ${r.names.joinToString(", ")} back", tint = Bridge.Text, size = 36.dp) {
                    startCall(who, r.video)
                }
            }
        }
    }
}

/**
 * A test call: this phone's voice and picture through a whole call and back, to hear and see how a
 * call sounds and looks with nobody on the other end. Voice, or video.
 */
@Composable
private fun TestCallRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val calls = ctx.container.calls
    var wantVideo by remember { mutableStateOf(false) }
    val perms = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { if (hasMic(ctx)) calls.testCall(wantVideo && hasCamera(ctx)) }
    val start = { video: Boolean ->
        wantVideo = video
        val need = listOfNotNull(
            android.Manifest.permission.RECORD_AUDIO.takeIf { !hasMic(ctx) },
            android.Manifest.permission.CAMERA.takeIf { video && !hasCamera(ctx) },
        )
        if (need.isEmpty()) calls.testCall(video) else perms.launch(need.toTypedArray())
    }
    GroupCard {
        MediaRow("Test call", "Your own voice and picture, through a call and back", BlazeIcons.Call, Color(0xFF34C759), first = true) {
            IconChip(BlazeIcons.Call, "Voice test call", tint = Bridge.Text, size = 36.dp) { start(false) }
            Spacer(Modifier.width(6.dp))
            IconChip(BlazeIcons.Video, "Video test call", tint = Bridge.Text, size = 36.dp) { start(true) }
        }
    }
}

/**
 * Two phones on different networks: one shows a code, the other types it in, and they link
 * through their tunnels (net/LinkCode.kt). The phone showing the code still allows the other in.
 */
@Composable
private fun LinkFromAnywhere(status: PeerStatus?) {
    val c = androidx.compose.ui.platform.LocalContext.current.container
    // "show": this phone's code is open; "enter": typing the other phone's.
    var mode by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var shownAt by remember { mutableStateOf(0L) }
    var text by remember { mutableStateOf("") }
    val busy = status is PeerStatus.Waiting
    GroupCard {
        when {
            mode == "show" -> Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Link code", style = CaptionStyle, color = Bridge.Muted)
                Spacer(Modifier.height(6.dp))
                Text(
                    dev.periy.bridge.net.LinkCode.shown(code),
                    style = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp),
                    color = Bridge.Text,
                )
                Spacer(Modifier.height(8.dp))
                // Five minutes from when it was made, counted down.
                var left by remember { mutableStateOf(dev.periy.bridge.net.LinkCode.TTL_MS) }
                LaunchedEffect(shownAt) {
                    while (true) {
                        left = (shownAt + dev.periy.bridge.net.LinkCode.TTL_MS - System.currentTimeMillis()).coerceAtLeast(0)
                        if (left == 0L) { mode = ""; break }
                        kotlinx.coroutines.delay(1_000)
                    }
                }
                val spent by c.linkTriesSpent.collectAsStateWithLifecycle()
                Text(
                    if (spent) "This code has had its three tries. If the other phone did not link, show a new one."
                    else "On the other phone: Phones, Enter a link code. Any network, for the next ${(left + 59_999) / 60_000} min. You allow it here when it asks.",
                    style = CaptionStyle, color = if (spent) Bridge.Danger else Bridge.Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                SoftButton("Done") { c.closeLinkCode(); mode = "" }
            }
            mode == "enter" -> Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BridgeTextField(
                        text, { t -> text = dev.periy.bridge.net.LinkCode.normalize(t).take(dev.periy.bridge.net.LinkCode.LENGTH) },
                        Modifier.weight(1f), placeholder = "4821", minHeight = 46.dp, mono = true, digits = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    BridgeButton(if (busy) "Linking" else "Link") { if (!busy) c.peers.linkByCode(text) }
                }
                val line = when (status) {
                    is PeerStatus.Waiting -> if (status.code.isEmpty()) "Looking for the other phone…" else "Allow it on the other phone · code ${status.code}"
                    is PeerStatus.Failed -> status.message
                    null -> "The code the other phone shows under Show a link code."
                }
                Spacer(Modifier.height(8.dp))
                Text(line, style = CaptionStyle, color = if (status is PeerStatus.Failed) Bridge.Danger else Bridge.Muted)
                if (!busy) {
                    Spacer(Modifier.height(8.dp))
                    SoftButton("Cancel") { mode = ""; text = "" }
                }
            }
            else -> {
                SettingRow("Show a link code", first = true, icon = BlazeIcons.Link, iconColor = Bridge.Orange, onClick = {
                    code = c.openLinkCode(); shownAt = System.currentTimeMillis(); mode = "show"
                }) { Icon(BlazeIcons.Chevron, null, tint = Bridge.Faint, modifier = Modifier.size(18.dp)) }
                SettingRow("Enter a link code", icon = BlazeIcons.Phones, iconColor = Bridge.Orange, onClick = { mode = "enter" }) {
                    Icon(BlazeIcons.Chevron, null, tint = Bridge.Faint, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
    Text(
        "Link from anywhere: for a phone on another network. Both turn on From other networks.",
        style = CaptionStyle, color = Bridge.Muted,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
    )
}

@Composable
private fun TransferRow(t: Transfer, first: Boolean) {
    val inbound = t.direction == Direction.INBOUND
    MediaRow(
        title = t.name,
        subtitle = null,
        icon = if (inbound) BlazeIcons.Download else BlazeIcons.Upload,
        color = if (inbound) Color(0xFFFF9F0A) else Color(0xFF30D158),
        first = first,
        below = {
            Spacer(Modifier.height(6.dp))
            BlockProgress(
                t.fraction,
                color = when (t.state) {
                    TransferState.DONE -> Bridge.Good
                    TransferState.FAILED -> Bridge.Danger
                    TransferState.STALLED -> Bridge.Faint
                    TransferState.ACTIVE -> Bridge.Text
                },
            )
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    // A folder saved as a zip has no size until it has all come.
                    formatBytes(t.transferred) + if (t.total > 0) " of " + formatBytes(t.total) else "",
                    style = BodyStyle.copy(fontSize = 12.sp, fontFeatureSettings = "tnum"),
                    color = if (t.state == TransferState.FAILED) Bridge.Danger else Bridge.Muted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when (t.state) {
                        TransferState.ACTIVE -> formatRate(t.bytesPerSec)
                        TransferState.STALLED -> "Paused"
                        TransferState.DONE -> "Done"
                        TransferState.FAILED -> "Failed"
                    },
                    style = BodyStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    color = if (t.state == TransferState.DONE) Bridge.Good else Bridge.Muted,
                )
            }
        },
    )
}

// ---------------------------------------------------------------------- tab: settings

/**
 * The direct link's switch, under the choice in Settings: whether it is on, and why not when
 * it failed. Android runs one hotspot at a time, so while the phone's own is on, this says so
 * and offers the setting that turns it off.
 */
@Composable
private fun DirectRow(direct: DirectLink.State, hotspotOn: Boolean, toggle: () -> Unit, openHotspot: () -> Unit) {
    val blocked = hotspotOn && direct !is DirectLink.State.On && direct != DirectLink.State.Starting
    SettingRow(
        when (direct) {
            is DirectLink.State.On -> "Direct link on"
            DirectLink.State.Starting -> "Starting the direct link…"
            else -> "Direct link off"
        },
        when {
            blocked -> "Turn the phone's hotspot off first"
            direct is DirectLink.State.Failed -> direct.reason
            direct is DirectLink.State.On -> direct.info.ssid + " · the laptop joins by itself"
            else -> "The laptop joins by itself, offline meanwhile"
        },
        titleColor = if (direct is DirectLink.State.On) Bridge.Lit else Bridge.Text,
        onClick = if (blocked) openHotspot else toggle,
    ) {
        Action(
            when {
                blocked -> "Hotspot"
                direct is DirectLink.State.On || direct == DirectLink.State.Starting -> "Stop"
                direct is DirectLink.State.Failed -> "Retry"
                else -> "Start"
            }
        )
    }
}

/**
 * From other networks, through the tunnel (docs/tunnel-protocol.md): who is connected that way
 * now and how much it has carried, since on mobile data every byte counts.
 */
@Composable
private fun RemoteRow(state: UiState, set: (Boolean) -> Unit) {
    val c = (LocalContext.current.applicationContext as dev.periy.bridge.BridgeApp).container
    val peers by c.tunnel.peers.collectAsStateWithLifecycle()
    val devices by c.devices.devices.collectAsStateWithLifecycle()
    val used = remember(peers) { c.tunnel.bytesSoFar() }
    val note = when {
        !state.remote -> null
        peers.isNotEmpty() -> peers.map { p -> devices.firstOrNull { it.id == p.deviceId }?.let { dev.periy.bridge.server.shownName(it, devices) } ?: "A device" }
            .distinct().joinToString(", ") + " connected · " + formatBytes(used)
        state.ipv6 == null -> "No IPv6 right now · over IPv4"
        used > 0 -> formatBytes(used) + " so far"
        else -> "Paired devices only"
    }
    SettingRow("From other networks", note, icon = BlazeIcons.Link, iconColor = Bridge.Blue) {
        Toggle(state.remote) { set(it) }
    }
}

private fun LazyListScope.settingsTab(
    state: UiState,
    vm: MainViewModel,
    theme: String,
    look: dev.periy.bridge.Look,
    laptopLink: MainViewModel.LaptopLink,
    direct: DirectLink.State,
    toggleDirect: () -> Unit,
    pickFolder: () -> Unit,
    requestNotifications: () -> Unit,
    requestMusic: () -> Unit,
    requestPhotos: () -> Unit,
    openSettings: (Intent) -> Unit,
    showOem: () -> Unit,
) {
    item { SectionBar("Appearance") }
    // Shared with every open page.
    item { ColourPicker(look.accent) { vm.setAccent(it) } }
    item {
        Box(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp)) {
            val themes = listOf("system", "light", "dark")
            SegmentedRow(listOf("Automatic", "Light", "Dark"), themes.indexOf(theme)) { vm.setTheme(themes[it]) }
        }
    }

    item { SectionBar("Receiving") }
    item {
        GroupCard {
            SettingRow(
                "Save files to",
                (state.destination ?: "Not chosen yet") + when (state.storageMode) {
                    Storage.Mode.DIRECT_SEEK -> if (state.freeSpace > 0) " · " + formatBytes(state.freeSpace) + " free" else ""
                    Storage.Mode.STAGED_COPY -> " · staged"
                    Storage.Mode.NO_DESTINATION -> ""
                },
                first = true, icon = BlazeIcons.File, iconColor = Bridge.Orange,
                onClick = pickFolder,
            ) { Action("Change") }
            SettingRow("Stage in app storage", "Only if files arrive damaged") {
                Toggle(state.forceStagedCopy) { vm.setForceStagedCopy(it) }
            }
        }
    }

    item { SectionBar("Speed") }
    item {
        GroupCard {
            SettingRow(
                "Laptop link",
                if (laptopLink.mode == "hotspot") "Keeps the laptop's internet · 44–66 MB/s"
                else "Fastest, offline · 55–115 MB/s",
                first = true,
                icon = if (laptopLink.mode == "hotspot") BlazeIcons.Hotspot else BlazeIcons.Bolt,
                iconColor = if (laptopLink.mode == "hotspot") Bridge.Purple else Bridge.Blue,
            )
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                val modes = listOf("direct", "hotspot")
                SegmentedRow(listOf("Direct link", "Hotspot"), modes.indexOf(laptopLink.mode)) { vm.setLaptopLink(mode = modes[it]) }
            }
            if (laptopLink.mode == "hotspot") HotspotFields(laptopLink, vm) { vm.tetherSettingsIntent()?.let(openSettings) }
            else DirectRow(direct, state.hotspotActive, toggleDirect) { vm.tetherSettingsIntent()?.let(openSettings) }
            SettingRow("Connections per file")
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                SegmentedRow(listOf("1", "2", "4", "8"), listOf(1, 2, 4, 8).indexOf(state.uploadStreams)) {
                    vm.setUploadStreams(listOf(1, 2, 4, 8)[it])
                }
            }
            SettingRow(
                "USB-C cable", "Needs USB tethering on",
                onClick = { vm.tetherSettingsIntent()?.let(openSettings) },
            ) { Action("Set up") }
        }
    }

    item { SectionBar("Laptop access") }
    item {
        GroupCard {
            SettingRow(
                "Browse this phone",
                if (Build.VERSION.SDK_INT < 30) "Needs Android 11" else "Read-only",
                first = true, icon = BlazeIcons.File, iconColor = Bridge.Orange,
                onClick = { vm.allFilesIntent()?.let(openSettings) },
            ) { if (state.browsable) Check(true) else Action("Allow") }
            SettingRow("Sync clipboard", icon = BlazeIcons.Paste, iconColor = Bridge.Teal) {
                Toggle(state.clipSync) { vm.setClipSync(it) }
            }
            RemoteRow(state) { vm.setRemote(it) }
            WebsiteRow()
            // Allowed is not always running: after an update HyperOS starts it again only with Autostart on.
            val notifCtx = LocalContext.current
            SettingRow(
                "Notifications on the laptop",
                when {
                    !state.notifAccess -> "Needs notification access"
                    state.notifLive -> null
                    state.autostartBlocked -> "Stopped: HyperOS needs Autostart on for Localhost 8787 to start it again"
                    else -> "Not running: turn its access off and on again"
                },
                icon = BlazeIcons.Message, iconColor = Bridge.Danger,
                onClick = {
                    openSettings(if (state.notifAccess && !state.notifLive && state.autostartBlocked) OemBatterySetup.autostartIntent(notifCtx) else vm.notifAccessIntent())
                },
            ) { if (state.notifAccess && state.notifLive) Check(true) else Action(if (state.notifAccess) "Fix" else "Allow") }
            SettingRow(
                "Copies from any app, at once",
                when {
                    !state.watchLogs -> "One-time, over USB: adb shell pm grant ${LocalContext.current.packageName} android.permission.READ_LOGS"
                    !state.watchOverlay -> "Needs Display over other apps"
                    else -> null
                },
                icon = BlazeIcons.Copy, iconColor = Bridge.Blue,
                onClick = if (state.watchLogs && !state.watchOverlay) ({ openSettings(vm.overlayIntent()) }) else null,
            ) {
                when {
                    state.watchLogs && state.watchOverlay -> Check(true)
                    state.watchLogs -> Action("Allow")
                    else -> Text("USB", style = LabelStyle, color = Bridge.Muted)
                }
            }
            SettingRow(
                "Screenshots to the laptop",
                if (state.screenshotClip && !state.canReadPhotos) "Needs photo access" else null,
                icon = BlazeIcons.Image, iconColor = Bridge.Purple,
            ) {
                Toggle(state.clipSync && state.screenshotClip && state.canReadPhotos) { on ->
                    if (on && !state.canReadPhotos) requestPhotos() else vm.setScreenshotClip(on)
                }
            }
        }
    }

    item { SectionBar("Music") }
    item {
        GroupCard {
            if (state.musicGranted) {
                SettingRow(
                    if (state.musicTracks > 0) "${state.musicTracks} songs" else "No music found",
                    first = true, icon = BlazeIcons.Pulse, iconColor = Bridge.Pink,
                )
            } else {
                SettingRow(
                    "Music library", "Audio only",
                    first = true, icon = BlazeIcons.Pulse, iconColor = Bridge.Pink, onClick = requestMusic,
                ) { Action("Allow") }
            }
            // Albums without a cover of their own get one from Apple's catalogue; only the
            // album and artist names are sent.
            val prefs = LocalContext.current.container.prefs
            var lookup by remember { mutableStateOf(prefs.coverLookup) }
            SettingRow("Find missing album art", "From Apple's catalogue", icon = BlazeIcons.Image, iconColor = Bridge.Purple) {
                Toggle(lookup) { lookup = it; prefs.coverLookup = it }
            }
        }
    }

    item { SectionBar("Keep running") }
    item {
        GroupCard {
            SettingRow(
                "Notifications", if (state.notificationsGranted) null else "Needed to stay running",
                first = true,
                onClick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !state.notificationsGranted) requestNotifications else null,
            ) { Check(state.notificationsGranted) }
            SettingRow(
                "Battery optimisation", if (state.batteryExempt) "Off" else "Tap to turn off",
                onClick = if (!state.batteryExempt) ({ vm.batteryOptimizationIntent()?.let(openSettings) ?: showOem() }) else null,
            ) { Check(state.batteryExempt) }
            SettingRow("Manufacturer settings", "If the app gets stopped", onClick = showOem) {
                Icon(BlazeIcons.Chevron, null, tint = Bridge.Faint, modifier = Modifier.size(18.dp))
            }
        }
    }

}

/** A word on the right of a setting that does something, in blue. */
@Composable
private fun Action(label: String) {
    Text(label, style = TextStyle(fontSize = 16.sp), color = Bridge.Blue)
}

/**
 * The colour: a row of round swatches, the one in use ringed. The first is Automatic, black and
 * white, drawn half and half.
 */
@Composable
private fun ColourPicker(current: String, onPick: (String) -> Unit) {
    val name = if (current == "auto") "Automatic" else current.replaceFirstChar { it.uppercase() }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Colour", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Text, modifier = Modifier.weight(1f))
            Text(name, style = TextStyle(fontSize = 15.sp), color = Bridge.Muted)
        }
        Spacer(Modifier.height(10.dp))
        // All on one line, sharing the width: Automatic, then the colours.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Swatch(
                Brush.linearGradient(0.5f to Color.White, 0.5f to Color(0xFF1D1D1F)),
                "Automatic", current == "auto", Modifier.weight(1f),
            ) { onPick("auto") }
            ACCENTS.forEach { (id, c) ->
                Swatch(SolidColor(c), id, current == id, Modifier.weight(1f)) { onPick(id) }
            }
        }
    }
}

@Composable
private fun Swatch(fill: androidx.compose.ui.graphics.Brush, description: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.widthIn(max = 44.dp).aspectRatio(1f).clip(CircleShape)
            .border(if (on) 2.5.dp else 0.dp, if (on) Bridge.Text else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .padding(if (on) 5.dp else 2.dp)
            .clip(CircleShape)
            .background(fill)
            // Half black, half white: a fine ring keeps its edge on either background.
            .then(if (description == "Automatic") Modifier.border(1.dp, Color(0x5A8E8E93), CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(
            BlazeIcons.Check, description, modifier = Modifier.size(16.dp),
            // Over the white half of Automatic, and over yellow, a white tick would not show.
            tint = if (description == "Automatic" || description == "yellow") Color(0xFF1D1D1F) else Color.White,
        )
    }
}


/**
 * The hotspot's name and password, as set in the phone's hotspot settings. Android keeps
 * them from apps, so they are typed here once; the laptop helper needs them to join.
 */
@Composable
private fun HotspotFields(link: MainViewModel.LaptopLink, vm: MainViewModel, openHotspot: () -> Unit) {
    var ssid by remember { mutableStateOf(link.ssid) }
    var pass by remember { mutableStateOf(link.pass) }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        BridgeTextField(ssid, { ssid = it; vm.setLaptopLink(ssid = it) }, placeholder = "Hotspot name", minHeight = 46.dp, mono = true)
        Spacer(Modifier.height(8.dp))
        BridgeTextField(pass, { pass = it; vm.setLaptopLink(pass = it) }, placeholder = "Password", minHeight = 46.dp, mono = true)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "As in the hotspot settings",
                style = CaptionStyle, color = Bridge.Muted, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            SoftButton("Hotspot settings", onClick = openHotspot)
        }
    }
}

@Composable
private fun Check(ok: Boolean) {
    if (ok) Icon(BlazeIcons.Check, "Done", tint = Bridge.Good, modifier = Modifier.size(20.dp))
    else Text("!", style = TitleStyle, color = Bridge.Orange)
}

// ---------------------------------------------------------------------- oem screen

@Composable
private fun OemScreen(
    steps: List<OemBatterySetup.Step>,
    modifier: Modifier,
    onOpen: (Intent) -> Unit,
    onDone: () -> Unit,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionBar("Keep Localhost 8787 running") }
        items(steps) { step ->
            BridgeRow(step.title) {
                RowNote(step.detail)
                Spacer(Modifier.height(10.dp))
                SoftButton("Open") { onOpen(step.intent) }
            }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(16.dp)) {
                BridgeButton("Done", Modifier.fillMaxWidth(), onClick = onDone)
            }
        }
    }
}

/** "just now", "5 min ago", "3 h ago", "2 d ago" -- coarse on purpose; it is a glance. */
private fun lastSeen(at: Long): String {
    val s = (System.currentTimeMillis() - at) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}
