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

    fun onReceived() { received++ }
    fun onDecoded() { decoded++ }
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
     * decoded one. Throws [IOException] if the stream ends without any received or decoded frame.
     */
    @Throws(IOException::class)
    fun <T : Any> consume(
        input: InputStream,
        contentType: String?,
        decode: (ByteArray) -> T?,
        onDecodeFailure: (FrameStats) -> Unit = {},
        onFrame: (jpeg: ByteArray, decoded: T, first: Boolean) -> Unit,
    ): FrameStats {
        val stats = FrameStats()
        MjpegParser.readFrames(input, MjpegParser.boundaryFromContentType(contentType)) { jpeg ->
            stats.onReceived()
            val value = decode(jpeg)
            if (value == null) {
                stats.onDecodeFailed(jpeg.size)
                onDecodeFailure(stats)
            } else {
                stats.onDecoded()
                onFrame(jpeg, value, stats.decoded == 1)
            }
        }
        stats.checkAtEnd()
        return stats
    }
}
