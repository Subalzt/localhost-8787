package dev.periy.bridge.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.periy.bridge.container
import dev.periy.bridge.music.Alarms
import kotlinx.coroutines.delay

/**
 * The alarm ringing: opened by Android's alarm clock over the lock screen (the phone stays
 * locked), the screen turned on. The time, the song, and Snooze, Stop or Keep listening (the
 * same song carries on in the player). Black, quiet, readable from the pillow.
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val c = container
        val id = intent.getIntExtra(Alarms.EXTRA_ID, 0)
        val snooze = intent.getBooleanExtra(Alarms.EXTRA_SNOOZE, false)
        // A fresh start rings; brought back (recents, rotation) it just shows.
        if (savedInstanceState == null && !intent.getBooleanExtra(EXTRA_SHOW_ONLY, false)) {
            if (intent.hasExtra(EXTRA_SILENT)) c.ringer.silent = intent.getBooleanExtra(EXTRA_SILENT, false)
            c.ringer.ring(c.alarms.get(id))
            // Tried from the editor: it has not really rung, so a one-off alarm stays on.
            if (!intent.getBooleanExtra(EXTRA_TRY, false)) c.alarms.rang(id, snooze)
            Log.i("AlarmActivity", "rang alarm $id (snooze $snooze)")
        }
        setContent {
            AlarmFace(
                onSnooze = { c.ringer.stop(); c.alarms.snooze(id); finish() },
                onStop = { c.ringer.stop(); finish() },
                onKeep = { c.ringer.keepListening(); finish() },
                onQuiet = { finish() },
            )
        }
    }

    companion object {
        const val EXTRA_SILENT = "alarmSilent"
        const val EXTRA_SHOW_ONLY = "alarmShowOnly"
        const val EXTRA_TRY = "alarmTry"
    }
}

@Composable
private fun AlarmFace(onSnooze: () -> Unit, onStop: () -> Unit, onKeep: () -> Unit, onQuiet: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val song by ctx.container.ringer.ringing.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    // Stopped elsewhere (it gave up, or the debug hook): nothing left to show.
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(song) { if (song != null) seen = true else if (seen) onQuiet() }
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(now))
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text(time, style = TextStyle(fontSize = 84.sp, fontWeight = FontWeight.Light, letterSpacing = (-3).sp, fontFeatureSettings = "tnum"), color = Color.White)
        Spacer(Modifier.height(12.dp))
        val s = song
        Text(s?.title ?: "Alarm", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold), color = Color.White.copy(alpha = 0.9f),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (s != null) Text(s.artist, style = TextStyle(fontSize = 15.sp), color = Color.White.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1.2f))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AlarmButton("Snooze ${Alarms.SNOOZE_MIN} min", Color.White.copy(alpha = 0.14f), Color.White, onSnooze)
            AlarmButton("Stop", Color(0xFFFF9F0A), Color.Black, onStop)
            if (s != null) Text(
                "Keep listening", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(50)).clickable(onClick = onKeep).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AlarmButton(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold), color = fg) }
}

/** After a reboot, an update or a clock change, Android forgets alarms: the next one is set again. */
class AlarmBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.container.alarms.arm()
    }
}
