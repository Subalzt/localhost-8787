package dev.periy.bridge.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress

/**
 * A STUN server (RFC 5389, Binding only), so two laptops' pages can find each other.
 *
 * Browsers hide a page's own addresses from WebRTC behind random names, which another computer
 * can only look up where the network passes multicast. Asking a STUN server gets a browser its
 * address as the server sees it; with the server on the phone, on the same network, that is the
 * laptop's real address there. Two pages then connect browser to browser, and a file goes straight
 * between the laptops without passing through the phone. Nothing but that one answer is sent.
 */
class StunServer(private val listenPort: Int = PORT) {

    @Volatile private var socket: DatagramSocket? = null

    @Synchronized
    fun start() {
        if (socket != null) return
        val s = runCatching { DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(listenPort)) } }
            .getOrElse { Log.w(TAG, "Cannot answer STUN: ${it.message}"); return }
        socket = s
        Thread({ loop(s) }, "stun").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        socket?.let { runCatching { it.close() } }
        socket = null
    }

    private fun loop(s: DatagramSocket) {
        val buf = ByteArray(1500)
        while (socket === s) {
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (_: Throwable) { break }
            runCatching { answer(s, p) }
        }
    }

    private fun answer(s: DatagramSocket, p: DatagramPacket) {
        val b = p.data
        if (p.length < 20) return
        val type = (b[0].toInt() and 0xFF shl 8) or (b[1].toInt() and 0xFF)
        if (type != BINDING_REQUEST) return
        for (i in 0 until 4) if (b[4 + i] != COOKIE[i]) return
        val from = p.address as? Inet4Address ?: return
        val ip = from.address
        val out = ByteArray(20 + 12)
        out[0] = 0x01; out[1] = 0x01 // Binding success
        out[2] = 0; out[3] = 12
        System.arraycopy(b, 4, out, 4, 16) // the cookie and the transaction id, as asked
        // XOR-MAPPED-ADDRESS: where the request came from, masked with the cookie.
        out[20] = 0x00; out[21] = 0x20; out[22] = 0; out[23] = 8
        out[24] = 0; out[25] = 0x01
        val xport = p.port xor 0x2112
        out[26] = (xport shr 8).toByte(); out[27] = xport.toByte()
        for (i in 0 until 4) out[28 + i] = (ip[i].toInt() xor COOKIE[i].toInt()).toByte()
        s.send(DatagramPacket(out, out.size, p.address, p.port))
    }

    companion object {
        private const val TAG = "Stun"
        const val PORT = 3478
        private const val BINDING_REQUEST = 0x0001
        private val COOKIE = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
    }
}
