package app.imagesaver

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Extracts JPEG frames from an MJPEG byte stream by scanning for SOI (FFD8) / EOI (FFD9) markers,
 * ignoring multipart headers. Returns normally at end of stream.
 */
object MjpegParser {
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024

    @Throws(IOException::class)
    fun readFrames(input: InputStream, onFrame: (ByteArray) -> Unit) {
        val buf = ByteArray(8192)
        val frame = ByteArrayOutputStream()
        var inFrame = false
        var prev = -1
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            for (i in 0 until n) {
                val b = buf[i].toInt() and 0xFF
                if (!inFrame) {
                    if (prev == 0xFF && b == 0xD8) {
                        inFrame = true
                        frame.reset()
                        frame.write(0xFF)
                        frame.write(0xD8)
                    }
                } else {
                    frame.write(b)
                    if (prev == 0xFF && b == 0xD9) {
                        onFrame(frame.toByteArray())
                        inFrame = false
                        prev = -1
                        continue
                    }
                    if (frame.size() > MAX_FRAME_BYTES) throw IOException("Frame too large")
                }
                prev = b
            }
        }
    }
}
