package dev.periy.bridge.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.periy.bridge.container
import dev.periy.bridge.server.FindCmd

/**
 * Lost mode, over the lock screen (server/FindMe.kt): the owner's message and a number to call, as
 * whoever finds the phone sees it when the screen comes on. Whoever has it may stop the ringing;
 * lost mode itself ends only from the owner's own devices, on the map.
 */
class LostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val find = container.findMe
        setContent {
            val lost by find.lost.collectAsStateWithLifecycle()
            val ringing by find.ringing.collectAsStateWithLifecycle()
            if (!lost.on && !ringing) { finish(); return@setContent }
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(if (lost.on) "This phone is lost" else "Finding this phone", style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold), color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        lost.message.ifEmpty { if (lost.on) "Please call its owner." else "Its owner is looking for it." },
                        style = TextStyle(fontSize = 18.sp), color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center,
                    )
                    if (lost.contact.isNotEmpty()) {
                        Spacer(Modifier.height(36.dp))
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF34C759))
                                .clickable { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(lost.contact)))) }
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("Call ${lost.contact}", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), color = Color.White) }
                    }
                    if (ringing) {
                        Spacer(Modifier.height(14.dp))
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.14f))
                                .clickable { find.handle(FindCmd("stop")) }
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("Stop the sound", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium), color = Color.White) }
                    }
                }
            }
        }
    }
}
