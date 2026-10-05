package dev.periy.bridge.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.periy.bridge.container
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Camera mode asked for from another device while this phone sat locked: shown over the lock screen
 * for a moment, so the app is in front, which is when Android lets it take the camera; it starts
 * CameraService and goes as soon as the camera runs. The phone stays locked.
 */
class CamWakeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val cams = container.cameras
        cams.woke()
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text("Turning the camera on…", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
            }
        }
        // Started once this is on screen: Android counts the app as in front from then.
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { startForegroundService(Intent(this, dev.periy.bridge.service.CameraService::class.java)) }
        }, 300)
        lifecycleScope.launch {
            withTimeoutOrNull(8_000) { cams.state.first { it.running || !it.on || it.error.isNotEmpty() } }
            finish()
        }
    }
}
