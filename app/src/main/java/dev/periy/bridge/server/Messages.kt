package dev.periy.bridge.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
 * One message in a conversation with a linked phone. Mine are "waiting" (kept here until the
 * other phone can be reached), "sent" (it has it) or "read"; theirs are "new" or "read".
 */
@Serializable
data class ChatMsg(val id: String, val mine: Boolean, val text: String, val at: Long, val state: String = "")

/** A message as it travels: [c] is the text sealed with the two phones' key (see [MsgCrypto]). */
@Serializable
data class MsgWire(val id: String, val at: Long, val n: String, val c: String)

/** The other phone has read everything of this one's up to [upTo] (this phone's own clock). */
@Serializable
data class MsgRead(val upTo: Long)

/**
 * Messages between linked phones, with nothing in between: each phone is the other's server.
 *
 * A message is kept on the phone that wrote it until the other phone can be reached (on the same
 * Wi-Fi, through its tunnel, or punched across IPv4, as the rest of phone to phone), then posted
 * straight to it. The text is sealed with a key only the two phones hold: the tunnel key the
 * receiving phone made for the sending one when they linked, so a message read off the Wi-Fi, or
 * anywhere on the way, is noise. Stored in this app's own files, which Android encrypts.
 */
class Messages(
    ctx: Context,
    private val peers: PeerManager,
    /** This phone's tunnel key for the linked phone that came in on [deviceId]. */
    private val keyHere: (deviceId: String) -> ByteArray,
) {
    private val app = ctx.applicationContext
    private val file = File(app.filesDir, "messages.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushing = ConcurrentHashMap.newKeySet<String>()

    private val _threads = MutableStateFlow(load())
    /** Every conversation, by the linked phone's name, oldest message first. */
    val threads: StateFlow<Map<String, List<ChatMsg>>> = _threads.asStateFlow()

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

    fun thread(name: String): List<ChatMsg> = _threads.value[name].orEmpty()

    fun unread(name: String): Int = thread(name).count { !it.mine && it.state == "new" }

    /** Writes [text] to [name]; it goes now if that phone can be reached, or waits here until it can. */
    fun send(name: String, text: String) {
        val t = text.trim().take(MAX_CHARS)
        if (t.isEmpty()) return
        change(name) { it + ChatMsg(UUID.randomUUID().toString(), true, t, System.currentTimeMillis(), "waiting") }
        flush(name)
    }

    fun retryAll() = _threads.value.filterValues { l -> l.any { it.mine && it.state == "waiting" } }.keys.forEach(::flush)

    /** Sends what is waiting for [name], in order, stopping at the first that does not get there. */
    private fun flush(name: String) {
        if (!flushing.add(name)) return
        scope.launch {
            try {
                val peer = peers.find(name) ?: return@launch
                val key = peers.messageKey(peer) ?: return@launch
                for (m in thread(name).filter { it.mine && it.state == "waiting" }) {
                    val body = json.encodeToString(MsgCrypto.seal(key, m)).toByteArray()
                    if (!peers.deliver(peer, "/api/peers/msg", body)) break
                    setState(name, m.id, "sent")
                }
            } finally {
                flushing.remove(name)
            }
        }
    }

    /** A message from the linked phone [from], which came in on [deviceId]. False when it does not open with that phone's key. */
    fun receive(from: String, deviceId: String, w: MsgWire): Boolean {
        val text = MsgCrypto.open(keyHere(deviceId), w) ?: return false
        if (thread(from).any { it.id == w.id && !it.mine }) return true
        val seen = open == from
        change(from) { (it + ChatMsg(w.id, false, text, w.at, if (seen) "read" else "new")).sortedBy { m -> m.at } }
        if (seen) sendRead(from) else notify(from, text)
        return true
    }

    /** Everything from [name] is read here: marked so, and the other phone told. */
    fun markRead(name: String) {
        if (thread(name).none { !it.mine && it.state == "new" }) return
        change(name) { l -> l.map { if (!it.mine && it.state == "new") it.copy(state = "read") else it } }
        app.getSystemService(NotificationManager::class.java)?.cancel(notificationId(name))
        sendRead(name)
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

    private fun setState(name: String, id: String, state: String) =
        change(name) { l -> l.map { if (it.id == id && it.state == "waiting") it.copy(state = state) else it } }

    @Synchronized
    private fun change(name: String, f: (List<ChatMsg>) -> List<ChatMsg>) {
        _threads.value = _threads.value + (name to f(thread(name)).takeLast(KEEP))
        save()
        EventBus.emit("messages", name)
    }

    private fun load(): Map<String, List<ChatMsg>> = runCatching {
        if (file.exists()) json.decodeFromString<Map<String, List<ChatMsg>>>(file.readText()) else emptyMap()
    }.getOrDefault(emptyMap())

    private fun save() = runCatching {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(_threads.value))
        tmp.renameTo(file)
    }

    // ------------------------------------------------------------------ notification

    private fun notify(from: String, text: String) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Messages from your linked phones." },
        )
        val open = PendingIntent.getActivity(
            app, notificationId(from),
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_CHAT, from),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val waiting = unread(from)
        val n = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(from)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setNumber(waiting)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(notificationId(from), n) }
    }

    private fun notificationId(name: String) = 7000 + (name.hashCode() and 0xFFF)

    companion object {
        const val CHANNEL_ID = "messages"
        const val MAX_CHARS = 8_000
        /** The newest this many messages are kept in each conversation. */
        private const val KEEP = 5_000
        private const val RETRY_MS = 20_000L
    }
}

/**
 * A message's text sealed for the other phone: AES-256-GCM under a key drawn from the tunnel key
 * the receiving phone made for the sending one, a fresh 12-byte nonce each time, and the
 * message's id and time bound in, so neither can be swapped onto another text.
 */
object MsgCrypto {
    private val L_MSG = "L87M/1 msg".toByteArray()
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

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}
