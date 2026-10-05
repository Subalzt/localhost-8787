package dev.periy.bridge.ui

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.periy.bridge.container
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Where your phones and laptops are, on a map (server/Where.kt): the map page the laptop's page
 * shows too (assets/map.html), from this phone's own server, handed the places as they change. Asks
 * for location first if it is not allowed, and for all the time, so the phone is found with the
 * app closed.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val where = ctx.container.where
    val self by where.selfTrail.collectAsStateWithLifecycle()
    val others by where.others.collectAsStateWithLifecycle()
    val zones by where.zones.collectAsStateWithLifecycle()
    var asked by remember { mutableIntStateOf(0) }
    val allowed = remember(asked) { where.allowed() }
    val always = remember(asked) { where.allowedAlways() }
    var view by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    BackHandler { onClose() }

    // Allowed: listening starts, and the app's service takes on location so it keeps going in the background.
    val granted = {
        asked++
        if (where.allowed()) {
            where.start()
            runCatching { dev.periy.bridge.service.BridgeService.start(ctx) }
        }
    }
    val askAlways = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted() }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { got ->
        granted()
        // Android asks for all the time on its own, after while using the app.
        if (got.values.any { it } && Build.VERSION.SDK_INT >= 29 && !where.allowedAlways()) askAlways.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    val json = remember { Json { encodeDefaults = true } }
    LaunchedEffect(self, others, zones, ready, asked) {
        val v = view ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val places = json.encodeToString(where.places())
        v.evaluateJavascript("setZones(" + json.encodeToString(where.zones.value) + ")", null)
        v.evaluateJavascript("setPlaces($places, $allowed, $always)", null)
    }

    Box(Modifier.fillMaxSize().background(Bridge.Bg)) {
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    // Filling the screen, said outright: a WebView left to wrap its content lays the
                    // page out as tall as its content, and a map 100% tall is then nothing at all.
                    layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // The map's Play sound and Lost mode (server/FindMe.kt), for this phone or a linked one.
                    addJavascriptInterface(object {
                        @android.webkit.JavascriptInterface
                        fun find(cmd: String) {
                            val c = runCatching { json.decodeFromString<dev.periy.bridge.server.FindCmd>(cmd) }.getOrNull() ?: return
                            Thread {
                                val why = where.find(c) { ctx.container.findMe.handle(it) }
                                post { evaluateJavascript("findSaid(" + org.json.JSONObject.quote(why ?: "") + ")", null) }
                            }.start()
                        }
                        @android.webkit.JavascriptInterface
                        fun zone(z: String) {
                            runCatching { json.decodeFromString<dev.periy.bridge.server.Zone>(z) }.getOrNull()?.let {
                                where.setZone(it.copy(id = it.id.ifEmpty { java.util.UUID.randomUUID().toString().take(12) }))
                            }
                        }

                        @android.webkit.JavascriptInterface
                        fun unzone(id: String) = where.deleteZone(id)
                    }, "Phone")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(w: WebView?, url: String?) { ready = true; asked++ }
                    }
                    loadUrl("http://127.0.0.1:${ctx.container.prefs.port}/map.html")
                    view = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Column(Modifier.padding(top = top + 10.dp, start = 12.dp, end = 70.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Bridge.Surface).clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { Icon(BlazeIcons.Back, "Back", tint = Bridge.Text, modifier = Modifier.size(20.dp)) }
            if (!allowed || !always) {
                Spacer(Modifier.padding(top = 10.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Bridge.Blue)
                        .clickable {
                            if (!allowed) askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            else if (Build.VERSION.SDK_INT >= 29) askAlways.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (!allowed) "Allow location" else "Allow all the time", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
                        Text(
                            if (!allowed) "To show this phone, precisely, on the map." else "So this phone is found with the app closed: choose Allow all the time.",
                            style = TextStyle(fontSize = 12.5.sp), color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}
