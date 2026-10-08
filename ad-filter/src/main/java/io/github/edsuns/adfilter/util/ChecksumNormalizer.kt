package io.github.edsuns.adfilter.util

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Preserves checksum normalization without repeated full-text replacements or a UTF-8 byte array. */
internal object ChecksumNormalizer {
    fun digest(data: String, checksumPattern: Regex): ByteArray {
        val normalized: CharSequence = if ('\r' !in data && "\n\n" !in data) data else {
            StringBuilder(data.length).apply {
                var newline = false
                for (character in data) {
                    if (character == '\r') continue
                    if (character != '\n' || !newline) append(character)
                    newline = character == '\n'
                }
            }
        }
        val excluded = checksumPattern.find(normalized)?.range
        val digest = MessageDigest.getInstance("MD5")
        val sink = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }
        OutputStreamWriter(DigestOutputStream(sink, digest), Charsets.UTF_8).use { writer ->
            val buffer = CharArray(8192)
            fun writeRange(start: Int, end: Int) {
                var offset = start
                while (offset < end) {
                    val count = minOf(buffer.size, end - offset)
                    for (i in 0 until count) buffer[i] = normalized[offset + i]
                    writer.write(buffer, 0, count)
                    offset += count
                }
            }
            if (excluded == null) writeRange(0, normalized.length) else {
                writeRange(0, excluded.first)
                writeRange(excluded.last + 1, normalized.length)
            }
        }
        return digest.digest()
    }
}
