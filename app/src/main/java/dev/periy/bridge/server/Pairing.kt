package dev.periy.bridge.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.periy.bridge.R
import dev.periy.bridge.service.BridgeService
import dev.periy.bridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/** A computer that has been allowed in. */
@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    val pairedAt: Long,
    val lastSeenAt: Long,
    val lastIp: String,
)

/** A linked phone in the device list: this, then its name. */
const val PHONE_PREFIX = "Phone: "

/** The laptop helper in the device list: this, then its machine's name. */
const val HELPER_PREFIX = "Laptop control on "

/**
 * A device as a person knows it, by machine, never by browser: a phone or a laptop helper by its
 * own name; a page by its machine's name when that machine's helper is paired from the same
 * address (or the page came through the helper's USB debugging) and the page is on Windows too,
 * as the helper is; else by its system alone ("Linux"
 * for "Firefox on Linux"). A browser in WSL or a virtual machine reaches the phone from the same
 * address as Windows but is another system, so it is "Linux", not the Windows machine.
 */
fun shownName(d: PairedDevice, all: Collection<PairedDevice>): String {
    if (d.name.startsWith(PHONE_PREFIX)) return d.name.removePrefix(PHONE_PREFIX)
    if (d.name.startsWith(HELPER_PREFIX)) return d.name.removePrefix(HELPER_PREFIX)
    val system = d.name.substringAfterLast(" on ", d.name)
    if (system != "Windows") return system
    // A page that came in over the cable's USB debugging arrives from the phone's own loopback,
    // through the helper's adb forward: it is on the helper's machine.
    val helpers = all.filter { it.name.startsWith(HELPER_PREFIX) }
    val machine = helpers.firstOrNull { it.lastIp == d.lastIp }
        ?: helpers.takeIf { d.lastIp.startsWith("127.") }?.maxByOrNull { it.lastSeenAt }
    return machine?.name?.removePrefix(HELPER_PREFIX) ?: system
}

/**
 * Who the shared clipboard reaches, for its "Shared with" line: every computer with the page or
 * the helper open right now, once each by [shownName], then [phones]. Not [except]'s own machine,
 * and not a browser on a phone (most likely the phone's own).
 */
fun clipboardReach(all: List<PairedDevice>, live: Map<String, Int>, except: PairedDevice?, phones: List<String>): List<String> {
    val mine = except?.let { shownName(it, all) }
    val computers = all.asSequence()
        .filter { (live[it.id] ?: 0) > 0 && !it.name.startsWith(PHONE_PREFIX) && !it.name.contains("Android", ignoreCase = true) }
        .map { shownName(it, all) }
        .filter { it != mine }
        .distinct()
        .toList()
    return computers + phones.filter { it !in computers }
}

/**
 * Every computer this phone has approved.
 *
 * This list *is* the access control. A session cookie only proves which device it was
 * issued to; whether that device is still welcome is decided here, on every request. So
 * removing an entry locks that computer out immediately, including any page it has open.
 */
class DeviceRegistry(ctx: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(ctx.applicationContext.filesDir, "devices.json")
    private val lock = Any()

    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<List<PairedDevice>> = _devices

    /** Open live connections per device, so the phone can show who is here right now. */
    private val _live = MutableStateFlow<Map<String, Int>>(emptyMap())
    val live: StateFlow<Map<String, Int>> = _live

    private var lastPersistAt = 0L

    fun get(id: String): PairedDevice? = _devices.value.firstOrNull { it.id == id }

    fun add(name: String, ip: String): PairedDevice = synchronized(lock) {
        val now = System.currentTimeMillis()
        val device = PairedDevice(
            id = UUID.randomUUID().toString().replace("-", ""),
            name = name,
            pairedAt = now,
            lastSeenAt = now,
            lastIp = ip,
        )
        _devices.value = listOf(device) + _devices.value
        persist()
        device
    }

    /**
     * Records activity. Called on every authenticated request, so the in-memory copy is
     * updated every time but the file at most once a minute -- otherwise a four-stream
     * transfer would be rewriting this file hundreds of times a second.
     */
    fun touch(id: String, ip: String) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val cur = _devices.value.firstOrNull { it.id == id } ?: return@synchronized
        if (now - cur.lastSeenAt < 5_000 && cur.lastIp == ip) return@synchronized
        _devices.value = _devices.value.map { if (it.id == id) it.copy(lastSeenAt = now, lastIp = ip) else it }
        if (now - lastPersistAt > 60_000) persist()
    }

    fun remove(id: String) = synchronized(lock) {
        _devices.value = _devices.value.filterNot { it.id == id }
        persist()
    }

    fun clear() = synchronized(lock) {
        _devices.value = emptyList()
        _live.value = emptyMap()
        persist()
    }

    fun connected(id: String) = synchronized(lock) {
        _live.value = _live.value + (id to ((_live.value[id] ?: 0) + 1))
        // Pages list the computers they can send to; one just opened.
        EventBus.emit("targets", "")
    }

    fun disconnected(id: String) = synchronized(lock) {
        val n = (_live.value[id] ?: 1) - 1
        _live.value = if (n <= 0) _live.value - id else _live.value + (id to n)
        EventBus.emit("targets", "")
    }

    private fun persist() {
        lastPersistAt = System.currentTimeMillis()
        runCatching {
            val tmp = File(file.parentFile, "devices.json.tmp")
            tmp.writeText(json.encodeToString(_devices.value))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private fun load(): List<PairedDevice> = runCatching {
        if (!file.exists()) emptyList() else json.decodeFromString<List<PairedDevice>>(file.readText())
    }.getOrDefault(emptyList())
}

/** A computer asking to be let in, waiting for a tap on the phone. */
data class PairRequest(
    val id: String,
    val name: String,
    val ip: String,
    /** Shown on both screens, so you can tell your request from someone else's. */
    val code: String,
    val createdAt: Long,
    val state: State,
    val deviceId: String? = null,
) {
    enum class State { PENDING, APPROVED, DENIED, EXPIRED }
}

/**
 * Approve-on-phone pairing, instead of a PIN.
 *
 * The address is not a secret -- it is a local IP and a fixed port, and anything on the
 * same Wi-Fi can find it in seconds. So something has to stand in the way. A PIN does,
 * but it makes you read six digits off one screen and type them into another. Here the
 * computer simply asks, the phone shows who is asking, and you tap Allow.
 *
 * Two things keep that from being a rubber stamp:
 *
 * - **A matching code** appears on the computer and on the phone, the way Bluetooth
 *   pairing works. If someone else on the network sends a request at the same moment as
 *   you, the codes will not match, and you will not approve the wrong one.
 * - **Rate limits.** At most one open request per address and three overall, each
 *   expiring after two minutes -- so nobody on the network can bury your phone in prompts.
 */
class PairingManager(ctx: Context, private val registry: DeviceRegistry) {

    private val app = ctx.applicationContext
    private val rng = SecureRandom()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private val requests = LinkedHashMap<String, PairRequest>()

    private val _pending = MutableStateFlow<List<PairRequest>>(emptyList())
    val pending: StateFlow<List<PairRequest>> = _pending

    /** Opens a request, or returns the one this address already has. Null when rate-limited. */
    fun request(name: String, ip: String): PairRequest? = synchronized(lock) {
        expireOld()
        requests.values.firstOrNull { it.ip == ip && it.state == PairRequest.State.PENDING }
            ?.let { return@synchronized it }
        if (requests.values.count { it.state == PairRequest.State.PENDING } >= MAX_PENDING) {
            return@synchronized null
        }
        val req = PairRequest(
            id = randomId(),
            name = name,
            ip = ip,
            code = (rng.nextInt(9000) + 1000).toString(),
            createdAt = System.currentTimeMillis(),
            state = PairRequest.State.PENDING,
        )
        requests[req.id] = req
        publish()
        notifyRequest(req)
        scope.launch {
            delay(TTL_MS + 1_000)
            synchronized(lock) { expireOld() }
        }
        req
    }

    fun status(id: String): PairRequest? = synchronized(lock) {
        expireOld()
        requests[id]
    }

    fun approve(id: String) = synchronized(lock) {
        val req = requests[id] ?: return@synchronized
        if (req.state != PairRequest.State.PENDING) return@synchronized
        val device = registry.add(req.name, req.ip)
        requests[id] = req.copy(state = PairRequest.State.APPROVED, deviceId = device.id)
        cancelNotification(req)
        publish()
    }

    fun deny(id: String) = synchronized(lock) {
        val req = requests[id] ?: return@synchronized
        if (req.state != PairRequest.State.PENDING) return@synchronized
        requests[id] = req.copy(state = PairRequest.State.DENIED)
        cancelNotification(req)
        publish()
    }

    private fun expireOld() {
        val now = System.currentTimeMillis()
        var changed = false
        requests.entries.toList().forEach { (id, r) ->
            val age = now - r.createdAt
            when {
                r.state == PairRequest.State.PENDING && age > TTL_MS -> {
                    requests[id] = r.copy(state = PairRequest.State.EXPIRED)
                    cancelNotification(r)
                    changed = true
                }
                // Resolved requests linger briefly so the browser can collect the answer.
                r.state != PairRequest.State.PENDING && age > TTL_MS * 2 -> {
                    requests.remove(id)
                    changed = true
                }
            }
        }
        if (changed) publish()
    }

    private fun publish() {
        _pending.value = requests.values.filter { it.state == PairRequest.State.PENDING }
    }

    private fun randomId(): String =
        ByteArray(16).also(rng::nextBytes).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ notification

    private fun notifyRequest(req: PairRequest) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Connection requests",
                // HIGH, so it appears on top of whatever you are doing -- you are usually
                // standing at the computer waiting for it.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "A computer asking to connect to this phone." }
        )

        fun action(act: String, code: Int) = PendingIntent.getService(
            app, code,
            Intent(app, BridgeService::class.java)
                .setAction(act)
                .putExtra(BridgeService.EXTRA_REQUEST, req.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            app, notificationId(req),
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val n = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${req.name} wants to connect")
            .setContentText("Code ${req.code}  ·  ${req.ip}")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Code ${req.code}  ·  ${req.ip}\nOnly allow it if the computer shows the same code."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setTimeoutAfter(TTL_MS)
            .setContentIntent(open)
            .addAction(0, "Allow", action(BridgeService.ACTION_APPROVE, notificationId(req)))
            .addAction(0, "Deny", action(BridgeService.ACTION_DENY, notificationId(req) + 1))
            .build()
        runCatching { nm.notify(notificationId(req), n) }
    }

    private fun cancelNotification(req: PairRequest) {
        app.getSystemService(NotificationManager::class.java)?.cancel(notificationId(req))
    }

    private fun notificationId(req: PairRequest) = 2000 + (req.id.hashCode() and 0x3FF) * 2

    private companion object {
        const val CHANNEL_ID = "xoosh_pairing"
        const val TTL_MS = 120_000L
        const val MAX_PENDING = 3
    }
}
