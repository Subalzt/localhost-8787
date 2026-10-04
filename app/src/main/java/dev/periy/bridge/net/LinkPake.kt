package dev.periy.bridge.net

import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The short link code made into a strong key, so four digits are enough. SPAKE2 (RFC 9382) over
 * the 2048-bit MODP group (RFC 3526, group 14): the typing phone and the showing phone each send
 * one number through the board, both made with the code, and from them both get the same key,
 * which only a phone that knew the code can have. The code itself never leaves either phone, in
 * any form; someone watching the board learns nothing from it, and someone guessing gets one guess
 * per exchange, which the showing phone allows [TRIES] of for each code. Under that key the showing
 * phone hands over the code's real secret (32 random bytes, [LinkCode]), which is what the tunnel
 * then uses.
 *
 * The board topic is the same for every code, so it says nothing about which code is open.
 */
object LinkPake {
    /** Wrong (or stray) tries a code takes before it closes. */
    const val TRIES = 3
    private const val TOPIC = "l87-link-v2"
    private fun replyTopic(sid: String) = "l87-lk-$sid"

    private val P = BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
            "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
            "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
            "15728E5A8AACAA68FFFFFFFFFFFFFFFF",
        16,
    )
    /** The order of the group of squares, where everything here lives (P is a safe prime; 2 is a square). */
    private val Q = P.shiftRight(1)
    private val G = BigInteger.valueOf(2)
    /** The two fixed numbers nobody knows the logarithm of: squares of hashes of their names. */
    private val M = toGroup("L87L/2 SPAKE2 M")
    private val N = toGroup("L87L/2 SPAKE2 N")
    private const val LEN = 256

    private val rng = SecureRandom()

    private fun sha512(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").run { parts.forEach { update(it) }; digest() }

    private fun toGroup(label: String): BigInteger {
        val bytes = (0 until 5).map { sha512(label.toByteArray(), byteArrayOf(it.toByte())) }.reduce { a, b -> a + b }
        return BigInteger(1, bytes).mod(P).modPow(BigInteger.TWO, P)
    }

    /** The code as a number in the group's exponents. */
    private fun w(code: String) = BigInteger(1, sha512("L87L/2 w".toByteArray(), code.toByteArray())).mod(Q)

    private fun bytes(x: BigInteger): ByteArray {
        val b = x.toByteArray().let { if (it.size > LEN) it.copyOfRange(it.size - LEN, it.size) else it }
        return ByteArray(LEN - b.size) + b
    }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING)

    /** A number from the other side: in range, and one of the group's (anything else could leak the code). */
    private fun element(s: String?): BigInteger? = runCatching {
        val x = BigInteger(1, unb64(s!!))
        x.takeIf { it > BigInteger.ONE && it < P.subtract(BigInteger.ONE) && it.modPow(Q, P) == BigInteger.ONE }
    }.getOrNull()

    private fun secretExp() = BigInteger(256, rng).add(BigInteger.TWO)

    /** The key both get, from everything said and the shared number; then the key the secret is sealed with. */
    private fun sealKey(sid: String, x: BigInteger, y: BigInteger, z: BigInteger, w: BigInteger): ByteArray {
        val k = MessageDigest.getInstance("SHA-256").run {
            listOf("L87L/2".toByteArray(), sid.toByteArray(), bytes(x), bytes(y), bytes(z), bytes(w)).forEach {
                update(byteArrayOf((it.size ushr 8).toByte(), it.size.toByte())); update(it)
            }
            digest()
        }
        return TunnelCrypto.hmac(k, "L87L/2 seal".toByteArray())
    }

    /**
     * The typing phone: asks the phone showing [code] for the code's secret. Throws with a reason
     * a person can act on (a wrong code, or no phone showing one right now).
     */
    fun fetch(code: String, waitMs: Int = 15_000): ByteArray {
        val sid = Punch.hex(ByteArray(8).also(rng::nextBytes))
        val w = w(code)
        val x = secretExp()
        val bigX = G.modPow(x, P).multiply(M.modPow(w, P)).mod(P)
        val c = URL("${Punch.BOARD}/${replyTopic(sid)}/json").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = waitMs + 1_000
        try {
            val lines = c.inputStream.bufferedReader()
            lines.readLine()   // the board's "open"
            val note = buildJsonObject { put("s", JsonPrimitive(sid)); put("x", JsonPrimitive(b64(bytes(bigX)))) }.toString()
            if (!Punch.post(TOPIC, note)) throw IOException("could not reach the meeting board")
            val end = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < end) {
                val line = try { lines.readLine() } catch (e: SocketTimeoutException) { null } ?: break
                val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                if (o["event"]?.jsonPrimitive?.content != "message") continue
                val a = runCatching { Json.parseToJsonElement(o["message"]!!.jsonPrimitive.content).jsonObject }.getOrNull() ?: continue
                if (a["s"]?.jsonPrimitive?.content != sid) continue
                val bigY = element(a["y"]?.jsonPrimitive?.content) ?: continue
                val z = bigY.multiply(N.modPow(w, P).modInverse(P)).mod(P).modPow(x, P)
                val opened = Punch.open(sealKey(sid, bigX, bigY, z, w), a["e"]?.jsonPrimitive?.content ?: continue)
                    ?: throw IOException("that is not the code the other phone shows")
                val k = Json.parseToJsonElement(opened).jsonObject["k"]?.jsonPrimitive?.content ?: throw IOException("the other phone sent nothing")
                return unb64(k).takeIf { it.size == 32 } ?: throw IOException("the other phone sent nothing")
            }
            throw IOException("no phone is showing that code right now")
        } finally {
            runCatching { c.disconnect() }
        }
    }

    /**
     * The showing phone: answers asks for [code] with its [secret] until [until] (or [stop]), at
     * most [TRIES] of them; [onSpent] says when it has answered that many.
     */
    class Door(private val code: String, private val secret: ByteArray, private val until: Long, private val onSpent: () -> Unit) {
        @Volatile private var open = true
        @Volatile private var conn: HttpURLConnection? = null
        private var tries = 0
        private val w = w(code)

        fun start() { Thread({ loop() }, "link-door").apply { isDaemon = true; start() } }

        fun stop() { open = false; runCatching { conn?.disconnect() } }

        private fun loop() {
            while (open && System.currentTimeMillis() < until) {
                try {
                    val c = URL("${Punch.BOARD}/$TOPIC/json").openConnection() as HttpURLConnection
                    conn = c
                    c.connectTimeout = 10_000
                    c.readTimeout = 70_000
                    c.inputStream.bufferedReader().use { lines ->
                        while (open && System.currentTimeMillis() < until) {
                            val line = lines.readLine() ?: break
                            val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                            if (o["event"]?.jsonPrimitive?.content != "message") continue
                            val a = runCatching { Json.parseToJsonElement(o["message"]!!.jsonPrimitive.content).jsonObject }.getOrNull() ?: continue
                            val sid = a["s"]?.jsonPrimitive?.content?.takeIf { it.length == 16 && it.all { ch -> ch in "0123456789abcdef" } } ?: continue
                            val bigX = element(a["x"]?.jsonPrimitive?.content) ?: continue
                            answer(sid, bigX)
                            if (++tries >= TRIES) { open = false; onSpent(); return }
                        }
                    }
                } catch (e: Exception) {
                    if (open) { Log.w("LinkPake", "The board went: ${e.message}"); Thread.sleep(2_000) }
                }
            }
        }

        private fun answer(sid: String, bigX: BigInteger) {
            val y = secretExp()
            val bigY = G.modPow(y, P).multiply(N.modPow(w, P)).mod(P)
            val z = bigX.multiply(M.modPow(w, P).modInverse(P)).mod(P).modPow(y, P)
            val sealed = Punch.seal(sealKey(sid, bigX, bigY, z, w), buildJsonObject { put("k", JsonPrimitive(b64(secret))) }.toString())
            val note = buildJsonObject {
                put("s", JsonPrimitive(sid)); put("y", JsonPrimitive(b64(bytes(bigY)))); put("e", JsonPrimitive(sealed))
            }.toString()
            Punch.post(replyTopic(sid), note)
        }
    }
}
