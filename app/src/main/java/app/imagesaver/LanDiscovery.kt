package app.imagesaver

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** A reachable HTTP host; [isMjpeg] is true only when an MJPEG response was actually detected at [url]. */
data class DiscoveryCandidate(val host: String, val url: String, val isMjpeg: Boolean) {
    val label: String get() = if (isMjpeg) "$host – MJPEG stream ($url)" else "$host – HTTP service (edit path)"
}

/** Platform-independent LAN discovery logic: scans only the /24 of the device's own IPv4 address on port 80. */
object LanDiscovery {
    const val PORT = 80
    const val MAX_CONCURRENCY = 32
    const val CONNECT_TIMEOUT_MS = 500
    const val HTTP_TIMEOUT_MS = 1500

    /** Common MJPEG paths probed (in order) on hosts with an open port 80. */
    val STREAM_PATHS = listOf("/", "/video", "/stream", "/mjpg/video.mjpg", "/video.mjpg", "/?action=stream", "/videostream.cgi")

    fun parseIpv4(ip: String): IntArray? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            out[i] = p.toInt()
            if (out[i] > 255) return null
        }
        return out
    }

    /** All other host addresses (.1–.254) in the /24 of [ownIp]; empty if [ownIp] is invalid. */
    fun subnetHosts(ownIp: String): List<String> {
        val o = parseIpv4(ownIp) ?: return emptyList()
        return (1..254).filter { it != o[3] }.map { "${o[0]}.${o[1]}.${o[2]}.$it" }
    }

    fun isMjpegContentType(contentType: String?): Boolean =
        contentType != null && contentType.trim().lowercase().startsWith("multipart/x-mixed-replace")

    /**
     * Probes [hosts] with at most [concurrency] simultaneous probes. [probe] returns a candidate or null.
     * [onProgress] receives (done, total). Cancellation of the calling coroutine stops the scan.
     */
    suspend fun scan(
        hosts: List<String>,
        concurrency: Int = MAX_CONCURRENCY,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        probe: suspend (String) -> DiscoveryCandidate?,
    ): List<DiscoveryCandidate> = coroutineScope {
        val gate = Semaphore(concurrency)
        val lock = Any()
        var done = 0
        hosts.map { host ->
            async {
                val result = gate.withPermit {
                    try { probe(host) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                }
                val n = synchronized(lock) { ++done }
                onProgress(n, hosts.size)
                result
            }
        }.awaitAll().filterNotNull().sortedBy { h -> parseIpv4(h.host)?.get(3) ?: 0 }
    }

    /** Real network probe: TCP connect to port 80, then look for an MJPEG response on common paths. */
    suspend fun probeHost(host: String): DiscoveryCandidate? = withContext(Dispatchers.IO) {
        runInterruptible {
            try {
                Socket().use { it.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS) }
            } catch (e: java.io.IOException) {
                return@runInterruptible null
            }
            for (path in STREAM_PATHS) {
                val url = "http://$host$path"
                val mjpeg = try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = HTTP_TIMEOUT_MS
                    c.readTimeout = HTTP_TIMEOUT_MS
                    c.instanceFollowRedirects = false
                    try {
                        c.responseCode in 200..299 && isMjpegContentType(c.contentType)
                    } finally {
                        c.disconnect()
                    }
                } catch (e: java.io.IOException) {
                    false
                }
                if (mjpeg) return@runInterruptible DiscoveryCandidate(host, url, true)
            }
            DiscoveryCandidate(host, "http://$host/", false)
        }
    }
}
