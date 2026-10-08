package io.github.edsuns.adfilter.impl

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FilterWorkCoordinatorTest {
    @Test fun `downloads are limited to two and cancelled waiter never starts`() = runBlocking {
        val entered = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val jobs = List(2) {
            launch { FilterWorkCoordinator.download { entered.incrementAndGet(); release.await() } }
        }
        withTimeout(5000) { while (entered.get() < 2) yield() }
        val waiter = launch { FilterWorkCoordinator.download { fail("Cancelled download must not start") } }
        yield()
        waiter.cancelAndJoin()
        release.complete(Unit)
        jobs.joinAll()
        // Cancellation must not leak a permit.
        withTimeout(5000) { FilterWorkCoordinator.download { assertEquals(2, entered.get()) } }
    }

    @Test fun `installation and loading share one processing slot`() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        List(8) {
            launch {
                FilterWorkCoordinator.process {
                    val count = active.incrementAndGet()
                    peak.updateAndGet { previous -> maxOf(previous, count) }
                    try { Thread.sleep(10) } finally { active.decrementAndGet() }
                }
            }
        }.joinAll()
        assertEquals(1, peak.get())
    }

    @Test fun `cancelled processing waiter never allocates and failure releases slot`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = launch(Dispatchers.Default) {
            FilterWorkCoordinator.process {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            val waiter = launch { FilterWorkCoordinator.process { fail("Cancelled install must not start") } }
            yield()
            waiter.cancelAndJoin()
        } finally {
            release.countDown()
        }
        first.join()
        try { FilterWorkCoordinator.process { throw IllegalStateException("parse failed") } } catch (_: IllegalStateException) { }
        withTimeout(5000) { FilterWorkCoordinator.process { } }
    }
}
