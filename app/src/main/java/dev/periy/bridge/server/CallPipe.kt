package dev.periy.bridge.server

import android.util.Log
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketAddress
import java.net.SocketTimeoutException

/**
 * A call's packets over a byte stream, when the networks between two phones let no call through
 * but the phones still reach each other for messages (their link: the same Wi-Fi, IPv6, the
 * tunnel, IPv4 punched through). Each end holds a UDP socket on its own address, which its own
 * call connection is told to send to (or, at the far end, sends to the call connection); every
 * datagram crosses the stream with its length first (two bytes, as RFC 4571 frames them), and
 * goes out the other side as one datagram again. WebRTC's own checks and encryption run end to
 * end through it, untouched: this only carries.
 */
class CallPipe(
    private val udp: DatagramSocket,
    private val input: InputStream,
    private val output: OutputStream,
    /** Where datagrams from the stream go: fixed (the far end's call connection), or null to learn it from the first that comes in. */
    @Volatile private var to: SocketAddress?,
    /** Stops it from outside as well (the call is over). */
    private val alive: () -> Boolean,
    private val onClose: () -> Unit = {},
) {
    @Volatile private var closed = false

    fun start(name: String) {
        Thread({ fromStream() }, "$name-in").apply { isDaemon = true; start() }
        Thread({ toStream() }, "$name-out").apply { isDaemon = true; start() }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { udp.close() }
        runCatching { input.close() }
        runCatching { output.close() }
        onClose()
    }

    /** The stream's frames, out as datagrams. */
    private fun fromStream() {
        val d = DataInputStream(input)
        val buf = ByteArray(65_536)
        try {
            while (!closed && alive()) {
                val n = d.readUnsignedShort()
                d.readFully(buf, 0, n)
                val at = to ?: continue
                udp.send(DatagramPacket(buf, n, at))
            }
        } catch (e: Exception) {
            if (!closed) Log.i(TAG, "Pipe in: ${e.message}")
        } finally {
            close()
        }
    }

    /** Datagrams, into the stream with their lengths. */
    private fun toStream() {
        val buf = ByteArray(65_536 + 2)
        val pkt = DatagramPacket(buf, 2, 65_536)
        try {
            udp.soTimeout = 2000
            while (!closed && alive()) {
                pkt.setData(buf, 2, 65_536)
                try { udp.receive(pkt) } catch (_: SocketTimeoutException) { continue }
                if (to == null) to = pkt.socketAddress
                val n = pkt.length
                buf[0] = (n shr 8).toByte(); buf[1] = n.toByte()
                synchronized(output) { output.write(buf, 0, n + 2); output.flush() }
            }
        } catch (e: Exception) {
            if (!closed) Log.i(TAG, "Pipe out: ${e.message}")
        } finally {
            close()
        }
    }

    companion object {
        private const val TAG = "CallPipe"
    }
}
