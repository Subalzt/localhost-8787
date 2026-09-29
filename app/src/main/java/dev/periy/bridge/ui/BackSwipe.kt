package dev.periy.bridge.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * A back swipe as Android shows it coming (Android 14 on): [progress] follows the finger from 0
 * towards 1 while it is down. Let go and the rest of the way plays out quickly, then back is
 * carried out; swipe back out before letting go and it all eases back to 0, the page as it was.
 * A back with no swipe to follow (the back button, or Android before 14) is carried out at once.
 */
@Stable
class BackSwipe {
    val progress = Animatable(0f)
}

/** How quickly a let-go swipe finishes, and how a cancelled one settles back. */
private const val FINISH_MS = 200
private const val SETTLE_MS = 260

/** The finish: quick off the mark and settling at the end, as the system's own back does. */
private val Finish = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Takes back while [enabled], for the surface that follows the returned swipe. [onBack] is told
 * whether a swipe carried the surface all the way out (so it needs no leaving animation of its
 * own) or not (a plain back, which it should animate as it always has).
 */
@Composable
fun rememberBackSwipe(enabled: Boolean = true, onBack: (swiped: Boolean) -> Unit): BackSwipe {
    val swipe = remember { BackSwipe() }
    val scope = rememberCoroutineScope()
    val back = rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { swipe.progress.snapTo(it.progress) }
            val swiped = swipe.progress.value > 0f
            if (swiped) swipe.progress.animateTo(1f, tween(FINISH_MS, easing = Finish))
            back.value(swiped)
            swipe.progress.snapTo(0f)
        } catch (e: CancellationException) {
            scope.launch { swipe.progress.animateTo(0f, tween(SETTLE_MS, easing = Finish)) }
        }
    }
    return swipe
}

/**
 * The app's back for a page over another (the Material shared-axis step back, as Android's own
 * apps do it): the page slides 30 towards the right and fades, gone by a third of the way; what
 * it covered comes back from 30 on the left, fading in over the rest. [under] is the page below's
 * modifier, [over] the page leaving's.
 */
object SharedAxisBack {
    fun over(swipe: BackSwipe): Modifier = Modifier.graphicsLayer {
        val p = swipe.progress.value
        if (p > 0f) {
            translationX = 30.dp.toPx() * p
            alpha = 1f - (p / 0.35f).coerceIn(0f, 1f)
        }
    }

    fun under(swipe: BackSwipe): Modifier = Modifier.graphicsLayer {
        val p = swipe.progress.value
        if (p > 0f) {
            translationX = -30.dp.toPx() * (1f - p)
            alpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
        }
    }
}
