package app.imagesaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CameraApiTest {
    @Test fun normalizesHosts() {
        assertEquals("192.168.43.5", CameraApi.normalizeHost(" 192.168.43.5 "))
        assertEquals("192.168.43.5", CameraApi.normalizeHost("http://192.168.43.5/stream"))
        assertEquals("192.168.43.5:81", CameraApi.normalizeHost("HTTP://192.168.43.5:81/stream?x=1#f"))
        assertEquals("cam.local", CameraApi.normalizeHost("user:pw@cam.local/video"))
        assertEquals("cam.local", CameraApi.normalizeHost("cam.local/"))
        assertEquals("[fe80::1]:80", CameraApi.normalizeHost("http://[fe80::1]:80/stream"))
    }

    @Test fun rejectsInvalidHosts() {
        for (bad in listOf("", "   ", "http://", "/stream", "a b", "host:", "host:99999", "-x", "ho$.st")) {
            assertNull(bad, CameraApi.normalizeHost(bad))
        }
        assertNull(CameraApi.normalizeHost(null))
    }

    @Test fun buildsEndpoints() {
        assertEquals("http://1.2.3.4/stream", CameraApi.streamUrl("1.2.3.4"))
        assertEquals("http://1.2.3.4/stream", CameraApi.streamUrl("http://1.2.3.4/stream"))
        assertEquals("http://1.2.3.4/status", CameraApi.statusUrl("1.2.3.4"))
        assertEquals("http://1.2.3.4/camera/settings", CameraApi.settingsUrl("http://1.2.3.4/stream"))
        assertNull(CameraApi.streamUrl(""))
    }

    @Test fun resolutionMapping() {
        assertEquals(listOf(6, 2, 1, 3, 4, 5), CameraResolution.values().map { it.id })
        assertEquals(320 to 240, CameraResolution.fromId(6)!!.let { it.width to it.height })
        assertEquals(1600 to 1200, CameraResolution.fromId(5)!!.let { it.width to it.height })
        assertEquals(1280 to 1024, CameraResolution.fromId(4)!!.let { it.width to it.height })
        assertNull(CameraResolution.fromId(0))
    }

    @Test fun qualityValidation() {
        assertEquals(10, CameraApi.parseQuality("10"))
        assertEquals(40, CameraApi.parseQuality(" 40 "))
        for (bad in listOf("9", "41", "", "abc", "12.5", "-15", "1e1", null)) assertNull(bad, CameraApi.parseQuality(bad))
    }

    @Test fun settingsBody() {
        val body = CameraApi.settingsBody(5, 40)!!
        assertEquals("resolution=5&quality=40", String(body, Charsets.US_ASCII))
        assert(body.size <= CameraApi.MAX_BODY_BYTES)
        assertNull(CameraApi.settingsBody(9, 20))
        assertNull(CameraApi.settingsBody(2, 9))
    }

    @Test fun parsesStatusIgnoringOtherFields() {
        val s = CameraApi.parseStatus(
            """{"resolution_id":2,"resolution_name":"VGA","resolution_width":640,"resolution_height":480,
            "frame_size":8,"jpeg_quality":20,"frame_max_bytes":50000,"other":"x"}""",
        )
        assertEquals(CameraSettings(2, "VGA", 640, 480, 20), s)
        assertNull(CameraApi.parseStatus("{}"))
        assertNull(CameraApi.parseStatus("not json"))
    }

    @Test fun parsesSettingsResponse() {
        val s = CameraApi.parseSettingsResponse("""{"ok":true,"resolution_id":4,"name":"SXGA","width":1280,"height":1024,"jpeg_quality":30}""")
        assertEquals(CameraSettings(4, "SXGA", 1280, 1024, 30), s)
        assertNotNull(CameraApi.parseSettingsResponse("""{"resolution_id":4,"name":"SXGA","width":1280,"height":1024,"jpeg_quality":30}"""))
        assertNull(CameraApi.parseSettingsResponse("""{"ok":false,"resolution_id":4,"name":"SXGA","width":1280,"height":1024,"jpeg_quality":30}"""))
        assertNull(CameraApi.parseSettingsResponse("""{"error":"bad"}"""))
        assertNull(CameraApi.parseSettingsResponse("<html>"))
    }

    @Test fun httpErrorMessages() {
        assert(CameraApi.errorForStatus(400).contains("400"))
        assert(CameraApi.errorForStatus(503).contains("503"))
        assert(CameraApi.errorForStatus(500).contains("500"))
    }
}
