package io.github.edsuns.adfilter.impl

import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Properties

/**
 * Created by Edsuns@qq.com on 2020/10/24.
 *
 * Blocking file IO. Call only from the loader's async methods or workers' IO dispatcher.
 */
internal class BinaryDataStore(private val dir: File) {

    init {
        if (!dir.exists() && !dir.mkdirs()) {
            Timber.v("BinaryDataStore: failed to create store dirs")
        }
    }

    fun hasData(name: String): Boolean = File(dir, name).length() > 0

    fun loadData(name: String, maxBytes: Long = Int.MAX_VALUE.toLong()): ByteArray {
        val file = File(dir, name)
        val expectedSize = file.length()
        if (expectedSize > maxBytes || expectedSize > Int.MAX_VALUE) throw IOException("Filter file exceeds limit")
        // Pre-size the buffer to the file length: filter data can be several MB and
        // readBytes()/ByteArrayOutputStream would otherwise grow and copy it repeatedly.
        val buffer = ByteArray(expectedSize.toInt())
        var offset = 0
        FileInputStream(file).use { input ->
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read < 0) {
                    break
                }
                offset += read
            }
            if (input.read() != -1) throw IOException("Filter file changed while reading")
        }
        if (offset != buffer.size) throw IOException("Truncated filter file")
        return buffer
    }

    fun saveData(name: String, byteArray: ByteArray) {
        saveData(name) { it.write(byteArray) }
    }

    /** Same-directory rename publishes only a fully written file, preserving the previous version. */
    fun saveData(name: String, write: (OutputStream) -> Unit) {
        val temporary = File.createTempFile(".filter-", ".part", dir)
        try {
            temporary.outputStream().use { output ->
                write(output)
                output.fd.sync()
            }
            if (!temporary.renameTo(File(dir, name))) throw IOException("Cannot publish filter file")
        } finally {
            temporary.delete()
        }
    }

    fun installedEtag(id: String, url: String, checksum: String): String? {
        if (checksum.isBlank() || !hasData(id)) return null
        val file = File(dir, "$id.http")
        if (!file.exists() || file.length() > 8192) return null
        return try {
            val properties = Properties().apply { file.inputStream().use { load(it) } }
            if (properties.getProperty("url") != url || properties.getProperty("checksum") != checksum) null
            else properties.getProperty("etag")?.takeIf { it.length <= 1024 && '\r' !in it && '\n' !in it }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun saveInstalledEtag(id: String, url: String, checksum: String, etag: String?) {
        if (etag == null) {
            clearData("$id.http")
            return
        }
        val properties = Properties().apply {
            setProperty("url", url)
            setProperty("checksum", checksum)
            setProperty("etag", etag)
        }
        saveData("$id.http") { properties.store(it, null) }
    }

    fun clearData(name: String) {
        File(dir, name).delete()
    }
}
