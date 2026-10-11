package app.imagesaver

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FrameStreamTest {
    private val ct = "multipart/x-mixed-replace;boundary=boundarydonotcross"

    private fun part(data: ByteArray) =
        "--boundarydonotcross\r\nContent-Type: image/jpeg\r\nContent-Length: ${data.size}\r\n\r\n".toByteArray() + data + "\r\n".toByteArray()

    private val good = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 5, 0xFF.toByte(), 0xD9.toByte())
    private val bad = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 6, 0xFF.toByte(), 0xD9.toByte())

    @Test fun reportsDecodedFramesAndPreservesBytes() {
        val frames = mutableListOf<ByteArray>()
        val firsts = mutableListOf<Boolean>()
        val stats = FrameStream.consume(
            ByteArrayInputStream(part(good) + part(good)), ct,
            decode = { it.size },
        ) { jpeg, _, first -> frames.add(jpeg); firsts.add(first) }
        assertEquals(2, stats.received)
        assertEquals(2, stats.decoded)
        assertEquals(listOf(true, false), firsts)
        assertArrayEquals(good, frames[0])
    }

    @Test fun undecodableFramesAreReportedAndStreamContinues() {
        val failures = mutableListOf<String>()
        val shown = mutableListOf<ByteArray>()
        val stats = FrameStream.consume(
            ByteArrayInputStream(part(bad) + part(good)), ct,
            decode = { if (it[2] == 6.toByte()) null else it.size },
            onDecodeFailure = { failures.add(it.failureDiagnostic()) },
        ) { jpeg, _, _ -> shown.add(jpeg) }
        assertEquals(1, failures.size)
        assertTrue(failures[0].contains("could not be decoded"))
        assertEquals(1, shown.size)
        assertEquals(1, stats.decodeFailures)
    }

    @Test fun framesReceivedButNeverDecodedIsAnError() {
        try {
            FrameStream.consume(ByteArrayInputStream(part(bad) + part(bad)), ct, decode = { _ -> null as Int? }) { _, _, _ -> }
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("none could be decoded"))
        }
    }

    @Test fun cleanEofBeforeAnyFrameIsAnError() {
        try {
            FrameStream.consume(ByteArrayInputStream(ByteArray(0)), ct, decode = { it.size }) { _, _, _ -> }
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("before the first frame"))
        }
    }

    private fun serve(response: ByteArray, block: (HttpURLConnection) -> Unit) {
        ServerSocket(0).use { server ->
            val t = thread {
                server.accept().use { s ->
                    val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    while (reader.readLine()?.isNotEmpty() == true) { /* skip request headers */ }
                    s.getOutputStream().write(response)
                    s.getOutputStream().flush()
                }
            }
            val conn = URL("http://127.0.0.1:${server.localPort}/stream").openConnection() as HttpURLConnection
            conn.readTimeout = 5000
            try { block(conn) } finally { conn.disconnect(); t.join(2000) }
        }
    }

    @Test fun parsesPiStyleHttpResponseOverSocket() {
        val head = "HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace;boundary=\"boundarydonotcross\"\r\n\r\n"
        serve(head.toByteArray() + part(good) + part(good)) { conn ->
            val stats = conn.inputStream.use { FrameStream.consume(it, conn.contentType, decode = { b -> b.size }) { _, _, _ -> } }
            assertEquals(2, stats.decoded)
        }
    }

    @Test fun httpOkButEmptyBodyGivesActionableError() {
        serve("HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace;boundary=b\r\n\r\n".toByteArray()) { conn ->
            try {
                conn.inputStream.use { FrameStream.consume(it, conn.contentType, decode = { b -> b.size }) { _, _, _ -> } }
                fail("expected IOException")
            } catch (e: IOException) {
                assertTrue(e.message, e.message!!.contains("closed the connection"))
            }
        }
    }
}
