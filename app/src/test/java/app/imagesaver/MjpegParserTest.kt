package app.imagesaver

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MjpegParserTest {
    private fun jpeg(vararg body: Int) =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + body.map { it.toByte() }.toByteArray() + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    @Test fun parsesMultipleFramesIgnoringHeaders() {
        val a = jpeg(1, 2)
        val b = jpeg(3)
        val data = "--b\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + a + "\r\n--b\r\n\r\n".toByteArray() + b
        val frames = mutableListOf<ByteArray>()
        MjpegParser.readFrames(ByteArrayInputStream(data)) { frames.add(it) }
        assertEquals(2, frames.size)
        assertArrayEquals(a, frames[0])
        assertArrayEquals(b, frames[1])
    }

    @Test fun truncatedFrameIsDropped() {
        val frames = mutableListOf<ByteArray>()
        MjpegParser.readFrames(ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1))) { frames.add(it) }
        assertEquals(0, frames.size)
    }

    @Test(expected = IOException::class) fun oversizedFrameThrows() {
        val data = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(MjpegParser.MAX_FRAME_BYTES + 10)
        MjpegParser.readFrames(ByteArrayInputStream(data)) { }
    }
}
