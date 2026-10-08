package io.github.edsuns.adfilter.impl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Shared by workers and the loader; acquire before allocating any full-file buffers. */
internal object FilterWorkCoordinator {
    const val MAX_SOURCE_BYTES = 32L * 1024 * 1024
    private val downloads = Semaphore(2)
    private val processing = Mutex()

    suspend fun <T> download(block: suspend () -> T): T = downloads.withPermit { block() }

    suspend fun <T> process(block: () -> T): T = processing.withLock {
        withContext(Dispatchers.IO) { block() }
    }
}
