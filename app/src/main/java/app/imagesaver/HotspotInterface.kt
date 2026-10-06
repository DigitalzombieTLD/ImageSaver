package app.imagesaver

/** An IPv4-configured, up network interface (platform-independent view for selection logic). */
data class IfaceInfo(val name: String, val ipv4: String)

/**
 * Picks the Android hotspot/tethering interface. Android has no public API for the tethering interface or its
 * clients, so interfaces are recognised by common tethering names and anything used by a regular (upstream)
 * network, e.g. the connected Wi-Fi, is excluded.
 */
object HotspotInterface {
    private val NAME_PATTERNS = listOf(Regex("^ap\\d*$"), Regex("^swlan\\d+$"), Regex("^softap\\d*$"),
        Regex("^wlan[1-9]\\d*$"), Regex("^wigig\\d+$"), Regex("^rndis\\d+$"), Regex("^ncm\\d+$"), Regex("^eth\\d+$"))

    fun looksLikeTetherName(name: String): Boolean = NAME_PATTERNS.any { it.matches(name) }

    /** Candidate hotspot interfaces: tether-like name, valid private IPv4, not an upstream interface. */
    fun select(all: List<IfaceInfo>, upstream: Set<String>): List<IfaceInfo> =
        all.filter { it.name !in upstream && looksLikeTetherName(it.name) && isPrivate(it.ipv4) }

    private fun isPrivate(ip: String): Boolean {
        val o = LanDiscovery.parseIpv4(ip) ?: return false
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
    }
}
