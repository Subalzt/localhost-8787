package dev.periy.bridge.net

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * The meeting place for punching across IPv4 (Punch.kt), in your own website's DNS zone instead of
 * a public message board: each end leaves its sealed note as a TXT record (dynv6's API, over HTTPS,
 * which even a web proxy lets through) and reads the other's straight from dynv6's nameservers. The
 * notes are sealed with the two ends' key, as on the board; a note is taken away once answered.
 */
object DnsBoard {
    private const val TAG = "DnsBoard"
    private const val API = "https://dynv6.com/api/v2"
    private val zoneIds = ConcurrentHashMap<String, Long>()

    private fun http(method: String, url: String, token: String, body: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/json")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() }.getOrNull().orEmpty()
        c.disconnect()
        return code to text
    }

    private fun zone(name: String, token: String): Long = zoneIds.getOrPut(name) {
        val (code, text) = http("GET", "$API/zones/by-name/$name", token)
        if (code !in 200..299) error("dynv6 said $code")
        Json.parseToJsonElement(text).jsonObject["id"]!!.jsonPrimitive.content.toLong()
    }

    /** The records named [topic] in the zone: id and text. */
    private fun records(zone: Long, token: String, topic: String): List<Pair<String, String>> {
        val (code, text) = http("GET", "$API/zones/$zone/records", token)
        if (code !in 200..299) return emptyList()
        return Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "TXT" && it["name"]?.jsonPrimitive?.content == topic }
            .map { it["id"]!!.jsonPrimitive.content to it["data"]?.jsonPrimitive?.content.orEmpty() }
    }

    /** Leaves [text] at [topic] in zone [name], in place of any note there before. */
    fun post(name: String, token: String, topic: String, text: String): Boolean = runCatching {
        val z = zone(name, token)
        records(z, token, topic).forEach { (id, _) -> http("DELETE", "$API/zones/$z/records/$id", token) }
        val body = buildJsonObject { put("name", JsonPrimitive(topic)); put("type", JsonPrimitive("TXT")); put("data", JsonPrimitive(text)) }.toString()
        http("POST", "$API/zones/$z/records", token, body).first in 200..299
    }.onFailure { Log.i(TAG, "Posting: ${it.message}") }.getOrDefault(false)

    /** The notes at [topic] in zone [name], straight from its nameservers (no cache between). */
    fun read(name: String, topic: String): List<String> = runCatching { Dns.first("$topic.$name") }.getOrDefault(emptyList())

    /** Takes the notes at [topic] away. */
    fun clear(name: String, token: String, topic: String) {
        runCatching {
            val z = zone(name, token)
            records(z, token, topic).forEach { (id, _) -> http("DELETE", "$API/zones/$z/records/$id", token) }
        }
    }

    /** Debug builds: how long a note takes to be seen at the nameservers (logcat DnsBoard). */
    fun timeIt(name: String, token: String) = Thread({
        val topic = "l87-test"
        val value = "t" + System.currentTimeMillis()
        val t0 = System.currentTimeMillis()
        val ok = post(name, token, topic, value)
        val posted = System.currentTimeMillis() - t0
        var seen = -1L
        while (System.currentTimeMillis() - t0 < 120_000) {
            if (read(name, topic).contains(value)) { seen = System.currentTimeMillis() - t0; break }
            Thread.sleep(500)
        }
        Log.e(TAG, "posted=$ok in $posted ms, seen at the nameservers after $seen ms")
        clear(name, token, topic)
    }, "dns-board-test").apply { isDaemon = true; start() }
}
