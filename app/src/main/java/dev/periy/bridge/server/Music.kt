package dev.periy.bridge.server

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.FileInputStream

@Serializable
data class TrackDto(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    /**
     * Text, not a number: Android 11+ album ids are 64-bit hashes (19 digits), and a
     * JavaScript number only holds 53 bits exactly -- the browser would round the id and
     * ask for an album that does not exist.
     */
    val albumId: String,
    val durationMs: Long,
    val mime: String,
    val size: Long,
    /** Track number within its disc; the disc is in [disc]. */
    val track: Int,
    val disc: Int,
    val year: Int,
    /** The genre tag, where the file has one (Android 11 and later index it). */
    val genre: String = "",
    /** The album artist tag, where there is one: who the album is by, not each song. */
    val albumArtist: String = "",
)

@Serializable
data class MusicDto(val granted: Boolean, val tracks: List<TrackDto>)

/**
 * The phone's music, read from Android's own media index.
 *
 * Nothing is copied or converted. Tracks are served from where they already live, with
 * byte-range support so the browser can start playing after the first few kilobytes and
 * seek anywhere without downloading what it skips.
 *
 * Reading the index needs the music permission -- READ_MEDIA_AUDIO on Android 13 and
 * later, which covers audio and nothing else. Not all-files access; not photos.
 */
class MusicLibrary(ctx: Context, lookupOnline: () -> Boolean = { true }) {

    private val app = ctx.applicationContext
    private val resolver: ContentResolver get() = app.contentResolver

    /** Covers from the catalogue, for albums with none; each one found is announced to the pages. */
    private val finder = CoverFinder(java.io.File(app.filesDir, "covers"), lookupOnline) { albumId ->
        art.remove(albumId)
        EventBus.emit("cover", albumId.toString())
    }

    @Volatile private var cached: List<TrackDto> = emptyList()
    @Volatile private var cachedAt = 0L

    /**
     * Encoded cover art by album. Covers are small and requested constantly while
     * scrolling a list, so they are kept in memory rather than re-decoded each time.
     * An empty array records "this album has no art", so a missing cover is not
     * searched for again on every scroll.
     */
    private val art = object : LruCache<Long, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: ByteArray) = value.size.coerceAtLeast(1)
    }

    fun granted(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(app, perm) == PackageManager.PERMISSION_GRANTED
    }

    /** All tracks, sorted artist -> album -> disc -> track. Cached for a minute. */
    fun tracks(refresh: Boolean = false): List<TrackDto> {
        if (!granted()) return emptyList()
        val now = System.currentTimeMillis()
        if (!refresh && cached.isNotEmpty() && now - cachedAt < CACHE_MS) return cached
        cached = query()
        cachedAt = now
        return cached
    }

    fun find(id: Long): TrackDto? =
        tracks().firstOrNull { it.id == id } ?: tracks(refresh = true).firstOrNull { it.id == id }

    fun uri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    private fun query(): List<TrackDto> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DISPLAY_NAME,
        ) + (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) arrayOf(MediaStore.Audio.Media.GENRE, MediaStore.Audio.Media.ALBUM_ARTIST) else emptyArray())
        val out = ArrayList<TrackDto>()
        runCatching {
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val displayName = c.getString(10).orEmpty()
                    val rawTrack = c.getInt(8)
                    out += TrackDto(
                        id = c.getLong(0),
                        title = c.getString(1)?.takeIf { it.isNotBlank() }
                            ?: displayName.substringBeforeLast('.'),
                        artist = c.getString(2).cleanTag("Unknown artist"),
                        album = c.getString(3).cleanTag("Unknown album"),
                        albumId = c.getLong(4).toString(),
                        durationMs = c.getLong(5),
                        mime = c.getString(6) ?: "audio/*",
                        size = c.getLong(7),
                        // Android packs disc and track into one number: 2003 = disc 2, track 3.
                        track = rawTrack % 1000,
                        disc = (rawTrack / 1000).coerceAtLeast(1),
                        year = c.getInt(9),
                        genre = if (c.columnCount > 11) c.getString(11).cleanTag("") else "",
                        albumArtist = if (c.columnCount > 12) c.getString(12).cleanTag("") else "",
                    )
                }
            }
        }
        return out.sortedWith(
            compareBy<TrackDto>({ it.artist.lowercase() }, { it.album.lowercase() }, { it.disc }, { it.track }, { it.title.lowercase() })
        )
    }

    /**
     * JPEG cover for an album, or null when it has none yet. In order: the album's art in
     * Android's index; a track's embedded cover; an image beside the files (cover.jpg and
     * the like, readable with All files access); one found in the catalogue earlier. With
     * none of those, the catalogue is asked in the background (CoverFinder) and the pages
     * are told when a cover arrives.
     */
    fun cover(albumId: Long): ByteArray? {
        art.get(albumId)?.let { return it.takeIf { b -> b.isNotEmpty() } }
        val size = Size(COVER_PX, COVER_PX)
        val first = tracks().firstOrNull { it.albumId == albumId.toString() }
        val bmp: Bitmap? = runCatching {
            resolver.loadThumbnail(
                ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId), size, null,
            )
        }.getOrNull() ?: first?.let { t ->
            // Some albums have no album-level art; the embedded cover of a track still works.
            runCatching { resolver.loadThumbnail(uri(t.id), size, null) }.getOrNull()
        } ?: first?.let { folderCover(it.id) }
        val bytes = bmp?.let {
            ByteArrayOutputStream().use { out ->
                it.compress(Bitmap.CompressFormat.JPEG, 86, out)
                out.toByteArray()
            }
        } ?: finder.cached(albumId) ?: ByteArray(0)
        if (bytes.isEmpty() && first != null) finder.request(albumId, first.artist, first.album)
        art.put(albumId, bytes)
        return bytes.takeIf { it.isNotEmpty() }
    }

    /** A song's own file, where All files access lets it be reached: for the lyrics kept beside it. */
    fun file(trackId: Long): java.io.File? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !android.os.Environment.isExternalStorageManager()) return null
        return runCatching {
            resolver.query(uri(trackId), arrayOf(MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && c.getString(0) != null && c.getString(1) != null)
                    java.io.File(java.io.File(android.os.Environment.getExternalStorageDirectory(), c.getString(0)), c.getString(1))
                else null
            }
        }.getOrNull()
    }

    /** An image kept beside the album's files, as many rips and downloads have one. */
    private fun folderCover(trackId: Long): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !android.os.Environment.isExternalStorageManager()) return null
        val rel = runCatching {
            resolver.query(uri(trackId), arrayOf(MediaStore.Audio.Media.RELATIVE_PATH), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: return null
        val folder = java.io.File(android.os.Environment.getExternalStorageDirectory(), rel)
        val pick = folder.listFiles()?.filter { f ->
            f.isFile && f.name.lowercase().let { n ->
                (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) &&
                    COVER_NAMES.any { n.startsWith(it) }
            }
        }?.maxByOrNull { it.length() } ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(pick.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_PX) sample *= 2
            BitmapFactory.decodeFile(pick.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    private fun String?.cleanTag(fallback: String): String =
        this?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: fallback

    private companion object {
        const val CACHE_MS = 60_000L
        const val COVER_PX = 512
        val COVER_NAMES = listOf("cover", "folder", "front", "albumart", "album")
    }
}

/**
 * A content URI served as a range-capable response body, with no transfer tracking.
 *
 * Music does not belong in the transfers list -- a playlist would fill it with rows that
 * are never "done" in any useful sense -- so this is the plain version of the file
 * download body: seek with lseek, stream exactly the requested range, stop. The writer
 * is launched in [scope], the call it answers, so it never outlives the request.
 */
class UriRangeContent(
    private val scope: CoroutineScope,
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val length: Long,
    private val type: ContentType,
) : OutgoingContent.ReadChannelContent() {

    override val contentType: ContentType get() = type
    override val contentLength: Long get() = length

    override fun readFrom(): ByteReadChannel = readFrom(0L until length)

    override fun readFrom(range: LongRange): ByteReadChannel {
        val first = range.first.coerceAtLeast(0)
        val count = (range.last.coerceAtMost(length - 1) - first + 1).coerceAtLeast(0)
        return scope.writer(Dispatchers.IO) {
            val pfd = resolver.openFileDescriptor(uri, "r") ?: return@writer
            try {
                val input = FileInputStream(pfd.fileDescriptor)
                runCatching { Os.lseek(pfd.fileDescriptor, first, OsConstants.SEEK_SET) }
                    .onFailure {
                        var skip = first
                        while (skip > 0) {
                            val s = input.skip(skip)
                            if (s <= 0) break
                            skip -= s
                        }
                    }
                // Small first chunk so the browser can start decoding immediately; after
                // that, full-size reads to keep the pipe full. Every chunk is flushed before
                // it is counted, the first one included.
                var chunk = 64 * 1024
                val buf = ByteArray(DOWNLOAD_BUFFER)
                var sent = 0L
                while (sent < count) {
                    val want = minOf(chunk.toLong(), count - sent).toInt()
                    val n = input.read(buf, 0, want)
                    if (n <= 0) break
                    channel.writeFully(buf, 0, n)
                    channel.flush()
                    Monitor.addOut(n, Lane.MUSIC)
                    sent += n
                    chunk = DOWNLOAD_BUFFER
                }
            } catch (_: Throwable) {
                // Skipping tracks and seeking cancel requests constantly; that is normal.
            } finally {
                runCatching { pfd.close() }
            }
        }.channel
    }
}
