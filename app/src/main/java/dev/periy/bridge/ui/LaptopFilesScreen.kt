package dev.periy.bridge.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.periy.bridge.server.FsEntry
import dev.periy.bridge.server.LaptopEntry
import dev.periy.bridge.server.LaptopFiles
import dev.periy.bridge.server.LaptopListing
import dev.periy.bridge.server.PhoneFiles
import dev.periy.bridge.service.formatBytes
import kotlinx.coroutines.launch

/**
 * The laptop's files, from the phone, wherever the two are, drawn as a linked phone's are: its
 * usual folders and drives at the top, then any folder; a file saves with a tap, a folder as one
 * zip with Save folder. Read only; asked of the laptop's helper over the way it already reaches the phone.
 */
@Composable
fun LaptopFilesScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Where the folders opened go: the top first.
    val trail = remember { mutableStateListOf("" to "") }
    val path = trail.last().first
    val seen = remember { mutableStateMapOf<String, LaptopListing>() }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(path, reload) { seen[path] = LaptopFiles.list(path) }
    val listing = seen[path]
    val laptop = seen[""]?.laptop?.ifEmpty { null } ?: "The laptop"
    val up = { if (trail.size > 1) trail.removeAt(trail.lastIndex) else onClose() }
    BackHandler(enabled = trail.size > 1) { up() }
    // A file as it is, a folder as one zip of everything in it; it lands with what this phone has received.
    val save = { target: String, name: String, dir: Boolean ->
        Toast.makeText(ctx, "Saving $name", Toast.LENGTH_SHORT).show()
        scope.launch {
            LaptopFiles.save(target)
                .onSuccess { Toast.makeText(ctx, "Saved $it", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, it.message ?: "Could not save it", Toast.LENGTH_LONG).show() }
        }
    }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().background(Bridge.Bg).padding(top = top)) {
        // The bar: back (up a folder, or out), where that goes, and the folder's own Save.
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable { up() }, contentAlignment = Alignment.Center) {
                Icon(BlazeIcons.Chevron, "Back", tint = Bridge.Accent, modifier = Modifier.size(24.dp).rotate(180f))
            }
            Text(
                when (trail.size) { 1 -> "Devices"; 2 -> laptop; else -> trail[trail.lastIndex - 1].second },
                style = TextStyle(fontSize = 17.sp), color = Bridge.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable { up() },
            )
            if (path.isNotEmpty() && listing != null && listing.error.isEmpty() && listing.entries.isNotEmpty()) {
                Text(
                    "Save folder", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Accent,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { save(path, trail.last().second, true) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        // The large title: the folder, or the laptop at the top.
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp)) {
            Text(
                if (path.isEmpty()) laptop else trail.last().second,
                style = LargeTitleStyle, color = Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (path.isEmpty()) "Folders and drives" else path,
                style = CaptionStyle, color = Bridge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }

        AnimatedContent(
            targetState = trail.size to path,
            transitionSpec = {
                val deeper = targetState.first > initialState.first
                (slideInHorizontally(tween(260)) { if (deeper) it / 4 else -it / 4 } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally(tween(220)) { if (deeper) -it / 6 else it / 6 } + fadeOut(tween(160)))
            },
            label = "folder",
            modifier = Modifier.weight(1f),
        ) { (_, shownPath) ->
            val l = seen[shownPath]
            when {
                l == null -> Note("Asking the laptop...")
                l.error.isNotEmpty() -> Note(l.error, "Try again") { seen.remove(shownPath); reload++ }
                l.entries.isEmpty() -> Note("This folder is empty.")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 24.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().panel()) {
                            l.entries.forEachIndexed { i, e ->
                                // A drive says how big it is; it opens like any folder.
                                val drive = e.dir && shownPath.isEmpty() && e.size > 0
                                FileEntryRow(
                                    e.asFsEntry(), first = i == 0, pic = null,
                                    caption = if (drive) "Drive · " + formatBytes(e.size) else null,
                                    onOpen = { trail.add(e.path to e.name) },
                                    onSave = { save(e.path, e.name, false) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LaptopEntry.asFsEntry() =
    FsEntry(name, dir, size, modified, if (dir) "" else PhoneFiles.mimeOf(name), items)
