package dev.periy.bridge.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.periy.bridge.container
import kotlinx.coroutines.delay

/** Whether this phone may record sound for a call. */
fun hasMic(ctx: android.content.Context) =
    ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * A call, full screen over the app, always black: who, where it stands (calling, ringing,
 * connecting, the time on it, how the sound goes), and the buttons: Mute, Speaker and End; for
 * one coming in, Decline and Answer.
 */
@Composable
fun CallScreen(answerNow: Boolean, onAnswered: () -> Unit) {
    val ctx = LocalContext.current
    val calls = ctx.container.calls
    val s by calls.state.collectAsState()
    val call = s ?: return
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) calls.answer() else calls.hangUp()
    }
    val answer = { if (hasMic(ctx)) calls.answer() else mic.launch(Manifest.permission.RECORD_AUDIO) }
    // Answer, from the notification: it opened the app to do it.
    LaunchedEffect(answerNow, call.id) { if (answerNow && call.phase == "ringing") { answer(); onAnswered() } }
    BackHandler { }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) { while (call.phase == "active") { now = System.currentTimeMillis(); delay(500) } }
    val status = when (call.phase) {
        "calling" -> if (call.why == "Ringing") "Ringing…" else "Calling…"
        "ringing" -> "Calling you, phone to phone"
        "connecting" -> "Connecting…"
        "active" -> ((now - call.since).coerceAtLeast(0) / 1000).let { "%d:%02d".format(it / 60, it % 60) }
        else -> call.why.ifEmpty { "Call ended" }
    }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(top = top + 72.dp, bottom = bottom + 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF1C1C1E)), contentAlignment = Alignment.Center) {
            Text(call.peer.take(1).uppercase(), style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
        }
        Spacer(Modifier.height(20.dp))
        Text(call.peer, style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold), color = Color.White, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(8.dp))
        Text(status, style = TextStyle(fontSize = 17.sp), color = Color.White.copy(alpha = 0.7f))
        if (call.phase == "active" && call.path.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(call.path + " · sealed", style = TextStyle(fontSize = 13.sp), color = Color.White.copy(alpha = 0.45f))
        }
        Spacer(Modifier.weight(1f))
        if (call.phase == "ringing" && !call.outgoing) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundButton(BlazeIcons.Close, "Decline", Color(0xFFFF3B30)) { calls.hangUp() }
                RoundButton(BlazeIcons.Call, "Answer", Color(0xFF34C759), onClick = answer)
            }
        } else if (call.phase != "ended") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundButton(BlazeIcons.Mute, if (call.muted) "Unmute" else "Mute", if (call.muted) Color.White else Color(0xFF2C2C2E),
                    tint = if (call.muted) Color.Black else Color.White) { calls.setMuted(!call.muted) }
                RoundButton(BlazeIcons.VolumeUp, "Speaker", if (call.speaker) Color.White else Color(0xFF2C2C2E),
                    tint = if (call.speaker) Color.Black else Color.White) { calls.setSpeaker(!call.speaker) }
            }
            Spacer(Modifier.height(36.dp))
            RoundButton(BlazeIcons.Call, "End", Color(0xFFFF3B30), rotate = true) { calls.hangUp() }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, bg: Color, tint: Color = Color.White, rotate: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(76.dp).pressable(CircleShape, scaleTo = 0.9f, onClick = onClick).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(32.dp).then(if (rotate) Modifier.rotate(135f) else Modifier))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = TextStyle(fontSize = 13.sp), color = Color.White.copy(alpha = 0.8f))
    }
}
