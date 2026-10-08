package io.github.edsuns.adfilter.util

import android.util.Base64

/**
 * Created by Edsuns@qq.com on 2021/1/7.
 *
 * Reference: [validateChecksum.py](https://hg.adblockplus.org/adblockplus/file/tip/validateChecksum.py)
 */
class Checksum(val filter: String) {
    // here don't use '$' as the end of a line
    private val checksumRegexp = Regex(
        "^\\s*!\\s*checksum[\\s\\-:]+([\\w+/=]+).*\\r?\\n",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )

    val checksumIn: String? by lazy { extractChecksum(filter) }
    val checksumCalc: String by lazy { calculateChecksum(filter) }

    fun validate() = validate(checksumIn, checksumCalc)

    fun validate(checksumIn: String?) = validate(checksumIn, checksumCalc)

    private fun validate(checksumIn: String?, checksumCalc: String): Boolean =
        checksumIn == null || checksumIn == checksumCalc

    private fun extractChecksum(data: String): String? =
        checksumRegexp.find(data)?.groupValues?.get(1)

    private fun calculateChecksum(data: String): String =
        Base64.encodeToString(
            ChecksumNormalizer.digest(data, checksumRegexp),
            Base64.NO_PADDING or Base64.NO_WRAP
        )

}
