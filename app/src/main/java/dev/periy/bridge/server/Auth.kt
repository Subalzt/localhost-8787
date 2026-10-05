package dev.periy.bridge.server

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

const val SESSION_COOKIE = "xoosh_session"

/**
 * Signed session cookie: `base64(payload).base64(hmacSha256(key, payload))`, where the
 * payload is `expiry:deviceId:nonce`.
 *
 * The device id is what makes per-computer control possible. The signature proves the
 * cookie was issued by this phone; the id says *to which computer*, so the phone can list
 * every paired computer and revoke any one of them without logging out the rest.
 * Revocation works by deleting the device from [DeviceRegistry] -- the cookie still
 * verifies, but it now points at nobody, and is refused.
 *
 * Rotating the key revokes everything at once, which is what "unpair all" does.
 */
object Session {

    private val rng = SecureRandom()

    fun issue(key: ByteArray, ttlMs: Long, deviceId: String): String {
        val payload = "${System.currentTimeMillis() + ttlMs}:$deviceId:${randomB64(12)}"
        val bytes = payload.toByteArray()
        return "${b64(bytes)}.${b64(hmac(key, bytes))}"
    }

    /** The device the cookie was issued to, or null if it is forged, malformed or expired. */
    fun verify(key: ByteArray, token: String?): String? {
        if (token.isNullOrEmpty()) return null
        val dot = token.indexOf('.')
        if (dot <= 0 || dot == token.length - 1) return null

        val payload = runCatching { Base64.decode(token.substring(0, dot), B64) }.getOrNull() ?: return null
        val sig = runCatching { Base64.decode(token.substring(dot + 1), B64) }.getOrNull() ?: return null

        // Check the signature before parsing anything, so a forged token never gets to
        // influence more than this one comparison.
        if (!constantTimeEquals(sig, hmac(key, payload))) return null

        val parts = String(payload).split(':')
        if (parts.size != 3) return null
        val exp = parts[0].toLongOrNull() ?: return null
        if (exp <= System.currentTimeMillis()) return null
        return parts[1].takeIf { it.isNotEmpty() }
    }

    /**
     * The device a cookie was issued to when this phone signed it, expired or not; null when it
     * is not one of this phone's own. A "linked phone" holding one of these is this phone itself.
     */
    fun signedHere(key: ByteArray, token: String?): String? {
        val t = token?.substringAfter('=')?.takeIf { it.isNotEmpty() } ?: return null
        val dot = t.indexOf('.')
        if (dot <= 0 || dot == t.length - 1) return null
        val payload = runCatching { Base64.decode(t.substring(0, dot), B64) }.getOrNull() ?: return null
        val sig = runCatching { Base64.decode(t.substring(dot + 1), B64) }.getOrNull() ?: return null
        if (!constantTimeEquals(sig, hmac(key, payload))) return null
        return String(payload).split(':').getOrNull(1)?.takeIf { it.isNotEmpty() }
    }

    /**
     * This phone's own id, given in /api/ping and with every request it makes to another phone,
     * so it can tell itself from another phone and never link with itself. Taken from the session
     * key, so it says nothing about the key and changes with it (Sign out all devices).
     */
    fun phoneId(key: ByteArray): String =
        hmac(key, "Localhost 8787 phone id".toByteArray()).copyOf(12).joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun b64(b: ByteArray) = Base64.encodeToString(b, B64)
    private fun randomB64(n: Int) = b64(ByteArray(n).also(rng::nextBytes))

    private const val B64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
}

/**
 * Comparison whose running time depends on the lengths of the inputs but not on their
 * contents.
 */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty()
    var diff = a.size xor b.size
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[min(i, b.size - 1)].toInt())
    return diff == 0
}

/**
 * A short human-readable name for a browser, from its User-Agent -- "Chrome on Windows".
 *
 * Order matters: Edge and Opera also claim to be Chrome, Android also claims to be Linux,
 * and iPads claim to be Macs, so the more specific tokens are checked first.
 */
fun describeUserAgent(ua: String?): String {
    if (ua.isNullOrBlank()) return "Browser"
    // The laptop helper names itself: "BlazeItPC/1 (DESKTOP-ABC)" (older copies: "XooshPC/1 (...)");
    // the Linux and Mac one adds its system, "BlazeItPC/1 (thinkpad; Linux)", kept as "(Linux)" after the name.
    // Another phone running BlazeIt: "BlazeItPhone/1 (Xiaomi 24129PN74I)".
    Regex("""BlazeItPhone/\S+ \((.+)\)""").find(ua)?.let { return "Phone: " + it.groupValues[1] }
    Regex("""(?:BlazeIt|Xoosh)PC/\S+ \((.+?)(?:; (Linux|Mac))?\)""").find(ua)?.let {
        val machine = it.groupValues[1]
        val system = it.groupValues[2]
        return "Laptop control on " + machine + if (system.isNotEmpty() && machine != system) " ($system)" else ""
    }
    val browser = when {
        "Edg/" in ua -> "Edge"
        "OPR/" in ua -> "Opera"
        "Firefox/" in ua -> "Firefox"
        "Chrome/" in ua -> "Chrome"
        "Safari/" in ua -> "Safari"
        else -> "Browser"
    }
    val os = when {
        "Windows" in ua -> "Windows"
        "Android" in ua -> "Android"
        "iPhone" in ua || "iPad" in ua -> "iOS"
        "Mac OS X" in ua -> "Mac"
        "CrOS" in ua -> "ChromeOS"
        "Linux" in ua -> "Linux"
        else -> ""
    }
    return if (os.isEmpty()) browser else "$browser on $os"
}
