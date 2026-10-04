package app.imagesaver

import java.util.Date
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameBuilderTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val time = Date(1_700_000_000_000L) // 2023-11-14 22:13:20 UTC

    @Test fun withGps() {
        val name = FilenameBuilder.build(7, time, GpsCoordinates(48.1234567, -11.5), "hello", utc)
        assertEquals("00007_2023-11-14_22-13-20_48.123457_-11.500000_hello.jpg", name)
    }

    @Test fun noGps() {
        val name = FilenameBuilder.build(1, time, null, "x", utc)
        assertEquals("00001_2023-11-14_22-13-20_no_gps_x.jpg", name)
        assertTrue(name.contains("_no_gps"))
    }

    @Test fun emptyTextOmitted() {
        assertEquals("00001_2023-11-14_22-13-20_no_gps.jpg", FilenameBuilder.build(1, time, null, "  ", utc))
    }

    @Test fun sanitizesUnsafeText() {
        assertEquals("a-b-c", FilenameBuilder.sanitize("../a/b\\c:*?"))
        assertEquals("", FilenameBuilder.sanitize("///"))
        assertEquals("caf", FilenameBuilder.sanitize("café"))
        assertEquals(FilenameBuilder.MAX_TEXT_LENGTH, FilenameBuilder.sanitize("a".repeat(200)).length)
    }

    @Test fun nonFiniteCoordinatesFallBack() {
        assertEquals("_no_gps", FilenameBuilder.formatCoordinates(GpsCoordinates(Double.NaN, 1.0)))
    }
}
