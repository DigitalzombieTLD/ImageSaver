package app.imagesaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class HotspotInterfaceTest {
    @Test fun excludesUpstreamAndUnrelatedInterfaces() {
        val all = listOf(
            IfaceInfo("wlan0", "192.168.1.20"), IfaceInfo("rmnet_data0", "10.1.2.3"),
            IfaceInfo("ap0", "192.168.43.1"), IfaceInfo("dummy0", "10.0.0.1"),
        )
        assertEquals(listOf("ap0"), HotspotInterface.select(all, setOf("wlan0", "rmnet_data0")).map { it.name })
    }
    @Test fun upstreamNameIsNeverSelected() {
        assertTrue(HotspotInterface.select(listOf(IfaceInfo("wlan1", "192.168.5.1")), setOf("wlan1")).isEmpty())
    }
    @Test fun rejectsPublicOrInvalidAddresses() {
        assertTrue(HotspotInterface.select(listOf(IfaceInfo("ap0", "8.8.8.8"), IfaceInfo("swlan0", "x")), emptySet()).isEmpty())
    }
    @Test fun tetherNames() {
        for (n in listOf("ap0", "swlan0", "wlan1", "rndis0")) assertTrue(n, HotspotInterface.looksLikeTetherName(n))
        for (n in listOf("wlan0", "lo", "rmnet0", "tun0")) assertFalse(n, HotspotInterface.looksLikeTetherName(n))
    }
}
