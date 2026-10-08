package io.github.edsuns.adfilter.workers

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.zip.GZIPOutputStream

class FilterDownloaderTest {
    private class Connection(
        private val code: Int = 200,
        private val bytes: ByteArray = "! Title: Test\n||ads.test^\n".toByteArray(),
        private val headers: Map<String, String> = emptyMap()
    ) : HttpURLConnection(URL("https://example.test/filter")) {
        var disconnected = false
        var bodyOpened = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String) = headers[name]
        override fun getContentType() = headers["Content-Type"]
        override fun getContentEncoding() = headers["Content-Encoding"]
        override fun getInputStream(): ByteArrayInputStream {
            bodyOpened = true
            return ByteArrayInputStream(bytes)
        }
    }

    @Test fun `gzip without charset streams UTF8 and preserves newlines`() {
        val text = "! Title: 中文\r\n" + "||ads.test^\n".repeat(9000)
        val gzip = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        }.toByteArray()
        val connection = Connection(bytes = gzip, headers = mapOf("Content-Encoding" to "gzip", "ETag" to "W/\"v1\""))
        val output = ByteArrayOutputStream()
        val response = FilterDownloader(1_000_000, connect = { connection })
            .download("https://example.test/filter", null) { it(output) }
        assertEquals(text, output.toString("UTF-8"))
        assertEquals("W/\"v1\"", response.etag)
        assertTrue(connection.disconnected)
    }

    @Test fun `declared legacy charset is transcoded`() {
        val text = "! Title: 中文\r\n||ads.test^"
        val connection = Connection(bytes = text.toByteArray(charset("GBK")), headers = mapOf("Content-Type" to "text/plain; charset=\"GBK\""))
        val output = ByteArrayOutputStream()
        FilterDownloader(1000, connect = { connection }).download("https://example.test/filter", null) { it(output) }
        assertEquals(text, output.toString("UTF-8"))
    }

    @Test fun `304 does not open body or output`() {
        val connection = Connection(code = 304)
        val response = FilterDownloader(100, connect = { connection })
            .download("https://example.test/filter", "\"v1\"") { fail("must not write") }
        assertTrue(response.notModified)
        assertEquals("\"v1\"", connection.getRequestProperty("If-None-Match"))
        assertFalse(connection.bodyOpened)
        assertTrue(connection.disconnected)
    }

    @Test fun `HTTP errors do not read even a large error body`() {
        val connection = Connection(code = 500)
        assertThrows(IOException::class.java) {
            FilterDownloader(100, connect = { connection }).download("https://example.test/filter", null) { fail("must not write") }
        }
        assertFalse(connection.bodyOpened)
        assertTrue(connection.disconnected)
    }

    @Test fun `gzip expansion is bounded independent of content length`() {
        val gzip = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(ByteArray(20_000) { 65 }) } }.toByteArray()
        val connection = Connection(bytes = gzip, headers = mapOf("Content-Encoding" to "gzip", "Content-Length" to gzip.size.toString()))
        assertThrows(IOException::class.java) {
            FilterDownloader(1000, connect = { connection }).download("https://example.test/filter", null) { it(ByteArrayOutputStream()) }
        }
        assertTrue(connection.disconnected)
    }

    @Test fun `transcoded output has its own byte limit`() {
        // 80 input bytes become 160 UTF-8 bytes.
        assertThrows(IOException::class.java) {
            FilterDownloader(100).transcode(ByteArrayInputStream(ByteArray(80) { 0xe9.toByte() }), ByteArrayOutputStream(), Charsets.ISO_8859_1)
        }
    }

    @Test fun `exact byte limit succeeds`() {
        val output = ByteArrayOutputStream()
        FilterDownloader(100).transcode(ByteArrayInputStream(ByteArray(100) { 65 }), output, Charsets.UTF_8)
        assertEquals(100, output.size())
    }

    @Test fun `invalid UTF8 empty and NUL responses fail`() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte()), byteArrayOf(), byteArrayOf(65, 0, 66))) {
            assertThrows(IOException::class.java) {
                FilterDownloader(100).transcode(ByteArrayInputStream(bytes), ByteArrayOutputStream(), Charsets.UTF_8)
            }
        }
    }

    @Test fun `cancellation while copying closes connection`() {
        val connection = Connection()
        var checks = 0
        assertThrows(CancellationException::class.java) {
            FilterDownloader(1000, { if (++checks > 2) throw CancellationException() }, { connection })
                .download("https://example.test/filter", null) { it(ByteArrayOutputStream()) }
        }
        assertTrue(connection.disconnected)
    }

    @Test fun `redirect preserves conditional request and closes both connections`() {
        val first = Connection(code = 302, headers = mapOf("Location" to "/new"))
        val second = Connection(code = 304)
        var calls = 0
        FilterDownloader(100, connect = { url ->
            if (calls++ == 0) first else { assertEquals("/new", url.path); second }
        }).download("https://example.test/old", "\"v1\"") { fail("must not write") }
        assertEquals("\"v1\"", second.getRequestProperty("If-None-Match"))
        assertTrue(first.disconnected && second.disconnected)
    }
}
