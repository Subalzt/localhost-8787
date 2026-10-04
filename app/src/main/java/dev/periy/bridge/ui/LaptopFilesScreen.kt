package dev.periy.bridge.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.periy.bridge.server.LaptopFiles
import dev.periy.bridge.server.LaptopListing
import dev.periy.bridge.service.formatBytes
import kotlinx.coroutines.launch

/**
 * The laptop's files, from the phone, wherever the two are: its usual folders and drives at the
 * top, then any folder; a tap on a file saves it with what this phone has received. Read only;
 * asked of the laptop's helper over the way it already reaches the phone.
 */
@Composable
fun LaptopFilesScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Where the folders opened go: the top first.
    val trail = remember { mutableStateListOf("" to "Files on the laptop") }
    val path = trail.last().first
    val seen = remember { mutableStateMapOf<String, LaptopListing>() }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(path, reload) { seen[path] = LaptopFiles.list(path) }
    val listing = seen[path]
    BackHandler { if (trail.size > 1) trail.removeAt(trail.lastIndex) else onClose() }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().background(Bridge.Bg).padding(top = top)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { if (trail.size > 1) trail.removeAt(trail.lastIndex) else onClose() }, contentAlignment = Alignment.Center) {
                Icon(BlazeIcons.Chevron, "Back", tint = Bridge.Text, modifier = Modifier.size(24.dp).rotate(180f))
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(if (trail.size > 1) trail.last().second else (listing?.laptop?.ifEmpty { null } ?: "The laptop"), style = TitleStyle, color = Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (path.isEmpty()) "Folders and drives" else path, style = CaptionStyle, color = Bridge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            listing == null -> Text("Asking the laptop…", style = BodyStyle, color = Bridge.Muted, modifier = Modifier.padding(24.dp))
            listing.error.isNotEmpty() -> Column(Modifier.padding(24.dp)) {
                Text(listing.error, style = BodyStyle, color = Bridge.Danger)
                SoftButton("Try again") { seen.remove(path); reload++ }
            }
            listing.entries.isEmpty() -> Text("Nothing here", style = BodyStyle, color = Bridge.Muted, modifier = Modifier.padding(24.dp))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottom)) {
                item {
                    GroupCard {
                        listing.entries.forEachIndexed { i, e ->
                            MediaRow(
                                e.name,
                                when {
                                    e.dir && path.isEmpty() && e.size > 0 -> formatBytes(e.size)
                                    e.dir -> "Folder"
                                    else -> formatBytes(e.size)
                                },
                                icon = if (e.dir) BlazeIcons.Folder else BlazeIcons.File,
                                color = if (e.dir) Color(0xFF0A84FF) else Color(0xFF8E8E93),
                                first = i == 0,
                                onClick = {
                                    if (e.dir) trail.add(e.path to e.name)
                                    else {
                                        Toast.makeText(ctx, "Saving ${e.name}", Toast.LENGTH_SHORT).show()
                                        scope.launch {
                                            LaptopFiles.save(e.path)
                                                .onSuccess { Toast.makeText(ctx, "Saved $it", Toast.LENGTH_SHORT).show() }
                                                .onFailure { Toast.makeText(ctx, it.message ?: "Could not save it", Toast.LENGTH_LONG).show() }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
