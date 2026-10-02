package dev.periy.bridge.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How each computer or phone reached this phone last: the link (the cable, the hotspot, Wi-Fi,
 * the direct link, USB debugging, the tunnel, the website), what the website or tunnel ran over,
 * and IPv4 or IPv6. For Home's "Connected now" and the page's own line about its link.
 */
data class Way(
    /** usb, hotspot, wifi, direct, adb, tunnel, website, other */
    val via: String,
    /** The website: hotspot, wifi, usb, tether or internet. The tunnel: tcp (IPv6) or udp (punched). */
    val over: String = "",
    val ip: String = "IPv4",
    val at: Long = System.currentTimeMillis(),
) {
    /** As a person would say it: "USB cable, IPv4", "Website over the internet, IPv6". */
    val words: String
        get() = when (via) {
            "usb" -> "USB cable"
            "hotspot" -> "Phone's hotspot"
            "wifi" -> "Same Wi-Fi"
            "direct" -> "Direct link"
            "adb" -> "USB debugging (the cable, tethering off)"
            "tunnel" -> if (over == "udp") "Tunnel over the internet, punched through" else "Tunnel over the internet"
            "website" -> "Website " + when (over) {
                "hotspot" -> "on the phone's hotspot"
                "wifi" -> "on the same Wi-Fi"
                "usb" -> "over the cable"
                "tether" -> "on the hotspot or cable"
                else -> "over the internet"
            }
            else -> "Local network"
        } + ", " + ip
}

object Ways {
    private val _now = MutableStateFlow<Map<String, Way>>(emptyMap())

    /** Device id to its last way in. */
    val now: StateFlow<Map<String, Way>> = _now

    fun seen(deviceId: String, way: Way) {
        val old = _now.value[deviceId]
        // The same way a moment ago: nothing new to say (and no redraw on every request).
        if (old != null && old.copy(at = way.at) == way && way.at - old.at < 5_000) return
        _now.value = _now.value + (deviceId to way)
    }

    fun last(deviceId: String): Way? = _now.value[deviceId]
}
