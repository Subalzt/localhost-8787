package dev.periy.bridge.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * What kind of link an address is reachable over, ordered by how fast and how steady it
 * tends to be in practice.
 */
enum class LinkKind(val label: String, val hint: String) {
    /**
     * USB tethering. Almost always the right answer when the cable is already there:
     * a dedicated link with no radio, no contention with other devices, and no
     * dependence on how far the phone is from an access point. Windows, macOS and Linux
     * all speak RNDIS/NCM out of the box, so the PC still installs nothing.
     */
    USB(
        "USB",
        "Usually the fastest and by far the steadiest. Plug the phone in and turn on USB tethering.",
    ),

    /** The phone's own hotspot: an AP-STA link with no router in the middle. */
    HOTSPOT(
        "Hotspot",
        "Direct to the phone with no router in between. Often faster than a shared Wi-Fi network.",
    ),

    /** Ordinary Wi-Fi, via whatever access point both devices are joined to. */
    WIFI(
        "Wi-Fi",
        "Speed depends on the band and the distance to the router. 5 or 6 GHz is several times 2.4 GHz.",
    ),

    /** Localhost 8787's own offline network, started from the app. Listed after Wi-Fi so the
     *  everyday address stays the one shown; the direct link has its own card. */
    DIRECT(
        "Direct link",
        "The phone's own offline network: one hop, nobody else on it.",
    ),

    /** Mobile data. Reachable in principle, but metered and behind carrier NAT. */
    CELLULAR(
        "Mobile data",
        "Metered, and most carriers block incoming connections. Phase 3 is what addresses this.",
    ),

    OTHER("Other", "")
}

/**
 * How far an address actually carries. Displaying a URL without this is misleading: a
 * mobile-data address looks exactly as legitimate as a Wi-Fi one and is not reachable
 * from anywhere at all.
 */
enum class Reach {
    /** Works from the same Wi-Fi or cable. The normal case. */
    LAN_ONLY,

    /**
     * Behind the carrier's NAT. The phone shares one public address with thousands of
     * subscribers and nothing can open a connection inbound to it. No amount of
     * configuration on this device changes that.
     */
    CARRIER_NAT,

    /** A globally routable address. Reachable if the other end can route to it. */
    PUBLIC,
}

data class Address(
    val iface: String,
    val host: String,
    val isIpv6: Boolean,
    /** True for RFC1918 / link-local ranges -- i.e. reachable only from the same LAN. */
    val isPrivate: Boolean,
    val kind: LinkKind,
) {
    fun url(port: Int): String =
        if (isIpv6) "http://[$host]:$port/" else "http://$host:$port/"

    val reach: Reach
        get() = when {
            isCarrierGrade(host) -> Reach.CARRIER_NAT
            isPrivate -> Reach.LAN_ONLY
            // A global IPv6 on mobile data is the one case where cellular can genuinely
            // be reachable -- provided the carrier does not firewall inbound, and
            // provided whatever is calling has IPv6 of its own.
            kind == LinkKind.CELLULAR && isIpv6 -> Reach.PUBLIC
            kind == LinkKind.CELLULAR -> Reach.CARRIER_NAT
            else -> Reach.PUBLIC
        }
}

/**
 * RFC 6598 shared address space, 100.64.0.0/10, which carriers hand out behind CGNAT.
 *
 * Worth detecting explicitly: it is not one of the RFC 1918 ranges, so
 * `isSiteLocalAddress` returns false for it and it would otherwise be mistaken for a
 * public address -- the exact confusion that makes a mobile-data URL look usable.
 */
private fun isCarrierGrade(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val a = parts[0].toIntOrNull() ?: return false
    val b = parts[1].toIntOrNull() ?: return false
    return a == 100 && b in 64..127
}

/** What the OS thinks the current default network can carry. */
data class LinkEstimate(
    val transport: String,
    val downKbps: Int,
    val upKbps: Int,
) {
    /** The OS estimate in MB/s, which is what a transfer is actually measured in. */
    val downMBps: Double get() = downKbps / 8000.0
    val upMBps: Double get() = upKbps / 8000.0
}

/**
 * Enumerates addresses this device is currently reachable on, best first.
 *
 * Vendor kernels name interfaces inconsistently, so classification is by prefix with a
 * generous list of aliases and a harmless default. Getting the *kind* wrong only mislabels
 * a row in the UI; getting the *order* wrong would hide the fastest option, which is why
 * USB sorts above everything else whenever a cable is present.
 */
object NetInfo {

    /** The direct link's address while it is up, so it is labelled as such. */
    @Volatile
    var directHost: String? = null

    /** Interfaces an access point runs on: swlan/ap/softap, or a second wlan. */
    fun isHotspotInterface(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") ||
            (n.length > 4 && n.startsWith("wlan") && n[4].isDigit() && n[4] != '0')
    }

    fun addresses(): List<Address> {
        val out = mutableListOf<Address>()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return out
        for (nif in ifaces) {
            if (!runCatching { nif.isUp }.getOrDefault(false)) continue
            if (runCatching { nif.isLoopback }.getOrDefault(true)) continue
            val ifaceKind = classify(nif.name)
            for (addr in nif.inetAddresses) {
                val kind = if (addr.hostAddress == directHost) LinkKind.DIRECT else ifaceKind
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                when (addr) {
                    is Inet4Address -> out += Address(
                        iface = nif.name,
                        host = addr.hostAddress ?: continue,
                        isIpv6 = false,
                        isPrivate = addr.isSiteLocalAddress,
                        kind = kind,
                    )

                    is Inet6Address -> {
                        // Strip the %scope suffix; it is meaningless to the PC.
                        val host = (addr.hostAddress ?: continue).substringBefore('%')
                        out += Address(
                            iface = nif.name,
                            host = host,
                            isIpv6 = true,
                            isPrivate = addr.isSiteLocalAddress || addr.isLinkLocalAddress ||
                                host.startsWith("fd") || host.startsWith("fc"),
                            kind = kind,
                        )
                    }
                }
            }
        }
        return out.sortedWith(
            compareBy(
                // USB first when it is there: it is both the fastest and the least
                // variable option, and it needs nothing installed on the PC.
                { it.kind.ordinal },
                // Then IPv4 on the LAN, which is what a laptop can actually reach today.
                { !(it.isPrivate && !it.isIpv6) },
                { it.isIpv6 },
                { it.host },
            )
        )
    }

    /**
     * The global IPv6 of the network the phone reaches the internet on, the one a laptop on
     * another network would have to connect to. Taken from the default network rather than the
     * interface list: mobile data also brings up an IMS network with a global address of its
     * own that nothing outside the carrier can reach. A stable address before a temporary one.
     */
    fun internetIpv6(ctx: Context): String? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork ?: return null) ?: return null
        lp.linkAddresses
            .filter { la ->
                val a = la.address
                a is Inet6Address && !a.isLinkLocalAddress && !a.isLoopbackAddress &&
                    (a.address[0].toInt() and 0xe0) == 0x20
            }
            .sortedBy { it.flags and 0x01 } // IFA_F_TEMPORARY last
            .firstOrNull()?.address?.hostAddress?.substringBefore('%')
    }.getOrNull().also {
        if (it != lastIpv6) android.util.Log.i("NetInfo", "Internet IPv6: ${it ?: "none"} (was ${lastIpv6 ?: "none"})")
        lastIpv6 = it
    }

    @Volatile
    private var lastIpv6: String? = null

    /** The address to put in the notification and the QR code. Null when offline. */
    fun preferred(): Address? = addresses().firstOrNull()

    /**
     * True when the only way out is mobile data, so nothing can reach this phone.
     *
     * The UI needs this because the failure is completely silent otherwise: the app shows
     * a real, correct-looking address, the browser simply times out, and nothing anywhere
     * explains that carrier NAT made the address undeliverable before the request left
     * the building.
     */
    fun onlyCellular(): Boolean {
        val all = addresses()
        return all.isNotEmpty() && all.all { it.kind == LinkKind.CELLULAR }
    }

    /**
     * True when the phone is running its own access point.
     *
     * Detected from the interface list rather than from WifiManager, because reading
     * tethering state properly needs privileged APIs, while an AP that is actually up
     * always has an interface with an address on it. If we can see it, it works.
     */
    fun hotspotActive(): Boolean = addresses().any { it.kind == LinkKind.HOTSPOT }

    /** The address a computer joined to this phone's hotspot should open. */
    fun hotspotAddress(): Address? = addresses().firstOrNull { it.kind == LinkKind.HOTSPOT }

    /**
     * True when a faster link is plugged in but the primary address is not on it -- used
     * to nudge toward the cable rather than silently leaving throughput on the table.
     */
    fun fasterLinkAvailable(current: Address?): Address? {
        val best = addresses().firstOrNull { it.kind == LinkKind.USB } ?: return null
        return if (current == null || current.kind != LinkKind.USB) best else null
    }

    /**
     * The platform's own estimate for the default network.
     *
     * Read from NetworkCapabilities rather than WifiInfo because the capability figures
     * need no location permission and cover cellular and USB too. They are estimates, not
     * measurements -- the benchmark endpoints are what produce real numbers -- but they
     * are enough to tell a 2.4 GHz link from a 5 GHz one at a glance.
     */
    fun estimate(ctx: Context): LinkEstimate? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "USB or Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            else -> "Unknown"
        }
        LinkEstimate(
            transport = transport,
            downKbps = caps.linkDownstreamBandwidthKbps,
            upKbps = caps.linkUpstreamBandwidthKbps,
        )
    }.getOrNull()

    private fun classify(name: String): LinkKind {
        val n = name.lowercase()
        return when {
            // rndis/ncm are the two USB tethering protocols; some ROMs expose usb0 or
            // an eth0 that is really the USB gadget.
            n.startsWith("rndis") || n.startsWith("ncm") || n.startsWith("usb") ||
                n.startsWith("eth") -> LinkKind.USB

            // Hotspot interfaces. swlan/softap on Qualcomm, ap on several others, and the
            // second or third wlan when the phone runs an access point beside its Wi-Fi.
            isHotspotInterface(n) -> LinkKind.HOTSPOT

            n.startsWith("wlan") || n.startsWith("wifi") || n.startsWith("wl") -> LinkKind.WIFI

            n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") ||
                n.startsWith("clat") || n.startsWith("v4-rmnet") -> LinkKind.CELLULAR

            else -> LinkKind.OTHER
        }
    }
}
