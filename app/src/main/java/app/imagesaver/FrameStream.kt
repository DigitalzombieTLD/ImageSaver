package app.imagesaver

import java.io.IOException
import java.io.InputStream

/** Counters distinguishing JPEGs received from the stream and frames that were actually decoded. */
class FrameStats {
    var received = 0
        private set
    var decoded = 0
        private set
    var decodeFailures = 0
        private set
    var firstDecodeFailureSize = -1
        private set
    var applied = 0
        private set

    fun onReceived() { received++ }
    fun onDecoded() { decoded++ }
    fun onApplied() { applied++ }
    fun onDecodeFailed(size: Int) {
        decodeFailures++
        if (firstDecodeFailureSize < 0) firstDecodeFailureSize = size
    }

    fun failureDiagnostic(): String =
        "Received JPEG frame ($firstDecodeFailureSize bytes) could not be decoded"

    /** Throws if the stream delivered frames but none could be shown. */
    @Throws(IOException::class)
    fun checkAtEnd() {
        if (received > 0 && decoded == 0) {
            throw IOException("Received $received JPEG frame(s) but none could be decoded (first was $firstDecodeFailureSize bytes)")
        }
    }
}

object FrameStream {
    /**
     * Reads [input] until it ends, decoding each part with [decode] (null = undecodable). [onFrame] gets the original JPEG
     * bytes and decoded value; [onDecodeFailure] is called for every undecodable frame, [onFirstFrame] for the first
     * decoded one. [onFrame] must return true only once the frame was really applied to the preview (it may block
     * until the UI thread did so); this is counted in [FrameStats.applied]. Throws [IOException] if the stream ends
     * without any received or decoded frame.
     */
    @Throws(IOException::class)
    fun <T : Any> consume(
        input: InputStream,
        contentType: String?,
        stats: FrameStats = FrameStats(),
        decode: (ByteArray) -> T?,
        onDecodeFailure: (FrameStats) -> Unit = {},
        onFrame: (jpeg: ByteArray, decoded: T, first: Boolean) -> Boolean,
    ): FrameStats {
        MjpegParser.readFrames(input, MjpegParser.boundaryFromContentType(contentType)) { jpeg ->
            stats.onReceived()
            val value = decode(jpeg)
            if (value == null) {
                stats.onDecodeFailed(jpeg.size)
                onDecodeFailure(stats)
            } else {
                stats.onDecoded()
                if (onFrame(jpeg, value, stats.decoded == 1)) stats.onApplied()
            }
        }
        stats.checkAtEnd()
        return stats
    }
}

/** Counts bytes read so "connected but no bytes" and "bytes but no frames" can be told apart. */
class CountingInputStream(private val inner: InputStream) : InputStream() {
    var count = 0L
        private set

    override fun read(): Int = inner.read().also { if (it >= 0) count++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
    override fun available(): Int = inner.available()
    override fun close() = inner.close()
}

/** Thrown when the camera ends a long-lived stream normally; the message is the diagnostic shown to the user. */
class StreamEndedException(message: String) : IOException(message)

object StreamDiagnostics {
    const val READ_TIMEOUT_MS = 10_000

    fun connectedStatus(frames: Int) = "Connected – $frames frame(s)"

    /** Concrete explanation of why an attempt ended, distinguishing the failure stages. */
    fun describeEnd(httpStatus: Int, contentType: String?, bytes: Long, stats: FrameStats): String {
        val ctx = "HTTP $httpStatus, Content-Type: ${contentType ?: "none"}, $bytes byte(s), " +
            "parsed ${stats.received}, decoded ${stats.decoded}, shown ${stats.applied}"
        val what = when {
            bytes == 0L -> "connected but the camera sent no bytes"
            stats.received == 0 -> "bytes received but no multipart frames found"
            stats.decoded == 0 -> "frames received but none could be decoded"
            stats.applied == 0 -> "frames decoded but none applied to the preview"
            else -> "stream closed by the camera after ${stats.applied} frame(s)"
        }
        return "$what [$ctx]"
    }

    fun describeTimeout(httpStatus: Int, contentType: String?, bytes: Long, stats: FrameStats): String =
        "no data for ${READ_TIMEOUT_MS / 1000}s (camera idle or slow, not a connection refusal) " +
            "[HTTP $httpStatus, Content-Type: ${contentType ?: "none"}, $bytes byte(s), parsed ${stats.received}, " +
            "decoded ${stats.decoded}, shown ${stats.applied}]"

    fun disconnectedStatus(error: Throwable?): String {
        val reason = error?.message?.takeIf { it.isNotBlank() } ?: error?.javaClass?.simpleName ?: "stream attempt ended"
        return "Disconnected: $reason – retrying in 1s…"
    }
}

object StreamHttp {
    /**
     * The camera answers `Connection: close` and keeps the body open without Content-Length, so the body is delimited by
     * EOF. Ask for a fresh, non-cached, non-compressed connection so no stale pooled socket or transparent gzip is used.
     */
    fun configure(conn: java.net.HttpURLConnection) {
        conn.connectTimeout = 5000
        conn.readTimeout = StreamDiagnostics.READ_TIMEOUT_MS
        conn.useCaches = false
        conn.setRequestProperty("Connection", "close")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.setRequestProperty("Accept", "multipart/x-mixed-replace, image/jpeg, */*")
    }
}

/** Save-success flash timing; flashes only for a saved image. */
object SaveFlash {
    const val PEAK_ALPHA = 0.35f
    const val FADE_IN_MS = 80L
    const val FADE_OUT_MS = 220L
    fun shouldFlash(result: CaptureResult) = result is CaptureResult.Saved
}
