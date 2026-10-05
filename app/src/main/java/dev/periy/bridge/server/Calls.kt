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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Someone in a call: their id in it, and the name the phone that started it knows them by. */
@Serializable
data class RosterEntry(val id: String, val name: String)

/**
 * One step of a call, between two phones in it. Call steps go between the phone that started the
 * call (the host) and each phone in it: [invite], ringing, accept, decline, [members] (the roster),
 * leave, end. Media steps go between any two phones in it, [from] one member [to] another (the
 * host passes them on between phones that are not linked with each other): offer, answer, ice, and
 * media ([camera] and [mic] on or off).
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
    val from: String = "",
    val to: String = "",
    val video: Boolean = false,
    val members: List<RosterEntry> = emptyList(),
    val camera: Boolean = false,
    val mic: Boolean = true,
    /** With the roster: more phones than a mesh carries, so each goes through the host. */
    val relay: Boolean = false,
    /** With media: the picture is this phone's screen, to be shown whole. */
    val screen: Boolean = false,
)

/** A call signal as it travels, sealed like a message ([CallCrypto]). */
@Serializable
data class CallWire(val call: String, val n: String, val c: String)

/** Another phone in the call, as the screen shows it. */
data class CallMember(
    val id: String,
    val name: String,
    /** invited (ringing there), joining, connected, left */
    val phase: String,
    val video: VideoTrack? = null,
    val camera: Boolean = false,
    val mic: Boolean = true,
    val speaking: Boolean = false,
    /** Their picture is their screen: shown whole, not cropped. */
    val screen: Boolean = false,
    /** How the call reaches them: "Same network, direct", "IPv6, direct", "IPv4, punched through". */
    val path: String = "",
)

/** Someone in the call, as a laptop's page shows them. */
@Serializable
data class PageCallMember(val name: String, val phase: String, val camera: Boolean, val mic: Boolean, val speaking: Boolean)

/** The call as a laptop's page shows it (sent with every change, as "call"). */
@Serializable
data class PageCall(
    val id: String,
    val phase: String,
    val video: Boolean,
    val outgoing: Boolean,
    val since: Long,
    val members: List<PageCallMember>,
    /** The laptop that has the call, or "" while this phone has it. */
    val laptop: String,
    val muted: Boolean,
    val camera: Boolean,
    val test: Boolean,
    val why: String,
)

/** The call on the screen. */
data class CallState(
    val id: String,
    /** This phone started it, and may add people to it. */
    val host: Boolean,
    val outgoing: Boolean,
    /** calling, ringing (theirs, on this phone), connecting, active, ended */
    val phase: String,
    /** It was started as a video call. */
    val video: Boolean = false,
    val since: Long = 0L,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val camera: Boolean = false,
    val frontCamera: Boolean = true,
    val speaking: Boolean = false,
    val members: List<CallMember> = emptyList(),
    val why: String = "",
    /** More phones than a mesh carries: each sends to the host, which sends everyone back one picture. */
    val relay: Boolean = false,
    /** A test call: this phone, through a call and back. */
    val test: Boolean = false,
    /** This phone's screen goes in place of its camera. */
    val sharing: Boolean = false,
    /** A laptop's page has the call (its name): its microphone, camera and speakers, this phone's quiet. */
    val laptop: String = "",
    val laptopMuted: Boolean = false,
    val laptopCamera: Boolean = false,
) {
    /** Who is in it (or being called), for a title or a notification. */
    val peer: String get() = members.filter { it.phase != "left" }.joinToString(", ") { it.name }.ifEmpty { members.firstOrNull()?.name ?: "Call" }
    val conference: Boolean get() = members.count { it.phase != "left" } > 1
    /** How the call goes, for a call with one other phone. */
    val path: String get() = members.singleOrNull { it.phase == "connected" }?.path.orEmpty()
}

/**
 * Calls between linked phones, voice and video, two phones or up to [MAX] at once, each phone the
 * others' server, with nothing in between. Past [MESH] phones the call goes through the phone that
 * started it: each phone sends it its picture and voice once, and it sends each one back everyone's
 * voice and one picture of everyone in a grid ([CallCompositor]). A test call ([testCall]) runs a
 * whole call on this one phone, its own voice and picture through a second connection and back.
 *
 * Every call is WebRTC: Opus voice with the phone's own echo cancelling and noise suppression, and
 * video (H.264 or VP8, in the phone's hardware) from the camera, over SRTP straight between phones,
 * on the same Wi-Fi, over IPv6, or punched across IPv4 with STUN. There is no relay (TURN). A group
 * call is a mesh, each phone connected to each other one, which is what a handful of phones can
 * carry without a server (about four, as each sends its picture to every other). Every connection
 * carries a video channel from the start, so the camera goes on and off without setting anything
 * up again; a video call is one that starts with it on.
 *
 * Setting up goes over the phone-to-phone channel messages use, each step sealed with the two
 * phones' key, so the calls' own keys are known to the phones in it only. The phone that started
 * a call keeps its roster and passes steps on between phones in it that are not linked with each
 * other.
 */
class Calls(
    ctx: Context,
    private val peers: PeerManager,
    /** This phone's tunnel key for the linked phone that came in on [deviceId]. */
    private val keyHere: (deviceId: String) -> ByteArray,
) {
    private val app = ctx.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    /** One thread for all of it, so the steps of a call never cross. */
    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor { r -> Thread(r, "calls").apply { isDaemon = true } }.asCoroutineDispatcher())

    private val _state = MutableStateFlow<CallState?>(null)
    /** The call shown on this phone, or null when there is none. */
    val state: StateFlow<CallState?> = _state.asStateFlow()

    private val _local = MutableStateFlow<VideoTrack?>(null)
    /** This phone's own camera while it is on, for the screen's picture of itself. */
    val localVideo: StateFlow<VideoTrack?> = _local.asStateFlow()

    /** Called when a call starts ringing here, changes, or ends: the notifications and the call service. */
    var onChange: (CallState?) -> Unit = {}

    /** Where each call goes when it ends (the list of recent calls). */
    var log: CallLog? = null

    /** The drawing context the screen's pictures share with the codecs. */
    val egl: EglBase by lazy { EglBase.create() }

    /** The phone's own speaker and microphone, muted while a laptop's page has the call. */
    private var adm: JavaAudioDeviceModule? = null

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            // While a laptop's page has the call, what the microphone recorded is replaced by the
            // laptop's voice, so every connection sends it (see PageLeg).
            .setAudioBufferCallback { buffer, format, channels, rate, bytes, at ->
                if (format == android.media.AudioFormat.ENCODING_PCM_16BIT) laptopVoice?.into(buffer, channels, rate, bytes)
                at
            }
            .createAudioDeviceModule()
        this.adm = adm
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    // ------------------------------------------------------------------ the call here

    private class Leg(val member: String, val pc: PeerConnection, val offerer: Boolean) {
        @Volatile var remoteSet = false
        @Volatile var sentDescription = false
        @Volatile var gatheredAll = false
        val pending = mutableListOf<IceCandidate>()
        var videoSender: RtpSender? = null
        /** This phone's microphone on it; the other phone's comes back on the same channel. */
        var micSender: RtpSender? = null
        /** Spare voice channels, which the host of a big call fills with the other phones' voices. */
        val audioSlots = mutableListOf<RtpSender>()
        var timeout: Job? = null
    }

    /** The call this phone is in; null when none. All of it is touched on [scope]'s thread only. */
    private class Call(val id: String, val host: Boolean, val video: Boolean) {
        val startedAt = System.currentTimeMillis()
        /** Everyone the call was with or meant for, for the list of recent calls. */
        val names = LinkedHashSet<String>()
        /** This phone's id in it. */
        var me: String = newId()
        /** Not the host: the linked phone that is, and its id. */
        var hostPeer: String = ""
        var hostId: String = ""
        /** The host: each member's linked phone. */
        val linkOf = HashMap<String, String>()
        /** The host: phones called and not yet answered, with when that runs out. */
        val invited = HashMap<String, Job>()
        /** Everyone in it, this phone too. */
        var roster: List<RosterEntry> = emptyList()
        /** Ringing here: the invitation it came with. */
        var invite: CallSignal? = null
        val legs = HashMap<String, Leg>()
        /** Candidates that came before their offer. */
        val early = HashMap<String, MutableList<IceCandidate>>()
        var levels: Job? = null
        /** Past MESH phones: everything through the host. */
        var relay = false
        /** Each member's voice and picture as they come in, for the host to pass on. */
        val remoteAudio = HashMap<String, AudioTrack>()
        val remoteVideo = HashMap<String, VideoTrack>()
        /** In a big call, not the host: the other phones' voices the host passes on, by connection. */
        val passed = HashMap<String, MutableList<AudioTrack>>()
        /** The host of a big call: the grid it sends everyone. */
        var grid: CallCompositor? = null
        /** A test call: the far end on this same phone, and its own grid. */
        var test = false
        var echo: PeerConnection? = null
        var echoGrid: CallCompositor? = null
        val echoPending = mutableListOf<IceCandidate>()
        var echoRemoteSet = false
    }

    private var call: Call? = null

    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var textures: SurfaceTextureHelper? = null
    /** The camera is on (its track on the senders, or in the host's grid). */
    private var cameraOn = false

    /** This phone's screen, while it is shared: it goes in place of the camera. */
    private var screenCapturer: org.webrtc.ScreenCapturerAndroid? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var screenTextures: SurfaceTextureHelper? = null

    /**
     * A laptop's page in the call in this phone's place: one more connection, to the browser, which
     * sends its microphone and camera and is sent everyone's voices and one picture back. This phone
     * passes the laptop's voice and picture on as its own, its own speaker and microphone muted.
     */
    private class PageLeg(val pc: PeerConnection, val laptop: String, val device: String) {
        var mic: AudioTrack? = null
        var cam: VideoTrack? = null
        var picture: RtpSender? = null
        /** Everyone else's voices to the laptop, mixed here (see [ToLaptop]). */
        @Volatile var voices: DataChannel? = null
        var toLaptop: ToLaptop? = null
        var voiceIn: LaptopVoice? = null
        @Volatile var gatheredAll = false
        var camera = false
        var muted = false
    }

    private var page: PageLeg? = null
    /** The laptop's camera, as a picture of this phone's own to send on. */
    private var pageCam: Forward? = null
    /** What the laptop is shown: the one other picture, or a grid of them. */
    private var pageView: Forward? = null
    private var pageGrid: CallCompositor? = null

    /** The laptop's voice while its page has the call, in place of what the microphone records. */
    @Volatile private var laptopVoice: LaptopVoice? = null

    /** What this phone sends as its picture now: its screen while shared, else the laptop's camera or its own while on. */
    private fun myVideo(): VideoTrack? = screenTrack ?: if (page?.camera == true) pageCam?.track else if (cameraOn) videoTrack else null

    // ------------------------------------------------------------------ starting, answering, ending

    /** Calls [name], with the camera on for a [video] call. Needs the microphone (and camera) allowed, and the screen in front. */
    fun call(name: String, video: Boolean, camera: Boolean = video) {
        scope.launch {
            if (call != null) return@launch
            val c = Call(UUID.randomUUID().toString(), host = true, video = video)
            c.roster = listOf(RosterEntry(c.me, peers.deviceName()))
            call = c
            show(CallState(c.id, host = true, outgoing = true, phase = "calling", video = video, camera = camera, speaker = camera))
            if (camera) setCameraNow(true)
            invite(name)
            // Registered with Android once it knows who is being called.
            if (call === c) register()
        }
    }

    /**
     * A test call: this phone's voice and picture through a whole call and back. The far end is a
     * second connection on this same phone, which sends back what it gets: the voice (heard after
     * a moment, as an echo test does) and the picture through the host's grid, so the camera, the
     * codecs, the connection, passing tracks on and the grid are all in it.
     */
    fun testCall(video: Boolean, camera: Boolean = video) {
        scope.launch {
            if (call != null) return@launch
            val c = Call(UUID.randomUUID().toString(), host = true, video = video)
            c.test = true
            c.roster = listOf(RosterEntry(c.me, peers.deviceName()), RosterEntry(ECHO, ECHO_NAME))
            call = c
            show(CallState(c.id, host = true, outgoing = true, phase = "connecting", video = video, camera = camera, test = true,
                members = listOf(CallMember(ECHO, ECHO_NAME, "joining"))))
            if (camera) setCameraNow(true)
            register()
            runCatching { startEcho(c) }.onFailure { Log.w(TAG, "Test call", it); endHere("The test could not start: ${it.message}") }
        }
    }

    private suspend fun startEcho(c: Call) {
        val leg = newLeg(ECHO, offerer = true)
        lateinit var echo: PeerConnection
        var echoAudio: RtpSender? = null
        var echoVideo: RtpSender? = null
        var gotAudio: AudioTrack? = null
        var gotVideo: VideoTrack? = null
        fun wire() {
            // What this phone records, sent back: WebRTC here takes no other voice on a connection
            // that sends (it pours the microphone into each one too), so none is passed on.
            echoAudio?.setTrack(audioTrack, false)
            val v = gotVideo ?: return
            val g = c.echoGrid ?: CallCompositor(factory).also { c.echoGrid = it }
            g.set(listOf("back" to v))
            echoVideo?.setTrack(g.track, false)
        }
        echo = factory.createPeerConnection(rtcConfig(), object : PeerConnection.Observer {
            override fun onIceCandidate(cand: IceCandidate) {
                scope.launch { if (call === c) { if (leg.remoteSet) leg.pc.addIceCandidate(cand) else synchronized(leg.pending) { leg.pending.add(cand) } } }
            }
            override fun onTrack(t: RtpTransceiver) {
                when (val track = t.receiver.track()) {
                    // Not played here: only what comes back is heard.
                    is AudioTrack -> scope.launch { if (gotAudio == null) { track.setVolume(0.0); gotAudio = track; wire() } }
                    is VideoTrack -> scope.launch { gotVideo = track; wire() }
                }
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceCandidatesRemoved(cs: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        }) ?: error("Could not start the far end")
        c.echo = echo
        val offer = leg.pc.awaitCreate(true)
        leg.pc.awaitSetLocal(offer)
        leg.sentDescription = true
        echo.awaitSetRemote(offer)
        c.echoRemoteSet = true
        synchronized(c.echoPending) { c.echoPending.forEach { echo.addIceCandidate(it) }; c.echoPending.clear() }
        // Asked for once: each time the list is asked for, the one before is let go.
        val ts = echo.transceivers
        ts.forEach { t -> t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV }
        echoAudio = ts.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }?.sender
        echoVideo = ts.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.sender
        wire()
        val answer = echo.awaitCreate(false)
        echo.awaitSetLocal(answer)
        leg.pc.awaitSetRemote(answer)
        leg.remoteSet = true
        drainPending(leg)
        connectTimeout(c)
    }

    /** Debug builds: what the call is doing now, logged (who, and each connection's sound and picture in and out). */
    fun logState() {
        scope.launch {
            val c = call
            Log.i(TAG, "State: " + _state.value?.let { st -> "phase=${st.phase} relay=${st.relay} camera=${st.camera} members=" + st.members.joinToString { "${it.name}:${it.phase}:video=${it.video != null}:speaking=${it.speaking}:${it.path}" } })
            c?.legs?.forEach { (m, leg) ->
                leg.pc.getStats { r ->
                    val parts = r.statsMap.values.filter { it.type == "inbound-rtp" || it.type == "outbound-rtp" }.map { st ->
                        val k = st.members["kind"]
                        "${st.type}/$k bytes=${st.members["bytesReceived"] ?: st.members["bytesSent"]} frames=${st.members["framesDecoded"] ?: st.members["framesEncoded"] ?: "-"} level=${st.members["audioLevel"] ?: "-"} size=${st.members["frameWidth"] ?: "-"}x${st.members["frameHeight"] ?: "-"}"
                    }
                    Log.i(TAG, "Leg $m: " + parts.joinToString(" | "))
                }
            }
        }
    }

    /** The host adds [name] to the call under way. */
    fun add(name: String) {
        scope.launch {
            val c = call ?: return@launch
            if (!c.host || name in c.invited || c.linkOf.values.contains(name)) return@launch
            if (c.test || c.roster.size + c.invited.size >= MAX) return@launch
            invite(name)
        }
    }

    private suspend fun invite(name: String) {
        val c = call ?: return
        val peer = peers.find(name)
        if (peer == null) { inviteGone(name, "$name is not linked"); return }
        val s = _state.value ?: return
        c.names += name
        if (s.members.none { it.name == name && it.phase != "left" }) show(s.copy(members = s.members + CallMember("?$name", name, "invited")))
        val sent = sendTo(name, CallSignal(c.id, "invite", from = c.me, video = c.video, members = c.roster))
        if (!sent) { inviteGone(name, "$name could not be reached"); return }
        c.invited[name] = scope.launch { delay(RING_MS); if (call === c && name in c.invited) { inviteGone(name, "No answer"); sendTo(name, CallSignal(c.id, "end", from = c.me, why = "Missed call")) } }
    }

    /** A phone called did not come into the call: off the screen, and the call ends if nobody else is in it. */
    private fun inviteGone(name: String, why: String) {
        val c = call ?: return
        c.invited.remove(name)?.cancel()
        val s = _state.value ?: return
        show(s.copy(members = s.members.filterNot { it.phase == "invited" && it.name == name }, why = if (s.phase == "active") s.why else why))
        if (c.roster.size <= 1 && c.invited.isEmpty()) endHere(why)
    }

    /** Answers the call ringing here, with the camera on for [video]. Needs the microphone (and camera) allowed. */
    fun answer(video: Boolean = false) {
        scope.launch {
            val c = call ?: return@launch
            val s = _state.value ?: return@launch
            if (c.host || s.phase != "ringing") return@launch
            show(s.copy(phase = "connecting", camera = video, speaker = video || s.video))
            if (video) setCameraNow(true)
            register()
            if (!sendTo(c.hostPeer, CallSignal(c.id, "accept", from = c.me))) endHere("${c.hostPeer} could not be reached")
            else connectTimeout(c)
        }
    }

    /** Ends the call on this phone (declines it while it rings), and tells the others. */
    fun hangUp() {
        scope.launch {
            val c = call ?: return@launch
            val s = _state.value
            when {
                c.host -> {
                    val tell = c.linkOf.values.toSet() + c.invited.keys
                    tell.forEach { sendTo(it, CallSignal(c.id, "end", from = c.me, why = "Call ended")) }
                }
                s?.phase == "ringing" -> sendTo(c.hostPeer, CallSignal(c.id, "decline", from = c.me, why = "Declined"))
                else -> sendTo(c.hostPeer, CallSignal(c.id, "leave", from = c.me))
            }
            endHere(if (s?.phase == "ringing") "Declined" else "Call ended")
        }
    }

    fun setMuted(muted: Boolean) {
        scope.launch {
            val s = _state.value ?: return@launch
            audioTrack?.setEnabled(!muted)
            show(s.copy(muted = muted))
            tellMedia()
        }
    }

    fun setCamera(on: Boolean) {
        scope.launch {
            val s = _state.value ?: return@launch
            setCameraNow(on)
            show((_state.value ?: s).copy(camera = on, speaker = if (on && !s.camera) true.also { setSpeakerNow(true) } else s.speaker))
            tellMedia()
        }
    }

    /**
     * Shares this phone's screen in the call, in place of its camera, from the permission Android
     * gave ([data], MediaProjection's). The call's service takes on screen capture first (Android
     * wants that before the capture starts), then the screen goes, 15 frames a second, at most
     * 1280 on its long side.
     */
    fun startScreen(data: android.content.Intent) {
        scope.launch {
            val s = _state.value ?: return@launch
            if (screenTrack != null) return@launch
            show(s.copy(sharing = true))
            delay(700)
            if (call == null) return@launch
            val cap = org.webrtc.ScreenCapturerAndroid(data, object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() { stopScreen() }
            })
            val src = factory.createVideoSource(true)
            val tex = SurfaceTextureHelper.create("screen", egl.eglBaseContext)
            runCatching {
                cap.initialize(tex, app, src.capturerObserver)
                val m = app.resources.displayMetrics
                val k = minOf(1f, 1280f / maxOf(m.widthPixels, m.heightPixels))
                cap.startCapture((m.widthPixels * k).toInt() and 1.inv(), (m.heightPixels * k).toInt() and 1.inv(), 15)
            }.onFailure {
                Log.w(TAG, "Screen share", it)
                runCatching { cap.dispose() }; runCatching { tex.dispose() }; runCatching { src.dispose() }
                _state.value?.let { st -> show(st.copy(sharing = false)) }
                return@launch
            }
            screenCapturer = cap; screenSource = src; screenTextures = tex
            screenTrack = factory.createVideoTrack("screen", src)
            refreshSenders()
            tellMedia()
        }
    }

    fun stopScreen() {
        scope.launch {
            stopScreenNow()
            _state.value?.let { show(it.copy(sharing = false)) }
            refreshSenders()
            tellMedia()
        }
    }

    private fun stopScreenNow() {
        val cap = screenCapturer ?: return
        screenCapturer = null
        runCatching { cap.stopCapture() }; runCatching { cap.dispose() }
        val t = screenTrack; screenTrack = null
        call?.legs?.values?.forEach { it.videoSender?.setTrack(null, false) }
        runCatching { t?.dispose() }; runCatching { screenSource?.dispose() }; runCatching { screenTextures?.dispose() }
        screenSource = null; screenTextures = null
    }

    fun flipCamera() {
        val cap = capturer ?: return
        cap.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) { scope.launch { _state.value?.let { show(it.copy(frontCamera = front)) } } }
            override fun onCameraSwitchError(e: String?) { Log.w(TAG, "Camera switch: $e") }
        })
    }

    fun setSpeaker(on: Boolean) {
        scope.launch {
            val s = _state.value ?: return@launch
            setSpeakerNow(on)
            show(s.copy(speaker = on))
        }
    }

    private fun setSpeakerNow(on: Boolean) {
        val r = registered
        val want = r?.endpoints?.firstOrNull { it.type == if (on) CallEndpointCompat.TYPE_SPEAKER else CallEndpointCompat.TYPE_EARPIECE }
        val control = r?.control
        if (control != null && want != null) {
            scope.launch { runCatching { control.requestEndpointChange(want) } }
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
    }

    /** Ends the call here: every connection closed, the camera off, the screen told why. */
    private fun endHere(why: String) {
        val c = call ?: return
        call = null
        c.invited.values.forEach { it.cancel() }
        c.levels?.cancel()
        c.legs.values.forEach { closeLeg(it) }
        c.legs.clear()
        page?.let { pageGone(it, quiet = true) }
        stopScreenNow()
        runCatching { c.echo?.close(); c.echo?.dispose() }
        c.echoGrid?.release(); c.grid?.release()
        c.echo = null; c.echoGrid = null; c.grid = null
        setCameraNow(false)
        audioTrack?.let { runCatching { it.setEnabled(false); it.dispose() } }
        audioSource?.let { runCatching { it.dispose() } }
        audioTrack = null; audioSource = null
        val s = _state.value
        if (s != null && s.id == c.id && !c.test) runCatching { log?.add(record(c, s, why)) }
        if (s != null && s.id == c.id) show(s.copy(phase = "ended", why = why, members = s.members.map { it.copy(video = null) }))
        if (Build.VERSION.SDK_INT >= 31) runCatching { app.getSystemService(AudioManager::class.java).clearCommunicationDevice() }
        unregister()
        // The ended call stays on the screen a moment, then goes.
        scope.launch { delay(2_000); if (_state.value?.id == c.id && _state.value?.phase == "ended") show(null) }
    }

    /** The call as the list of recent calls keeps it: who, which way, how long, and how it ended. */
    private fun record(c: Call, s: CallState, why: String): CallRecord {
        val names = (c.names + s.members.map { it.name }).distinct()
        val outcome = when {
            s.since > 0 -> "answered"
            s.outgoing -> when (why) { "No answer", "Missed call" -> "no answer"; "Declined" -> "declined"; "Busy" -> "busy"; "Call ended" -> "cancelled"; else -> "failed" }
            why == "Declined" -> "declined"
            else -> "missed"
        }
        return CallRecord(
            c.id, names, s.outgoing, s.video || s.camera, c.startedAt,
            if (s.since > 0) System.currentTimeMillis() - s.since else 0L, outcome, fresh = outcome == "missed",
        )
    }

    // ------------------------------------------------------------------ the camera

    private fun setCameraNow(on: Boolean) {
        if (on) {
            if (videoTrack == null) {
                val names = Camera2Enumerator(app).run { deviceNames.firstOrNull { isFrontFacing(it) } ?: deviceNames.firstOrNull() }
                val cap = names?.let { Camera2Enumerator(app).createCapturer(it, null) }
                if (cap == null) { Log.w(TAG, "No camera"); return }
                val src = factory.createVideoSource(false)
                val tex = SurfaceTextureHelper.create("camera", egl.eglBaseContext)
                cap.initialize(tex, app, src.capturerObserver)
                capturer = cap; textures = tex; videoSource = src
                videoTrack = factory.createVideoTrack("camera", src)
            }
            runCatching { capturer?.startCapture(1280, 720, 30) }
            videoTrack?.setEnabled(true)
            cameraOn = true
            refreshSenders()
            _local.value = videoTrack
        } else {
            cameraOn = false
            refreshSenders()
            _local.value = null
            runCatching { capturer?.stopCapture() }
            if (call == null) {
                runCatching { capturer?.dispose() }; runCatching { textures?.dispose() }
                runCatching { videoTrack?.dispose() }; runCatching { videoSource?.dispose() }
                capturer = null; textures = null; videoTrack = null; videoSource = null
            }
        }
    }

    // ------------------------------------------------------------------ Android's own call handling

    /**
     * Core-Telecom: the call is registered with Android once it is under way (this phone calling,
     * or answering), so it gets a call's priority, Bluetooth headsets and car kits carry it and
     * hang it up, and the speaker and earpiece are switched the way the system does. Where that is
     * not available the call goes on without it, with the audio mode set here.
     */
    private val callsManager: CallsManager? by lazy {
        runCatching { CallsManager(app).also { it.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING) } }
            .onFailure { Log.w(TAG, "Telecom unavailable", it) }.getOrNull()
    }

    private class Registered(val id: String) {
        @Volatile var control: CallControlScope? = null
        @Volatile var endpoints: List<CallEndpointCompat> = emptyList()
        val done = CompletableDeferred<Unit>()
    }

    @Volatile private var registered: Registered? = null

    private fun register() {
        val s = _state.value ?: return
        if (registered?.id == s.id) return
        val r = Registered(s.id)
        registered = r
        val cm = callsManager
        if (cm == null) { audioMode(true); if (s.speaker) setSpeakerNow(true); return }
        val type = if (s.video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL
        scope.launch {
            runCatching {
                cm.addCall(
                    CallAttributesCompat(
                        s.peer, Uri.parse("l87:" + Uri.encode(s.peer)),
                        if (s.outgoing) CallAttributesCompat.DIRECTION_OUTGOING else CallAttributesCompat.DIRECTION_INCOMING,
                        type,
                    ),
                    onAnswer = { },
                    onDisconnect = { if (call?.id == r.id) hangUp() },
                    onSetActive = {},
                    onSetInactive = {},
                ) {
                    r.control = this
                    if (!s.outgoing) launch { answer(type) }
                    launch {
                        availableEndpoints.collect {
                            r.endpoints = it
                            if (_state.value?.speaker == true) scope.launch { setSpeakerNow(true) }
                        }
                    }
                    launch { r.done.await(); disconnect(DisconnectCause(DisconnectCause.LOCAL)) }
                }
            }.onFailure { Log.w(TAG, "Telecom call", it); audioMode(true) }
        }
    }

    private fun unregister() {
        val r = registered ?: return
        registered = null
        r.done.complete(Unit)
        audioMode(false)
    }

    private fun audioMode(call: Boolean) = runCatching {
        app.getSystemService(AudioManager::class.java).mode = if (call) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
    }

    // ------------------------------------------------------------------ the other phones' signals

    /** A sealed signal from the linked phone [from], which came in on [deviceId]. False when it does not open. */
    fun receive(from: String, deviceId: String, w: CallWire): Boolean {
        val text = CallCrypto.open(keyHere(deviceId), w) ?: return false
        val sig = runCatching { json.decodeFromString<CallSignal>(text) }.getOrNull() ?: return false
        scope.launch { runCatching { handle(from, sig) }.onFailure { Log.w(TAG, "Signal ${sig.kind}", it) } }
        return true
    }

    private suspend fun handle(fromPeer: String, sig: CallSignal) {
        val c = call
        // A call from a phone with the older app (no member ids): it cannot be answered here.
        if (sig.from.isEmpty()) {
            if (sig.kind == "offer") sendTo(fromPeer, CallSignal(sig.call, "end", why = "Update Localhost 8787 on this phone to call it"))
            return
        }
        if (sig.kind == "invite") {
            if (c != null) { sendTo(fromPeer, CallSignal(sig.call, "decline", from = "x", why = "Busy")); return }
            val n = Call(sig.call, host = false, video = sig.video)
            n.hostPeer = fromPeer; n.hostId = sig.from; n.invite = sig
            n.names += fromPeer
            call = n
            val others = sig.members.filter { it.id != sig.from }.map { CallMember(it.id, it.name, "joining") }
            show(CallState(n.id, host = false, outgoing = false, phase = "ringing", video = sig.video, members = listOf(CallMember(sig.from, fromPeer, "joining")) + others))
            sendTo(fromPeer, CallSignal(n.id, "ringing", from = n.me))
            scope.launch { delay(RING_MS); if (call === n && _state.value?.phase == "ringing") endHere("Missed call") }
            return
        }
        if (c == null || c.id != sig.call) return

        if (c.host) {
            val memberOf = c.linkOf.entries.firstOrNull { it.value == fromPeer }?.key
            // For someone else in the call: passed on as it is, from a phone that is in it.
            if (sig.to.isNotEmpty() && sig.to != c.me) {
                if (memberOf == null || memberOf != sig.from) return
                c.linkOf[sig.to]?.let { sendWire(it, sig) }
                return
            }
            when (sig.kind) {
                "ringing" -> markInvited(fromPeer, "Ringing")
                "accept" -> {
                    if (fromPeer !in c.invited) return
                    c.invited.remove(fromPeer)?.cancel()
                    c.linkOf[sig.from] = fromPeer
                    val s = _state.value ?: return
                    show(s.copy(phase = if (s.phase == "calling") "connecting" else s.phase,
                        members = s.members.filterNot { it.phase == "invited" && it.name == fromPeer } + CallMember(sig.from, fromPeer, "joining")))
                    setRoster(c.roster + RosterEntry(sig.from, fromPeer))
                    if (_state.value?.phase == "connecting") connectTimeout(c)
                }
                "decline" -> if (fromPeer in c.invited) inviteGone(fromPeer, sig.why.ifEmpty { "Declined" })
                "leave" -> if (memberOf == sig.from) memberLeft(sig.from, "Left")
                else -> if (memberOf == sig.from) media(sig)
            }
        } else {
            if (fromPeer != c.hostPeer) return
            when (sig.kind) {
                "members" -> applyRoster(sig.members, sig.relay)
                "end" -> endHere(sig.why.ifEmpty { "Call ended" })
                else -> media(sig)
            }
        }
    }

    private fun markInvited(name: String, why: String) {
        val s = _state.value ?: return
        show(s.copy(why = if (s.phase == "calling") why else s.why))
    }

    /** The host: a new roster, told to everyone in it and acted on here. */
    private suspend fun setRoster(r: List<RosterEntry>) {
        val c = call ?: return
        c.roster = r
        // Past a mesh's worth, everything goes through this phone.
        c.relay = r.size > MESH
        c.linkOf.values.forEach { sendTo(it, CallSignal(c.id, "members", from = c.me, members = r, relay = c.relay)) }
        applyRoster(r, c.relay)
    }

    private suspend fun memberLeft(id: String, why: String) {
        val c = call ?: return
        if (c.host) {
            c.linkOf.remove(id)
            setRoster(c.roster.filterNot { it.id == id })
            if (c.roster.size <= 1 && c.invited.isEmpty()) endHere("Call ended")
        }
    }

    /** Everyone in the call now: new ones connected to (each pair once), gone ones let go. */
    private suspend fun applyRoster(r: List<RosterEntry>, relay: Boolean = false) {
        val c = call ?: return
        c.roster = r
        c.relay = relay
        val ids = r.map { it.id }.toSet()
        if (c.me !in ids && !c.host) return
        // Through the host: no connections between members.
        val keep = if (relay && !c.host) setOf(c.hostId) else ids
        c.legs.keys.filter { it !in ids || it !in keep }.forEach { id -> c.legs.remove(id)?.let { closeLeg(it) } }
        c.remoteAudio.keys.retainAll(ids); c.remoteVideo.keys.retainAll(ids); c.passed.keys.retainAll(c.legs.keys)
        val s = _state.value ?: return
        val known = s.members.associateBy { it.id }
        val members = r.filter { it.id != c.me }.map { e ->
            known[e.id] ?: CallMember(e.id, if (e.id == c.hostId) c.hostPeer else e.name, "joining")
        } + s.members.filter { it.phase == "invited" } +
            s.members.filter { it.id !in ids && it.phase != "invited" && !it.id.startsWith("?") }.map { it.copy(phase = "left", video = null, speaking = false) }
        // Through the host, the others are there as long as the host is.
        val hostPhase = members.firstOrNull { it.id == c.hostId }?.phase
        val shown = if (relay && !c.host && hostPhase != null) members.map { if (it.id != c.hostId && it.phase == "joining") it.copy(phase = hostPhase) else it } else members
        show(s.copy(members = shown.distinctBy { it.id }, relay = relay))
        if (!c.host && r.none { it.id != c.me && it.id != c.hostId } && r.size <= 1) { endHere("Call ended"); return }
        refreshSenders()
        setBitrates()
        for (e in r) if (e.id != c.me && e.id !in c.legs && c.me < e.id && e.id in keep) startLeg(e.id)
    }

    /**
     * What each connection sends: in a mesh, this phone's camera (and nothing on the spare voice
     * channels); as the host of a big call, the grid of everyone, and on each phone's spare
     * channels the other phones' voices.
     */
    private fun refreshSenders() {
        val c = call ?: run { return }
        if (!c.host || !c.relay || c.test) {
            c.legs.values.forEach { leg ->
                leg.videoSender?.setTrack(myVideo(), false)
                if (!c.test) leg.audioSlots.forEach { it.setTrack(null, false) }
            }
            if (!c.test) { c.grid?.release(); c.grid = null }
            refreshPage()
            return
        }
        val grid = c.grid ?: CallCompositor(factory).also { c.grid = it }
        grid.set(buildList {
            myVideo()?.let { add(c.me to it) }
            for (e in c.roster) if (e.id != c.me) c.remoteVideo[e.id]?.let { add(e.id to it) }
        })
        for ((member, leg) in c.legs) {
            leg.videoSender?.setTrack(grid.track, false)
            val others = c.roster.map { it.id }.filter { it != member && it != c.me }.mapNotNull { c.remoteAudio[it] }
            leg.audioSlots.forEachIndexed { i, sender -> sender.setTrack(others.getOrNull(i), false) }
        }
        refreshPage()
    }

    // ------------------------------------------------------------------ a laptop's page in the call

    /**
     * The laptop's page takes the call: [offer] is its browser's, with its microphone (and camera)
     * on the voice and picture channels, and a data channel ("voices") to hear everyone on. The
     * answer is returned once it carries this phone's addresses. [laptop] is the name to show.
     *
     * WebRTC on Android pours what the microphone records into every connection that sends a
     * voice, so no connection can send any other voice as well (fed from two threads at once, it
     * stops the app). So the laptop's voice goes in where the microphone's would ([LaptopVoice],
     * every connection then sends it), and everyone else's comes out of the call's own voices,
     * mixed here and sent to the laptop as sound over the data channel ([ToLaptop]). The pictures
     * go as pictures, both ways.
     */
    suspend fun pageJoin(offer: String, laptop: String, device: String): String = scope.async {
        val c = call ?: error("There is no call")
        page?.let { pageGone(it, quiet = true) }
        lateinit var leg: PageLeg
        val pc = factory.createPeerConnection(rtcConfig(), object : PeerConnection.Observer {
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                Log.i(TAG, "Call ${c.id} on $laptop: $s")
                if (s == PeerConnection.IceConnectionState.FAILED || s == PeerConnection.IceConnectionState.CLOSED)
                    scope.launch { if (page === leg) pageGone(leg, quiet = false) }
            }
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) leg.gatheredAll = true
            }
            override fun onDataChannel(d: DataChannel) { if (d.label() == "voices") leg.voices = d }
            override fun onIceCandidate(cand: IceCandidate) {}
            override fun onTrack(t: RtpTransceiver) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceCandidatesRemoved(cs: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        }) ?: error("Could not start the connection")
        leg = PageLeg(pc, laptop, device)
        pc.awaitSetRemote(SessionDescription(SessionDescription.Type.OFFER, offer))
        // Asked for once: each time the list is asked for, the one before is let go.
        val ts = pc.transceivers
        // Only received: a voice sent on this connection would carry what the microphone records.
        ts.filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }.forEachIndexed { i, t ->
            t.direction = RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            if (i == 0) leg.mic = t.receiver.track() as? AudioTrack
        }
        ts.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.let { t ->
            t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            leg.cam = t.receiver.track() as? VideoTrack
            leg.picture = t.sender
        }
        val answer = pc.awaitCreate(false)
        pc.awaitSetLocal(answer)
        withTimeoutOrNull(1_500) { while (!leg.gatheredAll) delay(30) }
        val sdp = pc.localDescription?.description ?: error("No answer")
        // The laptop has it now: its voice in place of this phone's microphone, this phone's
        // speaker quiet (the voices still come out of the call to be mixed), its camera off.
        page = leg
        leg.mic?.let { mic ->
            mic.setVolume(0.0)
            val v = LaptopVoice()
            mic.addSink(v)
            leg.voiceIn = v
            laptopVoice = v
        }
        leg.toLaptop = ToLaptop { leg.voices }
        adm?.setSpeakerMute(true)
        if (cameraOn) setCameraNow(false)
        leg.cam?.let { pageCam = Forward(factory, it, "laptop-camera") }
        refreshSenders()
        _state.value?.let { show(it.copy(laptop = laptop, camera = false)) }
        tellMedia()
        sdp
    }.await()

    /** The laptop's microphone and camera, as its page has them. */
    fun pageMedia(muted: Boolean, camera: Boolean) {
        scope.launch {
            val leg = page ?: return@launch
            leg.muted = muted; leg.camera = camera
            refreshSenders()
            _state.value?.let { show(it.copy(laptopMuted = muted, laptopCamera = camera)) }
            tellMedia()
        }
    }

    /** The call back on this phone: from its own screen ("Take back"), or the laptop's page handing it back. */
    fun pageLeave() {
        scope.launch { page?.let { pageGone(it, quiet = false) } }
    }

    private fun pageGone(leg: PageLeg, quiet: Boolean) {
        if (page !== leg) { closePage(leg); return }
        page = null
        laptopVoice = null
        leg.voiceIn?.let { v -> runCatching { leg.mic?.removeSink(v) } }
        leg.toLaptop?.release()
        refreshSenders()
        closePage(leg)
        pageCam?.release(); pageCam = null
        pageView?.release(); pageView = null
        pageGrid?.release(); pageGrid = null
        adm?.setSpeakerMute(false)
        if (quiet) return
        _state.value?.let { show(it.copy(laptop = "", laptopMuted = false, laptopCamera = false)) }
        scope.launch { tellMedia() }
    }

    private fun closePage(leg: PageLeg) {
        runCatching { leg.voices?.close() }
        runCatching { leg.pc.close() }
        runCatching { leg.pc.dispose() }
    }

    /**
     * What the laptop is sent: every voice in the call (each other phone's, and those a big call's
     * host passes on), mixed and sent as sound; and the one other picture, or a grid of them.
     */
    private fun refreshPage() {
        val c = call ?: return
        val leg = page ?: return
        val voices = (c.roster.map { it.id }.filter { it != c.me }.mapNotNull { c.remoteAudio[it] } + c.passed.values.flatten()).distinct()
        leg.toLaptop?.hear(voices)
        val pictures = c.roster.map { it.id }.filter { it != c.me }.mapNotNull { id -> c.remoteVideo[id]?.let { id to it } }
        val out: VideoTrack? = when {
            pictures.isEmpty() -> null
            pictures.size == 1 -> {
                pageGrid?.release(); pageGrid = null
                val v = pictures[0].second
                if (pageView?.from !== v) { pageView?.release(); pageView = Forward(factory, v, "laptop-view") }
                pageView?.track
            }
            else -> {
                pageView?.release(); pageView = null
                val g = pageGrid ?: CallCompositor(factory).also { pageGrid = it }
                g.set(pictures)
                g.track
            }
        }
        leg.picture?.setTrack(out, false)
    }

    /** Media steps between two phones in the call. */
    private suspend fun media(sig: CallSignal) {
        val c = call ?: return
        if (sig.to.isNotEmpty() && sig.to != c.me) return
        when (sig.kind) {
            "offer" -> {
                val leg = c.legs[sig.from] ?: newLeg(sig.from, offerer = false)
                c.early.remove(sig.from)?.let { synchronized(leg.pending) { leg.pending.addAll(it) } }
                leg.pc.awaitSetRemote(SessionDescription(SessionDescription.Type.OFFER, sig.sdp))
                leg.remoteSet = true
                // The video channel the offer brought: this phone's camera goes on it. The other voice
                // channels are the spare ones a big call's host fills.
                // Asked for once: each time the list is asked for, the one before is let go.
                val ts = leg.pc.transceivers
                ts.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.let { t ->
                    t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                    t.sender.setTrack(myVideo(), false)
                    leg.videoSender = t.sender
                }
                ts.filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO && it.sender.id() != leg.micSender?.id() }.forEach { t ->
                    t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                    leg.audioSlots += t.sender
                }
                refreshSenders()
                setBitrates()
                drainPending(leg)
                val answer = leg.pc.awaitCreate(false)
                leg.pc.awaitSetLocal(answer)
                val sdp = gathered(leg)
                sendToMember(sig.from, CallSignal(c.id, "answer", sdp = sdp))
                tellMedia(sig.from)
            }
            "answer" -> {
                val leg = c.legs[sig.from] ?: return
                leg.pc.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, sig.sdp))
                leg.remoteSet = true
                drainPending(leg)
                tellMedia(sig.from)
            }
            "ice" -> {
                val cand = IceCandidate(sig.mid, sig.line, sig.cand)
                val leg = c.legs[sig.from]
                if (leg == null) c.early.getOrPut(sig.from) { mutableListOf() }.add(cand)
                else if (leg.remoteSet) leg.pc.addIceCandidate(cand) else synchronized(leg.pending) { leg.pending.add(cand) }
            }
            "media" -> updateMember(sig.from) { it.copy(camera = sig.camera, mic = sig.mic, screen = sig.screen) }
        }
    }

    // ------------------------------------------------------------------ WebRTC

    private suspend fun startLeg(member: String) {
        val c = call ?: return
        val leg = newLeg(member, offerer = true)
        val offer = leg.pc.awaitCreate(true)
        leg.pc.awaitSetLocal(offer)
        val sdp = gathered(leg)
        sendToMember(member, CallSignal(c.id, "offer", sdp = sdp))
    }

    private fun rtcConfig() = PeerConnection.RTCConfiguration(
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

    private fun newLeg(member: String, offerer: Boolean): Leg {
        val c = call ?: error("No call")
        val rtc = rtcConfig()
        lateinit var leg: Leg
        val id = c.id
        val pc = factory.createPeerConnection(rtc, object : PeerConnection.Observer {
            override fun onIceCandidate(cand: IceCandidate) {
                // Sent on after the description, which already carries what was found by then.
                scope.launch { if (leg.sentDescription && call?.id == id) sendToMember(member, CallSignal(id, "ice", mid = cand.sdpMid ?: "", line = cand.sdpMLineIndex, cand = cand.sdp)) }
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                Log.i(TAG, "Call $id with $member: $s")
                scope.launch {
                    if (call?.id != id || call?.legs?.get(member) !== leg) return@launch
                    when (s) {
                        PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> connected(leg)
                        PeerConnection.IceConnectionState.FAILED -> legFailed(leg)
                        else -> {}
                    }
                }
            }
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) leg.gatheredAll = true
            }
            override fun onTrack(t: RtpTransceiver) {
                val track = t.receiver.track()
                scope.launch {
                    val cc = call ?: return@launch
                    when (track) {
                        is VideoTrack -> { cc.remoteVideo[member] = track; updateMember(member) { it.copy(video = track) }; refreshSenders() }
                        // The other phone's own voice is on the channel this phone's mic is on; the rest are voices passed on.
                        is AudioTrack -> if (t.sender.id() == leg.micSender?.id()) { cc.remoteAudio[member] = track; refreshSenders() }
                            else { cc.passed.getOrPut(member) { mutableListOf() }.let { if (track !in it) it += track }; refreshPage() }
                    }
                }
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
        val audio = audioTrack ?: run {
            val src = factory.createAudioSource(MediaConstraints())
            audioSource = src
            factory.createAudioTrack("voice", src).also {
                it.setEnabled(_state.value?.muted != true)
                audioTrack = it
            }
        }
        leg = Leg(member, pc, offerer)
        leg.micSender = pc.addTrack(audio, listOf("call"))
        if (offerer) {
            val t = pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
            t.sender.setTrack(myVideo(), false)
            leg.videoSender = t.sender
            // Spare voice channels, empty unless a big call's host fills them.
            if (!c.test) repeat(SLOTS) {
                leg.audioSlots += pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)).sender
            }
        }
        c.legs[member] = leg
        return leg
    }

    /**
     * The local description, sent as soon as it has this phone's own addresses and the public one
     * STUN gives (or after a second), so a call starts quickly; whatever is found later goes on
     * after it ("ice").
     */
    private suspend fun gathered(leg: Leg): String {
        withTimeoutOrNull(GATHER_MS) {
            while (!leg.gatheredAll && leg.pc.localDescription?.description?.contains(" typ srflx") != true) delay(30)
        }
        leg.sentDescription = true
        return leg.pc.localDescription?.description ?: error("No description")
    }

    private fun drainPending(leg: Leg) {
        val list = synchronized(leg.pending) { leg.pending.toList().also { leg.pending.clear() } }
        list.forEach { leg.pc.addIceCandidate(it) }
    }

    /** Each phone's picture to each other phone, at what a mesh of this many can carry. */
    private fun setBitrates() {
        val c = call ?: return
        val others = c.legs.size.coerceAtLeast(1)
        val bps = when {
            // A big call: the host's grid to each phone, and each phone's picture to the host.
            c.relay && c.host -> 2_500_000
            c.relay -> 1_000_000
            others == 1 -> 1_800_000
            others == 2 -> 1_000_000
            else -> 700_000
        }
        c.legs.values.forEach { leg ->
            val sender = leg.videoSender ?: return@forEach
            runCatching {
                val p = sender.parameters
                p.encodings.forEach { it.maxBitrateBps = bps }
                p.degradationPreference = org.webrtc.RtpParameters.DegradationPreference.BALANCED
                sender.parameters = p
            }
        }
    }

    private fun connectTimeout(c: Call) {
        scope.launch {
            delay(CONNECT_MS)
            if (call === c && _state.value?.phase == "connecting") {
                if (c.host) hangUp() else { sendTo(c.hostPeer, CallSignal(c.id, "leave", from = c.me)); endHere("No way through between the networks") }
            }
        }
    }

    private fun connected(leg: Leg) {
        val c = call ?: return
        val s = _state.value ?: return
        if (s.phase != "active") {
            show(s.copy(phase = "active", since = if (s.since > 0) s.since else System.currentTimeMillis(), why = ""))
            registered?.control?.let { ctl -> scope.launch { runCatching { ctl.setActive() } } }
            if (c.levels == null) c.levels = scope.launch { listen(c) }
        }
        updateMember(leg.member) { it.copy(phase = "connected") }
        if (c.relay && !c.host && leg.member == c.hostId) _state.value?.let { st ->
            show(st.copy(members = st.members.map { if (it.phase == "joining") it.copy(phase = "connected") else it }))
        }
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
            scope.launch { updateMember(leg.member) { it.copy(path = path) } }
        }
    }

    private suspend fun legFailed(leg: Leg) {
        val c = call ?: return
        c.legs.remove(leg.member)
        closeLeg(leg)
        val others = c.roster.count { it.id != c.me }
        if (others <= 1) {
            if (c.host) hangUp() else { sendTo(c.hostPeer, CallSignal(c.id, "leave", from = c.me)); endHere("No way through between the networks") }
            return
        }
        updateMember(leg.member) { it.copy(phase = "left", video = null, path = "", speaking = false) }
        if (c.host && leg.member in c.linkOf) memberLeft(leg.member, "Lost")
    }

    private fun closeLeg(leg: Leg) {
        leg.timeout?.cancel()
        runCatching { leg.pc.close(); leg.pc.dispose() }
    }

    /** Who is talking, from each connection's sound levels, a few times a second: for the screen's rings. */
    private suspend fun listen(c: Call) {
        while (call === c) {
            delay(350)
            for (leg in c.legs.values.toList()) {
                val level = suspendCancellableCoroutine<Double> { k ->
                    leg.pc.getStats { r ->
                        val v = r.statsMap.values.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "audio" }?.members?.get("audioLevel") as? Double
                        if (k.isActive) k.resume(v ?: 0.0)
                    }
                }
                updateMember(leg.member) { if (it.speaking == (level > SPEAKING)) it else it.copy(speaking = level > SPEAKING) }
                c.grid?.speaking(leg.member, level > SPEAKING)
            }
            val mine = c.legs.values.firstOrNull()?.let { leg ->
                suspendCancellableCoroutine<Double> { k ->
                    leg.pc.getStats { r ->
                        val v = r.statsMap.values.firstOrNull { it.type == "media-source" && it.members["kind"] == "audio" }?.members?.get("audioLevel") as? Double
                        if (k.isActive) k.resume(v ?: 0.0)
                    }
                }
            } ?: 0.0
            _state.value?.let { s -> val now = mine > SPEAKING && !s.muted; if (s.speaking != now) show(s.copy(speaking = now)) }
            c.grid?.speaking(c.me, (_state.value?.speaking) == true)
        }
    }

    private fun updateMember(id: String, f: (CallMember) -> CallMember) {
        val s = _state.value ?: return
        if (s.members.none { it.id == id }) return
        val next = s.copy(members = s.members.map { if (it.id == id) f(it) else it })
        if (next != s) show(next)
    }

    // ------------------------------------------------------------------ sending

    /** This phone's camera and microphone, told to one phone in the call, or all of them. */
    private suspend fun tellMedia(only: String? = null) {
        val c = call ?: return
        val s = _state.value ?: return
        val to = only?.let { listOf(it) } ?: c.roster.map { it.id }.filter { it != c.me }
        val p = page
        val camera = s.camera || screenTrack != null || p?.camera == true
        val mic = if (p != null) !p.muted else !s.muted
        to.forEach { sendToMember(it, CallSignal(c.id, "media", camera = camera, mic = mic, screen = screenTrack != null)) }
    }

    /** A media step to another member: straight to it when it is linked here, else through the host. */
    private suspend fun sendToMember(member: String, sig: CallSignal): Boolean {
        val c = call ?: return false
        if (c.test) {
            // The far end is on this phone: its candidates are handed over here.
            if (sig.kind == "ice") {
                val cand = IceCandidate(sig.mid, sig.line, sig.cand)
                val e = c.echo
                if (e != null && c.echoRemoteSet) e.addIceCandidate(cand) else synchronized(c.echoPending) { c.echoPending.add(cand) }
            }
            return true
        }
        val s = sig.copy(from = c.me, to = member)
        return when {
            c.host -> c.linkOf[member]?.let { sendWire(it, s) } ?: false
            member == c.hostId -> sendWire(c.hostPeer, s.copy(to = ""))
            else -> sendWire(c.hostPeer, s)
        }
    }

    private suspend fun sendTo(name: String, sig: CallSignal): Boolean = sendWire(name, sig)

    private suspend fun sendWire(name: String, sig: CallSignal): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val peer = peers.find(name) ?: return@withContext false
        val key = peers.messageKey(peer) ?: return@withContext false
        val body = json.encodeToString(CallCrypto.seal(key, sig.call, json.encodeToString(sig))).toByteArray()
        peers.deliver(peer, "/api/peers/call", body)
    }

    private fun show(s: CallState?) {
        _state.value = s
        onChange(s)
        EventBus.emit("call", json.encodeToString(forPage(s)))
    }

    /** The call as a laptop's page shows it; null when there is none. */
    fun forPage(s: CallState? = _state.value): PageCall? = s?.let {
        PageCall(
            it.id, it.phase, it.video, it.outgoing, it.since,
            it.members.filter { m -> m.phase != "left" }.map { m -> PageCallMember(m.name, m.phase, m.camera, m.mic, m.speaking) },
            it.laptop, if (it.laptop.isNotEmpty()) it.laptopMuted else it.muted, it.laptopCamera, it.test, it.why,
        )
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
        /** Phones in one call, this one too: up to [MESH] each straight to each other, past that through the host. */
        const val MAX = 8
        /** What a mesh carries on phones' own links: each phone sends its picture to each other one. */
        const val MESH = 4
        /** Spare voice channels on each connection, for the others' voices in a big call. */
        private const val SLOTS = MAX - 2
        private const val ECHO = "echo"
        const val ECHO_NAME = "Test call"
        private const val RING_MS = 45_000L
        private const val CONNECT_MS = 25_000L
        private const val GATHER_MS = 1_000L
        private const val SPEAKING = 0.04
        private val idRng = SecureRandom()
        private fun newId() = ByteArray(6).also(idRng::nextBytes).joinToString("") { "%02x".format(it) }
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

/**
 * A picture passed on: each frame of [from] (another connection's) fed to a source of this phone's
 * own, so it can be sent on a connection of its own, at the frames and size it comes in.
 */
class Forward(factory: PeerConnectionFactory, val from: VideoTrack, id: String) : org.webrtc.VideoSink {
    private val source = factory.createVideoSource(false)
    val track: VideoTrack = factory.createVideoTrack(id, source)

    init {
        source.capturerObserver.onCapturerStarted(true)
        from.addSink(this)
    }

    override fun onFrame(f: org.webrtc.VideoFrame) = source.capturerObserver.onFrameCaptured(f)

    fun release() {
        runCatching { from.removeSink(this) }
        runCatching { source.capturerObserver.onCapturerStopped() }
        runCatching { track.dispose() }
        runCatching { source.dispose() }
    }
}
