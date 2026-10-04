package app.imagesaver

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomLimitsTest {
    @Test fun clamps() {
        assertEquals(ZoomLimits.MIN, ZoomLimits.clamp(0.1f), 0f)
        assertEquals(ZoomLimits.MAX, ZoomLimits.clamp(100f), 0f)
        assertEquals(2f, ZoomLimits.clamp(2f), 0f)
        assertEquals(ZoomLimits.MIN, ZoomLimits.clamp(Float.NaN), 0f)
    }
}
