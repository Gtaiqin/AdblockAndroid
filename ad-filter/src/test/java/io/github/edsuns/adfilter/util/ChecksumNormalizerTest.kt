package io.github.edsuns.adfilter.util

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

class ChecksumNormalizerTest {
    private val pattern = Regex("^\\s*!\\s*checksum[\\s\\-:]+([\\w+/=]+).*\\r?\\n", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    private fun reference(data: String): ByteArray {
        val normalized = pattern.replaceFirst(Regex("\n+").replace(data.replace("\r", ""), "\n"), "")
        return MessageDigest.getInstance("MD5").digest(normalized.toByteArray())
    }

    @Test fun `checksum is identical for CRLF blank lines and unusual headers`() {
        val samples = listOf(
            "", "||ads.test^", "\r\n\r\n", "! Checksum: abc\r\n\r\n||ads.test^\r\n",
            "\n \t\n! \nchecksum:\nabc\r\n||ads.test^\n", "! Checksum: abc",
            "! Checksum: abc\n! checksum: def\nrule\n", "! check\rsum: abc\n\n中文规则\n",
            "! Checksum: abc\u2028suffix\nrule\n"
        )
        for (data in samples) assertArrayEquals(reference(data), ChecksumNormalizer.digest(data, pattern))
    }

    @Test fun `UTF8 encoder preserves surrogate pairs across chunks`() {
        val data = "a".repeat(8191) + "😀中文\r\n" + "b".repeat(9000)
        assertArrayEquals(reference(data), ChecksumNormalizer.digest(data, pattern))
    }

    @Test fun `mixed line endings match legacy normalization`() {
        val random = Random(9)
        repeat(200) {
            val data = buildString {
                repeat(100) {
                    append(listOf("\n", "\r", "\r\n", "中文", " ", "! Checksum: abc\r\n", "||ads.test^", "😀")[random.nextInt(8)])
                }
            }
            assertArrayEquals(reference(data), ChecksumNormalizer.digest(data, pattern))
        }
    }
}
