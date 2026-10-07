package app.imagesaver

import java.io.File
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private class MemoryIdStore(var last: Long = 0) : IdStore {
        override fun nextId() = ++last
    }

    private fun service(ids: IdStore, timeout: Long = 500) =
        CaptureService(FileImageStorage(tmp.root), ids, timeout, { Date(0) })

    @Test fun savesWithGps() = runBlocking {
        val r = service(MemoryIdStore()).capture(byteArrayOf(1, 2, 3), "4", "2.5") { GpsCoordinates(1.0, 2.0) }
        r as CaptureResult.Saved
        assertTrue(r.gpsAvailable)
        assertEquals(3L, r.sizeBytes)
        assertTrue(File(tmp.root, r.fileName).exists())
        assertTrue(r.fileName.contains("1.000000_2.000000"))
    }

    @Test fun nullLocationFallsBack() = runBlocking {
        val r = service(MemoryIdStore()).capture(byteArrayOf(1), "", "") { null } as CaptureResult.Saved
        assertFalse(r.gpsAvailable)
        assertTrue(r.fileName.contains("_0.0_0.0_"))
    }

    @Test fun locationExceptionFallsBack() = runBlocking {
        val r = service(MemoryIdStore()).capture(byteArrayOf(1), "", "") { throw SecurityException("x") }
        assertTrue(r is CaptureResult.Saved && r.fileName.contains("_0.0_0.0_"))
    }

    @Test fun locationTimeoutFallsBack() = runBlocking {
        val r = service(MemoryIdStore(), timeout = 50).capture(byteArrayOf(1), "", "") {
            delay(5000)
            GpsCoordinates(1.0, 1.0)
        }
        assertTrue(r is CaptureResult.Saved && r.fileName.contains("_0.0_0.0_") && !r.gpsAvailable)
    }

    @Test fun disabledLocationSkipsLookup() = runBlocking {
        val r = service(MemoryIdStore()).capture(byteArrayOf(1), "", "", includeLocation = false) {
            throw AssertionError("Location lookup must not run")
        } as CaptureResult.Saved
        assertFalse(r.gpsAvailable)
        assertTrue(r.fileName.contains("_0.0_0.0_"))
    }

    @Test fun idsIncreaseAndCollisionsAvoided() = runBlocking {
        val first = service(MemoryIdStore()).capture(byteArrayOf(1), "1", "1.5") { null } as CaptureResult.Saved
        // simulate a restart where the stored counter was lost: same ID would collide
        val second = service(MemoryIdStore()).capture(byteArrayOf(1), "1", "1.5") { null } as CaptureResult.Saved
        assertTrue(first.fileName != second.fileName)
        assertTrue(second.fileName.startsWith("00002_"))
    }

    @Test fun invalidNummerFailsWithoutSaving() = runBlocking {
        val r = service(MemoryIdStore()).capture(byteArrayOf(1), "abc", "") { null }
        assertTrue(r is CaptureResult.Failed)
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test fun invalidStandFailsWithoutSaving() = runBlocking {
        val r = service(MemoryIdStore()).capture(byteArrayOf(1), "1", "abc") { null }
        assertTrue(r is CaptureResult.Failed)
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test fun missingFrameFails() = runBlocking {
        val r = service(MemoryIdStore()).capture(null, "1", "1.5") { null }
        assertTrue(r is CaptureResult.Failed && !r.gpsAvailable)
    }

    @Test fun storageErrorReported() = runBlocking {
        val blocker = tmp.newFile("blocker")
        val r = CaptureService(FileImageStorage(File(blocker, "sub")), MemoryIdStore()).capture(byteArrayOf(1), "1", "1.5") { null }
        assertTrue(r is CaptureResult.Failed)
    }
}
