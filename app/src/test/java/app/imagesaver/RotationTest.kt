package app.imagesaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationTest {
    @Test fun rightWrapsAround() {
        assertEquals(90, Rotation.right(0)); assertEquals(0, Rotation.right(270))
    }
    @Test fun leftWrapsAround() {
        assertEquals(270, Rotation.left(0)); assertEquals(0, Rotation.left(90))
    }
    @Test fun fourStepsReturnToStart() {
        var d = 0
        repeat(4) { d = Rotation.right(d) }
        assertEquals(0, d)
        repeat(4) { d = Rotation.left(d) }
        assertEquals(0, d)
    }
    @Test fun axesSwapOnlyForQuarterTurns() {
        assertFalse(Rotation.swapsAxes(0)); assertTrue(Rotation.swapsAxes(90))
        assertFalse(Rotation.swapsAxes(180)); assertTrue(Rotation.swapsAxes(270))
    }
}
