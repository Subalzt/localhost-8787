package dev.periy.bridge.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.app.NotificationCompat
import dev.periy.bridge.R
import dev.periy.bridge.net.TunnelCrypto
import dev.periy.bridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One message in a conversation. Mine are "waiting" (kept here until every phone it is for has
 * it), "sent" or "read"; theirs are "new" or "read". Besides text: an image, a voice note or a
 * file ([kind]), kept in this app's files ([file], empty until it has arrived); in a group, who
 * wrote it ([from]); an "event" is a line like "made the group".
 */
@Serializable
data class ChatMsg(
    val id: String,
    val mine: Boolean,
    val text: String,
    val at: Long,
    val state: String = "",
    val kind: String = "text",
    val file: String = "",
    val name: String = "",
    val size: Long = 0,
    val mime: String = "",
    val durationMs: Long = 0,
    val w: Int = 0,
    val h: Int = 0,
    val from: String = "",
    /** The linked phones it still has to reach: its own for mine, the others' as a group's host passing it on. */
    val pending: List<String> = emptyList(),
)

/** A group conversation: its [host] keeps it and passes each message on ("" when that is this phone). */
@Serializable
data class ChatGroup(val id: String, val name: String, val host: String, val members: List<String>, val at: Long = 0)

/** A message as it travels: [c] is what it says, sealed with the two phones' key (see [MsgCrypto]); [v] 2 is a [MsgBody]. */
@Serializable
data class MsgWire(val id: String, val at: Long, val n: String, val c: String, val v: Int = 1)

/** What a message says, beyond plain text: its kind and attachment, and its group. */
@Serializable
data class MsgBody(
    val text: String = "",
    val kind: String = "text",
    val name: String = "",
    val size: Long = 0,
    val mime: String = "",
    val durationMs: Long = 0,
    val w: Int = 0,
    val h: Int = 0,
    val group: String = "",
    val gname: String = "",
    val gmembers: List<String> = emptyList(),
    val from: String = "",
)

/** The other phone has read everything of this one's up to [upTo] (this phone's own clock). */
@Serializable
data class MsgRead(val upTo: Long)

/**
 * Messages between linked phones, with nothing in between: each phone is the others' server.
 *
 * A message is kept on the phone that wrote it until the phone it is for can be reached (on the
 * same Wi-Fi, through its tunnel, or punched across IPv4, as the rest of phone to phone), then
 * posted straight to it, sealed with a key only the two phones hold. A photo, a voice note or a
 * file follows its message, sealed the same way. A group is kept by the phone that made it, which
 * passes each message on to the others, so only it needs to be linked with everyone. Stored in
 * this app's own files, which Android encrypts.
 */
class Messages(
    ctx: Context,
    private val peers: PeerManager,
    /** This phone's tunnel key for the linked phone that came in on [deviceId]. */
    private val keyHere: (deviceId: String) -> ByteArray,
) {
    private val app = ctx.applicationContext
    private val file = File(app.filesDir, "messages.json")
    private val groupsFile = File(app.filesDir, "groups.json")
    private val dir = File(app.filesDir, "chat").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushing = ConcurrentHashMap.newKeySet<String>()

    private val _threads = MutableStateFlow(load())
    /** Every conversation: a linked phone's name, or "group:" and the group's id; oldest message first. */
    val threads: StateFlow<Map<String, List<ChatMsg>>> = _threads.asStateFlow()

    private val _groups = MutableStateFlow(loadGroups())
    val groups: StateFlow<Map<String, ChatGroup>> = _groups.asStateFlow()

    /** The conversation open on the screen now: what arrives in it is read at once. */
    @Volatile
    var open: String? = null
        set(v) { field = v; if (v != null) markRead(v) }

    init {
        // Whatever is still waiting goes as soon as its phone answers again.
        scope.launch {
            while (true) {
                delay(RETRY_MS)
                retryAll()
            }
        }
    }

    fun thread(key: String): List<ChatMsg> = _threads.value[key].orEmpty()

    fun unread(key: String): Int = thread(key).count { !it.mine && it.state == "new" }

    fun group(key: String): ChatGroup? = if (key.startsWith(GROUP)) _groups.value[key.removePrefix(GROUP)] else null

    /** Who a conversation is with, as the screen shows it: the phone's name, or the group's. */
    fun title(key: String): String = group(key)?.name ?: key

    /** The phones a conversation's messages go to from this phone (never itself). */
    private fun recipients(key: String): List<String> {
        val g = group(key) ?: return listOf(key)
        return if (g.host.isEmpty()) g.members - peers.deviceName() else listOf(g.host)
    }

    /**
     * Everyone else in group [g], for the screen and its calls: its members and the phone that keeps
     * it, once each, without this phone (the phone keeping it lists this one among its members).
     */
    fun others(g: ChatGroup): List<String> = ((if (g.host.isEmpty()) g.members else g.members + g.host).distinct() - peers.deviceName())

    // ------------------------------------------------------------------ writing

    /** Writes [text] in conversation [key]; it goes now if its phones can be reached, or waits here until they can. */
    fun send(key: String, text: String) {
        val t = text.trim().take(MAX_CHARS)
        if (t.isEmpty()) return
        change(key) { it + ChatMsg(UUID.randomUUID().toString(), true, t, System.currentTimeMillis(), "waiting", pending = recipients(key)) }
        flush(key)
    }

    /**
     * A photo, a voice note or a file from [uri] into conversation [key]: copied into this app's
     * files first (a photo made smaller: 2048 across at most), then sent like a message.
     */
    fun sendFile(key: String, uri: Uri, kind: String, durationMs: Long = 0, caption: String = "") {
        scope.launch {
            val cr = app.contentResolver
            val (name, mime) = cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }.let { (it ?: "file") to (cr.getType(uri) ?: PhoneFiles.mimeOf(it ?: "")) }
            keep(key, kind, name, mime, durationMs, caption, { cr.openInputStream(uri) }, { rotateByExif(uri, it) })
        }
    }

    /**
     * A photo, a voice note or a file a laptop's page sent, in [tmp] (this app's cache, gone after):
     * kept and sent as [sendFile] does. A voice note keeps the browser's own format (Opus).
     */
    fun sendUpload(key: String, tmp: File, name: String, mime: String, kind: String, durationMs: Long = 0) {
        scope.launch {
            try {
                keep(key, kind, name, mime, durationMs, "", { tmp.inputStream() }) { bmp ->
                    runCatching {
                        val deg = androidx.exifinterface.media.ExifInterface(tmp.path).rotationDegrees
                        if (deg == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)
                    }.getOrDefault(bmp)
                }
            } finally { tmp.delete() }
        }
    }

    /** An attachment into this app's files (a photo made smaller: 2048 across at most), then sent like a message. */
    private fun keep(key: String, kind: String, name: String, mime: String, durationMs: Long, caption: String, open: () -> java.io.InputStream?, turn: (Bitmap) -> Bitmap) {
        val id = UUID.randomUUID().toString()
        val out: File
        var w = 0; var h = 0
        if (kind == "image") {
            out = File(dir, "$id.jpg")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
            val bmp = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return
            val turned = turn(bmp)
            val scale = minOf(1f, 2048f / maxOf(turned.width, turned.height))
            val fin = if (scale < 1f) Bitmap.createScaledBitmap(turned, (turned.width * scale).toInt(), (turned.height * scale).toInt(), true) else turned
            out.outputStream().use { fin.compress(Bitmap.CompressFormat.JPEG, 86, it) }
            w = fin.width; h = fin.height
        } else {
            out = File(dir, id + "." + (name.substringAfterLast('.', "bin").take(8)))
            open()?.use { i -> out.outputStream().use { i.copyTo(it) } } ?: return
        }
        if (out.length() > MAX_FILE) { out.delete(); return }
        change(key) {
            it + ChatMsg(
                id, true, caption.trim(), System.currentTimeMillis(), "waiting", kind = kind, file = out.path,
                name = if (kind == "image") "$id.jpg" else name, size = out.length(), mime = if (kind == "image") "image/jpeg" else mime,
                durationMs = durationMs, w = w, h = h, pending = recipients(key),
            )
        }
        flush(key)
    }

    /** A voice note recorded into [f] (this app's files), [durationMs] long. */
    fun sendVoice(key: String, f: File, durationMs: Long) {
        val id = UUID.randomUUID().toString()
        val kept = File(dir, "$id.m4a")
        if (!f.renameTo(kept)) { f.copyTo(kept, true); f.delete() }
        change(key) {
            it + ChatMsg(id, true, "", System.currentTimeMillis(), "waiting", kind = "voice", file = kept.path, name = kept.name,
                size = kept.length(), mime = "audio/mp4", durationMs = durationMs, pending = recipients(key))
        }
        flush(key)
    }

    private fun rotateByExif(uri: Uri, bmp: Bitmap): Bitmap = runCatching {
        val deg = app.contentResolver.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees } ?: 0
        if (deg == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)
    }.getOrDefault(bmp)

    /** A new group with [members] (linked phones), kept on this phone; they hear of it with its first line. */
    fun createGroup(name: String, members: List<String>): String {
        val people = members.distinct() - peers.deviceName()
        val g = ChatGroup(UUID.randomUUID().toString(), name.trim().ifEmpty { people.joinToString(", ") }.take(60), "", people, System.currentTimeMillis())
        _groups.value = _groups.value + (g.id to g)
        saveGroups()
        val key = GROUP + g.id
        change(key) { it + ChatMsg(UUID.randomUUID().toString(), true, "made the group", System.currentTimeMillis(), "waiting", kind = "event", pending = g.members) }
        flush(key)
        return key
    }

    fun retryAll() = _threads.value.filterValues { l -> l.any { it.pending.isNotEmpty() } }.keys.forEach(::flush)

    // ------------------------------------------------------------------ sending

    /** Sends what is waiting in [key] to each of its phones, in order; a phone that does not answer is tried again later. */
    private fun flush(key: String) {
        if (!flushing.add(key)) return
        scope.launch {
            try {
                val down = HashSet<String>()
                for (m in thread(key).filter { it.pending.isNotEmpty() }) {
                    // An attachment passed on goes once it has arrived here.
                    if (m.kind in FILE_KINDS && (m.file.isEmpty() || !File(m.file).exists())) continue
                    val done = m.pending.filter { r -> r !in down && deliver(key, m, r).also { ok -> if (!ok) down += r } }
                    if (done.isNotEmpty()) change(key) { l ->
                        l.map { if (it.id == m.id) it.copy(pending = it.pending - done.toSet(), state = if (it.mine && (it.pending - done.toSet()).isEmpty() && it.state == "waiting") "sent" else it.state) else it }
                    }
                }
            } finally {
                flushing.remove(key)
            }
        }
    }

    /** One message (and its attachment) to one phone [to]. */
    private fun deliver(key: String, m: ChatMsg, to: String): Boolean {
        val peer = peers.find(to) ?: return false
        val k = peers.messageKey(peer) ?: return false
        val g = group(key)
        val wire = if (g == null && m.kind == "text") MsgCrypto.seal(k, m)
        else MsgCrypto.sealBody(
            k, m.id, m.at,
            MsgBody(
                m.text, m.kind, m.name, m.size, m.mime, m.durationMs, m.w, m.h,
                group = g?.id.orEmpty(), gname = g?.name.orEmpty(),
                gmembers = g?.let { if (it.host.isEmpty()) it.members else it.members }.orEmpty(),
                from = if (m.mine) "" else m.from,
            ),
        )
        if (!peers.deliver(peer, "/api/peers/msg", json.encodeToString(wire).toByteArray())) return false
        if (m.kind in FILE_KINDS) {
            val bytes = runCatching { File(m.file).readBytes() }.getOrNull() ?: return false
            if (!peers.deliverBytes(peer, "/api/peers/msg/blob?id=" + m.id, MsgCrypto.sealBlob(k, m.id, bytes))) return false
        }
        return true
    }

    // ------------------------------------------------------------------ receiving

    /** A message from the linked phone [from], which came in on [deviceId]. False when it does not open with that phone's key. */
    fun receive(from: String, deviceId: String, w: MsgWire): Boolean {
        val key0 = keyHere(deviceId)
        val body = if (w.v >= 2) MsgCrypto.openBody(key0, w)?.let { runCatching { json.decodeFromString<MsgBody>(it) }.getOrNull() } ?: return false
        else MsgBody(text = MsgCrypto.open(key0, w) ?: return false)
        val key = if (body.group.isNotEmpty()) GROUP + body.group else from
        var relayTo = emptyList<String>()
        if (body.group.isNotEmpty()) {
            val known = _groups.value[body.group]
            when {
                known == null -> {
                    // A group this phone hears of for the first time: its host is the phone it came from.
                    _groups.value = _groups.value + (body.group to ChatGroup(body.group, body.gname.ifEmpty { "Group" }, from, body.gmembers, w.at))
                    saveGroups()
                }
                known.host.isEmpty() -> {
                    // This phone keeps the group: the others get it too.
                    if (from !in known.members) return true
                    relayTo = known.members - from
                }
                known.host == from && (known.name != body.gname || known.members != body.gmembers) && body.gname.isNotEmpty() -> {
                    _groups.value = _groups.value + (body.group to known.copy(name = body.gname, members = body.gmembers))
                    saveGroups()
                }
            }
        }
        if (thread(key).any { it.id == w.id }) return true
        val author = if (body.group.isNotEmpty()) body.from.ifEmpty { from } else ""
        val seen = open == key
        change(key) {
            (it + ChatMsg(
                w.id, false, body.text, w.at, if (seen) "read" else "new", kind = body.kind, name = body.name, size = body.size,
                mime = body.mime, durationMs = body.durationMs, w = body.w, h = body.h, from = author, pending = relayTo,
            )).sortedBy { m -> m.at }
        }
        if (seen && body.group.isEmpty()) sendRead(key) else if (!seen && body.kind != "event") notify(key, author, body)
        if (relayTo.isNotEmpty()) flush(key)
        return true
    }

    /** A message's attachment: kept in this app's files, and passed on when this phone keeps its group. */
    fun receiveBlob(deviceId: String, id: String, sealed: ByteArray): Boolean {
        val bytes = MsgCrypto.openBlob(keyHere(deviceId), id, sealed) ?: return false
        val (key, m) = _threads.value.entries.firstNotNullOfOrNull { (k, l) -> l.firstOrNull { it.id == id && !it.mine }?.let { k to it } } ?: return false
        if (m.file.isNotEmpty() && File(m.file).exists()) return true
        val ext = if (m.kind == "image") "jpg" else if (m.kind == "voice") "m4a" else m.name.substringAfterLast('.', "bin").take(8)
        val f = File(dir, "$id.$ext")
        f.writeBytes(bytes)
        change(key) { l -> l.map { if (it.id == id) it.copy(file = f.path) else it } }
        if (m.pending.isNotEmpty()) flush(key)
        return true
    }

    /** Everything in [key] is read here: marked so, and (one to one) the other phone told. */
    fun markRead(key: String) {
        if (thread(key).none { !it.mine && it.state == "new" }) return
        change(key) { l -> l.map { if (!it.mine && it.state == "new") it.copy(state = "read") else it } }
        app.getSystemService(NotificationManager::class.java)?.cancel(notificationId(key))
        if (group(key) == null) sendRead(key)
    }

    private fun sendRead(name: String) {
        val upTo = thread(name).filter { !it.mine }.maxOfOrNull { it.at } ?: return
        scope.launch {
            val peer = peers.find(name) ?: return@launch
            peers.deliver(peer, "/api/peers/msg/read", json.encodeToString(MsgRead(upTo)).toByteArray())
        }
    }

    /** [from] has read this phone's messages up to [upTo]. */
    fun readBy(from: String, upTo: Long) {
        change(from) { l -> l.map { if (it.mine && it.state == "sent" && it.at <= upTo) it.copy(state = "read") else it } }
    }

    @Synchronized
    private fun change(key: String, f: (List<ChatMsg>) -> List<ChatMsg>) {
        _threads.value = _threads.value + (key to f(thread(key)).takeLast(KEEP))
        save()
        EventBus.emit("messages", key)
    }

    private fun load(): Map<String, List<ChatMsg>> = runCatching {
        if (!file.exists()) return@runCatching emptyMap()
        val m = json.decodeFromString<Map<String, List<ChatMsg>>>(file.readText())
        // From before groups: what was waiting is waiting for its own phone.
        m.mapValues { (k, l) -> l.map { if (it.mine && it.state == "waiting" && it.pending.isEmpty() && !k.startsWith(GROUP)) it.copy(pending = listOf(k)) else it } }
    }.getOrDefault(emptyMap())

    private fun save() = runCatching {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(_threads.value))
        tmp.renameTo(file)
    }

    private fun loadGroups(): Map<String, ChatGroup> = runCatching {
        if (groupsFile.exists()) json.decodeFromString<Map<String, ChatGroup>>(groupsFile.readText()) else emptyMap()
    }.getOrDefault(emptyMap())

    private fun saveGroups() = runCatching { groupsFile.writeText(json.encodeToString(_groups.value)) }

    // ------------------------------------------------------------------ notification

    private fun notify(key: String, author: String, body: MsgBody) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Messages from your linked phones." },
        )
        val open = PendingIntent.getActivity(
            app, notificationId(key),
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_CHAT, key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val what = when (body.kind) {
            "image" -> "Photo" + (if (body.text.isNotEmpty()) ": " + body.text else "")
            "voice" -> "Voice note"
            "file" -> "File: " + body.name
            else -> body.text
        }
        val line = if (author.isNotEmpty()) "$author: $what" else what
        val n = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title(key))
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setNumber(unread(key))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(notificationId(key), n) }
    }

    private fun notificationId(name: String) = 7000 + (name.hashCode() and 0xFFF)

    companion object {
        const val CHANNEL_ID = "messages"
        const val GROUP = "group:"
        const val MAX_CHARS = 8_000
        /** An attachment's largest size: it is sealed whole in memory. */
        const val MAX_FILE = 40L * 1024 * 1024
        val FILE_KINDS = setOf("image", "voice", "file")
        /** The newest this many messages are kept in each conversation. */
        private const val KEEP = 5_000
        private const val RETRY_MS = 20_000L
    }
}

/**
 * A message sealed for the other phone: AES-256-GCM under a key drawn from the tunnel key the
 * receiving phone made for the sending one, a fresh 12-byte nonce each time, and the message's id
 * and time bound in, so neither can be swapped onto another text. Attachments are sealed the same
 * way under their own key, the message's id bound in.
 */
object MsgCrypto {
    private val L_MSG = "L87M/1 msg".toByteArray()
    private val L_BLOB = "L87M/1 blob".toByteArray()
    private val rng = SecureRandom()

    private fun cipher(mode: Int, psk: ByteArray, nonce: ByteArray, id: String, at: Long): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(TunnelCrypto.hmac(psk, L_MSG), "AES"), GCMParameterSpec(128, nonce))
            updateAAD("$id/$at".toByteArray())
        }

    fun seal(psk: ByteArray, m: ChatMsg): MsgWire {
        val nonce = ByteArray(12).also(rng::nextBytes)
        val ct = cipher(Cipher.ENCRYPT_MODE, psk, nonce, m.id, m.at).doFinal(m.text.toByteArray())
        return MsgWire(m.id, m.at, b64(nonce), b64(ct))
    }

    fun open(psk: ByteArray, w: MsgWire): String? = runCatching {
        val nonce = Base64.decode(w.n, Base64.NO_WRAP)
        String(cipher(Cipher.DECRYPT_MODE, psk, nonce, w.id, w.at).doFinal(Base64.decode(w.c, Base64.NO_WRAP)))
    }.getOrNull()

    private val bodyJson = Json { encodeDefaults = false }

    fun sealBody(psk: ByteArray, id: String, at: Long, body: MsgBody): MsgWire {
        val nonce = ByteArray(12).also(rng::nextBytes)
        val ct = cipher(Cipher.ENCRYPT_MODE, psk, nonce, id, at).doFinal(bodyJson.encodeToString(body).toByteArray())
        return MsgWire(id, at, b64(nonce), b64(ct), v = 2)
    }

    fun openBody(psk: ByteArray, w: MsgWire): String? = open(psk, w)

    fun sealBlob(psk: ByteArray, id: String, data: ByteArray): ByteArray {
        val nonce = ByteArray(12).also(rng::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(TunnelCrypto.hmac(psk, L_BLOB), "AES"), GCMParameterSpec(128, nonce))
            updateAAD(id.toByteArray())
        }
        return nonce + c.doFinal(data)
    }

    fun openBlob(psk: ByteArray, id: String, sealed: ByteArray): ByteArray? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(TunnelCrypto.hmac(psk, L_BLOB), "AES"), GCMParameterSpec(128, sealed.copyOf(12)))
            updateAAD(id.toByteArray())
        }
        c.doFinal(sealed, 12, sealed.size - 12)
    }.getOrNull()

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}
