package app.imagesaver

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MjpegParserTest {
    private fun jpeg(vararg body: Int) =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + body.map { it.toByte() }.toByteArray() + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun piPart(b: String, data: ByteArray) =
        "--$b\r\nContent-Type: image/jpeg\r\nContent-Length: ${data.size}\r\nX-Timestamp: 1.5\r\n\r\n".toByteArray() + data + "\r\n".toByteArray()

    private fun parse(data: ByteArray, boundary: String?, chunk: Int = Int.MAX_VALUE): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        val input = object : InputStream() {
            val src = ByteArrayInputStream(data)
            override fun read() = src.read()
            override fun read(b: ByteArray, off: Int, len: Int) = src.read(b, off, minOf(len, chunk))
        }
        MjpegParser.readFrames(input, boundary) { frames.add(it) }
        return frames
    }

    private fun expectIo(message: String, block: () -> Unit) {
        try { block(); fail("expected IOException") } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains(message, ignoreCase = true))
        }
    }

    @Test fun parsesPiStyleMultipartWithContentLength() {
        val a = jpeg(1, 2, 0xFF, 0xD9, 0x0D, 0x0A) // contains EOI marker + CRLF inside the body
        val b = jpeg(3)
        val data = piPart("boundarydonotcross", a) + piPart("boundarydonotcross", b)
        val frames = parse(data, "boundarydonotcross")
        assertEquals(2, frames.size)
        assertArrayEquals(a, frames[0])
        assertArrayEquals(b, frames[1])
    }

    @Test fun parsesInTinyChunksAndByteAtATime() {
        val a = jpeg(9, 8, 7)
        val data = piPart("frame", a) + piPart("frame", a)
        for (chunk in listOf(1, 2, 3, 7)) {
            val frames = parse(data, "frame", chunk)
            assertEquals(chunk.toString(), 2, frames.size)
            assertArrayEquals(a, frames[0])
        }
    }

    @Test fun parsesPartsWithoutContentLengthByBoundary() {
        val a = jpeg(1, 2)
        val b = jpeg(3)
        val data = "--b\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + a + "\r\n--b\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() +
            b + "\r\n--b--\r\n".toByteArray()
        val frames = parse(data, "b", 5)
        assertEquals(2, frames.size)
        assertArrayEquals(a, frames[0])
        assertArrayEquals(b, frames[1])
    }

    @Test fun toleratesLfOnlyAndBoundaryWithDashes() {
        val a = jpeg(4)
        val data = "--frame\nContent-Length: ${a.size}\n\n".toByteArray() + a + "\n--frame\nContent-Length: ${a.size}\n\n".toByteArray() + a
        assertEquals(2, parse(data, "frame").size)
        val dashed = "--myb\r\nContent-Length: ${a.size}\r\n\r\n".toByteArray() + a + "\r\n".toByteArray()
        assertEquals(1, parse(dashed, "--myb").size)
    }

    @Test fun extractsBoundaryFromContentType() {
        assertEquals("boundarydonotcross", MjpegParser.boundaryFromContentType("multipart/x-mixed-replace;boundary=boundarydonotcross"))
        assertEquals("abc", MjpegParser.boundaryFromContentType("multipart/x-mixed-replace; boundary=\"abc\""))
        assertEquals("a b;c", MjpegParser.boundaryFromContentType("multipart/x-mixed-replace; Boundary=\"a b;c\"; x=1"))
        assertEquals("abc", MjpegParser.boundaryFromContentType("Multipart/X-Mixed-Replace; charset=x; BOUNDARY=abc ; foo=bar"))
        assertEquals("--x", MjpegParser.boundaryFromContentType("multipart/x-mixed-replace; boundary=--x"))
        assertNull(MjpegParser.boundaryFromContentType("image/jpeg"))
        assertNull(MjpegParser.boundaryFromContentType("multipart/x-mixed-replace"))
        assertNull(MjpegParser.boundaryFromContentType("multipart/x-mixed-replace; boundary="))
        assertNull(MjpegParser.boundaryFromContentType(null))
    }

    @Test fun truncatedBodyThrows() {
        val full = piPart("b", jpeg(1, 2, 3, 4, 5, 6, 7, 8))
        val data = piPart("b", jpeg(1)) + full.copyOf(full.size - 6)
        val frames = mutableListOf<ByteArray>()
        expectIo("truncated") { MjpegParser.readFrames(ByteArrayInputStream(data), "b") { frames.add(it) } }
        assertEquals(1, frames.size)
    }

    @Test fun malformedPartsThrow() {
        expectIo("Content-Length") { parse("--b\r\nContent-Length: abc\r\n\r\nxx".toByteArray(), "b") }
        expectIo("Content-Length") { parse("--b\r\nContent-Length: -4\r\n\r\nxx".toByteArray(), "b") }
        expectIo("too large") { parse("--b\r\nContent-Length: ${MjpegParser.MAX_FRAME_BYTES + 1}\r\n\r\n".toByteArray(), "b") }
        expectIo("headers") { parse("--b\r\nContent-Type: image/jpeg\r\n".toByteArray(), "b") }
        expectIo("too long") { parse(("--b\r\n" + "X: " + "y".repeat(5000) + "\r\n\r\n").toByteArray(), "b").size }
        val manyHeaders = "--b\r\n" + "X-A: ${"y".repeat(1000)}\r\n".repeat(20) + "\r\n"
        expectIo("too large") { parse(manyHeaders.toByteArray(), "b") }
        expectIo("not found") { parse(ByteArray(MjpegParser.MAX_PREAMBLE_BYTES + 100) { 'x'.code.toByte() }.let { b -> b.indices.forEach { if (it % 50 == 49) b[it] = '\n'.code.toByte() }; b }, "b") }
    }

    @Test fun eofBeforeFirstFrameThrowsDescriptiveError() {
        expectIo("before the first frame") { parse(ByteArray(0), "b") }
        expectIo("no data") { parse(ByteArray(0), null) }
        expectIo("ended inside a frame") { parse("--b\r\n\r\n".toByteArray(), "b") }
        expectIo("before the first frame") { parse("--b--\r\n".toByteArray(), "b") }
        expectIo("before the first frame") { parse("<html>no stream</html>".toByteArray(), null) }
    }

    @Test fun markerFallbackWithoutBoundary() {
        val a = jpeg(1, 2)
        val b = jpeg(3)
        val data = "--b\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + a + "\r\n--b\r\n\r\n".toByteArray() + b
        val frames = parse(data, null)
        assertEquals(2, frames.size)
        assertArrayEquals(a, frames[0])
        assertArrayEquals(b, frames[1])
    }

    @Test fun markerFallbackTruncatedOnlyFrameThrows() {
        expectIo("before the first frame") { parse(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1), null) }
    }

    @Test fun oversizedFrameThrows() {
        val data = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(MjpegParser.MAX_FRAME_BYTES + 10)
        expectIo("too large") { parse(data, null) }
        val noLength = "--b\r\n\r\n".toByteArray() + ByteArray(MjpegParser.MAX_FRAME_BYTES + 100)
        expectIo("too large") { parse(noLength, "b") }
    }
}
