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
 * black, so it comes second; then Wikipedia's photo of them, for those with an article about a
 * musician. A catalogue with no photo of an artist shows one of their covers instead (Deezer's Kanye
 * West, Apple's Kyle Exum: a ..._cover.jpg), so a picture that is one of the artist's own covers in
 * the library is not kept, nor one all one colour (a placeholder). Nor, from Deezer or Apple, one
 * with no face in it (Android's face finder): their pictures are sometimes artwork, or someone else's
 * (Chino Moreno's was The Cure's); Wikipedia's are photos already. With none left, there is none,
 * and the pages show the artist's initials.
 *
 * Only an artist's name leaves the phone, as a search, and only while finding covers online is on
 * (the same setting as [CoverFinder]). One artist at a time, a couple of seconds apart, as the
 * catalogues turn away anyone asking faster. A picture found is kept in the app's storage, so each
 * artist is looked up once; one neither has is not asked about again for a week; a network failure
 * marks nothing, so the artist is tried again next time it is shown.
 *
 * Each picture is also kept beside the music, as the lyrics are ([shared]: Music/Artists, a JPEG
 * named after the artist, "Central Cee.jpg"), so it outlasts the app's own storage (a reinstall)
 * and other players can use it; a picture there is taken as found. The folder has a .nomedia, so
 * the photos do not show up in the gallery.
 */
class ArtistArt(
    private val dir: File,
    private val enabled: () -> Boolean,
    /** Where pictures are kept beside the music, or null when the phone's files cannot be written. */
    private val shared: () -> File? = { null },
    /** An album's cover in the library, by its id, to tell a cover from a photo. */
    private val cover: (Long) -> ByteArray? = { null },
    private val onFound: (String) -> Unit,
) {
    private class Ask(val name: String, val albums: List<String>, val covers: List<Long>)

    private val queue = LinkedBlockingQueue<Ask>()
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false

    private fun file(name: String, ext: String) = File(dir, fileKey(name) + ext)

    /** The artist's picture beside the music: their name, without what a file name cannot hold. */
    private fun sharedFile(name: String): File? =
        shared()?.let { File(it, name.trim().replace(Regex("""[\\/:*?"<>|]"""), "_").take(120) + ".jpg") }

    /** The picture found earlier, as a JPEG, or null: the app's copy, else the one beside the music (copied in). */
    fun cached(name: String): ByteArray? {
        val own = file(name, ".jpg")
        if (own.isFile) {
            val bytes = runCatching { own.readBytes() }.getOrNull() ?: return null
            sharedFile(name)?.let { if (!it.isFile) keepShared(it, bytes) }
            return bytes
        }
        val side = sharedFile(name)?.takeIf { it.isFile } ?: return null
        val bytes = runCatching { side.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { dir.mkdirs(); own.writeBytes(bytes) }
        return bytes
    }

    /**
     * Whether the picture kept for [name] is one of their covers ([covers], album ids) or has no face
     * in it, as the first pictures sometimes were; if so it is thrown away (both copies), so it is
     * looked up again (where Wikipedia's photo, kept without a face, may come back).
     */
    fun dropIfCover(name: String, covers: List<Long>): Boolean {
        val bytes = cached(name) ?: return false
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        if (!isCover(bmp, covers) && hasFace(bmp)) return false
        file(name, ".jpg").delete()
        file(name, ".none").delete()
        sharedFile(name)?.delete()
        asked.remove(key(name))
        return true
    }

    /** Whether the artist's picture is here already, either copy. */
    fun has(name: String): Boolean = file(name, ".jpg").isFile || sharedFile(name)?.isFile == true

    private fun keepShared(f: File, bytes: ByteArray) {
        runCatching {
            f.parentFile?.let { d -> d.mkdirs(); File(d, ".nomedia").let { n -> if (!n.exists()) n.createNewFile() } }
            f.writeBytes(bytes)
        }
    }

    /** Looks the artist up in the background (by one of [albums], their albums in the library, where it can), unless it is switched off, unnamed, or done already. */
    fun request(name: String, albums: List<String> = emptyList(), covers: List<Long> = emptyList()) {
        val n = name.trim()
        if (!enabled() || key(n).isEmpty() || n.equals("Unknown artist", true) || n.equals("Various Artists", true)) return
        if (has(n)) return
        val none = file(n, ".none")
        if (none.isFile && System.currentTimeMillis() - none.lastModified() < RETRY_MS) return
        if (!asked.add(key(n))) return
        queue.add(Ask(n, albums.filter { it.isNotBlank() && it != "Unknown album" }.take(3), covers.distinct().take(12)))
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
                            sharedFile(name)?.let { keepShared(it, bytes) }
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
        val sources = listOf<Pair<Boolean, () -> String?>>(true to { deezer(ask.name) }, true to { apple(ask) }, false to { wikipedia(ask.name) })
        for ((i, pair) in sources.withIndex()) {
            val (needFace, find) = pair
            val from = SOURCES[i]
            val url = try { find().also { reached = true } } catch (e: Exception) { android.util.Log.i(TAG, "${ask.name}: $from failed: ${e.message}"); null }
            if (url == null) { android.util.Log.i(TAG, "${ask.name}: $from has none"); continue }
            val image = runCatching { get(url) }.getOrNull() ?: run { android.util.Log.i(TAG, "${ask.name}: $from picture would not load"); null } ?: continue
            val bmp = BitmapFactory.decodeByteArray(image, 0, image.size) ?: continue
            val why = when {
                flat(bmp) -> "all one colour"
                isCover(bmp, ask.covers) -> "one of their covers"
                needFace && !hasFace(bmp) -> "no face"
                else -> null
            }
            if (why != null) { android.util.Log.i(TAG, "${ask.name}: $from picture is $why"); continue }
            android.util.Log.i(TAG, "${ask.name}: $from picture kept")
            return square(bmp)
        }
        if (!reached) error("No catalogue reachable")
        return EMPTY
    }

    /** Whether Android's face finder sees a face in [bmp] (a photo of someone, not artwork). */
    private fun hasFace(bmp: Bitmap): Boolean = runCatching {
        // It wants an even width and 565 colour; a small copy is quicker and finds faces as well.
        val w = 360
        val h = (bmp.height.toLong() * w / bmp.width).toInt().coerceAtLeast(2)
        val s = Bitmap.createScaledBitmap(bmp, w, h, true).copy(Bitmap.Config.RGB_565, false)
        val faces = arrayOfNulls<android.media.FaceDetector.Face>(4)
        android.media.FaceDetector(w, h, faces.size).findFaces(s, faces) > 0
    }.getOrDefault(true)

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

    /**
     * Whether [bmp] is one of the album covers [covers]: their average-brightness fingerprints (8 by 8,
     * of the middle square, so a cover cropped wide still matches) differ in at most 16 of 64 places.
     */
    private fun isCover(bmp: Bitmap, covers: List<Long>): Boolean {
        if (covers.isEmpty()) return false
        val mine = print(bmp)
        return covers.any { id ->
            val c = runCatching { cover(id) }.getOrNull() ?: return@any false
            val cb = BitmapFactory.decodeByteArray(c, 0, c.size) ?: return@any false
            java.lang.Long.bitCount(mine xor print(cb)) <= 16
        }
    }

    private fun print(bmp: Bitmap): Long {
        val side = minOf(bmp.width, bmp.height)
        val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val s = Bitmap.createScaledBitmap(sq, 8, 8, true)
        val v = IntArray(64)
        s.getPixels(v, 0, 8, 0, 0, 8, 8)
        val lum = v.map { c -> (c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114 }
        val mean = lum.average()
        var bits = 0L
        lum.forEachIndexed { i, l -> if (l > mean) bits = bits or (1L shl i) }
        return bits
    }

    private fun square(bmp: Bitmap): ByteArray {
        // Square: from the middle across; from near the top of a tall photo, where the face is.
        val side = minOf(bmp.width, bmp.height)
        val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, ((bmp.height - side) * 0.2f).toInt(), side, side)
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
        // Only a picture of the artist (Apple's own placeholder lives elsewhere; an album's art in its place is a ..._cover.jpg), asked for square.
        if (!og.contains("mzstatic.com") || og.contains("cover", ignoreCase = true)) return null
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

    /**
     * Wikipedia: the photo on the article about the artist, when the article is about a musician (its
     * one-line description says so: rapper, singer, band...), tried as "Name", then "Name (rapper)" and
     * the like. Only an artist well known enough for an article has one.
     */
    private fun wikipedia(name: String): String? {
        for (title in listOf(name, "$name (rapper)", "$name (musician)", "$name (singer)", "$name (band)")) {
            val body = try {
                get("https://en.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "_"))
            } catch (e: Exception) {
                if (e.message?.startsWith("HTTP 404") == true) continue else throw e
            }.decodeToString()
            val o = Json.parseToJsonElement(body).jsonObject
            if (o["type"]?.jsonPrimitive?.contentOrNull == "disambiguation") continue
            val about = o["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!MUSICIAN.containsMatchIn(about)) continue
            val thumb = o["thumbnail"]?.jsonObject?.get("source")?.jsonPrimitive?.contentOrNull ?: return null
            // The thumbnail asked for 500 across (Wikimedia serves only its own steps: 330, 500, 960...); else the original.
            return if (Regex("""/\d+px-""").containsMatchIn(thumb)) thumb.replace(Regex("""/\d+px-"""), "/500px-")
            else o["originalimage"]?.jsonObject?.get("source")?.jsonPrimitive?.contentOrNull ?: thumb
        }
        return null
    }

    private fun get(url: String, browser: Boolean = false): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 12_000
        c.setRequestProperty("User-Agent", if (browser) "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140 Mobile Safari/537.36" else "Localhost8787/1 (https://github.com/Subalzt/localhost-8787)")
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
        private const val TAG = "ArtistArt"
        private val SOURCES = listOf("Deezer", "Apple", "Wikipedia")
        private val MUSICIAN = Regex("""\b(rapper|singer|musician|band|group|duo|trio|producer|DJ|songwriter|composer|vocalist|record producer|recording artist|hip hop)\b""", RegexOption.IGNORE_CASE)
        private val OG = Regex("""<meta[^>]+property="og:image"[^>]+content="([^"]+)"""")
        private val OG2 = Regex("""<meta[^>]+content="([^"]+)"[^>]+property="og:image"""")

        /** Letters and digits only, lower case: an artist's name as the libraries and catalogues are compared. */
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        /** The file an artist's picture is kept in, from the name as compared (so any spelling finds it). */
        private fun fileKey(name: String): String =
            MessageDigest.getInstance("SHA-1").digest(key(name).toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    }
}
