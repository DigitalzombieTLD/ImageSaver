package app.imagesaver

import java.util.Date
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FilenameBuilderTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val time = Date(1_700_000_000_000L) // 2023-11-14 22:13:20 UTC

   @Test fun withGps() {
    val name = FilenameBuilder.build(7, time, GpsCoordinates(48.1234567, -11.5), "12,5", "2.5", utc)
    assertEquals("00007_2023-11-14_22-13-20_48.123457_-11.500000_12.5_2.5.jpg", name)
 }
 
 @Test fun noGpsUsesZeroCoordinates() {
     val name = FilenameBuilder.build(1, time, null, "3", "", utc)
     assertEquals("00001_2023-11-14_22-13-20_0.0_0.0_3_X.jpg", name)
     assertFalse(name.contains("no_gps"))
 }

    @Test fun emptyNummerUsesX() {
        assertEquals("00001_2023-11-14_22-13-20_0.0_0.0_X_2.5.jpg", FilenameBuilder.build(1, time, null, " ", "2.5", utc))
    }

    @Test fun emptyStandUsesX() {
        assertEquals("00001_2023-11-14_22-13-20_0.0_0.0_1.5_X.jpg", FilenameBuilder.build(1, time, null, "1.5", "  ", utc))
    }

    @Test fun emptyNummerAndStandBothUseX() {
        assertEquals("00001_2023-11-14_22-13-20_0.0_0.0_X_X.jpg", FilenameBuilder.build(1, time, null, " ", "  ", utc))
    }

    @Test fun invalidStandCannotEnterFilename() {
        assertEquals("00001_2023-11-14_22-13-20_0.0_0.0_1_X.jpg", FilenameBuilder.build(1, time, null, "1", "a/b", utc))
    }

    @Test fun nummerValidation() {
        assertEquals("12", FilenameBuilder.normalizeNummer(" 12 "))
        assertEquals("12.5", FilenameBuilder.normalizeNummer("12,5"))
        assertEquals("0.5", FilenameBuilder.normalizeNummer(".5"))
        assertEquals("-3.25", FilenameBuilder.normalizeNummer("-3.25"))
        assertEquals("3", FilenameBuilder.normalizeNummer("+3."))
        assertEquals("", FilenameBuilder.normalizeNummer(""))
        assertEquals("12.5", FilenameBuilder.normalizeStand("12,5"))
        assertEquals("0.5", FilenameBuilder.normalizeStand(".5"))
        assertEquals("", FilenameBuilder.normalizeStand(" "))
        for (bad in listOf("abc", "1e5", "1.2.3", "-", ".", "../1", "1_2", "1".repeat(30))) {
            assertNull(bad, FilenameBuilder.normalizeNummer(bad))
            assertNull(bad, FilenameBuilder.normalizeStand(bad))
        }
    }

    @Test fun sanitizesUnsafeText() {
        assertEquals("a-b-c", FilenameBuilder.sanitize("../a/b\\c:*?"))
        assertEquals("", FilenameBuilder.sanitize("///"))
        assertEquals("caf", FilenameBuilder.sanitize("café"))
        assertEquals(FilenameBuilder.MAX_TEXT_LENGTH, FilenameBuilder.sanitize("a".repeat(200)).length)
    }

    @Test fun nonFiniteCoordinatesFallBack() {
        assertEquals("0.0_0.0", FilenameBuilder.formatCoordinates(GpsCoordinates(Double.NaN, 1.0)))
    }
}
