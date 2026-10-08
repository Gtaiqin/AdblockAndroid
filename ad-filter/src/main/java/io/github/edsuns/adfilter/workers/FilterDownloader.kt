package io.github.edsuns.adfilter.workers

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream

/** Never buffers a response body in memory. The caller owns atomic file publication. */
internal class FilterDownloader(
    private val maxBytes: Long,
    private val checkActive: () -> Unit = {},
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    data class Response(val notModified: Boolean, val etag: String?)

    fun download(
        address: String,
        etag: String?,
        write: ((OutputStream) -> Unit) -> Unit
    ): Response {
        var url = URL(address)
        repeat(11) { redirect ->
            checkActive()
            if (url.protocol != "http" && url.protocol != "https") {
                throw IOException("Unsupported filter URL protocol")
            }
            val connection = connect(url)
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept-Encoding", "gzip")
                connection.setRequestProperty("User-Agent", "AdFilter/1.0")
                if (etag != null) connection.setRequestProperty("If-None-Match", etag)
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    if (redirect == 10) throw IOException("Too many filter redirects")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Missing redirect location")
                    url = URL(url, location)
                } else {
                    if (status == HttpURLConnection.HTTP_NOT_MODIFIED && etag != null) {
                        return Response(true, etag)
                    }
                    // Do not read error bodies, partial responses, or unexpected empty responses.
                    if (status != HttpURLConnection.HTTP_OK) throw IOException("Filter HTTP $status")
                    val charset = responseCharset(connection.contentType)
                    connection.inputStream.use { raw ->
                        val decoded = when (connection.contentEncoding?.lowercase()?.trim()) {
                            null, "", "identity" -> raw
                            "gzip" -> GZIPInputStream(raw)
                            else -> throw IOException("Unsupported filter content encoding")
                        }
                        decoded.use { input ->
                            write { output -> transcode(input, output, charset) }
                        }
                    }
                    checkActive()
                    return Response(false, connection.getHeaderField("ETag")
                        ?.takeIf { it.length <= 1024 && '\r' !in it && '\n' !in it })
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many filter redirects")
    }

    internal fun transcode(input: InputStream, output: OutputStream, charset: Charset) {
        val limitedInput = object : FilterInputStream(input) {
            var count = 0L
            override fun read(): Int {
                checkActive()
                val value = super.read()
                if (value >= 0 && ++count > maxBytes) throw IOException("Filter input exceeds limit")
                return value
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                checkActive()
                val n = `in`.read(b, off, minOf(len.toLong(), maxBytes - count + 1).toInt())
                if (n > 0) count += n
                if (count > maxBytes) throw IOException("Filter input exceeds limit")
                return n
            }
        }
        val limitedOutput = object : FilterOutputStream(output) {
            var count = 0L
            override fun write(value: Int) {
                checkActive()
                if (++count > maxBytes) throw IOException("Filter output exceeds limit")
                out.write(value)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                checkActive()
                if (len > maxBytes - count) throw IOException("Filter output exceeds limit")
                out.write(b, off, len)
                count += len
            }
        }
        // Stateful decoding also validates UTF-8 and handles characters split across read blocks.
        val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = InputStreamReader(limitedInput, decoder)
        val writer = OutputStreamWriter(limitedOutput, Charsets.UTF_8)
        val buffer = CharArray(8192)
        var total = 0L
        while (true) {
            checkActive()
            val n = reader.read(buffer)
            if (n < 0) break
            // Embedded NUL would truncate the C-string based native parser.
            if ((0 until n).any { buffer[it] == '\u0000' }) throw IOException("NUL in filter")
            writer.write(buffer, 0, n)
            total += n
        }
        if (total == 0L) throw IOException("Empty filter")
        writer.flush()
        checkActive()
    }

    private fun responseCharset(contentType: String?): Charset {
        val name = Regex("(?:^|;)\\s*charset\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
            .find(contentType.orEmpty())?.groupValues?.get(1)?.trim()?.trim('"', '\'')
            ?: return Charsets.UTF_8
        return try {
            Charset.forName(name)
        } catch (e: IllegalArgumentException) {
            throw IOException("Unsupported filter charset", e)
        }
    }
}
