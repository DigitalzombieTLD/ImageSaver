package app.imagesaver

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Incremental parser for MJPEG streams.
 *
 * When the response Content-Type carries a multipart boundary (the normal case,
 * `multipart/x-mixed-replace; boundary=...`) the stream is parsed part by part: delimiter line, part headers, then the
 * JPEG body, whose length comes from the part's `Content-Length` when present (falling back to reading up to the next
 * delimiter). Without a boundary the parser falls back to scanning for JPEG SOI (FFD8) / EOI (FFD9) markers.
 *
 * Frames are delivered as they complete; nothing but the current frame is buffered. A stream that ends (or is cut off)
 * before at least one complete frame has been received throws an [IOException] describing what happened.
 */
object MjpegParser {
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    const val MAX_HEADER_LINE = 4096
    const val MAX_HEADER_BYTES = 16 * 1024
    const val MAX_PREAMBLE_BYTES = 64 * 1024

    /** Extracts the multipart boundary (quotes removed) from a Content-Type value, or null if there is none. */
    fun boundaryFromContentType(contentType: String?): String? {
        val type = contentType?.trim() ?: return null
        if (!type.startsWith("multipart/", ignoreCase = true)) return null
        val paramsStart = type.indexOf(';')
        if (paramsStart < 0) return null
        val params = type.substring(paramsStart + 1)
        val match = Regex("""(?i)(?:^|;)\s*boundary\s*=""").find(";$params") ?: return null
        val rest = (";$params").substring(match.range.last + 1).trimStart()
        val value = if (rest.startsWith("\"")) {
            val sb = StringBuilder()
            var i = 1
            while (i < rest.length && rest[i] != '"') {
                if (rest[i] == '\\' && i + 1 < rest.length) i++
                sb.append(rest[i])
                i++
            }
            sb.toString()
        } else {
            rest.substringBefore(';').trim()
        }
        return value.takeIf { it.isNotEmpty() && it.length <= 200 }
    }

    /** Returns the number of frames delivered. Throws [IOException] on malformed/truncated data or if none arrived. */
    @Throws(IOException::class)
    fun readFrames(input: InputStream, boundary: String? = null, onFrame: (ByteArray) -> Unit): Int {
        val reader = ByteReader(input)
        val frames = if (boundary.isNullOrEmpty()) readByMarkers(reader, onFrame) else readMultipart(reader, boundary, onFrame)
        if (frames == 0) {
            throw IOException(
                "Stream ended before the first frame (server closed the connection after ${reader.total} bytes" +
                    (if (reader.total == 0L) ", no data" else "") + ")",
            )
        }
        return frames
    }

    private fun readMultipart(reader: ByteReader, boundary: String, onFrame: (ByteArray) -> Unit): Int {
        val delimiter = "--$boundary"
        val closing = "$delimiter--"
        // some servers advertise the boundary already including the leading "--"
        val alt = if (boundary.startsWith("--")) boundary else null
        val marker = ("\n$delimiter").toByteArray(Charsets.ISO_8859_1)
        var frames = 0
        var delimiterSeen = false
        while (true) {
            if (!delimiterSeen) {
                var skipped = 0
                while (true) {
                    val line = reader.readLine(MAX_HEADER_LINE) ?: return frames
                    val t = line.trim()
                    if (t == delimiter || t == alt) break
                    if (t == closing) return frames
                    skipped += line.length + 2
                    if (skipped > MAX_PREAMBLE_BYTES) throw IOException("Multipart boundary \"$boundary\" not found in stream")
                }
            }
            delimiterSeen = false
            val headers = HashMap<String, String>()
            var headerBytes = 0
            while (true) {
                val line = reader.readLine(MAX_HEADER_LINE) ?: throw IOException("Stream ended inside multipart part headers")
                if (line.isEmpty()) break
                headerBytes += line.length + 2
                if (headerBytes > MAX_HEADER_BYTES) throw IOException("Multipart part headers too large")
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val lengthHeader = headers["content-length"]
            if (lengthHeader != null) {
                val length = lengthHeader.toIntOrNull()
                    ?: throw IOException("Invalid part Content-Length \"$lengthHeader\"")
                if (length < 0) throw IOException("Invalid part Content-Length \"$lengthHeader\"")
                if (length > MAX_FRAME_BYTES) throw IOException("Frame too large")
                val body = reader.readFully(length)
                if (length > 0) { onFrame(body); frames++ }
            } else {
                var data = ByteArray(64 * 1024)
                var n = 0
                var matched = false
                while (!matched) {
                    val b = reader.read()
                    if (b < 0) throw IOException("Stream ended inside a frame ($n bytes received, no Content-Length)")
                    if (n == data.size) data = data.copyOf(minOf(data.size * 2, MAX_FRAME_BYTES + marker.size + 1))
                    data[n++] = b.toByte()
                    if (b == marker[marker.size - 1].toInt() && n >= marker.size) {
                        matched = marker.indices.all { data[n - marker.size + it] == marker[it] }
                    }
                    if (!matched && n > MAX_FRAME_BYTES + marker.size) throw IOException("Frame too large")
                }
                var end = n - marker.size
                if (end > 0 && data[end - 1] == '\r'.code.toByte()) end--
                if (end > 0) { onFrame(data.copyOf(end)); frames++ }
                val rest = reader.readLine(MAX_HEADER_LINE)?.trim() ?: return frames
                if (rest == "--") return frames
                if (rest.isNotEmpty()) throw IOException("Malformed multipart boundary line")
                delimiterSeen = true
            }
        }
    }

    private fun readByMarkers(reader: ByteReader, onFrame: (ByteArray) -> Unit): Int {
        val frame = ByteArrayOutputStream()
        var inFrame = false
        var prev = -1
        var frames = 0
        while (true) {
            val b = reader.read()
            if (b < 0) return frames
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
                    frames++
                    inFrame = false
                    prev = -1
                    continue
                }
                if (frame.size() > MAX_FRAME_BYTES) throw IOException("Frame too large")
            }
            prev = b
        }
    }

    /** Small buffered reader that never reads beyond what the parser needs from the underlying stream. */
    private class ByteReader(private val input: InputStream) {
        private val buf = ByteArray(16 * 1024)
        private var pos = 0
        private var limit = 0
        var total = 0L
            private set

        private fun fill(): Boolean {
            val n = input.read(buf, 0, buf.size)
            if (n < 0) return false
            if (n == 0) return fill()
            pos = 0
            limit = n
            total += n
            return true
        }

        fun read(): Int {
            if (pos >= limit && !fill()) return -1
            return buf[pos++].toInt() and 0xFF
        }

        /** Reads a CRLF/LF-terminated line (terminator stripped); null at EOF with no pending bytes. */
        fun readLine(maxLength: Int): String? {
            val sb = StringBuilder()
            while (true) {
                val b = read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) {
                    if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                    return sb.toString()
                }
                sb.append(b.toChar())
                if (sb.length > maxLength) throw IOException("Multipart line too long")
            }
        }

        fun readFully(length: Int): ByteArray {
            val out = ByteArray(length)
            var done = 0
            while (done < length) {
                if (pos >= limit && !fill()) {
                    throw IOException("Truncated frame: expected $length bytes, received $done")
                }
                val n = minOf(length - done, limit - pos)
                System.arraycopy(buf, pos, out, done, n)
                pos += n
                done += n
            }
            return out
        }
    }
}
