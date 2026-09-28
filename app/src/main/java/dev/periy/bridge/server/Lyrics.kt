package dev.periy.bridge.server

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A song's lyrics as a page found them, kept on the phone: synced lines (LRC), word-level timing
 * (LRCLIB's Lyricsfile), or plain text. [source] names where they came from; "none" records that
 * nothing was found, so a page does not look again at every play (it looks again after a while).
 */
@Serializable
data class LyricsDoc(
    val source: String = "",
    val lrc: String = "",
    val lyricsfile: String = "",
    val plain: String = "",
    val instrumental: Boolean = false,
    val at: Long = 0,
    /** The .lrc to keep beside the song, as the page wrote it (word timing as Enhanced LRC). */
    val sidecar: String = "",
)

@Serializable
data class LyricsDto(val found: Boolean, val lyrics: LyricsDoc? = null)

/**
 * Lyrics, found by a page with internet and kept here for every page after it, online or not.
 * The phone itself looks nothing up: lyrics come from the browser, which has the internet the
 * phone may not (a laptop on campus Ethernet, the phone on its own hotspot).
 *
 * Kept twice. As a .lrc beside the song in the music folder, the way music players look for them
 * (and the way LRCGET saves them), so any player on the phone shows them too; a .lrc that is
 * already there, from anywhere, is used first and never written over. And in the app's own files
 * as found (word timing included), named by the track and its size, so a song replaced by another
 * file is looked up afresh. Without All files access, only the second.
 */
class LyricsStore(ctx: Context, private val songFile: (TrackDto) -> File?) {

    private val app = ctx.applicationContext
    private val dir = File(app.filesDir, "lyrics").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(t: TrackDto) = File(dir, "${t.id}-${t.size}.json")

    /** What is known for [t]: as found online, else a .lrc beside the song, else that nothing was found. */
    fun get(t: TrackDto): LyricsDoc? {
        val own = runCatching { file(t).takeIf { it.isFile }?.let { json.decodeFromString<LyricsDoc>(it.readText()) } }.getOrNull()
        val side = sidecar(t)
        if (own != null && own.source != "none") {
            // Found before the .lrc was kept beside the song: it is now.
            if (side != null && !side.exists()) writeSidecar(side, own)
            return own
        }
        if (side != null && side.isFile) {
            val text = runCatching { side.readText() }.getOrNull()
            if (!text.isNullOrBlank()) {
                val timed = TIMED.containsMatchIn(text)
                return LyricsDoc(source = "file", lrc = if (timed) text else "", plain = if (timed) "" else text)
            }
        }
        return own
    }

    fun put(t: TrackDto, doc: LyricsDoc) {
        val f = file(t)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(json.encodeToString(LyricsDoc.serializer(), doc.copy(at = System.currentTimeMillis())))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        if (doc.source != "none" && doc.source != "file") sidecar(t)?.let { if (!it.exists()) writeSidecar(it, doc) }
    }

    /** "Song.flac" keeps its lyrics in "Song.lrc", beside it. */
    private fun sidecar(t: TrackDto): File? = songFile(t)?.let { File(it.parentFile, it.nameWithoutExtension + ".lrc") }

    private fun writeSidecar(f: File, doc: LyricsDoc) {
        val text = doc.sidecar.ifBlank { doc.lrc.ifBlank { doc.plain } }
        if (text.isBlank()) return
        runCatching {
            f.writeText(text)
            // So a computer browsing the phone over USB sees it at once.
            android.media.MediaScannerConnection.scanFile(app, arrayOf(f.path), null, null)
        }
    }

    companion object {
        /** A song's lyrics, whatever their form, are well under this. */
        const val MAX_BYTES = 1024 * 1024
        /** A line with a time on it, "[01:02.30]", as timed lyrics have. */
        private val TIMED = Regex("""^\s*\[\d+:\d+""", RegexOption.MULTILINE)
    }
}
