package io.github.edsuns.adfilter.workers

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream

/**
 * Download failure carrying whether another attempt can succeed.
 *
 * On weak or unstable networks the usual failures are timeouts, connection resets, DNS errors,
 * HTTP 5xx/429 and truncated bodies; all of them are worth retrying. An unusable source (HTTP
 * 4xx, oversized or malformed payload) is not, so retries do not waste the user's time and battery.
 */
internal class FilterDownloadException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null
) : IOException(message, cause)

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
        val start = try {
            URL(address)
        } catch (e: MalformedURLException) {
            throw FilterDownloadException("Invalid filter URL", retryable = false, e)
        }
        try {
            return download(start, etag, write)
        } catch (e: FilterDownloadException) {
            throw e
        } catch (e: IOException) {
            // Timeouts, resets, DNS failures and truncated bodies are transient on weak networks.
            throw FilterDownloadException(e.message ?: "Filter download failed", retryable = true, e)
        }
    }

    private fun download(
        start: URL,
        etag: String?,
        write: ((OutputStream) -> Unit) -> Unit
    ): Response {
        var url = start
        repeat(MAX_REDIRECTS + 1) { redirect ->
            checkActive()
            if (url.protocol != "http" && url.protocol != "https") {
                throw FilterDownloadException("Unsupported filter URL protocol", retryable = false)
            }
            val connection = connect(url)
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept-Encoding", "gzip")
                connection.setRequestProperty("User-Agent", "AdFilter/1.0")
                if (etag != null) connection.setRequestProperty("If-None-Match", etag)
                val status = connection.responseCode
                if (status in REDIRECT_STATUSES) {
                    if (redirect == MAX_REDIRECTS) {
                        throw FilterDownloadException("Too many filter redirects", retryable = false)
                    }
                    val location = connection.getHeaderField("Location")
                        ?: throw FilterDownloadException("Missing redirect location", retryable = false)
                    url = try {
                        URL(url, location)
                    } catch (e: MalformedURLException) {
                        throw FilterDownloadException("Invalid redirect location", retryable = false, e)
                    }
                } else {
                    if (status == HttpURLConnection.HTTP_NOT_MODIFIED && etag != null) {
                        return Response(true, etag)
                    }
                    // Do not read error bodies, partial responses, or unexpected empty responses.
                    if (status != HttpURLConnection.HTTP_OK) {
                        throw FilterDownloadException(
                            "Filter HTTP $status", retryable = isRetryableStatus(status)
                        )
                    }
                    val charset = responseCharset(connection.contentType)
                    connection.inputStream.use { raw ->
                        val decoded = when (connection.contentEncoding?.lowercase()?.trim()) {
                            null, "", "identity" -> raw
                            "gzip" -> GZIPInputStream(raw)
                            else -> throw FilterDownloadException(
                                "Unsupported filter content encoding", retryable = false
                            )
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
        throw FilterDownloadException("Too many filter redirects", retryable = false)
    }

    /** Server-side throttling and outages recover on their own; client errors do not. */
    private fun isRetryableStatus(status: Int): Boolean =
        status == 408 || status == 425 || status == 429 || status >= 500

    internal fun transcode(input: InputStream, output: OutputStream, charset: Charset) {
        val limitedInput = object : FilterInputStream(input) {
            var count = 0L
            override fun read(): Int {
                checkActive()
                val value = super.read()
                if (value >= 0 && ++count > maxBytes) {
                    throw FilterDownloadException("Filter input exceeds limit", retryable = false)
                }
                return value
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                checkActive()
                val n = `in`.read(b, off, minOf(len.toLong(), maxBytes - count + 1).toInt())
                if (n > 0) count += n
                if (count > maxBytes) {
                    throw FilterDownloadException("Filter input exceeds limit", retryable = false)
                }
                return n
            }
        }
        val limitedOutput = object : FilterOutputStream(output) {
            var count = 0L
            override fun write(value: Int) {
                checkActive()
                if (++count > maxBytes) {
                    throw FilterDownloadException("Filter output exceeds limit", retryable = false)
                }
                out.write(value)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                checkActive()
                if (len > maxBytes - count) {
                    throw FilterDownloadException("Filter output exceeds limit", retryable = false)
                }
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
        try {
            while (true) {
                checkActive()
                val n = reader.read(buffer)
                if (n < 0) break
                // Embedded NUL would truncate the C-string based native parser.
                if ((0 until n).any { buffer[it] == '\u0000' }) {
                    throw FilterDownloadException("NUL in filter", retryable = false)
                }
                writer.write(buffer, 0, n)
                total += n
            }
        } catch (e: CharacterCodingException) {
            // A different attempt would return the same bytes, so this is not worth retrying.
            throw FilterDownloadException("Malformed filter text", retryable = false, e)
        }
        if (total == 0L) throw FilterDownloadException("Empty filter", retryable = false)
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
            throw FilterDownloadException("Unsupported filter charset", retryable = false, e)
        }
    }

    private companion object {
        /**
         * Weak mobile links need considerably more patience than a Wi-Fi download: a 2G/3G
         * connection regularly stalls for several seconds before a byte arrives. This is twice the
         * previous budget while still bounding how long a cancelled download holds a shared permit.
         */
        const val CONNECT_TIMEOUT_MILLIS = 20_000
        const val READ_TIMEOUT_MILLIS = 20_000
        const val MAX_REDIRECTS = 10
        val REDIRECT_STATUSES = listOf(301, 302, 303, 307, 308)
    }
}
