package dev.periy.bridge.server

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One position: where, how sure (metres, the radius it is within), when; with what it came from. */
@Serializable
data class Fix(
    val lat: Double,
    val lon: Double,
    val acc: Float,
    val at: Long,
    val alt: Double? = null,
    val speed: Float? = null,
    /** gps, network, fused, wifi (a laptop's), as the source says. */
    val src: String = "",
    /** The device's battery then, percent; -1 when it does not say. */
    val battery: Int = -1,
)

/** A phone or laptop and where it is: its last position and the trail before it, newest last. */
@Serializable
data class Place(
    val id: String,
    val name: String,
    /** phone or laptop */
    val kind: String,
    val fix: Fix? = null,
    val trail: List<Fix> = emptyList(),
    /** This phone itself. */
    val self: Boolean = false,
    /** In lost mode (server/FindMe.kt). */
    val lost: Boolean = false,
    /** Through which phone it is known, when not this one ("" for this phone and its own laptops). */
    val via: String = "",
)

/** A place marked on the map: arriving and leaving it is said in a notification. */
@Serializable
data class Zone(val id: String, val name: String, val lat: Double, val lon: Double, val radius: Double)

/** What a phone tells its linked phones: itself and its laptops. */
@Serializable
data class WhereShare(val places: List<Place>)

/**
 * Where your phones and laptops are, as precisely as each can tell, kept on your own devices.
 *
 * This phone: Android's own fused location (GPS, Wi-Fi and cells together; no Google services),
 * high accuracy, every 20 s or 5 m while it moves, kept going in the background by the app's
 * service when location is allowed all the time. Its laptops: their helpers report Windows' own
 * position (POST /api/where/laptop). Linked phones: each tells the others where it and its laptops
 * are, sealed with the two phones' key, as messages are. The trail keeps two days, a point every
 * 15 m or 5 minutes. Nothing goes anywhere else; the map's pictures come from OpenStreetMap.
 */
class Where(
    ctx: Context,
    private val peers: PeerManager,
    /** This phone's tunnel key for the linked phone that came in on [deviceId]. */
    private val keyHere: (deviceId: String) -> ByteArray,
) {
    private val app = ctx.applicationContext
    private val file = File(app.filesDir, "where.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Serializable
    private data class Kept(
        val self: List<Fix> = emptyList(),
        val others: List<Place> = emptyList(),
        val zones: List<Zone> = emptyList(),
        /** Which device is in which place now ("device|place"), so a restart says nothing twice. */
        val inside: Map<String, Boolean> = emptyMap(),
    )

    private val kept = runCatching { json.decodeFromString<Kept>(file.readText()) }.getOrDefault(Kept())

    private val _self = MutableStateFlow(kept.self)
    /** This phone's trail, the newest last. */
    val selfTrail: StateFlow<List<Fix>> = _self.asStateFlow()

    private val _others = MutableStateFlow(kept.others.associateBy { it.id })
    /** Every other phone and laptop, by id. */
    val others: StateFlow<Map<String, Place>> = _others.asStateFlow()

    private val _zones = MutableStateFlow(kept.zones)
    /** The places marked on the map. */
    val zones: StateFlow<List<Zone>> = _zones.asStateFlow()
    private val inside = java.util.concurrent.ConcurrentHashMap(kept.inside)

    private var listening = false
    /** Lost mode: always precise, as often as it moves (set by the app's container). */
    var lostNow: () -> Boolean = { false }
    /**
     * Moving: high accuracy, every 20 s or 5 m. Still (3 minutes within 25 m): balanced power (Wi-Fi
     * and cells, mostly no GPS), every 2 minutes, so all-the-time location costs little; back to
     * moving the moment a position is 40 m from where it stood, or comes with a walking speed.
     */
    private var still = false
    private var stillSince = 0L
    private var anchor: Fix? = null
    private var lastShared = 0L
    private var lastSharedFix: Fix? = null

    fun allowed(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun allowedAlways(): Boolean = allowed() && (Build.VERSION.SDK_INT < 29 ||
        app.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    private val listener = LocationListener { took(it) }

    /** Starts listening, once location is allowed; called again when it is. */
    fun start() {
        if (listening || !allowed()) return
        val lm = app.getSystemService(LocationManager::class.java) ?: return
        runCatching {
            request(lm)
            // Something to show at once: the last known position, while a fresh one comes.
            @Suppress("MissingPermission")
            for (p in lm.allProviders) runCatching { lm.getLastKnownLocation(p)?.let(::took) }
            listening = true
            Log.i(TAG, "Listening for this phone's position")
        }.onFailure { Log.w(TAG, "Location", it) }
    }

    /** Asks for positions as the phone is now: precisely while it moves, gently while it is still. */
    @Suppress("MissingPermission")
    private fun request(lm: LocationManager) {
        runCatching { lm.removeUpdates(listener) }
        val every = if (still) STILL_MS else INTERVAL_MS
        val metres = if (still) STILL_METRES else MIN_METRES
        if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) {
            val req = android.location.LocationRequest.Builder(every)
                .setQuality(if (still) android.location.LocationRequest.QUALITY_BALANCED_POWER_ACCURACY else android.location.LocationRequest.QUALITY_HIGH_ACCURACY)
                .setMinUpdateDistanceMeters(metres)
                .build()
            lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, req, app.mainExecutor, listener)
        } else {
            // Without Android's own fusion: GPS for precision (not while still), the network for a quick fix indoors.
            val providers = if (still) listOf(LocationManager.NETWORK_PROVIDER) else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (p in providers) if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, every, metres, listener, Looper.getMainLooper())
        }
    }

    /** Still or moving, from each new position; the asking changes with it. */
    private fun pace(fix: Fix) {
        val a = anchor
        val walking = (fix.speed ?: 0f) > 1.0f
        if (a == null || metres(a, fix) > (if (still) 40.0 else STILL_RADIUS) || walking) {
            anchor = fix
            stillSince = fix.at
            if (still) { still = false; app.getSystemService(LocationManager::class.java)?.let(::request); Log.i(TAG, "Moving: precise positions") }
            return
        }
        if (!still && fix.at - stillSince >= STILL_AFTER_MS && !lostNow()) {
            still = true
            app.getSystemService(LocationManager::class.java)?.let(::request)
            Log.i(TAG, "Still: gentle positions")
        }
    }

    private fun battery(): Int = runCatching {
        app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(-1)

    private fun took(l: Location) {
        val fix = Fix(
            l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 999f, l.time,
            if (l.hasAltitude()) l.altitude else null, if (l.hasSpeed()) l.speed else null,
            l.provider.orEmpty(), battery(),
        )
        val trail = _self.value
        val last = trail.lastOrNull()
        // An older or much less sure position than the one just had is not news.
        if (last != null && (fix.at < last.at || (fix.at - last.at < 60_000 && fix.acc > last.acc * 3 && fix.acc > 50))) return
        val moved = last == null || metres(last, fix) >= TRAIL_METRES || fix.at - last.at >= TRAIL_MS
        val next = (if (moved) trail + fix else trail.dropLast(1) + fix).filter { fix.at - it.at <= KEEP_MS }.takeLast(KEEP_POINTS)
        _self.value = next
        checkZones("self", peers.deviceName(), fix)
        changed()
        share(fix)
        if (listening) pace(fix)
    }

    /** A laptop's position, from its helper. */
    fun laptop(id: String, name: String, fix: Fix) {
        val was = _others.value[id]
        val trail = was?.trail.orEmpty()
        val last = trail.lastOrNull()
        val moved = last == null || metres(last, fix) >= TRAIL_METRES || fix.at - last.at >= TRAIL_MS
        val next = (if (moved) trail + fix else trail.dropLast(1) + fix).filter { fix.at - it.at <= KEEP_MS }.takeLast(KEEP_POINTS)
        _others.value = _others.value + (id to Place(id, name, "laptop", fix, next))
        checkZones(id, name, fix)
        changed()
        // Linked phones hear of this phone's laptops too, with its own position's next word.
        lastShared = 0
        _self.value.lastOrNull()?.let(::share) ?: shareNow()
    }

    /** Everything known, this phone first, for the map. */
    fun places(): List<Place> {
        val me = Place("self", peers.deviceName(), "phone", _self.value.lastOrNull(), _self.value, self = true, lost = lostNow())
        return listOf(me) + _others.value.values.sortedByDescending { it.fix?.at ?: 0L }
    }

    // ------------------------------------------------------------------ linked phones

    /** Tells the linked phones, now and then: on a new place (15 m), or each 2 minutes. */
    private fun share(fix: Fix) {
        val now = System.currentTimeMillis()
        val prev = lastSharedFix
        if (prev != null && now - lastShared < SHARE_MS && metres(prev, fix) < TRAIL_METRES) return
        lastShared = now
        lastSharedFix = fix
        shareNow()
    }

    private fun shareNow() {
        val mine = listOf(Place(peers.deviceName(), peers.deviceName(), "phone", _self.value.lastOrNull(), _self.value.takeLast(SHARE_TRAIL), lost = lostNow())) +
            _others.value.values.filter { it.kind == "laptop" && it.via.isEmpty() }.map { it.copy(trail = it.trail.takeLast(SHARE_TRAIL)) }
        val text = json.encodeToString(WhereShare(mine))
        scope.launch {
            for (p in peers.dto()) {
                val peer = peers.find(p.name) ?: continue
                val key = peers.messageKey(peer) ?: continue
                val body = json.encodeToString(WhereCrypto.seal(key, text)).toByteArray()
                runCatching { peers.deliver(peer, "/api/peers/where", body) }
            }
        }
    }

    /** Where a linked phone ([from]) and its laptops are, sealed; false when it does not open. */
    fun receive(from: String, deviceId: String, w: CallWire): Boolean {
        val text = WhereCrypto.open(keyHere(deviceId), w) ?: return false
        val share = runCatching { json.decodeFromString<WhereShare>(text) }.getOrNull() ?: return false
        val next = _others.value.toMutableMap()
        for (p in share.places.take(16)) {
            val id = "$from/${p.id}"
            next[id] = p.copy(id = id, via = from, self = false,
                // Its trail grows here too, as it is told more.
                trail = (next[id]?.trail.orEmpty() + p.trail).distinctBy { it.at }.sortedBy { it.at }.takeLast(KEEP_POINTS))
        }
        _others.value = next
        share.places.forEach { p -> p.fix?.let { checkZones("$from/${p.id}", p.name, it) } }
        changed()
        return true
    }

    /** Lost mode went on or off: linked phones hear of it at once. */
    fun lostChanged() {
        lastShared = 0
        if (still) { still = false; app.getSystemService(LocationManager::class.java)?.let(::request) }
        shareNow()
        changed()
    }

    /**
     * Ring a phone or put it in lost mode ([c].target: "self" or empty for this one, else a linked
     * phone's name, told sealed). Blocks while a linked phone is asked. Null when done, else why not.
     */
    fun find(c: FindCmd, here: (FindCmd) -> Unit): String? {
        if (c.target.isEmpty() || c.target == "self" || c.target == peers.deviceName()) { here(c); return null }
        val peer = peers.find(c.target) ?: return "${c.target} is not linked"
        val key = peers.messageKey(peer) ?: return "${c.target} is not linked"
        val body = json.encodeToString(WhereCrypto.seal(key, json.encodeToString(c.copy(target = "")))).toByteArray()
        return if (runCatching { peers.deliver(peer, "/api/peers/find", body) }.getOrDefault(false)) null else "${c.target} could not be reached"
    }

    // ------------------------------------------------------------------ places

    /** Marks a place (or changes one, by its id). */
    fun setZone(z: Zone) {
        _zones.value = _zones.value.filterNot { it.id == z.id } + z
        changed()
    }

    fun deleteZone(id: String) {
        _zones.value = _zones.value.filterNot { it.id == id }
        inside.keys.removeAll { it.endsWith("|$id") }
        changed()
    }

    /**
     * Whether [name] has arrived at or left a marked place, said once each time: in when within its
     * radius, out when past it by 30 m or the position's own uncertainty (so a position wobbling at
     * the edge says nothing); a position much less sure than the place is wide is not used.
     */
    private fun checkZones(device: String, name: String, fix: Fix) {
        for (z in _zones.value) {
            if (fix.acc > z.radius * 2 && fix.acc > 100) continue
            val d = metres(Fix(z.lat, z.lon, 0f, 0), fix)
            val key = "$device|${z.id}"
            val was = inside[key]
            val now = when {
                d <= z.radius -> true
                d >= z.radius + maxOf(30.0, fix.acc.toDouble()) -> false
                else -> was ?: continue
            }
            inside[key] = now
            if (was != null && was != now) say(if (now) "$name arrived at ${z.name}" else "$name left ${z.name}", fix)
        }
    }

    private fun say(text: String, fix: Fix) {
        val nm = app.getSystemService(android.app.NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(PLACES) == null) nm.createNotificationChannel(
            android.app.NotificationChannel(PLACES, "Arriving and leaving", android.app.NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Your phones and laptops arriving at and leaving the places marked on the map."
            },
        )
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(fix.at))
        val n = androidx.core.app.NotificationCompat.Builder(app, PLACES)
            .setSmallIcon(dev.periy.bridge.R.drawable.ic_notification)
            .setContentTitle(text)
            .setContentText("At $time, within ${fix.acc.toInt()} m")
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(text.hashCode(), n) }
        EventBus.emit("place", text)
    }

    /** A phone or laptop taken off the map. */
    fun forget(id: String) {
        _others.value = _others.value - id
        changed()
    }

    private fun changed() {
        scope.launch { runCatching { file.writeText(json.encodeToString(Kept(_self.value, _others.value.values.toList(), _zones.value, HashMap(inside)))) } }
        EventBus.emit("where", "")
    }

    companion object {
        private const val TAG = "Where"
        private const val PLACES = "places"
        private const val INTERVAL_MS = 20_000L
        private const val MIN_METRES = 5f
        private const val TRAIL_METRES = 15.0
        private const val TRAIL_MS = 5 * 60_000L
        private const val KEEP_MS = 48 * 3600_000L
        private const val KEEP_POINTS = 3000
        private const val SHARE_MS = 2 * 60_000L
        private const val SHARE_TRAIL = 300
        private const val STILL_MS = 120_000L
        private const val STILL_METRES = 25f
        private const val STILL_RADIUS = 25.0
        private const val STILL_AFTER_MS = 3 * 60_000L

        /** Metres between two positions (haversine). */
        fun metres(a: Fix, b: Fix): Double {
            val r = 6_371_000.0
            val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
            val dp = p2 - p1; val dl = Math.toRadians(b.lon - a.lon)
            val h = Math.sin(dp / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2).let { it * it }
            return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(h)))
        }
    }
}

/** Where a phone is, sealed for a linked phone: as [CallCrypto], under its own label. */
object WhereCrypto {
    private val L_WHERE = "L87W/1 where".toByteArray()
    private val rng = SecureRandom()

    private fun cipher(mode: Int, psk: ByteArray, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(dev.periy.bridge.net.TunnelCrypto.hmac(psk, L_WHERE), "AES"), GCMParameterSpec(128, nonce))
        }

    fun seal(psk: ByteArray, text: String): CallWire {
        val nonce = ByteArray(12).also(rng::nextBytes)
        val ct = cipher(Cipher.ENCRYPT_MODE, psk, nonce).doFinal(text.toByteArray())
        return CallWire("where", Base64.encodeToString(nonce, Base64.NO_WRAP), Base64.encodeToString(ct, Base64.NO_WRAP))
    }

    fun open(psk: ByteArray, w: CallWire): String? = runCatching {
        String(cipher(Cipher.DECRYPT_MODE, psk, Base64.decode(w.n, Base64.NO_WRAP)).doFinal(Base64.decode(w.c, Base64.NO_WRAP)))
    }.getOrNull()
}
