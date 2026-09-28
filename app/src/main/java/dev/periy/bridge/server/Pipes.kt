package dev.periy.bridge.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** A laptop asking to send a file somewhere other than this phone's own folder. */
@Serializable
data class PipeOffer(
    /** "phone" (this phone's folder), "dev:<id>" (a computer open here), "peer:<phone>/<to>" (on through a linked phone). */
    val to: String,
    val name: String,
    val size: Long,
    val mime: String = "application/octet-stream",
    /** Who is sending, as the receiving computer will see it; filled in here when empty. */
    val from: String = "",
)

@Serializable
data class PipeDto(val id: String)

/**
 * "waiting" for the computer to take it, "accepted" (through the phone), "direct" (it will take it
 * browser to browser if the two can reach each other), "declined", or "gone".
 */
@Serializable
data class PipeStateDto(val state: String)

/** What a computer is asked to take: shown on its page with Save and Decline. */
@Serializable
data class IncomingDto(val id: String, val name: String, val size: Long, val mime: String, val from: String)

/** Somewhere a laptop's page can send files: this phone, a computer, or a linked phone. */
@Serializable
data class TargetDto(val id: String, val name: String, val kind: String, val via: String = "")

@Serializable
data class TargetsDto(val phone: String, val targets: List<TargetDto>, val clipWith: List<String> = emptyList())

/**
 * Straight through: a file from one computer to another, handed on as it arrives and never
 * written down on the way.
 *
 * A laptop's page cannot take a connection, but it keeps one open to its phone (the event
 * stream), so the phone can ask it. The sending page opens a pipe; the phone asks the receiving
 * page, which shows Save and Decline; on Save that page starts an ordinary download of the pipe,
 * and only then does the sender start its upload. The phone copies each piece from the one
 * request into the other, holding a few megabytes at most. So a 50 GB file needs no room on the
 * phone at all, and its speed is the slower of the two links.
 *
 * Through a linked phone it is the same thing twice: this phone opens a pipe on the other one
 * and passes the upload on to it ([Sink.Relay]), and the other phone asks its computer. Or the
 * other phone keeps it in its own folder for received files ([Sink.Store]).
 *
 * There is no resuming: a pipe that breaks is started again. That is the price of keeping
 * nothing on the way.
 */
object Pipes {

    /** Where a pipe's bytes go. */
    sealed interface Sink {
        /** A computer with the page open on this phone, which takes the file as a download. */
        data class Device(val id: String) : Sink
        /** This phone's folder for received files. */
        data object Store : Sink
        /** On to linked phone [peer], into its pipe [remote]. */
        data class Relay(val peer: Peer, val remote: String) : Sink
    }

    class Pipe(
        val id: String,
        val name: String,
        val size: Long,
        val mime: String,
        val from: String,
        val sink: Sink,
        /** The computer that opened it, here; its introductions for a direct send go back to it. */
        val senderId: String,
        /** When a linked phone opened it for its computer: where the introductions go back to. */
        val senderPeer: Peer?,
    ) {
        /** The pieces on their way from the sender's request to the receiver's download. */
        val chunks = Channel<ByteArray>(BUFFERED)
        /** The receiving computer's answer: true once it asks for the download. */
        val answer = CompletableDeferred<Boolean>()
        /** Whether the receiver got every byte. */
        val delivered = CompletableDeferred<Boolean>()
        val createdAt = System.currentTimeMillis()
        @Volatile var sending = false
        /** The receiver takes it browser to browser, if the two laptops can reach each other. */
        @Volatile var direct = false
        /** The receiver's download has started (a direct send that fell back to the phone, or not). */
        @Volatile var receiving = false

        fun incoming() = IncomingDto(id, name, size, mime, from)

        fun state(): String = when {
            !answer.isCompleted -> "waiting"
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) answer.getCompleted() -> if (direct) "direct" else "accepted"
            else -> "declined"
        }
    }

    private val pipes = ConcurrentHashMap<String, Pipe>()
    private val rng = SecureRandom()

    fun open(name: String, size: Long, mime: String, from: String, sink: Sink, senderId: String, senderPeer: Peer?): Pipe {
        sweep()
        val id = ByteArray(16).also(rng::nextBytes).joinToString("") { "%02x".format(it) }
        val p = Pipe(id, name, size, mime, from, sink, senderId, senderPeer)
        // The phone's own folder needs nobody to say yes.
        if (sink is Sink.Store) p.answer.complete(true)
        pipes[id] = p
        return p
    }

    fun get(id: String): Pipe? = pipes[id]

    /** The pipe here that passes on to pipe [remote] on linked phone [peer]. */
    fun byRelay(peer: Peer, remote: String): Pipe? =
        pipes.values.firstOrNull { (it.sink as? Sink.Relay)?.let { r -> r.peer.name == peer.name && r.remote == remote } == true }

    fun close(p: Pipe) {
        pipes.remove(p.id)
    }

    /** Files waiting for computer [deviceId] to take them: asked again when its page comes back. */
    fun waitingFor(deviceId: String): List<Pipe> =
        pipes.values.filter { (it.sink as? Sink.Device)?.id == deviceId && !it.answer.isCompleted }

    /** Offers nobody answered in time are closed, and so are pipes left behind by a sender that went. */
    private fun sweep() {
        val now = System.currentTimeMillis()
        pipes.values.filter { now - it.createdAt > ANSWER_MS && !it.sending }.forEach {
            it.answer.complete(false)
            it.chunks.close()
            pipes.remove(it.id)
        }
    }

    /** How long a computer has to answer, and a sender to start once it has. */
    const val ANSWER_MS = 5L * 60 * 1000
    /** A receiver waits this long for the next piece before it gives up on the sender. */
    const val IDLE_MS = 60_000L
    /** Pieces of 1 MB; eight of them in flight between the two requests. */
    const val PIECE = 1 shl 20
    private const val BUFFERED = 8
}
