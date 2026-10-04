package dev.periy.bridge.ui

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.shadow
import kotlin.math.roundToInt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.periy.bridge.server.Control
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min

/**
 * The phone as the laptop's trackpad and keyboard.
 *
 * Gestures follow a Windows precision touchpad, and the helper turns each one into what
 * Windows itself does for that gesture:
 *
 *   1 finger       move          tap = click, tap then drag = drag, hold = drag
 *   2 fingers      scroll (with momentum), pinch = zoom, tap = right click
 *   3 fingers      up = Task View, down = desktop, sideways = switch apps
 *   4 fingers      sideways = switch desktops, up / down as 3
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlPane(running: Boolean, onStart: () -> Unit, modifier: Modifier = Modifier, active: Boolean = true) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val laptops by Control.connected.collectAsStateWithLifecycle()
    val pad = remember { PadState(ctx) }

    // While this screen is open: keep the display on, and ask Wi-Fi for low latency --
    // power-save naps in the radio are what make a remote pointer feel sticky. Only while it is
    // the tab: a slide between two other tabs passes through it, and should cost nothing.
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose {}
        view.keepScreenOn = true
        Control.inUse = true
        @Suppress("DEPRECATION")
        val lock = runCatching {
            ctx.applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "xoosh-control")
                ?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        onDispose {
            view.keepScreenOn = false
            Control.inUse = false
            runCatching { lock?.takeIf { it.isHeld }?.release() }
        }
    }

    // The keyboard's room is made by the screen around this pane.
    Column(modifier) {
        if (!running || laptops.isEmpty()) {
            Column(Modifier.fillMaxWidth().panel().padding(14.dp)) {
                if (!running) {
                    Text("Start the app, then the laptop helper.", style = BodyStyle, color = Bridge.Text)
                    BridgeButton("Start", Modifier.padding(top = 10.dp), onClick = onStart)
                } else {
                    Text(
                        "Run blazeit-pc.bat on the laptop (the page's Laptop control has it), then allow it here once.",
                        style = BodyStyle, color = Bridge.Text,
                    )
                }
            }
        }

        val status = when {
            !running -> "Off"
            laptops.isEmpty() -> "No laptop"
            else -> dev.periy.bridge.server.helperMachine(laptops.first())
        }
        // The laptop's screen here instead of a trackpad: this phone as its second monitor.
        if (running && laptops.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp)
                    .clip(ButtonShape).background(Bridge.Surface)
                    .clickable {
                        ctx.startActivity(android.content.Intent(ctx, SecondScreenActivity::class.java))
                    }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(BlazeIcons.Laptop, null, tint = Bridge.Text, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("The laptop's screen", style = LabelStyle, color = Bridge.Text, modifier = Modifier.weight(1f))
                Icon(BlazeIcons.Chevron, null, tint = Bridge.Muted, modifier = Modifier.size(18.dp))
            }
        }
        // Locked each time the tab opens (leaving it drops this state), so a swipe across the
        // middle of the screen turns the page instead of moving the pointer. A tap unlocks.
        var unlocked by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Trackpad(pad, status, laptops.isNotEmpty(), Modifier.fillMaxSize())
            if (!unlocked) PadLock(Modifier.matchParentSize()) {
                unlocked = true
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
        }

        // With the keyboard up, the keys sit directly on top of it. Clicks live on the pad
        // itself: tap to click, two-finger tap to right-click, tap then drag to hold.
        KeyRow(pad, WindowInsets.isImeVisible)
    }
}

/**
 * Over the trackpad while it is locked: a padlock and what a tap does. It takes taps only, so a
 * swipe across it goes on to the tabs.
 */
@Composable
private fun PadLock(modifier: Modifier, unlock: () -> Unit) {
    val shape = RoundedCornerShape(cardRadius)
    Box(
        modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(shape)
            .background(Bridge.Surface)
            .clickable(onClickLabel = "Unlock the trackpad", onClick = unlock),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Bridge.Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(BlazeIcons.Lock, null, tint = Bridge.Accent, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("Tap to use the trackpad", style = TitleStyle, color = Bridge.Text)
        }
    }
}

// -------------------------------------------------------------------- state

/** Pointer speed, sticky modifier keys, and the send path. */
private class PadState(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("xoosh_control", Context.MODE_PRIVATE)
    var speed by mutableFloatStateOf(prefs.getFloat("speed", 1f))
        private set
    /** Modifier keys tapped on the key row, held for the next key, character or click. */
    val mods = mutableStateListOf<String>()

    fun chooseSpeed(k: Float) { speed = k; prefs.edit().putFloat("speed", k).apply() }

    fun send(line: String) = Control.send(line)

    fun toggleMod(name: String) { if (name in mods) mods.remove(name) else mods.add(name) }

    /** Run [block] with the sticky modifiers held, then release them. */
    fun withMods(block: () -> Unit) {
        val held = mods.toList()
        held.forEach { send("kd $it") }
        block()
        held.asReversed().forEach { send("ku $it") }
        mods.clear()
    }

    fun key(name: String) = withMods { send("k $name") }
    fun click(button: String) = withMods { send("c $button") }

    fun type(text: String) {
        if (text.isEmpty()) return
        // With Ctrl or Alt held, a letter is a shortcut (Ctrl+C), not a character.
        if (mods.isNotEmpty() && text.length == 1 && text[0].isLetterOrDigit()) {
            key(text.lowercase())
            return
        }
        val parts = text.split('\n')
        parts.forEachIndexed { i, part ->
            if (part.isNotEmpty()) {
                send("t " + URLEncoder.encode(part, "UTF-8").replace("+", "%20"))
            }
            if (i < parts.lastIndex) key("enter")
        }
    }
}

// -------------------------------------------------------------------- trackpad

private enum class Mode { UNDECIDED, POINT, DRAG, SCROLL, PINCH, SWIPE3, SWITCHER, SWIPE4, DONE }

@Composable
private fun Trackpad(pad: PadState, status: String, live: Boolean, modifier: Modifier) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // Where the speed control sits, in the pad's own coordinates. A touch that starts
    // there belongs to the control, not to the pointer.
    var speedArea by remember { mutableStateOf(Rect.Zero) }
    // The two seek arrows on the sides: touches there are theirs, not the pointer's.
    var backArea by remember { mutableStateOf(Rect.Zero) }
    var skipArea by remember { mutableStateOf(Rect.Zero) }
    var fling by remember { mutableStateOf<Job?>(null) }
    var lastTapAt by remember { mutableStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .card()
            // Keep Android's back gesture from eating swipes that start near the edges.
            .systemGestureExclusion()
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val swipeDist = 48.dp.toPx()
                val switchStep = 64.dp.toPx()
                val dragRadius = 48.dp.toPx()

                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    if (speedArea.contains(first.position) || backArea.contains(first.position) ||
                        skipArea.contains(first.position)
                    ) return@awaitEachGesture
                    fling?.cancel()
                    val t0 = first.uptimeMillis
                    val dragArmed = t0 - lastTapAt < DOUBLE_TAP_MS &&
                        (first.position - lastTapPos).getDistance() < dragRadius

                    var mode = Mode.UNDECIDED
                    var maxFingers = 1
                    var travel = 0f
                    var acc = Offset.Zero          // centroid movement since the mode began
                    var spanLog = 0f               // pinch: accumulated log(span ratio)
                    var carry = Offset.Zero        // sub-count remainders for move / wheel
                    val velocity = VelocityTracker()
                    var centroid = first.position

                    fun reset() { acc = Offset.Zero; spanLog = 0f; carry = Offset.Zero; velocity.resetTracking() }

                    while (true) {
                        val event = if (mode == Mode.UNDECIDED && maxFingers == 1) {
                            // Waking up without an event is how a still finger becomes a hold.
                            val left = HOLD_MS - (SystemClock.uptimeMillis() - t0)
                            withTimeoutOrNull(left.coerceAtLeast(1)) { awaitPointerEvent() }
                        } else awaitPointerEvent()

                        if (event == null) {
                            if (travel < slop) {
                                mode = Mode.DRAG
                                pad.send("b l d")
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }
                            continue
                        }

                        // Every touch on the pad is the pad's: taken here, it never turns the page
                        // (the tabs swipe from the keys under the pad instead).
                        event.changes.forEach { it.consume() }
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val fingers = pressed.size
                        if (fingers > maxFingers) {
                            maxFingers = fingers
                            // A second finger landing after the pointer started moving means
                            // "now scroll", exactly as on a laptop trackpad.
                            if (mode == Mode.POINT) { mode = Mode.UNDECIDED; reset() }
                        }

                        val moving = pressed.filter { it.previousPressed }
                        val delta = if (moving.isEmpty()) Offset.Zero
                        else moving.fold(Offset.Zero) { a, c -> a + (c.position - c.previousPosition) } / moving.size.toFloat()
                        travel += delta.getDistance()
                        acc += delta
                        centroid = pressed.fold(Offset.Zero) { a, c -> a + c.position } / fingers.toFloat()
                        velocity.addPosition(event.changes.first().uptimeMillis, centroid)
                        if (fingers == 2 && moving.size == 2) spanLog += spanChange(moving)

                        if (mode == Mode.UNDECIDED && travel > slop) {
                            mode = when {
                                fingers == 1 && dragArmed -> Mode.DRAG.also { pad.send("b l d") }
                                fingers == 1 -> Mode.POINT
                                fingers == 2 -> if (abs(spanLog) > 0.12f && abs(spanLog) * 400f > acc.getDistance()) Mode.PINCH else Mode.SCROLL
                                fingers == 3 -> Mode.SWIPE3
                                else -> Mode.SWIPE4
                            }
                            if (mode == Mode.PINCH || mode == Mode.SCROLL) { acc = Offset.Zero; spanLog = 0f }
                        }

                        when (mode) {
                            Mode.POINT, Mode.DRAG -> {
                                val dt = (event.changes.first().uptimeMillis - event.changes.first().previousUptimeMillis).coerceAtLeast(1)
                                val v = delta.getDistance() / dt
                                val k = POINTER_K * pad.speed * (1f + ACCEL * min(v, 4f))
                                carry += delta * k
                                val dx = carry.x.toInt(); val dy = carry.y.toInt()
                                if (dx != 0 || dy != 0) {
                                    pad.send("m $dx $dy")
                                    carry = Offset(carry.x - dx, carry.y - dy)
                                }
                            }
                            Mode.SCROLL -> {
                                // Content follows the fingers, as Windows does by default.
                                carry += Offset(-delta.x, delta.y) * WHEEL_K
                                val wx = carry.x.toInt(); val wy = carry.y.toInt()
                                if (wx != 0 || wy != 0) {
                                    pad.send("w $wy $wx")
                                    carry = Offset(carry.x - wx, carry.y - wy)
                                }
                            }
                            Mode.PINCH -> {
                                while (spanLog > PINCH_STEP) { pad.send("z 1"); spanLog -= PINCH_STEP }
                                while (spanLog < -PINCH_STEP) { pad.send("z -1"); spanLog += PINCH_STEP }
                            }
                            Mode.SWIPE3 -> if (acc.getDistance() > swipeDist) {
                                if (abs(acc.y) > abs(acc.x)) {
                                    pad.send(if (acc.y < 0) "h win+tab" else "h win+d")
                                    mode = Mode.DONE
                                } else {
                                    // Hold Alt and step through the app switcher, like the real gesture.
                                    pad.send("kd alt")
                                    pad.send(if (acc.x > 0) "k tab" else "h shift+tab")
                                    mode = Mode.SWITCHER
                                    acc = Offset.Zero
                                }
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            Mode.SWITCHER -> while (abs(acc.x) > switchStep) {
                                pad.send(if (acc.x > 0) "k tab" else "h shift+tab")
                                acc = Offset(acc.x - switchStep * if (acc.x > 0) 1 else -1, acc.y)
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            Mode.SWIPE4 -> if (acc.getDistance() > swipeDist) {
                                pad.send(
                                    when {
                                        abs(acc.y) > abs(acc.x) -> if (acc.y < 0) "h win+tab" else "h win+d"
                                        acc.x < 0 -> "h ctrl+win+right"
                                        else -> "h ctrl+win+left"
                                    }
                                )
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                mode = Mode.DONE
                            }
                            else -> Unit
                        }
                        event.changes.forEach(PointerInputChange::consume)
                    }

                    when (mode) {
                        Mode.UNDECIDED -> {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            when (maxFingers) {
                                1 -> {
                                    pad.click("l")
                                    lastTapAt = SystemClock.uptimeMillis()
                                    lastTapPos = first.position
                                }
                                2 -> pad.click("r")
                                else -> Unit
                            }
                        }
                        Mode.DRAG -> pad.send("b l u")
                        Mode.SWITCHER -> pad.send("ku alt")
                        Mode.SCROLL -> {
                            val v = velocity.calculateVelocity()
                            val vx = -v.x * WHEEL_K / 1000f
                            val vy = v.y * WHEEL_K / 1000f
                            if (hypot(vx, vy) > FLING_MIN) fling = scope.launch { momentum(pad, vx, vy) }
                        }
                        else -> Unit
                    }
                    if (mode != Mode.UNDECIDED || maxFingers != 1) lastTapAt = 0L
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (if (live) "● " else "○ ") + status.uppercase(),
            style = LabelStyle.copy(fontSize = 10.sp),
            color = if (live) Bridge.Text else Bridge.Muted,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        )
        SpeedControl(
            pad,
            Modifier
                .align(Alignment.TopEnd)
                .onGloballyPositioned { speedArea = it.boundsInParent() },
        )
        // Rewind and skip: the left and right arrow keys, which seek in YouTube, Netflix and most
        // players (and step through photos and slides). Hold to keep going.
        SeekArrow(
            pad, "left", "Back",
            Modifier.align(Alignment.CenterStart).onGloballyPositioned { backArea = it.boundsInParent() },
        )
        SeekArrow(
            pad, "right", "Skip",
            Modifier.align(Alignment.CenterEnd).onGloballyPositioned { skipArea = it.boundsInParent() },
        )
        Column(Modifier.padding(horizontal = 62.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(BlazeIcons.Trackpad, null, tint = Bridge.Faint, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(12.dp))
            Text("Trackpad", style = TitleStyle, color = Bridge.Muted)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap to click · two-finger tap to right-click\nTwo fingers scroll · pinch to zoom\nThree fingers for apps · four for desktops",
                style = BodyStyle.copy(fontSize = 13.sp, lineHeight = 19.sp),
                color = Bridge.Faint,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A tall, quiet arrow at the pad's edge that sends an arrow key: once on a tap, and again and
 * again while held, as a real key repeats.
 */
@Composable
private fun SeekArrow(pad: PadState, key: String, label: String, modifier: Modifier) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var held by remember { mutableStateOf(false) }
    Column(
        modifier
            .padding(6.dp)
            .width(44.dp)
            .height(112.dp)
            .clip(ButtonShape)
            .background(if (held) Bridge.Accent else Bridge.Chip.copy(alpha = 0.7f))
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown()
                    held = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    pad.key(key)
                    val repeat = scope.launch {
                        kotlinx.coroutines.delay(REPEAT_AFTER_MS)
                        while (true) {
                            pad.key(key)
                            kotlinx.coroutines.delay(REPEAT_EVERY_MS)
                        }
                    }
                    // Wait for every finger to lift.
                    do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                    repeat.cancel()
                    held = false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            BlazeIcons.Chevron, label,
            tint = if (held) Bridge.OnAccent else Bridge.Text,
            modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = if (key == "left") 180f else 0f },
        )
        Text(label, style = LabelStyle.copy(fontSize = 10.sp), color = if (held) Bridge.OnAccent else Bridge.Muted)
    }
}

private const val REPEAT_AFTER_MS = 420L
private const val REPEAT_EVERY_MS = 110L

/**
 * Pointer speed as one quiet control in the pad's corner: a small dial whose needle
 * sweeps further for faster, and the word. A tap moves to the next speed.
 */
@Composable
private fun SpeedControl(pad: PadState, modifier: Modifier) {
    val view = LocalView.current
    val level = SPEEDS.indexOfFirst { abs(it.second - pad.speed) < 0.01f }.coerceAtLeast(0)
    val sweep by animateFloatAsState(0.18f + 0.64f * level / (SPEEDS.size - 1), label = "needle")
    val track = Bridge.Faint
    val fill = Bridge.Accent
    val needle = Bridge.Text
    Box(modifier.padding(8.dp)) {
        Row(
            Modifier
                .clip(ButtonShape)
                .background(Bridge.Chip)
                .clickable {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    pad.chooseSpeed(SPEEDS[(level + 1) % SPEEDS.size].second)
                }
                .padding(start = 9.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(width = 22.dp, height = 14.dp)) {
                val w = 2.dp.toPx()
                val r = size.width / 2 - w
                val c = Offset(size.width / 2, size.height - w / 2)
                val box = androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r)
                drawArc(track, 180f, 180f, false, box.topLeft, box.size, style = Stroke(w, cap = StrokeCap.Round))
                drawArc(fill, 180f, 180f * sweep, false, box.topLeft, box.size, style = Stroke(w, cap = StrokeCap.Round))
                val a = Math.PI * (1 + sweep)
                val tip = Offset(c.x + (r - w) * kotlin.math.cos(a).toFloat(), c.y + (r - w) * kotlin.math.sin(a).toFloat())
                drawLine(needle, c, tip, strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(needle, 1.8.dp.toPx(), c)
            }
            Spacer(Modifier.width(7.dp))
            Text(SPEEDS[level].first, style = LabelStyle.copy(fontSize = 12.5.sp), color = Bridge.Text)
        }
    }
}

private val SPEEDS = listOf("Slow" to 0.6f, "Normal" to 1f, "Fast" to 1.6f)

/** Log of how much the two fingers moved apart since the previous event. */
private fun spanChange(two: List<PointerInputChange>): Float {
    val a = two[0]; val b = two[1]
    val now = (a.position - b.position).getDistance()
    val before = (a.previousPosition - b.previousPosition).getDistance()
    if (now < 1f || before < 1f) return 0f
    return ln(now / before)
}

/** Scrolling that keeps going after the fingers lift, slowing like a real trackpad. */
private suspend fun momentum(pad: PadState, startX: Float, startY: Float) {
    var vx = startX; var vy = startY          // wheel units per ms
    var cx = 0f; var cy = 0f
    var last = withFrameMillis { it }
    while (hypot(vx, vy) > FLING_STOP) {
        val now = withFrameMillis { it }
        val dt = (now - last).coerceIn(1, 50).toFloat()
        last = now
        cx += vx * dt; cy += vy * dt
        val wx = cx.toInt(); val wy = cy.toInt()
        if (wx != 0 || wy != 0) { pad.send("w $wy $wx"); cx -= wx; cy -= wy }
        val decay = exp(-dt / FLING_TAU)
        vx *= decay; vy *= decay
    }
}

// -------------------------------------------------------------------- keys

/**
 * Under the pad. With the phone keyboard down: the keyboard button and the laptop's media keys
 * (previous, play or pause, next, volume, mute), which work in whatever is playing. With the
 * keyboard up, the keys a phone keyboard lacks sit on top of it, in a row that scrolls: Esc, Tab,
 * Ctrl, Alt, Shift and Win (tap to hold for the next key or click), the arrows, Del, Home, End.
 */
@Composable
private fun KeyRow(pad: PadState, imeUp: Boolean) {
    val ctx = LocalContext.current
    var catcher by remember { mutableStateOf<KeyCatcher?>(null) }

    Row(
        Modifier
            .fillMaxWidth()
            // Over the phone keyboard the row needs its own ground; otherwise it floats like the rest.
            .then(if (imeUp) Modifier.background(Bridge.Bg) else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The phone's own keyboard, typing straight into the laptop.
        AndroidView(
            factory = { KeyCatcher(it, onText = pad::type, onKey = pad::key).also { v -> catcher = v } },
            modifier = Modifier.size(1.dp),
        )
        KeyChip(if (imeUp) "Done" else "Keyboard", on = imeUp) {
            val v = catcher ?: return@KeyChip
            val imm = ctx.getSystemService(InputMethodManager::class.java)
            if (imeUp) {
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
            } else {
                v.requestFocus()
                imm?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        if (imeUp) {
            KeyChip("Esc") { pad.key("esc") }
            KeyChip("Ctrl", on = "ctrl" in pad.mods) { pad.toggleMod("ctrl") }
            KeyChip("Win", on = "win" in pad.mods) {
                // Tapped twice with nothing in between, Win opens Start, as the real key does.
                if ("win" in pad.mods) { pad.mods.remove("win"); pad.key("win") } else pad.toggleMod("win")
            }
            // The arrows fill the rest of the row, big enough to hit without looking, and
            // repeat while held.
            ArrowKey(pad, "left", Modifier.weight(1f))
            ArrowKey(pad, "up", Modifier.weight(1f))
            ArrowKey(pad, "down", Modifier.weight(1f))
            ArrowKey(pad, "right", Modifier.weight(1f))
        } else {
            // The laptop's media keys: they reach whatever is playing, even in the background.
            // Play or pause is the one reached for most, so it is the big one in the middle, in the accent.
            IconKeyChip(BlazeIcons.Prev, "Previous", Modifier.weight(1f)) { pad.key("prev") }
            PlayPauseKey(pad)
            IconKeyChip(BlazeIcons.Next, "Next", Modifier.weight(1f)) { pad.key("next") }
            Spacer(Modifier.width(4.dp))
            VolumeKey(pad, Modifier.weight(1.3f))
        }
    }
}

/**
 * The laptop's volume: a speaker key that opens a tall bar above it. Drag or tap the bar to set
 * the level, the speaker at its foot mutes; tap the key again, or anywhere else, to close it.
 * The level shown is the laptop's own, reported by its helper, so a change made on the laptop
 * shows here too.
 */
@Composable
private fun VolumeKey(pad: PadState, modifier: Modifier) {
    val vol by Control.volume.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val lift = with(androidx.compose.ui.platform.LocalDensity.current) { 50.dp.roundToPx() }
    val icon = when {
        vol.muted || vol.level == 0f -> BlazeIcons.Mute
        vol.level in 0f..0.5f -> BlazeIcons.VolumeDown
        else -> BlazeIcons.VolumeUp
    }
    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(ButtonShape)
                .background(if (open) Bridge.Accent else Bridge.Surface)
                .clickable { open = !open },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, "Volume", tint = if (open) Bridge.OnAccent else Bridge.Text, modifier = Modifier.size(20.dp))
                if (vol.level >= 0f) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        (vol.level * 100).roundToInt().toString(),
                        style = LabelStyle.copy(fontSize = 12.sp), color = if (open) Bridge.OnAccent else Bridge.Muted,
                    )
                }
            }
        }
        if (open) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.BottomCenter,
                offset = androidx.compose.ui.unit.IntOffset(0, -lift),
                onDismissRequest = { open = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) { VolumeBar(pad, vol) }
        }
    }
}

@Composable
private fun VolumeBar(pad: PadState, vol: Control.Volume) {
    val view = LocalView.current
    // What the finger set, shown until the laptop reports it back.
    var local by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(vol) { if (!dragging) local = null }
    val level = local ?: vol.level.takeIf { it >= 0f } ?: 0.5f
    Column(
        Modifier
            .width(68.dp)
            .shadow(14.dp, RoundedCornerShape(26.dp))
            .clip(RoundedCornerShape(26.dp))
            .background(Bridge.Surface)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text((level * 100).roundToInt().toString(), style = LabelStyle, color = Bridge.Text)
        Box(
            Modifier
                .padding(vertical = 10.dp)
                .width(42.dp)
                .height(210.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(Bridge.Chip)
                .pointerInput(Unit) {
                    fun at(y: Float) = (1f - y / size.height).coerceIn(0f, 1f)
                    var lastSent = 0L
                    fun set(v: Float, force: Boolean) {
                        local = v
                        val now = SystemClock.uptimeMillis()
                        if (force || now - lastSent > 45) {
                            pad.send("v " + String.format(java.util.Locale.US, "%.3f", v))
                            lastSent = now
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        dragging = true
                        var cur = at(down.position.y)
                        set(cur, false)
                        down.consume()
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            cur = at(c.position.y)
                            set(cur, false)
                            c.consume()
                        }
                        set(cur, true)
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        dragging = false
                    }
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(level)
                    .background(if (vol.muted) Bridge.Faint else Bridge.Accent),
            )
        }
        Box(
            Modifier
                .size(42.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (vol.muted) Bridge.Accent else Bridge.Chip)
                .clickable {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    pad.send("vm")
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(BlazeIcons.Mute, if (vol.muted) "Unmute" else "Mute", tint = if (vol.muted) Bridge.OnAccent else Bridge.Text, modifier = Modifier.size(20.dp))
        }
    }
}

/** Play or pause on the laptop: a big circle in the accent, the key a remote is held for. */
@Composable
private fun PlayPauseKey(pad: PadState) {
    val view = LocalView.current
    Box(
        Modifier
            .size(54.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Bridge.Accent)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                pad.key("playpause")
            },
        contentAlignment = Alignment.Center,
    ) { Icon(BlazeIcons.PlayPause, "Play or pause", tint = Bridge.OnAccent, modifier = Modifier.size(26.dp)) }
}

/** An arrow key over the phone keyboard: sent once on a tap, again and again while held. */
@Composable
private fun ArrowKey(pad: PadState, key: String, modifier: Modifier) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var held by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(40.dp)
            .clip(ButtonShape)
            .background(if (held) Bridge.Accent else Bridge.Surface)
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown()
                    held = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    pad.key(key)
                    val repeat = scope.launch {
                        kotlinx.coroutines.delay(REPEAT_AFTER_MS)
                        while (true) { pad.key(key); kotlinx.coroutines.delay(REPEAT_EVERY_MS) }
                    }
                    do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                    repeat.cancel()
                    held = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            BlazeIcons.Chevron, key,
            tint = if (held) Bridge.OnAccent else Bridge.Text,
            modifier = Modifier.size(22.dp).graphicsLayer {
                rotationZ = when (key) { "left" -> 180f; "up" -> -90f; "down" -> 90f; else -> 0f }
            },
        )
    }
}

/** A key chip showing an icon, for keys a word would not fit on. */
@Composable
private fun IconKeyChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier
            .widthIn(min = 44.dp)
            .height(40.dp)
            .clip(ButtonShape)
            .background(Bridge.Surface)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Bridge.Text, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun KeyChip(label: String, modifier: Modifier = Modifier, on: Boolean = false, onClick: () -> Unit) {
    val view = LocalView.current
    Box(
        modifier
            .widthIn(min = 44.dp)
            .height(40.dp)
            .clip(ButtonShape)
            .background(if (on) Bridge.Accent else Bridge.Surface)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = LabelStyle, color = if (on) Bridge.OnAccent else Bridge.Text)
    }
}

/**
 * An invisible view the phone's keyboard types into. It keeps no text of its own: every
 * character is passed straight on, and suggestions are turned off so each key arrives as
 * it is pressed rather than as a word the keyboard rewrites later.
 */
internal class KeyCatcher(
    ctx: Context,
    private val onText: (String) -> Unit,
    private val onKey: (String) -> Unit,
) : View(ctx) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(out: EditorInfo): InputConnection {
        out.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            /** Text the keyboard is still composing, already sent to the laptop. */
            private var composing = ""

            private fun replaceComposing(next: String) {
                val common = composing.commonPrefixWith(next).length
                repeat(composing.length - common) { onKey("back") }
                if (next.length > common) onText(next.substring(common))
                composing = next
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                replaceComposing(text?.toString().orEmpty())
                composing = ""
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                replaceComposing(text?.toString().orEmpty())
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { onKey("back") }
                repeat(afterLength) { onKey("del") }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                onKey("enter")
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> onKey("back")
                    KeyEvent.KEYCODE_FORWARD_DEL -> onKey("del")
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> onKey("enter")
                    KeyEvent.KEYCODE_TAB -> onKey("tab")
                    KeyEvent.KEYCODE_ESCAPE -> onKey("esc")
                    KeyEvent.KEYCODE_DPAD_LEFT -> onKey("left")
                    KeyEvent.KEYCODE_DPAD_RIGHT -> onKey("right")
                    KeyEvent.KEYCODE_DPAD_UP -> onKey("up")
                    KeyEvent.KEYCODE_DPAD_DOWN -> onKey("down")
                    else -> {
                        val ch = event.unicodeChar
                        if (ch != 0) onText(String(Character.toChars(ch)))
                    }
                }
                return true
            }
        }
    }
}

private const val DOUBLE_TAP_MS = 300L
private const val HOLD_MS = 450L
/** Laptop pixels per phone pixel at "Normal", before acceleration. */
private const val POINTER_K = 0.85f
private const val ACCEL = 0.55f
/** Wheel units (120 = one notch) per phone pixel of two-finger travel. */
private const val WHEEL_K = 1.6f
private const val PINCH_STEP = 0.16f
private const val FLING_MIN = 0.6f
private const val FLING_STOP = 0.02f
private const val FLING_TAU = 325f
