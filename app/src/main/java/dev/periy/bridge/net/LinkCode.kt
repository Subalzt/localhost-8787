package dev.periy.bridge.net

import java.security.SecureRandom

/**
 * Linking two phones that have never shared a network: one shows a code, the other types it in.
 * The code stands in for a paired device's tunnel key for five minutes, so the typing phone can
 * reach the showing one through its tunnel (IPv6, or punched across IPv4) and ask to be let in
 * there, exactly as on a shared Wi-Fi. Four digits, easy to read out: too few to be a secret from
 * someone watching the meeting board at that moment, so what keeps a stranger out is that the
 * showing phone still has to allow the phone that asks, by its name, and the code is spent as soon
 * as one phone has linked.
 */
object LinkCode {
    /** The stand-in device id the tunnel answers for while a code is open. */
    const val ID = "link-code"
    const val TTL_MS = 5 * 60_000L
    const val LENGTH = 4

    private val L_PSK = "L87L/1 psk".toByteArray()
    private val L_ID = "L87L/1 id".toByteArray()
    private val rng = SecureRandom()

    fun new(): String = String(CharArray(LENGTH) { '0' + rng.nextInt(10) })

    /** As typed: only the digits count. */
    fun normalize(typed: String): String = typed.filter { it in '0'..'9' }

    fun valid(code: String) = code.length == LENGTH && code.all { it in '0'..'9' }

    /** For showing: the digits spaced apart, as a phone shows a one-time code. */
    fun shown(code: String) = code.toList().joinToString(" ")

    fun psk(code: String): ByteArray = TunnelCrypto.hmac(L_PSK, code.toByteArray())
    fun tid(code: String): ByteArray = TunnelCrypto.hmac(psk(code), L_ID).copyOf(16)
}
