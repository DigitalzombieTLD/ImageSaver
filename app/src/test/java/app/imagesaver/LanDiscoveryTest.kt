package app.imagesaver

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanDiscoveryTest {
    @Test fun subnetHostsStayInSlash24AndSkipOwn() {
        val hosts = LanDiscovery.subnetHosts("192.168.1.42")
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("192.168.1.") })
        assertFalse(hosts.contains("192.168.1.42"))
        assertFalse(hosts.contains("192.168.1.0"))
        assertFalse(hosts.contains("192.168.1.255"))
    }

    @Test fun invalidIpYieldsNoHosts() {
        for (bad in listOf("", "1.2.3", "256.1.1.1", "a.b.c.d", "1.2.3.4.5", "::1")) {
            assertTrue(bad, LanDiscovery.subnetHosts(bad).isEmpty())
        }
        assertNull(LanDiscovery.parseIpv4("1.2.3.-4"))
    }

    @Test fun mjpegContentType() {
        assertTrue(LanDiscovery.isMjpegContentType("multipart/x-mixed-replace; boundary=--frame"))
        assertTrue(LanDiscovery.isMjpegContentType(" Multipart/X-Mixed-Replace;boundary=a"))
        assertFalse(LanDiscovery.isMjpegContentType("text/html"))
        assertFalse(LanDiscovery.isMjpegContentType(null))
    }

    @Test fun scanCollectsSortedResultsAndReportsProgress() = runBlocking {
        val hosts = LanDiscovery.subnetHosts("10.0.0.1")
        var last = 0
        val found = LanDiscovery.scan(hosts, concurrency = 8, onProgress = { d, t -> last = maxOf(last, d); assertEquals(hosts.size, t) }) { h ->
            when (h) {
                "10.0.0.200" -> DiscoveryCandidate(h, "http://$h/", false)
                "10.0.0.20" -> DiscoveryCandidate(h, "http://$h/video", true)
                "10.0.0.99" -> throw java.io.IOException("boom")
                else -> null
            }
        }
        assertEquals(listOf("10.0.0.20", "10.0.0.200"), found.map { it.host })
        assertTrue(found[0].isMjpeg)
        assertEquals(hosts.size, last)
    }
}
