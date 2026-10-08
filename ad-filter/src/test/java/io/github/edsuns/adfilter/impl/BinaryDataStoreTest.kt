package io.github.edsuns.adfilter.impl

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class BinaryDataStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `failed write preserves installed data and removes partial file`() {
        val store = BinaryDataStore(temporary.root)
        store.saveData("filter", "old".toByteArray())
        assertThrows(IOException::class.java) {
            store.saveData("filter") { it.write("partial".toByteArray()); throw IOException("network failed") }
        }
        assertEquals("old", String(store.loadData("filter")))
        assertEquals(listOf("filter"), temporary.root.list()!!.toList())
    }

    @Test fun `successful replacement publishes complete file`() {
        val store = BinaryDataStore(temporary.root)
        store.saveData("filter", "old".toByteArray())
        store.saveData("filter") { it.write("new".toByteArray()) }
        assertEquals("new", String(store.loadData("filter")))
    }

    @Test fun `oversized install file fails before loading`() {
        val store = BinaryDataStore(temporary.root)
        store.saveData("filter", ByteArray(20))
        assertThrows(IOException::class.java) { store.loadData("filter", 10) }
    }

    @Test fun `etag requires installed file matching URL and installed checksum`() {
        val store = BinaryDataStore(temporary.root)
        store.saveInstalledEtag("filter", "url", "checksum", "\"etag\"")
        assertNull(store.installedEtag("filter", "url", "checksum"))
        store.saveData("filter", byteArrayOf(1))
        assertEquals("\"etag\"", store.installedEtag("filter", "url", "checksum"))
        assertNull(store.installedEtag("filter", "different", "checksum"))
        assertNull(store.installedEtag("filter", "url", "old-checksum"))
        assertNull(store.installedEtag("filter", "url", ""))
        store.saveInstalledEtag("filter", "url", "checksum", null)
        assertNull(store.installedEtag("filter", "url", "checksum"))
    }
}
