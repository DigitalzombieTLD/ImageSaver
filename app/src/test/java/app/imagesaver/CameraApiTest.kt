package app.imagesaver

import org.json.JSONObject
import org.junit.Assert.assertEquals
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

    private val realCaps = """{"connected":true,"device":"/dev/video0","format":"MJPG",
        "modes":[{"resolution":"1280x720","fps":[30,260]}],"quality":null,
        "controls":{
          "brightness":{"type":"int","min":-64,"max":64,"step":1,"default":-8193,"value":10},
          "contrast":{"type":"int","min":0,"max":64,"step":1,"default":57343,"value":32},
          "saturation":{"type":"int","min":0,"max":128,"step":1,"default":64,"value":60},
          "hue":{"type":"int","min":-40,"max":40,"step":1,"default":0,"value":0},
          "gamma":{"type":"int","min":72,"max":500,"step":1,"default":100,"value":100},
          "gain":{"type":"int","min":0,"max":100,"step":1,"default":0,"value":0},
          "power_line_frequency":{"type":"menu","min":0,"max":2,"step":1,"default":1,"value":1},
          "white_balance_temperature":{"type":"int","min":2800,"max":6500,"step":10,"default":4600,"value":4600},
          "sharpness":{"type":"int","min":0,"max":6,"step":1,"default":3,"value":3},
          "backlight_compensation":{"type":"int","min":0,"max":2,"step":1,"default":1,"value":1},
          "auto_exposure":{"type":"menu","min":1,"max":3,"step":1,"default":3,"value":3},
          "exposure_time_absolute":{"type":"int","min":1,"max":5000,"step":1,"default":157,"value":157},
          "focus_absolute":{"type":"int","min":0,"max":1023,"step":5,"default":0,"value":0}},
        "focus_controls":{"focus_absolute":{"type":"int","min":0,"max":1023,"step":5,"default":0,"value":0}}}"""

    @Test fun parsesControlsDeduplicatesFocusAndKeepsSentinelDefaults() {
        val caps = CameraApi.parseCapabilities(realCaps)!!
        assertTrue(caps.connected)
        assertEquals("/dev/video0", caps.device)
        assertEquals(13, caps.controls.size)
        assertEquals(1, caps.controls.count { it.name == "focus_absolute" })
        val brightness = caps.controls.first { it.name == "brightness" }
        assertEquals(-8193, brightness.default)
        assertEquals(10, brightness.validValue)
        assertEquals(57343, caps.controls.first { it.name == "contrast" }.default)
        assertTrue(caps.controls.first { it.name == "power_line_frequency" }.isMenu)
        assertNull(CameraApi.parseCapabilities("not json"))
    }

    @Test fun skipsBadControlsWithoutRejectingResponse() {
        val caps = CameraApi.parseCapabilities(
            """{"connected":true,"controls":{"a":{"type":"int","min":5,"max":1},"b":{"type":"bool","min":0,"max":1},
               "c":{"type":"int","min":0,"max":10,"value":99},"d":"x"},"focus_controls":null}""",
        )!!
        assertEquals(listOf("c"), caps.controls.map { it.name })
        assertNull(caps.controls[0].validValue)
        assertTrue(CameraApi.parseCapabilities("""{"connected":false}""")!!.controls.isEmpty())
    }

    @Test fun splitsMainAndMoreControls() {
        val caps = CameraApi.parseCapabilities(realCaps)!!
        val (main, more) = ControlLabels.split(caps.controls)
        assertEquals(
            listOf("brightness", "contrast", "saturation", "gamma", "gain", "sharpness", "focus_absolute"),
            main.map { it.name },
        )
        assertEquals(
            listOf("hue", "power_line_frequency", "white_balance_temperature", "backlight_compensation",
                "auto_exposure", "exposure_time_absolute"),
            more.map { it.name },
        )
        val none = ControlLabels.split(emptyList())
        assertTrue(none.first.isEmpty() && none.second.isEmpty())
        assertEquals("Focus", ControlLabels.title("focus_absolute"))
        assertEquals("White Balance Temperature", ControlLabels.title("white_balance_temperature"))
        assertEquals("2 – 60 Hz", ControlLabels.menuLabel("power_line_frequency", 2))
        assertEquals("7", ControlLabels.menuLabel("power_line_frequency", 7))
        assertEquals("3 – Aperture priority", ControlLabels.menuLabel("auto_exposure", 3))
    }

    @Test fun parsesSettingsAndPostResponseAsReadOnlyStatus() {
        val current = CameraApi.parseSettings("""{"resolution":"1280x720","fps":30,"quality":null,"controls":{"brightness":12}}""")!!
        assertEquals("1280x720", current.resolution)
        assertEquals(30, current.fps)
        assertEquals(mapOf("brightness" to 12), current.controls)
        assertEquals("1280x720, 30 fps", current.display)
        val posted = CameraApi.parseSettingsResponse("""{"saved":true,"settings":{"resolution":"640x480","fps":260,"controls":{}}}""")!!
        assertEquals(260, posted.fps)
        assertNull(CameraApi.parseSettingsResponse("""{"saved":false,"settings":{}}"""))
        assertNull(CameraApi.parseSettingsResponse("""{"error":"bad"}"""))
        assertNull(CameraApi.parseSettingsResponse("<html>"))
        assertNull(CameraApi.parseSettings("<html>"))
    }

    @Test fun postBodyContainsOnlyControlsAndValidatesAgainstCapabilities() {
        val caps = CameraApi.parseCapabilities(realCaps)!!
        val body = JSONObject(String(CameraApi.settingsBody(mapOf("brightness" to 5, "power_line_frequency" to 2))!!, Charsets.UTF_8))
        assertEquals(setOf("controls"), body.keySet())
        assertEquals(5, body.getJSONObject("controls").getInt("brightness"))
        assertNull(CameraApi.settingsBody(emptyMap()))

        assertNull(CameraApi.validateControls(caps, mapOf("brightness" to 5, "focus_absolute" to 100, "power_line_frequency" to 2)))
        assertTrue(CameraApi.validateControls(caps, mapOf("brightness" to 500))!!.contains("Brightness"))
        assertTrue(CameraApi.validateControls(caps, mapOf("focus_absolute" to 101))!!.contains("steps of 5"))
        assertTrue(CameraApi.validateControls(caps, mapOf("power_line_frequency" to 3))!!.contains("Power Line"))
        assertTrue(CameraApi.validateControls(caps, mapOf("zoom_absolute" to 1))!!.contains("does not support"))
        assertEquals("No controls changed", CameraApi.validateControls(caps, emptyMap()))
    }

    @Test fun reportsServerErrorStatuses() {
        assertTrue(CameraApi.errorForStatus(400).contains("400"))
        assertTrue(CameraApi.errorForStatus(503).contains("503"))
        assertTrue(CameraApi.errorForStatus(500).contains("500"))
    }
}
