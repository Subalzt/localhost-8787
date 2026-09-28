package dev.periy.bridge.server

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Environment
import android.util.Size
import android.webkit.MimeTypeMap
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One row of a folder listing. */
@Serializable
data class FsEntry(
    val name: String,
    val dir: Boolean,
    val size: Long,
    val modified: Long,
    val mime: String = "",
    /** Items inside, for a folder; -1 when it could not be read. */
    val items: Int = -1,
    /** How the page can show it before downloading; see [PhoneFiles.previewOf]. */
    val preview: String = "",
)

/** A document's words for the page's preview; [found] is false when none could be read. */
@Serializable
data class DocTextDto(val found: Boolean, val text: String = "")

@Serializable
data class FsListDto(
    /** Whether the phone's owner has allowed browsing (Android's "All files access"). */
    val granted: Boolean,
    val path: String = "",
    val entries: List<FsEntry> = emptyList(),
    val message: String? = null,
)

/**
 * The phone's shared storage, read-only, for the laptop to browse: the folder with DCIM,
 * Download, Documents and the rest.
 *
 * Nothing is reachable until the owner turns on Android's "All files access" for Localhost 8787,
 * and every path is resolved against the storage root and refused if it would climb out.
 * Nothing here writes, renames or deletes.
 */
class PhoneFiles {

    val root: File = Environment.getExternalStorageDirectory()
    private val rootPath: String = root.canonicalPath

    fun granted(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** The file for a path relative to the storage root, or null if it is outside it. */
    fun resolve(rel: String?): File? {
        val clean = rel.orEmpty().replace('\\', '/').trim('/')
        val f = runCatching { File(root, clean).canonicalFile }.getOrNull() ?: return null
        return if (f.path == rootPath || f.path.startsWith("$rootPath/")) f else null
    }

    fun relative(f: File): String = f.canonicalPath.removePrefix(rootPath).trimStart('/')

    fun list(rel: String?): FsListDto {
        if (!granted()) return FsListDto(false, message = "Allow browsing on the phone: Settings, Laptop access")
        val dir = resolve(rel) ?: return FsListDto(true, message = "That folder is outside the phone's storage")
        if (!dir.isDirectory) return FsListDto(true, relative(dir), message = "Not a folder")
        val kids = dir.listFiles() ?: return FsListDto(true, relative(dir), message = "Android does not let apps read this folder")
        val entries = kids
            .filter { !it.name.startsWith(".") }
            .map { f ->
                if (f.isDirectory) FsEntry(f.name, true, 0, f.lastModified(), items = f.list()?.count { !it.startsWith(".") } ?: -1)
                else FsEntry(f.name, false, f.length(), f.lastModified(), mimeOf(f.name), preview = previewOf(f.name))
            }
            .sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
        return FsListDto(true, relative(dir), entries)
    }

    /** A JPEG of a photo or a video's frame, at most [px] on its long side; null for anything else. */
    fun thumbnail(f: File, px: Int, quality: Int = 80): ByteArray? {
        val mime = mimeOf(f.name)
        val bmp: Bitmap = runCatching {
            when {
                mime.startsWith("image/") -> ThumbnailUtils.createImageThumbnail(f, Size(px, px), null)
                mime.startsWith("video/") -> videoFrame(f, px) ?: ThumbnailUtils.createVideoThumbnail(f, Size(px, px), null)
                else -> null
            }
        }.getOrNull() ?: return null
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    /**
     * A frame a tenth of the way into a video. The first frame, which the thumbnail helper
     * takes, is black for most films and many clips (they fade in), and a row of black squares
     * tells nobody anything.
     */
    private fun videoFrame(f: File, px: Int): Bitmap? {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            r.getScaledFrameAtTime(ms * 1000 / 10, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, px, px)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * A photo the browser cannot decode (HEIC, DNG...) as a JPEG of at most [px] on its long
     * side, for the page's preview. Decoded in full and scaled, turned the right way up: the
     * thumbnail helper would hand back the small preview embedded in the file (320 x 240 for a
     * phone's HEIC), which is fine for a list and useless for looking at.
     */
    fun viewImage(f: File, px: Int): ByteArray? {
        val bmp = runCatching {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(f)) { dec, info, _ ->
                val w = info.size.width; val h = info.size.height
                val scale = minOf(1.0, px.toDouble() / maxOf(w, h, 1))
                dec.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return thumbnail(f, px, quality = 90)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    /**
     * The words of a Word, PowerPoint, Excel or OpenDocument file, for a preview in the page:
     * browsers cannot show those formats, but each is a zip of XML with the text inside. Null
     * for anything else, or when there is no text. Reads at most a few megabytes of XML, so an
     * odd or hostile file cannot tie up the phone.
     */
    fun documentText(f: File, maxChars: Int = 300_000): String? = runCatching {
        java.util.zip.ZipFile(f).use { z ->
            val slide = Regex("""ppt/slides/slide(\d+)\.xml""")
            val parts: List<Pair<String, String?>> = when (f.extension.lowercase()) {
                "docx" -> listOf("word/document.xml" to null)
                "xlsx" -> listOf("xl/sharedStrings.xml" to null)
                "odt", "odp", "ods" -> listOf("content.xml" to null)
                "pptx" -> z.entries().asSequence().mapNotNull { e -> slide.matchEntire(e.name)?.let { e.name to it.groupValues[1] } }
                    .sortedBy { it.second.toInt() }.map { it.first to "Slide ${it.second}" }.toList()
                else -> return null
            }
            val out = StringBuilder()
            for ((name, heading) in parts) {
                val e = z.getEntry(name) ?: continue
                val xml = z.getInputStream(e).bufferedReader().use { r ->
                    val buf = CharArray(XML_LIMIT); var n = 0
                    while (n < buf.size) { val k = r.read(buf, n, buf.size - n); if (k < 0) break; n += k }
                    String(buf, 0, n)
                }
                if (heading != null) out.append("── ").append(heading).append(" ──\n")
                out.append(xmlText(xml).trim()).append("\n\n")
                if (out.length >= maxChars) break
            }
            out.toString().take(maxChars).trim().ifEmpty { null }
        }
    }.getOrNull()

    /** Plain text from document XML: paragraphs and rows become lines, tags go, entities are read. */
    private fun xmlText(xml: String): String = xml
        .replace(Regex("""</(w:p|a:p|text:p|text:h|si|table:table-row)>"""), "\n")
        .replace(Regex("""<(w:tab|text:tab)\b[^>]*/>"""), "\t")
        .replace(Regex("""<(w:br|a:br|text:line-break)\b[^>]*/>"""), "\n")
        .replace(Regex("""<[^>]+>"""), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("""&#(x?)([0-9a-fA-F]+);""")) { m ->
            runCatching { String(Character.toChars(m.groupValues[2].toInt(if (m.groupValues[1].isEmpty()) 10 else 16))) }
                .getOrDefault("")
        }
        .replace("&amp;", "&")
        .replace(Regex("""\n{3,}"""), "\n\n")

    /**
     * Writes [dir] as a zip, stored rather than compressed: photos and videos are already
     * compressed, and packing them again would only cost the phone's CPU and slow the link.
     */
    fun zip(dir: File, out: OutputStream) {
        val base = dir.parentFile ?: dir
        ZipOutputStream(out.buffered(DOWNLOAD_BUFFER)).use { z ->
            z.setLevel(Deflater.NO_COMPRESSION)
            val buf = ByteArray(DOWNLOAD_BUFFER)
            dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.forEach { f ->
                val name = f.canonicalPath.removePrefix(base.canonicalPath).trimStart('/')
                z.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                f.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        z.write(buf, 0, n)
                        Monitor.addOut(n)
                    }
                }
                z.closeEntry()
            }
        }
    }

    companion object {
        /** XML read per document part for a text preview: 4 MB of characters. */
        private const val XML_LIMIT = 4 * 1024 * 1024

        fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"

        private val TEXT_EXT = setOf(
            "txt", "md", "log", "csv", "tsv", "json", "xml", "yml", "yaml", "ini", "conf", "cfg", "toml",
            "srt", "vtt", "html", "htm", "css", "js", "ts", "kt", "java", "py", "sh", "bat", "ps1", "c", "h", "cpp", "go", "rs",
        )
        private val BROWSER_IMAGES = setOf(
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/avif", "image/svg+xml",
            "image/x-icon", "image/vnd.microsoft.icon",
        )

        /**
         * How the page can show a file before it is downloaded: "image" (the browser shows it),
         * "photo" (a photo the browser cannot decode, such as HEIC or DNG: the phone converts
         * it), "video", "audio", "pdf", "text", "doc" (Office and OpenDocument: its text), or ""
         * for nothing but the details.
         */
        fun previewOf(name: String): String {
            val mime = mimeOf(name)
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                mime in BROWSER_IMAGES -> "image"
                mime.startsWith("image/") -> "photo"
                mime.startsWith("video/") -> "video"
                mime.startsWith("audio/") -> "audio"
                mime == "application/pdf" -> "pdf"
                ext in setOf("docx", "pptx", "xlsx", "odt", "odp", "ods") -> "doc"
                mime.startsWith("text/") || ext in TEXT_EXT -> "text"
                else -> ""
            }
        }
    }
}

/**
 * A file on the phone as a range-capable response, so the page can fetch it over several
 * connections at once and resume where it stopped. The body is produced in a writer
 * launched in [scope], the call it answers, so it never outlives the request.
 */
class FileRangeContent(
    private val scope: CoroutineScope,
    private val file: File,
    private val type: ContentType,
) : OutgoingContent.ReadChannelContent() {
    private val length = file.length()
    override val contentType: ContentType get() = type
    override val contentLength: Long get() = length

    override fun readFrom(): ByteReadChannel = readFrom(0L until length)

    override fun readFrom(range: LongRange): ByteReadChannel {
        val first = range.first.coerceAtLeast(0)
        val count = (range.last.coerceAtMost(length - 1) - first + 1).coerceAtLeast(0)
        return scope.writer(Dispatchers.IO) {
            runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(first)
                    val buf = ByteArray(DOWNLOAD_BUFFER)
                    var sent = 0L
                    while (sent < count) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), count - sent).toInt())
                        if (n <= 0) break
                        channel.writeFully(buf, 0, n)
                        channel.flush()
                        Monitor.addOut(n)
                        sent += n
                    }
                }
            }
        }.channel
    }
}
