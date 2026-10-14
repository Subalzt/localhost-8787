package dev.periy.bridge.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * Pictures of the artists in the library, for the Artists pages, found online: Deezer's photo of the
 * artist first (of the artists of that name there, the one most people follow: the Aitch with
 * 168,000 followers, not the one with 78), then Apple Music's when Deezer has none. Apple has several
 * artists of one name too, so there the artist is found through one of their albums in the library,
 * and their page's preview image is the picture; but Apple's is sometimes an album cover or plain
 * black, so it comes second. A picture that is all one colour (a placeholder) is not kept.
 *
 * Only an artist's name leaves the phone, as a search, and only while finding covers online is on
 * (the same setting as [CoverFinder]). One artist at a time, a couple of seconds apart, as the
 * catalogues turn away anyone asking faster. A picture found is kept in the app's storage, so each
 * artist is looked up once; one neither has is not asked about again for a week; a network failure
 * marks nothing, so the artist is tried again next time it is shown.
 */
class ArtistArt(
    private val dir: File,
    private val enabled: () -> Boolean,
    private val onFound: (String) -> Unit,
) {
    private class Ask(val name: String, val albums: List<String>)

    private val queue = LinkedBlockingQueue<Ask>()
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false

    private fun file(name: String, ext: String) = File(dir, fileKey(name) + ext)

    /** The picture found earlier, as a JPEG, or null. */
    fun cached(name: String): ByteArray? =
        file(name, ".jpg").takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }

    /** Looks the artist up in the background (by one of [albums], their albums in the library, where it can), unless it is switched off, unnamed, or done already. */
    fun request(name: String, albums: List<String> = emptyList()) {
        val n = name.trim()
        if (!enabled() || key(n).isEmpty() || n.equals("Unknown artist", true) || n.equals("Various Artists", true)) return
        if (file(n, ".jpg").isFile) return
        val none = file(n, ".none")
        if (none.isFile && System.currentTimeMillis() - none.lastModified() < RETRY_MS) return
        if (!asked.add(key(n))) return
        queue.add(Ask(n, albums.filter { it.isNotBlank() && it != "Unknown album" }.take(3)))
        startWorker()
    }

    @Synchronized
    private fun startWorker() {
        if (running) return
        running = true
        scope.launch {
            try {
                while (true) {
                    val ask = queue.poll() ?: break
                    val name = ask.name
                    if (!enabled()) { asked.remove(key(name)); continue }
                    val bytes = try { lookup(ask) } catch (e: Exception) { asked.remove(key(name)); null }
                    when {
                        bytes == null -> {}
                        bytes.isEmpty() -> runCatching { dir.mkdirs(); file(name, ".none").writeText("") }
                        else -> {
                            runCatching { dir.mkdirs(); file(name, ".jpg").writeBytes(bytes) }
                            onFound(name)
                        }
                    }
                    delay(SPACING_MS)
                }
            } finally {
                running = false
                if (queue.isNotEmpty()) startWorker()
            }
        }
    }

    /** The picture as a square JPEG, [EMPTY] when neither catalogue has one, or an exception when they could not be reached. */
    private fun lookup(ask: Ask): ByteArray {
        // Neither reachable (no network): an exception, so nothing is recorded and it is tried again.
        var reached = false
        for (find in listOf({ deezer(ask.name) }, { apple(ask) })) {
            val url = try { find().also { reached = true } } catch (e: Exception) { null } ?: continue
            val image = runCatching { get(url) }.getOrNull() ?: continue
            val bmp = BitmapFactory.decodeByteArray(image, 0, image.size) ?: continue
            if (flat(bmp)) continue
            return square(bmp)
        }
        if (!reached) error("No catalogue reachable")
        return EMPTY
    }

    /** A placeholder: nearly one colour all over (the brightness hardly varies across a small copy). */
    private fun flat(bmp: Bitmap): Boolean {
        val s = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        val v = IntArray(24 * 24)
        s.getPixels(v, 0, 24, 0, 0, 24, 24)
        val lum = v.map { c -> ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000f }
        val mean = lum.average().toFloat()
        val sd = kotlin.math.sqrt(lum.map { (it - mean) * (it - mean) }.average()).toFloat()
        return sd < 10f
    }

    private fun square(bmp: Bitmap): ByteArray {
        // Square, from the middle (Apple's are square already; a few of Deezer's are not).
        val side = minOf(bmp.width, bmp.height)
        val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val out = if (side > PX) Bitmap.createScaledBitmap(sq, PX, PX, true) else sq
        return ByteArrayOutputStream().use { o -> out.compress(Bitmap.CompressFormat.JPEG, 88, o); o.toByteArray() }
    }

    /** Apple Music: the artist found in the catalogue, then the picture its page shows (null when it has none). */
    private fun apple(ask: Ask): String? {
        val want = key(ask.name)
        fun search(entity: String, term: String) = Json.parseToJsonElement(
            get("https://itunes.apple.com/search?media=music&entity=$entity&limit=10&term=" + URLEncoder.encode(term, "UTF-8")).decodeToString(),
        ).jsonObject["results"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        fun named(r: kotlinx.serialization.json.JsonObject) = key(r["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty()).let { it == want || it.startsWith(want) }
        // Through an album of theirs: the catalogue's album of that name, by them.
        var artistId: String? = null
        for (album in ask.albums) {
            val a = key(album.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), ""))
            val hit = search("album", "${ask.name} $album").firstOrNull { r ->
                named(r) && key(r["collectionName"]?.jsonPrimitive?.contentOrNull.orEmpty()).let { it.startsWith(a) || a.startsWith(it) }
            }
            artistId = hit?.get("artistId")?.jsonPrimitive?.contentOrNull
            if (artistId != null) break
        }
        val link = if (artistId != null) "https://music.apple.com/us/artist/$artistId"
        else search("musicArtist", ask.name).firstOrNull { key(it["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty()) == want }
            ?.get("artistLinkUrl")?.jsonPrimitive?.contentOrNull?.substringBefore('?') ?: return null
        val page = get(link, browser = true).decodeToString()
        val og = OG.find(page)?.groupValues?.get(1) ?: OG2.find(page)?.groupValues?.get(1) ?: return null
        // Only a picture of the artist (Apple's own placeholder lives elsewhere), asked for square.
        if (!og.contains("mzstatic.com")) return null
        return og.replace(Regex("""/\d+x\d+\w*\.(jpg|jpeg|png|webp)$"""), "/${PX}x${PX}cc.jpg")
    }

    /** Deezer: the picture of the most followed artist of that name, when it has one (an artist with none has an empty path in its link). */
    private fun deezer(name: String): String? {
        val body = get("https://api.deezer.com/search/artist?limit=25&q=" + URLEncoder.encode(name, "UTF-8")).decodeToString()
        val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return null
        val want = key(name)
        val hit = data.map { it.jsonObject }.filter { key(it["name"]?.jsonPrimitive?.contentOrNull.orEmpty()) == want }
            .maxByOrNull { it["nb_fan"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L } ?: return null
        val pic = hit["picture_xl"]?.jsonPrimitive?.contentOrNull ?: return null
        return pic.takeUnless { it.contains("/artist//") }
    }

    private fun get(url: String, browser: Boolean = false): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 12_000
        c.setRequestProperty("User-Agent", if (browser) "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140 Mobile Safari/537.36" else "Localhost8787/1")
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private const val SPACING_MS = 2_500L
        private const val RETRY_MS = 7L * 24 * 3600 * 1000
        private const val PX = 600
        private val OG = Regex("""<meta[^>]+property="og:image"[^>]+content="([^"]+)"""")
        private val OG2 = Regex("""<meta[^>]+content="([^"]+)"[^>]+property="og:image"""")

        /** Letters and digits only, lower case: an artist's name as the libraries and catalogues are compared. */
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        /** The file an artist's picture is kept in, from the name as compared (so any spelling finds it). */
        private fun fileKey(name: String): String =
            MessageDigest.getInstance("SHA-1").digest(key(name).toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    }
}
