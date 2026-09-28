package dev.periy.bridge.server

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** One server-sent event. `name` maps to the SSE `event:` field; [to], when set, is the one computer it is for. */
data class BridgeEvent(val name: String, val data: String, val to: String? = null)

object EventBus {
    private val _events = MutableSharedFlow<BridgeEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        // A browser tab that has stalled must never be able to block a file transfer.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<BridgeEvent> = _events

    fun emit(name: String, data: String) {
        _events.tryEmit(BridgeEvent(name, data))
    }

    /** An event for one computer only, by its id in the device list. */
    fun emitTo(deviceId: String, name: String, data: String) {
        _events.tryEmit(BridgeEvent(name, data, deviceId))
    }
}

/**
 * The shared clipboard: one item at a time (text, a picture or any file), the same on the phone,
 * the page and the laptop helper, plus a history of recent items like a keyboard's clipboard.
 *
 * This is the app's own buffer, not the Android system clipboard. On API 29+ an app cannot read
 * the system clipboard unless it has focus, so the phone side feeds it from what it can see:
 * Localhost 8787 opening, the tile, the text-selection menu, the share sheet, and new screenshots.
 * Writing to the system clipboard is unrestricted, so whatever the laptop sends lands there,
 * even with Localhost 8787 closed (the server runs in its foreground service).
 */
class ClipboardStore(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("clipboard", Context.MODE_PRIVATE)

    /**
     * Pictures and files, current and in the history: each in a folder named by its version,
     * under its own name with the right extension. Other apps paste it through Localhost 8787's
     * FileProvider, which tells them its type from that extension: "12.bin" would read as
     * unknown data, and WhatsApp or Gboard would not take it as a picture.
     */
    private val dir = java.io.File(ctx.applicationContext.filesDir, "clip").apply { mkdirs() }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSer = kotlinx.serialization.builtins.ListSerializer(ClipMeta.serializer())

    private val _meta = MutableStateFlow(
        sp.getString(KEY_META, null)?.let { runCatching { json.decodeFromString<ClipMeta>(it) }.getOrNull() }
            ?.takeIf { it.kind == "text" || it.kind == "empty" || file(it) != null }
            ?: sp.getString(KEY, "").orEmpty().let { if (it.isEmpty()) ClipMeta() else ClipMeta("text", it, v = 1) }
    )

    /**
     * What is on the shared clipboard: text, a picture or a file, or nothing. The phone UI
     * watches this; the page and the laptop helper get the same thing as the "clip" event.
     */
    val meta: StateFlow<ClipMeta> = _meta

    private val _history = MutableStateFlow(
        sp.getString(KEY_HISTORY, null)?.let { runCatching { json.decodeFromString(listSer, it) }.getOrNull() }
            .orEmpty().filter { it.kind == "text" || file(it) != null }
    )

    /** Recent items, newest first, the current one included. */
    val history: StateFlow<List<ClipMeta>> = _history

    private val _flow = MutableStateFlow(_meta.value.text)

    /**
     * The text on the shared clipboard ("" when it holds a picture or a file), so the phone
     * UI updates the instant the PC sends something and vice versa.
     */
    val flow: StateFlow<String> = _flow

    val text: String get() = _flow.value

    /** The copied picture or file, when that is what the clipboard holds. */
    fun blob(): java.io.File? = file(_meta.value)

    /** A picture or file from the history. */
    fun historyBlob(v: Long): java.io.File? = _history.value.firstOrNull { it.v == v }?.let(::file)

    private fun file(m: ClipMeta): java.io.File? =
        if (m.kind == "image" || m.kind == "file") place(m.v, m.name, m.mime).takeIf { it.isFile } else null

    /** Where item [v] lives: its own folder, its own name, an extension that says what it is. */
    private fun place(v: Long, name: String, mime: String): java.io.File {
        var clean = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.').take(100).ifEmpty { "File" }
        val want = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        if (want != null && !clean.lowercase().endsWith(".$want") &&
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(clean.substringAfterLast('.', "").lowercase()) != mime
        ) clean = "$clean.$want"
        return java.io.File(java.io.File(dir, v.toString()), clean)
    }

    private fun nextV(): Long = maxOf(_meta.value.v, _history.value.maxOfOrNull { it.v } ?: 0) + 1

    /** Text; "" clears. Returns false when the text was rejected for size. */
    @Synchronized
    fun set(value: String, at: Long = now()): Boolean {
        if (value.length > MAX_CHARS) return false
        val m = _meta.value
        if (value.isEmpty()) {
            if (m.kind != "empty") publish(ClipMeta(v = nextV(), at = at))
            return true
        }
        if (m.kind == "text" && m.text == value) return true
        publish(ClipMeta("text", value, v = nextV(), at = at))
        return true
    }

    /**
     * A picture or any other file, read from [input] up to [MAX_BYTES]. Returns the new state,
     * or null when it was too big (nothing changes then).
     */
    @Synchronized
    fun setBlob(name: String, mime: String, input: java.io.InputStream, at: Long = now()): ClipMeta? {
        val v = nextV()
        val kind = if (mime.startsWith("image/")) "image" else "file"
        val clean = name.substringAfterLast('/').substringAfterLast('\\').take(120).ifBlank { if (kind == "image") "Picture" else "File" }
        val type = mime.ifBlank { "application/octet-stream" }
        val dest = place(v, clean, type)
        dest.parentFile?.mkdirs()
        val tmp = java.io.File(dest.parentFile, ".part")
        var size = 0L
        tmp.outputStream().use { out ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                size += n
                if (size > MAX_BYTES) { out.close(); tmp.parentFile?.deleteRecursively(); return null }
                out.write(buf, 0, n)
            }
        }
        if (!tmp.renameTo(dest)) { tmp.delete(); return null }
        return ClipMeta(kind, name = clean, mime = type, size = size, v = v, at = at).also { publish(it) }
    }

    /** Puts an item from the history back on the clipboard, as the newest. Null if it is gone. */
    @Synchronized
    fun reuse(v: Long): ClipMeta? {
        val old = _history.value.firstOrNull { it.v == v } ?: return null
        if (old.kind == "text") { set(old.text); return _meta.value }
        if (file(old) == null) return null
        val nv = nextV()
        if (!java.io.File(dir, v.toString()).renameTo(java.io.File(dir, nv.toString()))) return null
        _history.value = _history.value.filter { it.v != v }
        return old.copy(v = nv, at = now()).also { publish(it) }
    }

    /** Removes one item from the history (and from the clipboard, if it is the current one). */
    @Synchronized
    fun forget(v: Long) {
        if (_meta.value.v == v) publish(ClipMeta(v = nextV(), at = now()), record = false)
        saveHistory(_history.value.filter { it.v != v })
    }

    /** Empties the history and the clipboard with it: the one way to clear it, everywhere. */
    @Synchronized
    fun forgetAll() {
        if (_meta.value.kind != "empty") publish(ClipMeta(v = nextV(), at = now()), record = false)
        saveHistory(emptyList())
    }

    private fun publish(m: ClipMeta, record: Boolean = true) {
        _meta.value = m
        _flow.value = m.text
        sp.edit { putString(KEY_META, json.encodeToString(ClipMeta.serializer(), m)); putString(KEY, m.text) }
        // Newest first; the same text copied again moves up instead of appearing twice.
        if (record && m.kind != "empty") {
            saveHistory(listOf(m) + _history.value.filter {
                it.v != m.v && !(m.kind == "text" && it.kind == "text" && it.text == m.text)
            })
        }
        // "clipboard" carries the text, for laptop helpers older than pictures; "clip" says everything.
        EventBus.emit("clipboard", m.text)
        EventBus.emit("clip", json.encodeToString(ClipMeta.serializer(), m))
    }

    /** Keeps the history within [HISTORY_MAX] items and [HISTORY_BYTES] of pictures and files. */
    private fun saveHistory(list: List<ClipMeta>) {
        var bytes = 0L
        val kept = list.take(HISTORY_MAX).filter { m ->
            if (m.kind == "text" || m.v == _meta.value.v) return@filter true
            bytes += m.size
            bytes <= HISTORY_BYTES
        }
        _history.value = kept
        sp.edit { putString(KEY_HISTORY, json.encodeToString(listSer, kept)) }
        // Only files still in the history, or on the clipboard, are kept.
        val keep = kept.map { it.v.toString() }.toSet() + _meta.value.v.toString()
        dir.listFiles()?.forEach { if (it.name !in keep && it.name.toLongOrNull()?.let { v -> v < nextV() } != false) it.deleteRecursively() }
        EventBus.emit("cliphistory", historyJson())
    }

    fun metaJson(): String = json.encodeToString(ClipMeta.serializer(), _meta.value)

    fun historyJson(): String = json.encodeToString(listSer, _history.value)

    private fun now() = System.currentTimeMillis()

    companion object {
        /**
         * 256k characters. Large enough for any realistic paste, small enough that it
         * cannot be used to push the app into an OOM through the text lane -- files have
         * their own path, and it streams.
         */
        const val MAX_CHARS = 256 * 1024
        /** A copied picture or file: 50 MB, plenty for a screenshot, a photo or a document. */
        const val MAX_BYTES = 50L * 1024 * 1024
        /** The history: the last 30 items, and at most 300 MB of pictures and files among them. */
        const val HISTORY_MAX = 30
        const val HISTORY_BYTES = 300L * 1024 * 1024
        private const val KEY = "text"
        private const val KEY_META = "meta"
        private const val KEY_HISTORY = "history"
    }
}

/**
 * What the shared clipboard holds. [kind] is "empty", "text", "image" or "file"; [v] goes up
 * with every change, so each side can tell a new copy from one it has already seen; [at] is
 * when it was copied.
 */
@kotlinx.serialization.Serializable
data class ClipMeta(
    val kind: String = "empty",
    val text: String = "",
    val name: String = "",
    val mime: String = "",
    val size: Long = 0,
    val v: Long = 0,
    val at: Long = 0,
)
