package dev.periy.bridge.server

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CompletableDeferred
import android.util.Base64
import android.util.Log
import dev.periy.bridge.net.TunnelCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One step of setting up a call, from one linked phone to the other: an [offer] or [answer] with
 * its session description, a late [ice] candidate, [ringing], or [end] with why.
 */
@Serializable
data class CallSignal(
    val call: String,
    val kind: String,
    val sdp: String = "",
    val mid: String = "",
    val line: Int = 0,
    val cand: String = "",
    val why: String = "",
)

/** A call signal as it travels, sealed like a message ([CallCrypto]). */
@Serializable
data class CallWire(val call: String, val n: String, val c: String)

/** The call on the screen: who, which way, and where it stands. */
data class CallState(
    val id: String,
    val peer: String,
    val outgoing: Boolean,
    /** calling, ringing (theirs, on this phone), connecting, active, ended */
    val phase: String,
    val since: Long = 0L,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    /** How the sound goes once connected: "Same Wi-Fi", "IPv6, direct", "IPv4, punched through". */
    val path: String = "",
    val why: String = "",
)

/**
 * Voice calls between linked phones, each phone the other's server, with nothing in between.
 *
 * The call is WebRTC: the sound is Opus over SRTP, with the phone's own echo cancelling and noise
 * suppression and WebRTC's jitter buffer, straight from one phone to the other (on the same
 * Wi-Fi, over IPv6, or punched across IPv4 with STUN, as the rest of phone to phone). There is no
 * relay (TURN): when the two networks cannot reach each other, the call does not connect. Setting
 * it up (the offer, the answer, late candidates) goes over the phone-to-phone channel messages
 * use, each step sealed with the two phones' key, so the call's own keys are known to the two
 * phones only.
 */
class Calls(
    ctx: Context,
    private val peers: PeerManager,
    /** This phone's tunnel key for the linked phone that came in on [deviceId]. */
    private val keyHere: (deviceId: String) -> ByteArray,
) {
    private val app = ctx.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<CallState?>(null)
    /** The call shown on this phone, or null when there is none. */
    val state: StateFlow<CallState?> = _state.asStateFlow()

    /** Called when an incoming call starts ringing, or the call ends: the notifications and the call service. */
    var onChange: (CallState?) -> Unit = {}

    private class Leg(
        val id: String,
        val peer: String,
        val outgoing: Boolean,
        val pc: PeerConnection,
        val track: AudioTrack,
    ) {
        @Volatile var remoteSet = false
        val pending = mutableListOf<IceCandidate>()
        var offer: String = ""
        var timeout: Job? = null
    }

    /** Calls by id and side: a phone linked with itself (for a test) is both ends at once. */
    private val legs = ConcurrentHashMap<String, Leg>()

    /** Late candidates that came before their call's offer (the steps travel side by side). */
    private val early = ConcurrentHashMap<String, MutableList<IceCandidate>>()

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
    }

    // ------------------------------------------------------------------ starting and answering

    /** Calls [name]. Needs the microphone allowed, and this to be asked from the screen. */
    fun call(name: String) {
        if (_state.value?.let { it.phase != "ended" } == true) return
        val id = UUID.randomUUID().toString()
        show(CallState(id, name, outgoing = true, phase = "calling"))
        register(_state.value!!)
        scope.launch {
            try {
                val peer = peers.find(name) ?: error("$name is not linked")
                val leg = newLeg(id, name, outgoing = true)
                val offer = leg.pc.awaitCreate(true)
                leg.pc.awaitSetLocal(offer)
                val sdp = gathered(leg)
                if (!send(peer.name, CallSignal(id, "offer", sdp = sdp))) error("$name could not be reached")
                // Nobody answers in 45 s: it ends.
                leg.timeout = scope.launch { delay(RING_MS); if (phaseOf(id) == "calling") end(id, "No answer", tell = true) }
            } catch (t: Throwable) {
                Log.w(TAG, "Call failed", t)
                finish(id, t.message ?: "Could not call")
            }
        }
    }

    /** Answers the call ringing here. Needs the microphone allowed, and the screen in front. */
    fun answer() {
        val s = _state.value ?: return
        if (s.outgoing || s.phase != "ringing") return
        val leg = legs[key(s.id, false)] ?: return
        show(s.copy(phase = "connecting"))
        register(s)
        scope.launch {
            try {
                leg.pc.awaitSetRemote(SessionDescription(SessionDescription.Type.OFFER, leg.offer))
                leg.remoteSet = true
                drainPending(leg)
                val answer = leg.pc.awaitCreate(false)
                leg.pc.awaitSetLocal(answer)
                val sdp = gathered(leg)
                if (!send(leg.peer, CallSignal(s.id, "answer", sdp = sdp))) error("${leg.peer} could not be reached")
                connectTimeout(s.id)
            } catch (t: Throwable) {
                Log.w(TAG, "Answer failed", t)
                end(s.id, t.message ?: "Could not answer", tell = true)
            }
        }
    }

    /** Ends the call on this phone, or declines it while it rings, and tells the other phone. */
    fun hangUp() {
        val s = _state.value ?: return
        end(s.id, if (s.phase == "ringing") "Declined" else "Call ended", tell = true)
    }

    fun setMuted(muted: Boolean) {
        val s = _state.value ?: return
        legs.values.filter { it.id == s.id && it.outgoing == s.outgoing }.forEach { it.track.setEnabled(!muted) }
        show(s.copy(muted = muted))
    }

    fun setSpeaker(on: Boolean) {
        val s = _state.value ?: return
        val r = registered?.takeIf { it.id == s.id }
        val want = r?.endpoints?.firstOrNull {
            it.type == if (on) CallEndpointCompat.TYPE_SPEAKER else CallEndpointCompat.TYPE_EARPIECE
        }
        val control = r?.control
        if (control != null && want != null) {
            scope.launch { runCatching { control.requestEndpointChange(want) } }
            show(s.copy(speaker = on))
            return
        }
        val am = app.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
            else am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
        show(s.copy(speaker = on))
    }

    // ------------------------------------------------------------------ Android's own call handling

    /**
     * Core-Telecom: the call is registered with Android once it is under way (this phone calling,
     * or answering), so it gets a call's priority, Bluetooth headsets and car kits carry it and
     * hang it up, and the speaker and earpiece are switched the way the system does. Where that is
     * not available the call goes on without it, with the audio mode set here.
     */
    private val callsManager: CallsManager? by lazy {
        runCatching { CallsManager(app).also { it.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE) } }
            .onFailure { Log.w(TAG, "Telecom unavailable", it) }.getOrNull()
    }

    private class Registered(val id: String) {
        @Volatile var control: CallControlScope? = null
        @Volatile var endpoints: List<CallEndpointCompat> = emptyList()
        val done = CompletableDeferred<Unit>()
    }

    @Volatile private var registered: Registered? = null

    private fun register(s: CallState) {
        if (registered?.id == s.id) return
        val r = Registered(s.id)
        registered = r
        val cm = callsManager
        if (cm == null) { audioMode(true); return }
        scope.launch {
            runCatching {
                cm.addCall(
                    CallAttributesCompat(
                        s.peer, Uri.parse("l87:" + Uri.encode(s.peer)),
                        if (s.outgoing) CallAttributesCompat.DIRECTION_OUTGOING else CallAttributesCompat.DIRECTION_INCOMING,
                        CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                    ),
                    onAnswer = { answer() },
                    onDisconnect = { if (phaseOf(r.id) != null && phaseOf(r.id) != "ended") end(r.id, "Call ended", tell = true) },
                    onSetActive = {},
                    onSetInactive = {},
                ) {
                    r.control = this
                    if (!s.outgoing) launch { answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) }
                    launch { availableEndpoints.collect { r.endpoints = it } }
                    launch { r.done.await(); disconnect(DisconnectCause(DisconnectCause.LOCAL)) }
                }
            }.onFailure { Log.w(TAG, "Telecom call", it); audioMode(true) }
        }
    }

    private fun unregister(id: String) {
        val r = registered?.takeIf { it.id == id } ?: return
        registered = null
        r.done.complete(Unit)
        audioMode(false)
    }

    private fun audioMode(call: Boolean) = runCatching {
        app.getSystemService(AudioManager::class.java).mode = if (call) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
    }

    // ------------------------------------------------------------------ the other phone's signals

    /** A sealed signal from the linked phone [from], which came in on [deviceId]. False when it does not open. */
    fun receive(from: String, deviceId: String, w: CallWire): Boolean {
        val text = CallCrypto.open(keyHere(deviceId), w) ?: return false
        val sig = runCatching { json.decodeFromString<CallSignal>(text) }.getOrNull() ?: return false
        scope.launch { runCatching { handle(from, sig) }.onFailure { Log.w(TAG, "Signal ${sig.kind}", it) } }
        return true
    }

    private suspend fun handle(from: String, sig: CallSignal) {
        when (sig.kind) {
            "offer" -> {
                val busy = _state.value?.let { it.phase != "ended" && !(it.outgoing && it.id == sig.call) } == true
                // A test call to this same phone is its own other end, so it is not busy with itself.
                if (busy && _state.value?.id != sig.call) { send(from, CallSignal(sig.call, "end", why = "Busy")); return }
                val leg = newLeg(sig.call, from, outgoing = false)
                leg.offer = sig.sdp
                early.remove(sig.call)?.let { l -> synchronized(l) { synchronized(leg.pending) { leg.pending.addAll(l) } } }
                show(CallState(sig.call, from, outgoing = false, phase = "ringing"))
                send(from, CallSignal(sig.call, "ringing"))
                leg.timeout = scope.launch { delay(RING_MS); if (phaseOf(sig.call) == "ringing") end(sig.call, "Missed call", tell = false) }
            }
            "ringing" -> if (phaseOf(sig.call) == "calling") _state.value?.let { if (it.id == sig.call) show(it.copy(why = "Ringing")) }
            "answer" -> {
                val leg = legs[key(sig.call, true)] ?: return
                leg.timeout?.cancel()
                _state.value?.let { if (it.id == sig.call && it.outgoing) show(it.copy(phase = "connecting", why = "")) }
                leg.pc.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, sig.sdp))
                leg.remoteSet = true
                drainPending(leg)
                connectTimeout(sig.call)
            }
            "ice" -> {
                val c = IceCandidate(sig.mid, sig.line, sig.cand)
                // Late candidates go to whichever side of this call is here (both, on a test call to
                // itself); for a call not seen yet, they wait for it.
                val here = legs.values.filter { it.id == sig.call }
                if (here.isEmpty()) early.getOrPut(sig.call) { mutableListOf() }.let { l -> synchronized(l) { l.add(c) } }
                here.forEach { leg ->
                    if (leg.remoteSet) leg.pc.addIceCandidate(c) else synchronized(leg.pending) { leg.pending.add(c) }
                }
            }
            "end" -> end(sig.call, sig.why.ifEmpty { "Call ended" }, tell = false)
        }
    }

    // ------------------------------------------------------------------ WebRTC

    private fun key(id: String, outgoing: Boolean) = id + if (outgoing) ">" else "<"

    private fun newLeg(id: String, peer: String, outgoing: Boolean): Leg {
        val rtc = PeerConnection.RTCConfiguration(
            // Only to learn this phone's own public addresses, as the tunnel's punching does; the
            // call itself never goes through them, and there is no relay.
            listOf(
                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            ),
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // Wi-Fi to mobile data and back mid-call: ICE keeps looking and finds the new way.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        lateinit var leg: Leg
        val pc = factory.createPeerConnection(rtc, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                // Sent on after the description, which already carries what was found by then.
                if (leg.sentDescription) scope.launch { send(leg.peer, CallSignal(id, "ice", mid = c.sdpMid ?: "", line = c.sdpMLineIndex, cand = c.sdp)) }
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                Log.i(TAG, "Call $id ${if (outgoing) "out" else "in"}: $s")
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> connected(leg)
                    PeerConnection.IceConnectionState.FAILED -> end(id, "No way through between the two networks", tell = true)
                    else -> {}
                }
            }
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) leg.gatheredAll = true
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        }) ?: error("Could not start the call")
        val source = factory.createAudioSource(MediaConstraints())
        val track = factory.createAudioTrack("voice-" + (if (outgoing) "out" else "in"), source)
        pc.addTrack(track, listOf("call"))
        leg = Leg(id, peer, outgoing, pc, track)
        legs[key(id, outgoing)] = leg
        return leg
    }

    private val Leg.sentDescription: Boolean get() = sent.contains(key(id, outgoing))
    private var Leg.gatheredAll: Boolean
        get() = gatheredSet.contains(key(id, outgoing))
        set(v) { if (v) gatheredSet.add(key(id, outgoing)) }
    private val sent = ConcurrentHashMap.newKeySet<String>()
    private val gatheredSet = ConcurrentHashMap.newKeySet<String>()

    /**
     * The local description, sent as soon as it has this phone's own addresses and the public one
     * STUN gives (or after a second), so a call starts quickly; whatever is found later goes on
     * after it ("ice").
     */
    private suspend fun gathered(leg: Leg): String {
        withTimeoutOrNull(GATHER_MS) {
            while (!leg.gatheredAll && leg.pc.localDescription?.description?.contains(" typ srflx") != true) delay(30)
        }
        sent.add(key(leg.id, leg.outgoing))
        return leg.pc.localDescription?.description ?: error("No description")
    }

    private fun drainPending(leg: Leg) {
        val list = synchronized(leg.pending) { leg.pending.toList().also { leg.pending.clear() } }
        list.forEach { leg.pc.addIceCandidate(it) }
    }

    private fun connectTimeout(id: String) {
        scope.launch { delay(CONNECT_MS); if (phaseOf(id) == "connecting") end(id, "No way through between the two networks", tell = true) }
    }

    private fun connected(leg: Leg) {
        val s = _state.value ?: return
        if (s.id != leg.id || s.outgoing != leg.outgoing || s.phase == "active") {
            // The other side of a test call to itself: it is connected too, but not what is shown.
            return
        }
        show(s.copy(phase = "active", since = System.currentTimeMillis(), why = ""))
        registered?.takeIf { it.id == leg.id }?.control?.let { c -> scope.launch { runCatching { c.setActive() } } }
        // How it goes: the pair of candidates in use, as a person would say it.
        leg.pc.getStats { report ->
            val pair = report.statsMap.values.firstOrNull { it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true }
            val local = pair?.let { report.statsMap[it.members["localCandidateId"] as? String] }
            val remote = pair?.let { report.statsMap[it.members["remoteCandidateId"] as? String] }
            val types = listOfNotNull(local?.members?.get("candidateType"), remote?.members?.get("candidateType")).map { it.toString() }
            val addr = (remote?.members?.get("address") ?: remote?.members?.get("ip"))?.toString().orEmpty()
            val path = when {
                types.any { it == "relay" } -> "Relayed"
                types.all { it == "host" } -> "Same network, direct"
                addr.contains(':') -> "IPv6, direct"
                else -> "IPv4, punched through"
            }
            _state.value?.let { if (it.id == leg.id) show(it.copy(path = path)) }
        }
    }

    private fun phaseOf(id: String): String? = _state.value?.takeIf { it.id == id }?.phase

    /** Ends call [id] here, closing both its sides; with [tell], the other phone hears why. */
    private fun end(id: String, why: String, tell: Boolean) {
        val mine = legs.values.filter { it.id == id }
        mine.forEach { leg ->
            legs.remove(key(leg.id, leg.outgoing))
            sent.remove(key(leg.id, leg.outgoing)); gatheredSet.remove(key(leg.id, leg.outgoing))
            leg.timeout?.cancel()
            runCatching { leg.track.setEnabled(false); leg.pc.close(); leg.pc.dispose() }
        }
        if (tell) mine.firstOrNull()?.let { leg -> scope.launch { send(leg.peer, CallSignal(id, "end", why = why)) } }
        finish(id, why)
    }

    private fun finish(id: String, why: String) {
        val s = _state.value ?: return
        if (s.id != id) return
        show(s.copy(phase = "ended", why = why))
        if (Build.VERSION.SDK_INT >= 31) runCatching { app.getSystemService(AudioManager::class.java).clearCommunicationDevice() }
        unregister(id)
        // The ended call stays on the screen a moment, then goes.
        scope.launch { delay(2_000); if (_state.value?.id == id && _state.value?.phase == "ended") show(null) }
    }

    private fun show(s: CallState?) {
        _state.value = s
        onChange(s)
    }

    private fun send(name: String, sig: CallSignal): Boolean {
        val peer = peers.find(name) ?: return false
        val key = peers.messageKey(peer) ?: return false
        val body = json.encodeToString(CallCrypto.seal(key, sig.call, json.encodeToString(sig))).toByteArray()
        return peers.deliver(peer, "/api/peers/call", body)
    }

    // ------------------------------------------------------------------ coroutines over WebRTC's callbacks

    private suspend fun PeerConnection.awaitCreate(offer: Boolean): SessionDescription = suspendCancellableCoroutine { k ->
        val o = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { if (k.isActive) k.resume(d) }
            override fun onCreateFailure(e: String?) { if (k.isActive) k.resumeWithException(IllegalStateException(e)) }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }
        if (offer) createOffer(o, MediaConstraints()) else createAnswer(o, MediaConstraints())
    }

    private suspend fun PeerConnection.awaitSet(local: Boolean, d: SessionDescription): Unit = suspendCancellableCoroutine { k ->
        val o = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetSuccess() { if (k.isActive) k.resume(Unit) }
            override fun onSetFailure(e: String?) { if (k.isActive) k.resumeWithException(IllegalStateException(e)) }
        }
        if (local) setLocalDescription(o, d) else setRemoteDescription(o, d)
    }

    private suspend fun PeerConnection.awaitSetLocal(d: SessionDescription) = awaitSet(true, d)
    private suspend fun PeerConnection.awaitSetRemote(d: SessionDescription) = awaitSet(false, d)

    companion object {
        private const val TAG = "Calls"
        private const val RING_MS = 45_000L
        private const val CONNECT_MS = 20_000L
        private const val GATHER_MS = 1_000L
    }
}

/** A call signal sealed for the other phone: as [MsgCrypto], under its own label. */
object CallCrypto {
    private val L_CALL = "L87C/1 call".toByteArray()
    private val rng = SecureRandom()

    private fun cipher(mode: Int, psk: ByteArray, nonce: ByteArray, call: String): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(TunnelCrypto.hmac(psk, L_CALL), "AES"), GCMParameterSpec(128, nonce))
            updateAAD(call.toByteArray())
        }

    fun seal(psk: ByteArray, call: String, text: String): CallWire {
        val nonce = ByteArray(12).also(rng::nextBytes)
        val ct = cipher(Cipher.ENCRYPT_MODE, psk, nonce, call).doFinal(text.toByteArray())
        return CallWire(call, Base64.encodeToString(nonce, Base64.NO_WRAP), Base64.encodeToString(ct, Base64.NO_WRAP))
    }

    fun open(psk: ByteArray, w: CallWire): String? = runCatching {
        String(cipher(Cipher.DECRYPT_MODE, psk, Base64.decode(w.n, Base64.NO_WRAP), w.call).doFinal(Base64.decode(w.c, Base64.NO_WRAP)))
    }.getOrNull()
}
