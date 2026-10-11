package app.imagesaver

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraApiTest {
    @Test fun normalizesHostsAndBuildsEndpoints() {
        assertEquals("192.168.43.5", CameraApi.normalizeHost(" 192.168.43.5 "))
        assertEquals("192.168.43.5", CameraApi.normalizeHost("http://192.168.43.5/stream"))
        assertEquals("192.168.43.5:81", CameraApi.normalizeHost("HTTP://192.168.43.5:81/stream?x=1#f"))
        assertEquals("cam.local", CameraApi.normalizeHost("user:pw@cam.local/video"))
        assertEquals("[fe80::1]:80", CameraApi.normalizeHost("http://[fe80::1]:80/stream"))
        assertEquals("http://1.2.3.4/stream", CameraApi.streamUrl("1.2.3.4"))
        assertEquals("http://1.2.3.4/api/capabilities", CameraApi.capabilitiesUrl("1.2.3.4"))
        assertEquals("http://1.2.3.4/api/settings", CameraApi.settingsUrl("http://1.2.3.4/stream"))
        for (bad in listOf("", "http://", "/stream", "a b", "host:", "host:99999", "-x", "ho$.st")) {
            assertNull(bad, CameraApi.normalizeHost(bad))
        }
    }

    @Test fun parsesDynamicCapabilitiesAndQuality() {
        val capabilities = CameraApi.parseCapabilities(
            """{"connected":true,"device":"/dev/video0","format":"MJPG",
               "modes":[{"resolution":"1280x720","fps":[30,60]},{"resolution":"640x480","fps":[15,30]}],
               "quality":{"control":"jpeg_quality","min":10,"max":100,"step":5,"default":75,"value":80},
               "controls":{"brightness":{"min":0,"max":255}}}""",
        )!!
        assertTrue(capabilities.connected)
        assertEquals("/dev/video0", capabilities.device)
        assertEquals(listOf("1280x720", "640x480"), capabilities.modes.map { it.resolution })
        assertEquals(listOf(30, 60), capabilities.modes.first().fps)
        assertEquals("1280x720 (30, 60 fps)", capabilities.modes.first().displayName)
        assertTrue(capabilities.quality!!.accepts(85))
        assertFalse(capabilities.quality.accepts(82))
        assertNull(CameraApi.parseCapabilities("not json"))
        assertNull(CameraApi.parseCapabilities("""{"connected":true,"modes":[],"quality":null}"""))
    }

    @Test fun supportsUnavailableCameraAndMissingQualityControl() {
        val unavailable = CameraApi.parseCapabilities(
            """{"connected":false,"device":null,"format":null,"modes":[],"quality":null,"controls":{}}""",
        )!!
        assertFalse(unavailable.connected)
        assertNull(unavailable.quality)
        assertNull(CameraApi.parseCapabilities("""{"connected":true,"modes":[{"resolution":"640x480","fps":["30"]}],"quality":null}"""))

        val withoutQuality = CameraApi.parseCapabilities(
            """{"connected":true,"modes":[{"resolution":"640x480","fps":[30]}],"quality":null}""",
        )!!
        assertTrue(CameraApi.validateSettings(withoutQuality, "640x480", 30, null) == null)
        assertTrue(CameraApi.validateSettings(withoutQuality, "640x480", 30, 20)!!.contains("does not support"))
    }

    @Test fun parsesSettingsAndNestedPostResponse() {
        val current = CameraApi.parseSettings("""{"resolution":"1280x720","fps":30,"quality":80,"controls":{}}""")
        assertEquals(CameraSettings("1280x720", 30, 80), current)
        assertEquals(
            CameraSettings("640x480", null, null),
            CameraApi.parseSettingsResponse("""{"saved":true,"settings":{"resolution":"640x480","fps":null,"quality":null,"controls":{}},"note":"ok"}"""),
        )
        assertNull(CameraApi.parseSettings("{}"))
        assertNull(CameraApi.parseSettings("""{"resolution":"640x480","fps":"30","quality":20}"""))
        assertNull(CameraApi.parseSettingsResponse("""{"saved":false,"settings":{"resolution":"640x480","fps":null,"quality":null}}"""))
        assertNull(CameraApi.parseSettingsResponse("""{"error":"bad"}"""))
        assertNull(CameraApi.parseSettingsResponse("<html>"))
    }

    @Test fun buildsJsonRequestAndValidatesAdvertisedValues() {
        val caps = CameraApi.parseCapabilities(
            """{"connected":true,"modes":[{"resolution":"1280x720","fps":[30,60]}],
               "quality":{"control":"jpeg_quality","min":10,"max":100,"step":5,"default":75,"value":80}}""",
        )!!
        val body = JSONObject(String(CameraApi.settingsBody("1280x720", 60, 85)!!, Charsets.UTF_8))
        assertEquals("1280x720", body.getString("resolution"))
        assertEquals(60, body.getInt("fps"))
        assertEquals(85, body.getInt("quality"))
        val noFps = JSONObject(String(CameraApi.settingsBody("1280x720", null, null)!!, Charsets.UTF_8))
        assertTrue(noFps.isNull("fps"))
        assertFalse(noFps.has("quality"))

        assertNull(CameraApi.validateSettings(caps, "1280x720", 60, 85))
        assertTrue(CameraApi.validateSettings(caps, "640x480", 30, 80)!!.contains("resolution"))
        assertTrue(CameraApi.validateSettings(caps, "1280x720", 25, 80)!!.contains("frame rate"))
        assertTrue(CameraApi.validateSettings(caps, "1280x720", 30, 82)!!.contains("Quality"))
        assertEquals(85, CameraApi.parseQuality("85", caps.quality!!))
        assertNull(CameraApi.parseQuality("82", caps.quality))
        assertNull(CameraApi.settingsBody("not-a-resolution", 30, 80))
    }

    @Test fun reportsServerErrorStatuses() {
        assertTrue(CameraApi.errorForStatus(400).contains("400"))
        assertTrue(CameraApi.errorForStatus(503).contains("503"))
        assertTrue(CameraApi.errorForStatus(500).contains("500"))
    }
}
