package app.imagesaver

import java.util.Date
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

sealed class CaptureResult {
    abstract val gpsAvailable: Boolean

    data class Saved(
        val fileName: String,
        val sizeBytes: Long,
        override val gpsAvailable: Boolean,
        val uri: String? = null,
    ) : CaptureResult()
    data class Failed(val message: String, override val gpsAvailable: Boolean) : CaptureResult()
}

/** Saves a JPEG frame; GPS is best-effort and never blocks or prevents saving. */
class CaptureService(
    private val storage: ImageStorage,
    private val idStore: IdStore,
    private val gpsTimeoutMs: Long = 10_000,
    private val now: () -> Date = { Date() },
) {
    suspend fun capture(
        jpeg: ByteArray?,
        nummer: String,
        stand: String,
        includeLocation: Boolean = true,
        locate: suspend () -> GpsCoordinates?,
    ): CaptureResult {
        if (FilenameBuilder.normalizeNummer(nummer) == null) {
            return CaptureResult.Failed("Nummer must be a decimal number (e.g. 12 or 12.5)", false)
        }
        if (FilenameBuilder.normalizeStand(stand) == null) {
            return CaptureResult.Failed("Stand must be a decimal number (e.g. 12 or 12.5)", false)
        }
        val coords = (if (includeLocation) {
            try {
                withTimeoutOrNull(gpsTimeoutMs) { locate() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } else null)?.takeIf { it.latitude.isFinite() && it.longitude.isFinite() }
        val gps = coords != null
        if (jpeg == null || jpeg.isEmpty()) {
            return CaptureResult.Failed("No frame available to save. Is the stream running?", gps)
        }
        return try {
            val time = now()
            var attempts = 0
            var stored: StoredImage? = null
            while (stored == null) {
                val name = FilenameBuilder.build(idStore.nextId(), time, coords, nummer, stand)
                try {
                    stored = storage.save(name, jpeg)
                } catch (e: NameExistsException) {
                    if (++attempts >= 100) throw java.io.IOException("Could not find a free filename")
                }
            }
            CaptureResult.Saved(stored.fileName, stored.sizeBytes, gps, stored.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CaptureResult.Failed("Save failed: ${e.message ?: e.javaClass.simpleName}", gps)
        }
    }
}
