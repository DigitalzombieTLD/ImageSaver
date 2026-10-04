package app.imagesaver

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamRetryTest {
    @Test fun retriesEverySecondAfterFailure() = runTest {
        var attempts = 0
        val job = launch { runWithRetry { attempts++; throw java.io.IOException("down") } }
        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(999); runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, attempts)
        advanceTimeBy(1000); runCurrent()
        assertEquals(3, attempts)
        job.cancel()
    }

    @Test fun attemptsNeverOverlap() = runTest {
        var active = 0
        var maxActive = 0
        val job = launch {
            runWithRetry {
                active++; maxActive = maxOf(maxActive, active)
                try { delay(2500) } finally { active-- }
            }
        }
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, maxActive)
        job.cancel()
    }

    @Test fun cancelStopsRetrying() = runTest {
        var attempts = 0
        val job = launch { runWithRetry { attempts++ } }
        runCurrent(); job.cancel()
        advanceTimeBy(5000); runCurrent()
        assertEquals(1, attempts)
        assertTrue(job.isCancelled)
    }
}
