package app.imagesaver

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkHelper {
    /** IPv4 hotspot/tethering interfaces (excluding interfaces used by regular networks); empty if none found. */
    fun hotspotInterfaces(context: Context): List<IfaceInfo> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val upstream = cm.allNetworks.mapNotNull { cm.getLinkProperties(it)?.interfaceName }.toSet()
        val all = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }.mapNotNull { ni ->
                ni.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress?.let { IfaceInfo(ni.name, it) }
            }
        } catch (e: java.net.SocketException) {
            emptyList()
        }
        return HotspotInterface.select(all, upstream)
    }
}
