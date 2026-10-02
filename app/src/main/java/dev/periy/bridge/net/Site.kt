package dev.periy.bridge.net

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * The phone as a website (docs/website.md): https://NAME.dynv6.net:8443, from any browser, let in
 * with a tap on the phone. The name is kept pointing at the phone's current IPv6 through dynv6's API; the certificate
 * comes from Let's Encrypt (a DNS check, also through dynv6, so nothing has to reach the phone for
 * it) and renews itself; a TLS door on port 8443 passes each connection to the page, arriving from
 * [LOCAL_HOST] so the page knows it came this way. Only the token typed on the phone and the keys
 * are kept, in the app's own files.
 */
class Site(private val ctx: Context, private val pagePort: () -> Int) {

    @Serializable
    data class Conf(
        val name: String = "",
        val token: String = "",
        /** The person tapped "I agree" to Let's Encrypt's Subscriber Agreement, on the phone. */
        val agreed: Boolean = false,
        val on: Boolean = false,
        val staging: Boolean = false,
        val accountKey: String = "",
        val accountUrl: String = "",
        val certKey: String = "",
        val certChain: String = "",
        val certUntil: Long = 0,
        val published: String = "",
    ) {
        val ready: Boolean get() = name.isNotBlank() && token.isNotBlank() && agreed
    }

    /** What Settings shows. */
    data class State(
        val on: Boolean = false,
        val name: String = "",
        val hasToken: Boolean = false,
        val agreed: Boolean = false,
        val address: String = "",
        val certUntil: Long = 0,
        val serving: Boolean = false,
        val status: String = "",
        val problem: Boolean = false,
    )

    private val file = File(ctx.filesDir, "site.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var conf: Conf = runCatching { json.decodeFromString(Conf.serializer(), file.readText()) }.getOrDefault(Conf())
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val rng = SecureRandom()
    @Volatile private var worker: Thread? = null
    @Volatile private var door: SSLServerSocket? = null
    @Volatile private var doorCert = ""
    @Volatile private var lastIssueTry = 0L
    private val issuing = Object()
    @Volatile private var status = ""
    @Volatile private var problem = false

    init { publish(); KeepAwake.init(ctx) }

    // ------------------------------------------------------------------ settings

    val url: String get() = if (conf.name.isBlank()) "" else "https://${conf.name}:$PORT"

    /** Saves what Settings has; a blank token leaves the old one. */
    @Synchronized
    fun configure(name: String?, token: String?, agreed: Boolean?) {
        var c = conf
        if (name != null) {
            val n = name.trim().lowercase().removePrefix("https://").removeSuffix("/")
            if (n != c.name) c = c.copy(name = n, certChain = "", certKey = "", certUntil = 0, published = "")
        }
        if (!token.isNullOrBlank()) c = c.copy(token = token.trim())
        if (agreed != null) c = c.copy(agreed = agreed)
        save(c)
        if (c.on) restart()
    }

    @Synchronized
    fun setOn(on: Boolean) {
        save(conf.copy(on = on))
        if (on) restart() else stop()
    }

    /** Debug: Let's Encrypt's staging server, for testing without its rate limits. */
    fun setStaging(staging: Boolean) { save(conf.copy(staging = staging, certChain = "", certKey = "", certUntil = 0, accountUrl = "")) }

    fun start() { if (conf.on) restart() }

    @Synchronized
    private fun restart() {
        stop()
        lastIssueTry = 0L   // switched on (again) by hand: try at once
        val t = Thread({ loop() }, "site")
        t.isDaemon = true
        worker = t
        t.start()
    }

    @Synchronized
    fun stop() {
        worker?.interrupt()
        worker = null
        runCatching { door?.close() }
        door = null
        doorCert = ""
        say("")
    }

    private fun save(c: Conf) {
        conf = c
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(Conf.serializer(), c))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        }
        publish()
    }

    private fun say(s: String, bad: Boolean = false) {
        if (s == status && bad == problem) return
        status = s
        problem = bad
        if (s.isNotEmpty()) Log.i(TAG, s)
        publish()
    }

    private fun publish() {
        val c = conf
        _state.value = State(
            on = c.on, name = c.name, hasToken = c.token.isNotBlank(), agreed = c.agreed,
            address = c.published, certUntil = c.certUntil, serving = door != null, status = status, problem = problem,
        )
    }

    // ------------------------------------------------------------------ the work

    private fun loop() {
        val me = Thread.currentThread()
        while (worker === me) {
            val c = conf
            if (!c.ready) { say(missing(c), bad = true); return }
            // Each step on its own: the door opens with the certificate there is, whatever dynv6
            // or Let's Encrypt say this time; a step that fails is tried again on the next round.
            val problems = ArrayList<String>()
            for (step in listOf(::serve, ::dns, ::cert, ::serve)) {
                try {
                    step()
                } catch (e: InterruptedException) {
                    return
                } catch (e: Exception) {
                    problems += e.message ?: e.javaClass.simpleName
                }
            }
            when {
                problems.isNotEmpty() -> say(problems.distinct().joinToString("; ") + if (door != null) " (the website is still on)" else "", bad = true)
                door != null -> say("On at $url")
            }
            // Every 20 s: a new IPv6 (mobile data reconnecting) reaches dynv6 within half a minute.
            try { Thread.sleep(20_000) } catch (e: InterruptedException) { return }
        }
    }

    private fun missing(c: Conf) = when {
        c.name.isBlank() -> "Needs your name at dynv6 (yourname.dynv6.net)"
        c.token.isBlank() -> "Needs the dynv6 token"
        else -> "Needs your agreement to Let's Encrypt's terms"
    }

    /** The name points at the phone's current IPv6 (its IPv4 is carrier NAT: no A record). */
    private fun dns() {
        val v6 = bestV6() ?: throw IOException("No IPv6 address right now")
        if (v6 == conf.published) return
        say("Pointing ${conf.name} at $v6")
        pointAt(v6)
        save(conf.copy(published = v6))
    }

    private fun cert() {
        val c = conf
        if (c.certChain.isNotBlank() && c.certUntil - System.currentTimeMillis() > RENEW_MS) return
        // A failed attempt waits 15 minutes: Let's Encrypt allows 5 failures an hour per name.
        if (System.currentTimeMillis() - lastIssueTry < 900_000L) return
        lastIssueTry = System.currentTimeMillis()
        try {
            // One attempt at a time: an attempt switched off midway still removes its own TXT
            // record on the way out, and must not take the next attempt's with it.
            synchronized(issuing) {
                if (worker !== Thread.currentThread()) return
                Acme(this).issue()
            }
        } catch (e: IOException) {
            // The name does not exist at all: dynv6 is not serving the zone (not activated yet).
            if ("NXDOMAIN" in (e.message ?: "")) throw IOException(
                "dynv6 is not serving ${c.name} yet: confirm your email at dynv6, then switch Website off and on"
            )
            throw e
        }
    }

    /**
     * The address to give the name: mobile data's own IPv6 when there is one, even while Wi-Fi is
     * up (the carrier lets connections in; a home router usually does not), a stable one rather
     * than a temporary one; else whatever global IPv6 the phone has.
     */
    private fun bestV6(): String? = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val nets = cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            .filter { it.second.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            .sortedBy { if (it.second.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) 0 else 1 }
        for ((n, _) in nets) {
            val lp = cm.getLinkProperties(n) ?: continue
            val a = lp.linkAddresses.sortedBy { it.flags and 0x01 }.map { it.address }
                .firstOrNull { it is java.net.Inet6Address && (it.address[0].toInt() and 0xe0) == 0x20 }
            if (a != null) return@runCatching a.hostAddress?.substringBefore('%')
        }
        null
    }.getOrNull()

    // ------------------------------------------------------------------ dynv6

    private fun dynv6Error(code: Int, text: String): IOException = IOException(
        when (code) {
            401, 403 -> "dynv6 did not accept the token"
            404 -> "dynv6 has no zone ${conf.name}: create it there first"
            else -> "dynv6 said $code: ${text.take(160)}"
        }
    )

    /** The name's AAAA, through dynv6's update URL. IPv4 is left alone: dynv6 refuses "-" now, and carrier NAT has no use for one. */
    private fun pointAt(v6: String) {
        val q = "zone=" + java.net.URLEncoder.encode(conf.name, "UTF-8") + "&token=" + java.net.URLEncoder.encode(conf.token, "UTF-8") +
            "&ipv6=" + java.net.URLEncoder.encode(v6, "UTF-8")
        val (code, text) = http("GET", "https://dynv6.com/api/update?$q", null)
        if (code !in 200..299) throw dynv6Error(code, text)
    }

    private fun zoneId(): Long {
        val (code, text) = http("GET", "$DYNV6/zones/by-name/${conf.name}", null, mapOf("Authorization" to "Bearer ${conf.token}"))
        if (code !in 200..299) throw dynv6Error(code, text)
        return Json.parseToJsonElement(text).jsonObject["id"]!!.jsonPrimitive.content.toLong()
    }

    /** A TXT record on the name, for the certificate check. Returns how to remove it again. */
    internal fun txt(name: String, value: String): () -> Unit {
        val zone = zoneId()
        val body = buildJsonObject { put("name", JsonPrimitive(name)); put("type", JsonPrimitive("TXT")); put("data", JsonPrimitive(value)) }.toString()
        val (code, text) = http("POST", "$DYNV6/zones/$zone/records", body, mapOf("Authorization" to "Bearer ${conf.token}"))
        if (code !in 200..299) throw dynv6Error(code, text)
        val id = Json.parseToJsonElement(text).jsonObject["id"]!!.jsonPrimitive.content
        return { http("DELETE", "$DYNV6/zones/$zone/records/$id", null, mapOf("Authorization" to "Bearer ${conf.token}")) }
    }

    // ------------------------------------------------------------------ the door

    /** TLS on [PORT]; each connection is passed to the page from [LOCAL_HOST]. */
    @Synchronized
    private fun serve() {
        val c = conf
        if (c.certChain.isBlank()) return
        if (door != null && doorCert == c.certChain) return
        runCatching { door?.close() }
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(unb64(c.certKey)))
        val chain = CertificateFactory.getInstance("X.509").generateCertificates(c.certChain.byteInputStream()).map { it as X509Certificate }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setKeyEntry("site", key, CharArray(0), chain.toTypedArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, rng) }
        val ss = (ssl.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("::"), PORT), 64)
        }
        door = ss
        doorCert = c.certChain
        Thread({ accept(ss) }, "site-door").apply { isDaemon = true; start() }
        publish()
    }

    private fun accept(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = runCatching { ss.accept() as SSLSocket }.getOrNull() ?: continue
            Thread({ pass(s) }, "site-conn").apply { isDaemon = true; start() }
        }
        if (door === ss) { door = null; publish() }
    }

    /** No more than 60 connections a minute from one address (one /64 for IPv6). */
    private val recent = ConcurrentHashMap<String, ArrayDeque<Long>>()

    private fun pass(s: SSLSocket) {
        val who = (s.inetAddress?.hostAddress ?: "?").substringBefore('%').removePrefix("::ffff:")
        val key = s.inetAddress?.address?.let { if (it.size == 16) it.copyOf(8).joinToString("") { b -> "%02x".format(b) } else who } ?: who
        val now = System.currentTimeMillis()
        val q = recent.getOrPut(key) { ArrayDeque() }
        val ok = synchronized(q) {
            while (q.isNotEmpty() && now - q.first > 60_000) q.poll()
            if (q.size >= 60) false else { q.add(now); true }
        }
        if (recent.size > 1000) recent.clear()
        if (!ok) { runCatching { s.close() }; return }
        var page: Socket? = null
        KeepAwake.start()
        try {
            s.soTimeout = 15_000
            s.startHandshake()
            s.soTimeout = 0
            page = Socket().apply {
                bind(InetSocketAddress(LOCAL_HOST, 0))
                tcpNoDelay = true
                connect(InetSocketAddress("127.0.0.1", pagePort()), 5_000)
            }
            clients[page.localPort] = who
            dests[page.localPort] = (s.localAddress?.hostAddress ?: "").substringBefore('%').removePrefix("::ffff:")
            val p = page
            val up = Thread({ copy(s, p) }, "site-up").apply { isDaemon = true; start() }
            copy(p, s)
            up.join()
        } catch (e: Exception) {
            // A bad handshake (a scanner, an old client) or the page going away: nothing to say.
        } finally {
            page?.let { clients.remove(it.localPort); dests.remove(it.localPort); runCatching { it.close() } }
            runCatching { s.close() }
            KeepAwake.end()
        }
    }

    private fun copy(from: Socket, to: Socket) {
        val buf = ByteArray(32 * 1024)
        try {
            val i = from.getInputStream()
            val o = to.getOutputStream()
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                o.flush()
            }
        } catch (e: IOException) {
        } finally {
            runCatching { if (to is SSLSocket) to.close() else to.shutdownOutput() }
            runCatching { if (from !is SSLSocket) from.shutdownInput() }
        }
    }

    /** Who is behind a request the page got from [LOCAL_HOST], by its source port. */
    private val clients = ConcurrentHashMap<Int, String>()

    fun clientFor(localPort: Int): String = clients[localPort] ?: "?"

    /** Which of the phone's own addresses that browser connected to. */
    private val dests = ConcurrentHashMap<Int, String>()

    fun destFor(localPort: Int): String = dests[localPort] ?: ""

    // ------------------------------------------------------------------ helpers' sign-in codes

    /** One-time codes baked into a helper downloaded through the website: code -> expiry. */
    private val codes = ConcurrentHashMap<String, Long>()

    /** A fresh code, good once within the hour. */
    fun newCode(): String {
        val now = System.currentTimeMillis()
        codes.entries.removeIf { it.value < now }
        val b = ByteArray(18).also(rng::nextBytes)
        return b64u(b).also { codes[it] = now + 3_600_000L }
    }

    /** True once for a code that is still good; it is gone after that. */
    fun useCode(code: String): Boolean {
        val until = codes.remove(code) ?: return false
        return until >= System.currentTimeMillis()
    }

    // ------------------------------------------------------------------ asking the phone

    private val asks = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val allAsks = ArrayDeque<Long>()

    /**
     * A browser through the website asks the phone to let it in (the person taps Allow, as on the
     * local network). Anyone who finds the name could make the phone ask, so: no more than 10 asks
     * per 10 minutes from one address (one /64), and 60 an hour from everywhere. Null when it may
     * ask, else why not.
     */
    fun mayAsk(client: String): String? {
        val now = System.currentTimeMillis()
        val key = client.split(':').take(4).joinToString(":")
        synchronized(allAsks) {
            while (allAsks.isNotEmpty() && now - allAsks.first > 3_600_000L) allAsks.poll()
            if (allAsks.size >= 60) return "The phone has had too many requests from the internet. Try again in an hour."
        }
        val q = asks.getOrPut(key) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first > 600_000L) q.poll()
            if (q.size >= 10) return "Asked too often. Try again in 10 minutes."
            q.add(now)
        }
        synchronized(allAsks) { allAsks.add(now) }
        if (asks.size > 1000) asks.clear()
        return null
    }

    // ------------------------------------------------------------------ plumbing

    internal fun http(method: String, url: String, body: String?, headers: Map<String, String> = emptyMap(), type: String = "application/json"): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", type)
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() }.getOrNull() ?: ""
        lastHeaders = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }
        return code to text
    }

    @Volatile internal var lastHeaders: Map<String, List<String>> = emptyMap()

    internal val confNow: Conf get() = conf
    internal fun update(f: (Conf) -> Conf) = synchronized(this) { save(f(conf)) }
    internal fun progress(s: String) = say(s)

    companion object {
        const val PORT = 8443
        /** Where the door hands connections to the page from, so it knows they came over the internet. */
        const val LOCAL_HOST = "127.0.0.88"
        const val DYNV6 = "https://dynv6.com/api/v2"
        const val ACME = "https://acme-v02.api.letsencrypt.org/directory"
        const val ACME_STAGING = "https://acme-staging-v02.api.letsencrypt.org/directory"
        const val AGREEMENT = "https://letsencrypt.org/repository/"
        /** Renewed when less than this is left (Let's Encrypt certificates last 90 days). */
        const val RENEW_MS = 30L * 24 * 3600 * 1000
        private const val TAG = "Site"

        fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
        fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
        fun b64u(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}

/**
 * Let's Encrypt (ACME, RFC 8555) for one name, proved by a DNS TXT record set through dynv6:
 * account (an EC key of the phone's own), order, the TXT, the check, a CSR, the certificate.
 */
private class Acme(private val site: Site) {
    private val rng = SecureRandom()
    private var nonce: String? = null
    private lateinit var dir: JsonObject
    private lateinit var accountKey: PrivateKey
    private lateinit var accountPub: ECPublicKey
    private var kid: String = ""

    fun issue() {
        val c = site.confNow
        val name = c.name
        site.progress("Getting a certificate for $name")
        dir = Json.parseToJsonElement(site.http("GET", if (c.staging) Site.ACME_STAGING else Site.ACME, null).second).jsonObject
        account()
        val (orderUrl, order) = post(url("newOrder"), buildJsonObject {
            put("identifiers", buildJsonArray { add(buildJsonObject { put("type", JsonPrimitive("dns")); put("value", JsonPrimitive(name)) }) })
        }.toString()).let { (h, b) -> (h["location"]?.firstOrNull() ?: throw IOException("Let's Encrypt gave no order")) to b }
        var removeTxt: (() -> Unit)? = null
        try {
            for (a in order["authorizations"]!!.jsonArray) {
                val authz = post(a.jsonPrimitive.content, null).second
                if (authz["status"]?.jsonPrimitive?.content == "valid") continue
                val ch = authz["challenges"]!!.jsonArray.map { it.jsonObject }.first { it["type"]?.jsonPrimitive?.content == "dns-01" }
                val token = ch["token"]!!.jsonPrimitive.content
                val keyAuth = "$token.${thumbprint()}"
                removeTxt = site.txt("_acme-challenge", Site.b64u(sha256(keyAuth.toByteArray())))
                site.progress("Waiting for the DNS check record to spread")
                Thread.sleep(45_000)
                post(ch["url"]!!.jsonPrimitive.content, "{}")
                val until = System.currentTimeMillis() + 120_000
                while (true) {
                    Thread.sleep(3_000)
                    val st = post(a.jsonPrimitive.content, null).second
                    when (st["status"]?.jsonPrimitive?.content) {
                        "valid" -> break
                        "invalid" -> throw IOException("Let's Encrypt could not check the name: " +
                            (st["challenges"]?.jsonArray?.firstOrNull { it.jsonObject["error"] != null }?.jsonObject?.get("error")?.jsonObject?.get("detail")?.jsonPrimitive?.contentOrNull ?: "invalid"))
                    }
                    if (System.currentTimeMillis() > until) throw IOException("Let's Encrypt took too long to check the name")
                }
            }
            // The certificate's own key, and a request for it.
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rng) }.generateKeyPair()
            val csr = csr(name, kp.public.encoded, kp.private)
            post(order["finalize"]!!.jsonPrimitive.content, buildJsonObject { put("csr", JsonPrimitive(Site.b64u(csr))) }.toString())
            var certUrl: String? = null
            val until = System.currentTimeMillis() + 120_000
            while (certUrl == null) {
                Thread.sleep(2_000)
                val o = post(orderUrl, null).second
                when (o["status"]?.jsonPrimitive?.content) {
                    "valid" -> certUrl = o["certificate"]?.jsonPrimitive?.content
                    "invalid" -> throw IOException("Let's Encrypt refused the certificate request")
                }
                if (System.currentTimeMillis() > until) throw IOException("Let's Encrypt took too long to issue")
            }
            val pem = postRaw(certUrl!!, null, "application/pem-certificate-chain").second
            val first = CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate
            site.update { it.copy(certKey = Site.b64(kp.private.encoded), certChain = pem, certUntil = first.notAfter.time) }
            site.progress("Certificate for $name until ${java.text.DateFormat.getDateInstance().format(first.notAfter)}")
        } finally {
            removeTxt?.let { runCatching { it() } }
        }
    }

    private fun url(k: String) = dir[k]!!.jsonPrimitive.content

    /** The account: its key made once and kept; registered once, agreeing to the terms the person agreed to. */
    private fun account() {
        val c = site.confNow
        if (c.accountKey.isBlank()) {
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rng) }.generateKeyPair()
            site.update { it.copy(accountKey = Site.b64(kp.private.encoded) + "|" + Site.b64(kp.public.encoded), accountUrl = "") }
        }
        val parts = site.confNow.accountKey.split("|")
        accountKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Site.unb64(parts[0])))
        accountPub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Site.unb64(parts[1]))) as ECPublicKey
        kid = site.confNow.accountUrl
        if (kid.isBlank()) {
            if (!site.confNow.agreed) throw IOException("Needs your agreement to Let's Encrypt's terms")
            val (h, _) = postRaw(url("newAccount"), """{"termsOfServiceAgreed":true}""", jwk = true)
            kid = h["location"]?.firstOrNull() ?: throw IOException("Let's Encrypt gave no account")
            site.update { it.copy(accountUrl = kid) }
        }
    }

    private fun post(url: String, payload: String?): Pair<Map<String, List<String>>, JsonObject> {
        val (h, b) = postRaw(url, payload)
        return h to (runCatching { Json.parseToJsonElement(b).jsonObject }.getOrDefault(JsonObject(emptyMap())))
    }

    /** A signed POST (POST-as-GET when [payload] is null), once more on a stale nonce. */
    private fun postRaw(url: String, payload: String?, accept: String? = null, jwk: Boolean = false): Pair<Map<String, List<String>>, String> {
        repeat(3) {
            val n = nonce ?: run { site.http("HEAD", url("newNonce"), null); site.lastHeaders["replay-nonce"]?.firstOrNull() }
            val protected = buildJsonObject {
                put("alg", JsonPrimitive("ES256")); put("nonce", JsonPrimitive(n)); put("url", JsonPrimitive(url))
                if (jwk) put("jwk", jwkJson()) else put("kid", JsonPrimitive(kid))
            }.toString()
            val p64 = Site.b64u(protected.toByteArray())
            val pl64 = if (payload == null) "" else Site.b64u(payload.toByteArray())
            val sig = Site.b64u(sign("$p64.$pl64".toByteArray()))
            val body = """{"protected":"$p64","payload":"$pl64","signature":"$sig"}"""
            val headers = if (accept != null) mapOf("Accept" to accept) else emptyMap()
            val (code, text) = site.http("POST", url, body, headers, type = "application/jose+json")
            val h = site.lastHeaders
            nonce = h["replay-nonce"]?.firstOrNull()
            if (code in 200..299) return h to text
            if ("badNonce" in text) return@repeat
            val detail = runCatching { Json.parseToJsonElement(text).jsonObject["detail"]?.jsonPrimitive?.content }.getOrNull()
            throw IOException("Let's Encrypt said $code: ${detail ?: text.take(160)}")
        }
        throw IOException("Let's Encrypt kept refusing the request")
    }

    private fun coord(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    private fun jwkJson() = buildJsonObject {
        put("crv", JsonPrimitive("P-256")); put("kty", JsonPrimitive("EC"))
        put("x", JsonPrimitive(Site.b64u(coord(accountPub.w.affineX)))); put("y", JsonPrimitive(Site.b64u(coord(accountPub.w.affineY))))
    }

    /** RFC 7638: the key's members in order, no spaces. */
    private fun thumbprint(): String {
        val j = jwkJson()
        val canonical = """{"crv":"P-256","kty":"EC","x":"${j["x"]!!.jsonPrimitive.content}","y":"${j["y"]!!.jsonPrimitive.content}"}"""
        return Site.b64u(sha256(canonical.toByteArray()))
    }

    /** ES256: the DER signature Java makes, as the 64 raw bytes JWS wants. */
    private fun sign(data: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").apply { initSign(accountKey); update(data) }.sign()
        var i = 2
        if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7F
        val rLen = der[i + 1].toInt(); val r = der.copyOfRange(i + 2, i + 2 + rLen); i += 2 + rLen
        val sLen = der[i + 1].toInt(); val s = der.copyOfRange(i + 2, i + 2 + sLen)
        return coord(BigInteger(1, r)) + coord(BigInteger(1, s))
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    // A PKCS#10 request, in DER by hand: subject CN=name, the key, and the name again as a SAN.
    private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        val len = body.size
        val l = when {
            len < 128 -> byteArrayOf(len.toByte())
            len < 256 -> byteArrayOf(0x81.toByte(), len.toByte())
            else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
        }
        return byteArrayOf(tag.toByte()) + l + body
    }

    private fun csr(name: String, spki: ByteArray, key: PrivateKey): ByteArray {
        val oidCn = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
        val oidExtReq = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x09, 0x0E)
        val oidSan = byteArrayOf(0x06, 0x03, 0x55, 0x1D, 0x11)
        val oidEcdsa256 = byteArrayOf(0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)
        val n = name.toByteArray()
        val subject = der(0x30, der(0x31, der(0x30, oidCn, der(0x0C, n))))
        val san = der(0x30, oidSan, der(0x04, der(0x30, der(0x82, n))))
        val attrs = der(0xA0, der(0x30, oidExtReq, der(0x31, der(0x30, san))))
        val info = der(0x30, byteArrayOf(0x02, 0x01, 0x00), subject, spki, attrs)
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(info) }.sign()
        return der(0x30, info, der(0x30, oidEcdsa256), der(0x03, byteArrayOf(0) + sig))
    }
}
