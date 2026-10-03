package dev.periy.bridge.net

import java.security.SecureRandom

/**
 * Linking two phones that have never shared a network: one shows a code, the other types it in.
 * The code stands in for a paired device's tunnel key for ten minutes, so the typing phone can
 * reach the showing one through its tunnel (IPv6, or punched across IPv4) and ask to be let in
 * there, exactly as on a shared Wi-Fi: the showing phone still has to allow it. Ten letters from
 * 32 (50 bits), and only a question to its owner can come of guessing one.
 */
object LinkCode {
    /** The stand-in device id the tunnel answers for while a code is open. */
    const val ID = "link-code"
    const val TTL_MS = 10 * 60_000L

    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private val L_PSK = "L87L/1 psk".toByteArray()
    private val L_ID = "L87L/1 id".toByteArray()
    private val rng = SecureRandom()

    fun new(): String = String(CharArray(10) { ALPHABET[rng.nextInt(ALPHABET.length)] })

    /** As typed: spaces, dashes and case do not matter (the code has no 0, 1, I or O to mix up). */
    fun normalize(typed: String): String = typed.uppercase().filter { it in ALPHABET }

    fun valid(code: String) = code.length == 10 && code.all { it in ALPHABET }

    /** For showing: two groups of five. */
    fun shown(code: String) = code.chunked(5).joinToString("-")

    fun psk(code: String): ByteArray = TunnelCrypto.hmac(L_PSK, code.toByteArray())
    fun tid(code: String): ByteArray = TunnelCrypto.hmac(psk(code), L_ID).copyOf(16)
}
