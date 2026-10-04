package app.imagesaver

import java.io.File
import java.io.IOException
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

sealed class CaptureResult {
    abstract val gpsAvailable: Boolean

    data class Saved(val fileName: String, val sizeBytes: Long, override val gpsAvailable: Boolean) : CaptureResult()
    data class Failed(val message: String, override val gpsAvailable: Boolean) : CaptureResult()
}

/** Saves a JPEG frame; GPS is best-effort and never blocks or prevents saving. */
class CaptureService(
    private val directory: File,
    private val idStore: IdStore,
    private val gpsTimeoutMs: Long = 10_000,
    private val now: () -> Date = { Date() },
) {
    suspend fun capture(
        jpeg: ByteArray?,
        text: String,
        locate: suspend () -> GpsCoordinates?,
    ): CaptureResult {
        val coords = try {
            withTimeoutOrNull(gpsTimeoutMs) { locate() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val gps = coords != null
        if (jpeg == null || jpeg.isEmpty()) {
            return CaptureResult.Failed("No frame available to save. Is the stream running?", gps)
        }
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create ${directory.path}")
            val time = now()
            var file: File
            var attempts = 0
            while (true) {
                file = File(directory, FilenameBuilder.build(idStore.nextId(), time, coords, text))
                if (file.createNewFile()) break // atomic: fails if name already exists
                if (++attempts >= 100) throw IOException("Could not find a free filename")
            }
            try {
                file.writeBytes(jpeg)
            } catch (e: IOException) {
                file.delete()
                throw e
            }
            CaptureResult.Saved(file.name, file.length(), gps)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CaptureResult.Failed("Save failed: ${e.message ?: e.javaClass.simpleName}", gps)
        }
    }
}
