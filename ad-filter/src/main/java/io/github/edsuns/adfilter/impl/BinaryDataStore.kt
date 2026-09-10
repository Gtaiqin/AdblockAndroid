package io.github.edsuns.adfilter.impl

import timber.log.Timber
import java.io.File
import java.io.FileInputStream

/**
 * Created by Edsuns@qq.com on 2020/10/24.
 *
 * Every method below performs blocking file IO. Never call them on the main thread: go through
 * [FilterDataLoader]'s `*Async` variants, which move the work to a background dispatcher.
 */
internal class BinaryDataStore(private val dir: File) {

    init {
        if (!dir.exists() && !dir.mkdirs()) {
            Timber.v("BinaryDataStore: failed to create store dirs")
        }
    }

    fun hasData(name: String): Boolean = File(dir, name).exists()

    fun loadData(name: String): ByteArray {
        val file = File(dir, name)
        val expectedSize = file.length()
        if (expectedSize <= 0L || expectedSize > Int.MAX_VALUE.toLong()) {
            return file.readBytes()
        }
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
        }
        return if (offset == buffer.size) buffer else buffer.copyOf(offset)
    }

    fun saveData(name: String, byteArray: ByteArray) {
        File(dir, name).writeBytes(byteArray)
    }

    fun clearData(name: String) {
        File(dir, name).delete()
    }
}
