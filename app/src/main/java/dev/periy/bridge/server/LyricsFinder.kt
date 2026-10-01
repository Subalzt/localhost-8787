package dev.periy.bridge.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A word of a line, timed on its own (LRCLIB's Lyricsfile, or Enhanced LRC); times in milliseconds. */
data class LyricWord(val t: Long, val e: Long, val w: String)

/** A line of timed lyrics: when it starts and ends, its text, and its words when each is timed. */
data class LyricLine(val t: Long, val e: Long, val text: String, val words: List<LyricWord> = emptyList(), val bg: Boolean = false)

/** A song's lyrics as the player shows them. [offline]: none known, and they could not be looked up. */
data class ShownLyrics(
    val kind: Kind,
    val lines: List<LyricLine> = emptyList(),
    val plain: String = "",
    val source: String = "",
    val offline: Boolean = false,
) {
    enum class Kind { NONE, INSTRUMENTAL, PLAIN, LINE, WORD }
    val timed: Boolean get() = kind == Kind.LINE || kind == Kind.WORD
}

/**
 * Lyrics for the phone's own player, found the way the page finds them (bridge.html, lyricsFor):
 * what the phone keeps first (LyricsStore: found before, or a .lrc beside the song), else LRCLIB,
 * looked up by the phone itself. What is found is kept the same way the page keeps it, a .lrc
 * beside the song and the app's own copy, so the page and the phone share it. A song with none
 * is looked up again after a few days; a lookup that could not be made (no internet) is tried
 * again the next time the song plays.
 */
class LyricsFinder(val store: LyricsStore) {

    private val known = ConcurrentHashMap<Long, ShownLyrics>()
    /** Songs whose untimed lyrics have been looked for timed ones this run, found or not. */
    private val triedTimed = ConcurrentHashMap.newKeySet<Long>()
    private val lock = Mutex()
    private val _changed = MutableStateFlow(0L)

    /** Goes up whenever lyrics already shown may have changed (a page left new ones): read them again. */
    val changed: StateFlow<Long> = _changed

    /** What is known for [t] now, without looking anything up. */
    fun cached(t: TrackDto): ShownLyrics? = known[t.id]

    /** [t]'s lyrics: from the phone, else from LRCLIB (and then kept). */
    suspend fun forTrack(t: TrackDto): ShownLyrics = withContext(Dispatchers.IO) {
        known[t.id] ?: lock.withLock {
            known[t.id] ?: run {
                var doc = runCatching { store.get(t) }.getOrNull()
                val stale = doc != null && doc.source == "none" && System.currentTimeMillis() - doc.at > RETRY_MS
                // Words kept with the song but with no timing (a plain .lrc, or the file's own tag):
                // LRCLIB is asked once a run for timed ones, which then take their place.
                val untimed = doc != null && doc.source != "none" && !doc.instrumental &&
                    doc.lrc.isBlank() && doc.lyricsfile.isBlank() && triedTimed.add(t.id)
                var failed = false
                if (doc == null || stale || untimed) {
                    val found = lrclib(t)?.takeIf { f -> !untimed || f.lrc.isNotBlank() || f.lyricsfile.isNotBlank() }
                    if (found != null) {
                        // The .lrc the phone keeps beside the song, for every music player on it.
                        val kept = if (found.source != "none") found.copy(sidecar = LyricsText.lrcFile(t, found, LyricsText.parse(found))) else found
                        runCatching { store.put(t, kept) }
                        doc = kept
                    } else failed = doc == null && !untimed
                }
                val shown = LyricsText.parse(doc).let { if (doc == null && failed) it.copy(offline = true) else it }
                if (!shown.offline) known[t.id] = shown
                shown
            }
        }
    }

    /** A page left new lyrics for [id]: they are read again. */
    fun forget(id: Long) {
        known.remove(id)
        _changed.value = _changed.value + 1
    }

    // ---- LRCLIB: the exact song first (title, artist, album, length), then a search

    private val json = Json { ignoreUnknownKeys = true }

    /** Null when it could not be asked (no internet, LRCLIB down); a "none" document when it has nothing. */
    private fun lrclib(t: TrackDto): LyricsDoc? {
        val secs = Math.round(t.durationMs / 1000.0)
        fun doc(o: JsonObject) = LyricsDoc(
            source = "LRCLIB",
            lrc = o.str("syncedLyrics"),
            lyricsfile = if (o.bool("hasWordSync")) o.str("lyricsfile") else "",
            plain = o.str("plainLyrics"),
            instrumental = o.bool("instrumental"),
        )
        // LRCLIB's search for the song: the closest in length, nothing more than ten seconds out;
        // with [timedOnly], only timed lyrics, and within three seconds.
        // Timed lyrics that run well past the song's end are another version's (a 4:33 song's on
        // a 1:30 mix, say): their timing is no use here.
        val lastStamp = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")
        fun fits(x: JsonObject): Boolean {
            val lrc = x.str("syncedLyrics")
            if (lrc.isEmpty() || secs <= 0) return true
            val last = lastStamp.findAll(lrc).maxOfOrNull { it.groupValues[1].toInt() * 60 + it.groupValues[2].toDouble() } ?: return true
            return last <= secs + 15
        }
        /** As [doc], without timing that does not fit the song (the words kept). */
        fun fitted(o: JsonObject) = doc(o).let { if (fits(o)) it else it.copy(lrc = "", lyricsfile = "") }
        fun search(timedOnly: Boolean): Pair<JsonObject?, Double>? {
            val (code, body) = get("$LRCLIB/search?track_name=${enc(plainTitle(t.title))}&artist_name=${enc(mainArtist(t.artist))}")
            if (code != 200) return null
            var best: JsonObject? = null
            var bestScore = 1e9
            for (e in json.parseToJsonElement(body).jsonArray) {
                val x = e as? JsonObject ?: continue
                val off = if (secs > 0) kotlin.math.abs((x.num("duration") ?: 0.0) - secs) else 0.0
                val timed = x.str("syncedLyrics").isNotEmpty() && fits(x)
                if (secs > 0 && off > (if (timedOnly) 3 else 10)) continue
                if (timedOnly && !timed) continue
                val score = off + (if (timed) 0 else 20) +
                    (if (x.str("plainLyrics").isNotEmpty() || x.bool("instrumental")) 0 else 100)
                if (score < bestScore) { bestScore = score; best = x }
            }
            return best to bestScore
        }
        return try {
            val q = "artist_name=${enc(t.artist)}&track_name=${enc(t.title)}&album_name=${enc(t.album)}" + (if (secs > 0) "&duration=$secs" else "")
            val (code, body) = get("$LRCLIB/get?$q")
            if (code == 200) {
                val exact = json.parseToJsonElement(body).jsonObject
                if ((exact.str("syncedLyrics").isNotEmpty() && fits(exact)) || exact.bool("instrumental")) return doc(exact)
                // The song's own entry has no timing; another entry for it often has (LRCLIB keeps
                // several per song): the timed one of the same length, else the untimed words.
                val timed = runCatching { search(timedOnly = true) }.getOrNull()?.first
                return fitted(timed ?: exact)
            }
            if (code != 404) return null
            val (best, bestScore) = search(timedOnly = false) ?: return null
            best?.takeIf { bestScore < 100 }?.let(::fitted) ?: LyricsDoc(source = "none")
        } catch (e: Exception) {
            null
        }
    }

    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 12_000
            // LRCLIB asks every app to say who it is.
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val body = if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else ""
            return code to body
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull

    companion object {
        const val LRCLIB = "https://lrclib.net/api"
        const val USER_AGENT = "Localhost 8787 (https://github.com/subalzt/localhost-8787)"
        /** A song with no lyrics on LRCLIB is looked up again after this long. */
        const val RETRY_MS = 3L * 24 * 3600 * 1000

        private val FEAT = Regex("""\s+(?:feat\.?|ft\.?|featuring|with|x)\s+|\s*[,;&/]\s*""", RegexOption.IGNORE_CASE)
        private val TITLE_EXTRA = Regex("""\s*[(\[][^)\]]*(feat|ft\.|with|remaster|explicit|live|version|edit|mono|stereo)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
        private val TITLE_TAIL = Regex("""\s+-\s+.*(remaster|version|edit|live|mix).*$""", RegexOption.IGNORE_CASE)

        /** "Artist feat. Other" and "A, B & C" look up as their first artist. */
        fun mainArtist(a: String) = a.split(FEAT).first().trim()
        /** "Song (feat. X) - Remastered 2011 [Explicit]" looks up as "Song". */
        fun plainTitle(t: String) = t.replace(TITLE_EXTRA, "").replace(TITLE_TAIL, "").trim()
    }
}

/** Reading lyrics in the forms they are kept in, and writing the .lrc kept beside a song; as the page does. */
object LyricsText {

    /** Lines, each with its words when they are timed; or plain text; or nothing. */
    fun parse(doc: LyricsDoc?): ShownLyrics {
        if (doc == null || doc.source == "none") return ShownLyrics(ShownLyrics.Kind.NONE)
        if (doc.instrumental) return ShownLyrics(ShownLyrics.Kind.INSTRUMENTAL, source = doc.source)
        val src = doc.source
        if (doc.lyricsfile.isNotBlank()) {
            val lf = runCatching { linesFromLyricsfile(yamlLite(doc.lyricsfile)) }.getOrNull()
            if (!lf.isNullOrEmpty()) return ShownLyrics(if (lf.any { it.words.isNotEmpty() }) ShownLyrics.Kind.WORD else ShownLyrics.Kind.LINE, lf, source = src)
        }
        if (doc.lrc.isNotBlank()) {
            val lines = parseLrc(doc.lrc)
            if (lines.isNotEmpty()) return ShownLyrics(if (lines.any { it.words.isNotEmpty() }) ShownLyrics.Kind.WORD else ShownLyrics.Kind.LINE, lines, source = src)
        }
        if (doc.plain.isNotBlank()) return ShownLyrics(ShownLyrics.Kind.PLAIN, plain = doc.plain.trim(), source = src)
        return ShownLyrics(ShownLyrics.Kind.NONE)
    }

    private val STAMP = Regex("""^(\d+):(\d+(?:[.:]\d+)?)$""")
    private val TAG = Regex("""^\s*\[([^\]]*)]""")
    private val WORD = Regex("""<(\d+:\d+(?:\.\d+)?)>([^<]*)""")
    private val HAS_WORDS = Regex("""<\d+:\d+""")
    private val HEAD_TAGS = Regex("""^\s*\[(ti|ar|al|length|re|by):[^\]]*]\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    private fun lrcTime(s: String): Long? {
        val m = STAMP.matchEntire(s.trim()) ?: return null
        return Math.round((m.groupValues[1].toLong() * 60 + m.groupValues[2].replace(":", ".").toDouble()) * 1000)
    }

    /** LRC: "[01:02.30] words", several times on one line, an [offset:], and <01:02.30> word times (Enhanced LRC). */
    fun parseLrc(text: String): List<LyricLine> {
        class Raw(val t: Long, val text: String, val words: List<Pair<Long, String>>)
        val out = mutableListOf<Raw>()
        var offset = 0L
        for (raw in text.split(Regex("\r?\n"))) {
            var rest = raw
            val stamps = mutableListOf<Long>()
            while (true) {
                val m = TAG.find(rest) ?: break
                val tag = m.groupValues[1]
                if (tag.trim().startsWith("offset", ignoreCase = true) && ':' in tag) offset = tag.substringAfter(':').trim().toLongOrNull() ?: 0L
                lrcTime(tag)?.let { stamps += it }
                rest = rest.substring(m.value.length)
            }
            if (stamps.isEmpty()) continue
            val words = mutableListOf<Pair<Long, String>>()
            var line = rest
            if (HAS_WORDS.containsMatchIn(rest)) {
                val sb = StringBuilder()
                for (w in WORD.findAll(rest)) {
                    val wt = lrcTime(w.groupValues[1])
                    if (w.groupValues[2].isNotEmpty() && wt != null) words += wt to w.groupValues[2]
                    sb.append(w.groupValues[2])
                }
                line = sb.toString()
            }
            stamps.forEach { st -> out += Raw(st, line.trim(), words.toList()) }
        }
        out.sortBy { it.t }
        // A positive offset shows the words earlier.
        val lines = out.map { r -> Raw(r.t - offset, r.text, r.words.map { (wt, w) -> (wt - offset) to w }) }
        val result = lines.mapIndexed { i, r ->
            val e = if (i + 1 < lines.size) lines[i + 1].t else r.t + 6000
            val words = r.words.mapIndexed { k, (wt, w) -> LyricWord(wt, if (k + 1 < r.words.size) r.words[k + 1].first else e, w) }
            LyricLine(r.t, e, r.text, words)
        }
        // A blank line only marks where the one before it ends.
        return result.filter { it.text.isNotEmpty() }
    }

    /** LRCLIB's Lyricsfile: lines with start_ms and end_ms, and their words, each with its own. */
    fun linesFromLyricsfile(doc: Any?): List<LyricLine> {
        val ls = ((doc as? Map<*, *>)?.get("lines") as? List<*>).orEmpty()
        fun ms(v: Any?): Long = when (v) { is Number -> v.toLong(); is String -> v.toDoubleOrNull()?.toLong() ?: 0L; else -> 0L }
        val out = ls.mapNotNull { l ->
            val m = l as? Map<*, *> ?: return@mapNotNull null
            val words = (m["words"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.filter { it["text"] != null }
            // Words stored without their spaces get one between them.
            val spaced = words.any { it["text"].toString().endsWith(" ") || it["text"].toString().endsWith("\t") }
            val ws = words.mapIndexed { i, w ->
                LyricWord(ms(w["start_ms"]), ms(w["end_ms"]), w["text"].toString() + if (!spaced && i < words.size - 1) " " else "")
            }
            val text = (m["text"]?.toString() ?: "").trim()
            val bg = m["background"] == true || m["bg"] == true
            if (text.isEmpty() && ws.isEmpty()) null else LyricLine(ms(m["start_ms"]), ms(m["end_ms"]), text, ws, bg)
        }.sortedBy { it.t }
        return out.mapIndexed { i, l ->
            val e = if (l.e <= 0 || l.e <= l.t) (if (i + 1 < out.size) out[i + 1].t else l.t + 6000) else l.e
            l.copy(e = e, text = l.text.ifEmpty { l.words.joinToString("") { it.w }.trim() })
        }
    }

    /**
     * The YAML a Lyricsfile is written in, as much as it uses: maps, lists (of maps), plain and
     * quoted values, and | or > blocks. Not a general YAML reader.
     */
    fun yamlLite(src: String): Any? {
        class Row(val ind: Int, val s: String, val raw: String)
        val rows = src.split(Regex("\r?\n")).filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .map { Row(it.indexOfFirst { c -> !c.isWhitespace() }, it.trim(), it) }.toMutableList()
        var i = 0
        fun item(s: String) = s == "-" || s.startsWith("- ")
        fun colon(s: String): Int {
            var q: Char? = null
            for (k in s.indices) {
                val c = s[k]
                if (q != null) { if (c == q) q = null }
                else if ((c == '\'' || c == '"') && k == 0) q = c
                else if (c == ':' && (k + 1 == s.length || s[k + 1] == ' ')) return k
            }
            return -1
        }
        fun scalar(v0: String): Any? {
            val v = v0.trim()
            if (v.length >= 2 && v.startsWith("'") && v.endsWith("'")) return v.substring(1, v.length - 1).replace("''", "'")
            if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                return runCatching { (Json.parseToJsonElement(v) as JsonPrimitive).content }.getOrElse { v.substring(1, v.length - 1) }
            }
            if (Regex("""^-?\d+(\.\d+)?$""").matches(v)) return v.toDouble()
            return when (v) { "true" -> true; "false" -> false; "null", "~" -> null; else -> v }
        }
        lateinit var block: () -> Any?
        fun map(ind: Int): Map<String, Any?> {
            val o = LinkedHashMap<String, Any?>()
            while (i < rows.size && rows[i].ind == ind && !item(rows[i].s)) {
                val s = rows[i].s
                val c = colon(s)
                if (c < 0) { i++; continue }
                val k = s.substring(0, c).trim()
                val v = s.substring(c + 1).trim()
                i++
                if (v == "|" || v == ">" || v == "|-" || v == ">-") {
                    val parts = mutableListOf<String>()
                    while (i < rows.size && rows[i].ind > ind) { parts += rows[i].raw.substring(minOf(rows[i].ind, ind + 2)); i++ }
                    o[k] = parts.joinToString(if (v[0] == '|') "\n" else " ")
                } else if (v.isEmpty()) {
                    o[k] = if (i < rows.size && (rows[i].ind > ind || (rows[i].ind == ind && item(rows[i].s)))) block() else null
                } else o[k] = scalar(v)
            }
            return o
        }
        fun list(ind: Int): List<Any?> {
            val a = mutableListOf<Any?>()
            while (i < rows.size && rows[i].ind == ind && item(rows[i].s)) {
                val rest = rows[i].s.substring(1).trim()
                if (rest.isEmpty()) { i++; a += block(); continue }
                if (colon(rest) >= 0) {
                    // "- key: value" opens a map whose keys line up after the dash.
                    rows[i] = Row(ind + (rows[i].s.length - rest.length), rest, rows[i].raw)
                    a += map(rows[i].ind)
                } else { a += scalar(rest); i++ }
            }
            return a
        }
        block = { if (i >= rows.size) null else if (item(rows[i].s)) list(rows[i].ind) else map(rows[i].ind) }
        return block()
    }

    private fun stamp(ms0: Long): String {
        val ms = ms0.coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%05.2f", ms / 60000, (ms % 60000) / 1000.0)
    }

    private fun clock(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

    /**
     * A song's lyrics as a .lrc, the way music players read them: the song named at the top, then
     * timed lines; word timing as Enhanced LRC ("<00:12.50>word"); untimed lyrics as they are.
     */
    fun lrcFile(t: TrackDto, doc: LyricsDoc, d: ShownLyrics): String {
        val head = "[ti:${t.title}]\n[ar:${t.artist}]\n[al:${t.album}]\n" +
            (if (t.durationMs > 0) "[length:${clock(t.durationMs)}]\n" else "") + "[re:${doc.source}, by Localhost 8787]\n"
        return when (d.kind) {
            ShownLyrics.Kind.WORD -> head + d.lines.joinToString("\n") { l ->
                if (l.words.isEmpty()) "[${stamp(l.t)}]${l.text}"
                else "[${stamp(l.t)}]" + l.words.joinToString("") { "<${stamp(it.t)}>${it.w}" } + "<${stamp(l.e)}>"
            } + "\n"
            ShownLyrics.Kind.LINE -> head + doc.lrc.replace(HEAD_TAGS, "").trim() + "\n"
            ShownLyrics.Kind.PLAIN -> doc.plain.trim() + "\n"
            else -> ""
        }
    }
}
