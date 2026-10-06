package dev.periy.bridge.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Debug builds: how this network's NAT treats UDP, for reaching the phone across IPv4 with no
 * message board. Learns the public mapping of a socket on local port 3478 (and of an ordinary one)
 * from both STUN servers, then for three minutes logs every packet that arrives from anywhere it
 * did not send to: whether the NAT lets strangers in (endpoint-independent filtering), and whether
 * it keeps the port (a lab that lets UDP out only to 3478 could then reach it). Tag NatProbe.
 */
object NatProbe {
    private const val TAG = "NatProbe"

    fun run() = Thread({
        val socks = listOfNotNull(
            runCatching { DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(3478)) } }
                .onFailure { Log.e(TAG, "local port 3478: ${it.message}") }.getOrNull(),
            DatagramSocket(),
        )
        for (s in socks) {
            val seen = Punch.STUN.map { (h, p) -> h to Punch.stun(s, h, p) }
            Log.e(TAG, "local ${s.localPort}: " + seen.joinToString("; ") { (h, a) -> "$h saw ${a?.let(Punch::addr) ?: "nothing"}" })
        }
        val until = System.currentTimeMillis() + 180_000
        var kept = 0L
        val buf = ByteArray(2048)
        while (System.currentTimeMillis() < until) {
            if (System.currentTimeMillis() - kept > 20_000) {
                kept = System.currentTimeMillis()
                // Kept open: one STUN request each, so the mapping stays.
                for (s in socks) Punch.stun(s, Punch.STUN[1].first, Punch.STUN[1].second, 600)
            }
            for (s in socks) {
                s.soTimeout = 200
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { continue }
                Log.e(TAG, "local ${s.localPort} got ${p.length} bytes from ${p.address.hostAddress}:${p.port}: " +
                    String(buf, 0, minOf(p.length, 40)).filter { it.isLetterOrDigit() || it == ' ' })
            }
        }
        socks.forEach { runCatching { it.close() } }
        Log.e(TAG, "done")
    }, "nat-probe").apply { isDaemon = true; start() }
}
