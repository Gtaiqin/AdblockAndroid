package io.github.edsuns.adfilter.impl

import io.github.edsuns.adblockclient.AdBlockClient
import io.github.edsuns.adfilter.CustomFilter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Created by Edsuns@qq.com on 2020/10/24.
 *
 * Owns every access to [BinaryDataStore] and to native [AdBlockClient] parsing.
 *
 * The `*Async` functions below move the blocking work to [Dispatchers.IO] and serialize it, so they
 * are always safe to call from the main thread. Their non-suspend counterparts do blocking disk IO
 * plus native parsing and MUST only be called from a background thread.
 */
internal class FilterDataLoader(
    val detector: Detector,
    private val binaryDataStore: BinaryDataStore
) {

    /**
     * Serializes disk/native work. Parsing several multi-MB filters in parallel causes large
     * memory spikes, so the requests are queued one after another.
     */
    private val loadLock = Mutex()

    /**
     * Scope for fire-and-forget loading. [FilterDataLoader] is owned by the process-wide
     * [AdFilterImpl] singleton, so this scope intentionally lives as long as the process.
     */
    internal val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Timber.e(e, "Filter data loading failed") }
    )

    fun load(id: String) {
        if (binaryDataStore.hasData(id)) {
            val client = AdBlockClient(id)
            client.loadProcessedData(binaryDataStore.loadData(id))
            if (id == ID_CUSTOM) {
                detector.customFilterClient = client
            } else {
                detector.addClient(client)
            }
        } else {
            Timber.v("Couldn't find client processed data: $id")
        }
    }

    suspend fun loadAsync(id: String) = serialized { load(id) }

    fun unload(id: String) {
        detector.removeClient(id)
    }

    suspend fun unloadAsync(id: String) = serialized { unload(id) }

    fun unloadAll() {
        detector.clearAllClient()
    }

    fun remove(id: String) {
        binaryDataStore.clearData(id)
        binaryDataStore.clearData("_$id")
        unload(id)
    }

    suspend fun removeAsync(id: String) = serialized { remove(id) }

    fun isCustomFilterEnabled() = detector.customFilterClient != null

    fun getCustomFilter(): CustomFilter {
        if (binaryDataStore.hasData(RAW_CUSTOM)) {
            return CustomFilterImpl(this, String(binaryDataStore.loadData(RAW_CUSTOM)))
        }
        return CustomFilterImpl(this)
    }

    suspend fun getCustomFilterAsync(): CustomFilter = serialized { getCustomFilter() }

    fun loadCustomFilter(rawData: ByteArray) {
        binaryDataStore.saveData(RAW_CUSTOM, rawData)
        val client = AdBlockClient(ID_CUSTOM)
        client.loadBasicData(rawData, true)
        binaryDataStore.saveData(ID_CUSTOM, client.getProcessedData())
        load(ID_CUSTOM)
    }

    suspend fun loadCustomFilterAsync(rawData: ByteArray) =
        serialized { loadCustomFilter(rawData) }

    fun unloadCustomFilter() {
        detector.customFilterClient = null
    }

    /**
     * Runs [block] with blocking IO/native work moved to [Dispatchers.IO] and serialized against
     * every other loader operation.
     */
    private suspend fun <T> serialized(block: () -> T): T = loadLock.withLock {
        withContext(Dispatchers.IO) { block() }
    }

    companion object {
        const val RAW_CUSTOM = "_custom"
        const val ID_CUSTOM = "custom"
    }
}
