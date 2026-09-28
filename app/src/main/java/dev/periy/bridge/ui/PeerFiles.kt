package dev.periy.bridge.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.periy.bridge.container
import dev.periy.bridge.server.FsEntry
import dev.periy.bridge.server.FsListDto
import dev.periy.bridge.server.Peer
import dev.periy.bridge.service.formatBytes

/**
 * Another phone's storage, from this one: the same folders its computers see on their page,
 * walked through as in Files. A file is saved into this phone's folder for received files with
 * a tap; a folder, all of it, as one zip. Photos and videos show their pictures.
 */
@Composable
fun PeerFilesScreen(peer: Peer, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val peers = ctx.container.peers
    var path by remember { mutableStateOf("") }
    // Each folder once seen shows at once when you come back to it, and is looked at again.
    val seen = remember { androidx.compose.runtime.mutableStateMapOf<String, FsListDto>() }
    var failed by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(path, reload) {
        failed = null
        runCatching { peers.list(peer, path) }
            .onSuccess { seen[path] = it }
            .onFailure { failed = "Could not reach ${peer.name}. Is Localhost 8787 on there, on this Wi-Fi?" }
    }
    val listing = seen[path]
    val up = { path = path.substringBeforeLast('/', "") }
    BackHandler { if (path.isEmpty()) onClose() else up() }

    val save = { e: FsEntry ->
        val at = if (path.isEmpty()) e.name else "$path/${e.name}"
        Toast.makeText(ctx, "Saving ${e.name}", Toast.LENGTH_SHORT).show()
        peers.save(peer, at, e.name, e.size, folder = e.dir) { err ->
            Toast.makeText(ctx, err ?: "Saved ${e.name}${if (e.dir) ".zip" else ""}", if (err == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
    }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().background(Bridge.Bg).padding(top = top)) {
        // The bar: back (up a folder, or out), where this is, and the folder's own Save.
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable { if (path.isEmpty()) onClose() else up() },
                contentAlignment = Alignment.Center,
            ) { Icon(BlazeIcons.Chevron, "Back", tint = Bridge.Accent, modifier = Modifier.size(24.dp).rotate(180f)) }
            Text(
                if (path.isEmpty()) "Devices" else path.substringBeforeLast('/', "").substringAfterLast('/').ifEmpty { peer.name },
                style = TextStyle(fontSize = 17.sp), color = Bridge.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable { if (path.isEmpty()) onClose() else up() },
            )
            val l = listing
            if (path.isNotEmpty() && l != null && l.granted && l.entries.isNotEmpty()) {
                Text(
                    "Save folder", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = Bridge.Accent,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                        save(FsEntry(path.substringAfterLast('/'), dir = true, size = 0, modified = 0))
                    }.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        // The large title: the folder, or the phone at the top.
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp)) {
            Text(
                if (path.isEmpty()) peer.name else path.substringAfterLast('/'),
                style = LargeTitleStyle, color = Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (path.isEmpty()) "Internal storage" else "Internal storage / " + path.replace("/", " / "),
                style = CaptionStyle, color = Bridge.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }

        AnimatedContent(
            targetState = path,
            transitionSpec = {
                val deeper = targetState.length > initialState.length
                (slideInHorizontally(tween(260)) { if (deeper) it / 4 else -it / 4 } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally(tween(220)) { if (deeper) -it / 6 else it / 6 } + fadeOut(tween(160)))
            },
            label = "folder",
            modifier = Modifier.weight(1f),
        ) { shownPath ->
            val l = seen[shownPath]
            when {
                l == null && failed != null && shownPath == path -> Note(failed!!, "Try again") { reload++ }
                l == null -> Note("Loading...")
                !l.granted -> Note("${peer.name} does not let anyone look through its files yet. On ${peer.name}: Settings, Laptop access, Browse this phone.")
                l.entries.isEmpty() -> Note(l.message ?: "This folder is empty.")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 24.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().panel()) {
                            l.entries.forEachIndexed { i, e ->
                                EntryRow(peer, shownPath, e, first = i == 0,
                                    onOpen = { path = if (shownPath.isEmpty()) e.name else "$shownPath/${e.name}" },
                                    onSave = { save(e) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = BodyStyle.copy(fontSize = 15.sp), color = Bridge.Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            SoftButton(action, onClick = onAction)
        }
    }
}

/** One row: a folder opens; a file saves with a tap on it or on its download button. */
@Composable
private fun EntryRow(peer: Peer, dir: String, e: FsEntry, first: Boolean, onOpen: () -> Unit, onSave: () -> Unit) {
    val thumb = 44.dp
    if (!first) Box(Modifier.fillMaxWidth().padding(start = 16.dp + thumb + 12.dp).height(0.5.dp).background(Bridge.Outline))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = if (e.dir) onOpen else onSave).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(thumb), contentAlignment = Alignment.Center) {
            val media = !e.dir && (e.mime.startsWith("image/") || e.mime.startsWith("video/"))
            val pic = if (media) peerThumb(peer, if (dir.isEmpty()) e.name else "$dir/${e.name}") else null
            if (pic != null) Image(pic, null, contentScale = ContentScale.Crop, modifier = Modifier.size(thumb).clip(RoundedCornerShape(9.dp)))
            else {
                val (icon, color) = kindOf(e)
                AppIcon(icon, color, size = if (e.dir) 40.dp else 36.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = TextStyle(fontSize = 16.sp, letterSpacing = (-0.2).sp), color = Bridge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (e.dir) (if (e.items < 0) "Folder" else "${e.items} item${if (e.items == 1) "" else "s"}") else formatBytes(e.size)) +
                    " · " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(e.modified)),
                style = CaptionStyle, color = Bridge.Muted, maxLines = 1,
            )
        }
        if (e.dir) Icon(BlazeIcons.Chevron, null, tint = Bridge.Faint, modifier = Modifier.size(18.dp))
        else IconChip(BlazeIcons.Download, "Save ${e.name} on this phone", tint = Bridge.Accent, size = 34.dp, onClick = onSave)
    }
}

/** A photo's or a video's small picture from the other phone, or null while it comes (or if there is none). */
@Composable
private fun peerThumb(peer: Peer, path: String): ImageBitmap? {
    val peers = LocalContext.current.container.peers
    val bmp by produceState<ImageBitmap?>(null, peer.name, path) {
        value = peers.thumb(peer, path)?.let { b -> runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() }
    }
    return bmp
}

/** What a row's icon says: a folder, a picture, a film, music, a document, an archive, or a file. */
@Composable
private fun kindOf(e: FsEntry): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> {
    if (e.dir) return BlazeIcons.Folder to Bridge.Blue
    val ext = e.name.substringAfterLast('.', "").lowercase()
    return when {
        e.mime.startsWith("image/") -> BlazeIcons.Image to Bridge.Purple
        e.mime.startsWith("video/") -> BlazeIcons.Play to Bridge.Danger
        e.mime.startsWith("audio/") -> BlazeIcons.VolumeUp to Bridge.Pink
        ext in setOf("zip", "rar", "7z", "tar", "gz") -> BlazeIcons.Folder to Bridge.Orange
        ext == "apk" -> BlazeIcons.Download to Bridge.Good
        else -> BlazeIcons.File to Bridge.Indigo
    }
}
