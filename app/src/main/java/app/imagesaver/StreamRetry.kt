package app.imagesaver

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Runs [attempt] repeatedly until cancelled. The next attempt only starts after the previous one
 * has ended and [retryDelayMs] has passed, so attempts never overlap. Failures never propagate. A normal return
 * is reported as a [StreamEndedException] so it is never mistaken for "no reason".
 */
suspend fun runWithRetry(
    retryDelayMs: Long = 1000,
    onError: (Throwable?) -> Unit = {},
    attempt: suspend () -> Unit,
) {
    while (true) {
        currentCoroutineContext().ensureActive()
        var error: Throwable? = null
        try {
            attempt()
            error = StreamEndedException("attempt ended without error")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e
        }
        onError(error)
        delay(retryDelayMs)
    }
}
