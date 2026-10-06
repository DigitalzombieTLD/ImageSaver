package app.imagesaver

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

object NetworkHelper {
    /** IPv4 address of the active network, or null if there is none. */
    fun localIpv4(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val props = cm.getLinkProperties(network) ?: return null
        return props.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }?.hostAddress
    }
}
