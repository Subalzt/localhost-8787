package dev.periy.bridge.net

import java.security.SecureRandom

/**
 * Linking two phones that have never shared a network: one shows a code, the other types it in.
 * Four digits, easy to read out, open for five minutes. The code never travels: with it the typing
 * phone gets the code's secret (32 random bytes) from the showing phone through a key exchange
 * only a phone that knows the code can finish ([LinkPake]), and that secret stands in for a paired
 * device's tunnel key, so the typing phone can reach the showing one through its tunnel (IPv6, or
 * punched across IPv4) and ask to be let in there, exactly as on a shared Wi-Fi: the showing phone
 * still allows it, by name. A guess is one try of three per code; the code is spent on the first link.
 */
object LinkCode {
    /** The stand-in device id the tunnel answers for while a code is open. */
    const val ID = "link-code"
    const val TTL_MS = 5 * 60_000L
    const val LENGTH = 4

    private val L_PSK = "L87L/2 psk".toByteArray()
    private val L_ID = "L87L/2 id".toByteArray()
    private val rng = SecureRandom()

    fun new(): String = String(CharArray(LENGTH) { '0' + rng.nextInt(10) })

    /** The secret a code hands over, which the tunnel's keys come from. */
    fun newSecret(): ByteArray = ByteArray(32).also(rng::nextBytes)

    /** As typed: only the digits count. */
    fun normalize(typed: String): String = typed.filter { it in '0'..'9' }

    fun valid(code: String) = code.length == LENGTH && code.all { it in '0'..'9' }

    /** For showing: the digits spaced apart, as a phone shows a one-time code. */
    fun shown(code: String) = code.toList().joinToString(" ")

    fun psk(secret: ByteArray): ByteArray = TunnelCrypto.hmac(L_PSK, secret)
    fun tid(secret: ByteArray): ByteArray = TunnelCrypto.hmac(psk(secret), L_ID).copyOf(16)
}
