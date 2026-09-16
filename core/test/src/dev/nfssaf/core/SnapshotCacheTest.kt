package dev.nfssaf.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SnapshotCacheTest {
    private fun CountDownLatch.awaitReady() =
        assertTrue("Worker timed out", await(5, TimeUnit.SECONDS))

    @Test
    fun coldAndExpiredQueriesReturnCompleteSnapshotsAndWarmQueriesReuseThem() {
        var now = 0L
        var loads = 0
        val cache = SnapshotCache<String, List<String>>(2, 2000) { now }
        fun query() =
            cache.get("folder") {
                loads++
                listOf("existing")
            }
        assertEquals(listOf("existing"), query())
        now = 1999
        assertEquals(listOf("existing"), query())
        assertEquals(1, loads)
        now = 2000
        assertEquals(listOf("existing"), query())
        assertEquals(2, loads)
    }

    @Test
    fun concurrentCallersShareLoadAndAnotherFolderDoesNotWaitForIt() {
        val cache = SnapshotCache<String, Int>(4, 2000)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loads = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val first =
                pool.submit<Int> {
                    cache.get("a") {
                        loads.incrementAndGet()
                        started.countDown()
                        release.awaitReady()
                        42
                    }
                }
            started.awaitReady()
            val callers =
                (1..6).map {
                    pool.submit<Int> {
                        cache.get("a") {
                            loads.incrementAndGet()
                            -1
                        }
                    }
                }
            assertEquals(
                7,
                pool.submit<Int> { cache.get("b") { 7 } }.get(5, TimeUnit.SECONDS).toInt(),
            )
            release.countDown()
            assertEquals(42, first.get(5, TimeUnit.SECONDS).toInt())
            callers.forEach { assertEquals(42, it.get(5, TimeUnit.SECONDS).toInt()) }
            assertEquals(1, loads.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun invalidatedLoadCannotOverwriteNewerSnapshot() {
        val cache = SnapshotCache<String, String>(2, 2000)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val old =
                pool.submit<String> {
                    cache.get("a") {
                        started.countDown()
                        release.awaitReady()
                        "old"
                    }
                }
            started.awaitReady()
            cache.clear()
            assertEquals("new", cache.get("a") { "new" })
            release.countDown()
            assertEquals("old", old.get(5, TimeUnit.SECONDS))
            assertEquals("new", cache.get("a") { error("Stale load evicted new snapshot") })
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun failuresPropagateWithOriginalErrnoAndAreNotCachedAsEmptyFolders() {
        val cache = SnapshotCache<String, List<String>>(2, 2000)
        val failure = NfsException(110, "timeout")
        try {
            cache.get("a") { throw failure }
            fail("Expected timeout")
        } catch (e: NfsException) {
            assertSame(failure, e)
        }
        assertEquals(listOf("recovered"), cache.get("a") { listOf("recovered") })
    }

    @Test
    fun evictionIsBoundedAndRetainsMostRecentlyUsedSnapshot() {
        val cache = SnapshotCache<String, Int>(2, 2000)
        assertEquals(1, cache.get("a") { 1 })
        assertEquals(2, cache.get("b") { 2 })
        assertEquals(1, cache.get("a") { error("Warm cache missed") })
        cache.get("c") { 3 }
        assertEquals(1, cache.get("a") { error("Recently used entry evicted") })
        assertEquals(20, cache.get("b") { 20 })
    }

    @Test
    fun evictedPendingLoadDoesNotRepopulateCache() {
        val cache = SnapshotCache<String, Int>(1, 2000)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val old =
                pool.submit<Int> {
                    cache.get("a") {
                        started.countDown()
                        release.awaitReady()
                        1
                    }
                }
            started.awaitReady()
            cache.get("b") { 2 }
            release.countDown()
            old.get(5, TimeUnit.SECONDS)
            assertEquals(2, cache.get("b") { error("Evicted completion replaced cache") })
            assertEquals(3, cache.get("a") { 3 })
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun interruptedWaiterDoesNotCancelSharedLoad() {
        val cache = SnapshotCache<String, Int>(2, 2000)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val owner =
                pool.submit<Int> {
                    cache.get("a") {
                        started.countDown()
                        release.awaitReady()
                        42
                    }
                }
            started.awaitReady()
            pool
                .submit {
                    Thread.currentThread().interrupt()
                    try {
                        cache.get("a") { error("Duplicate load") }
                        fail("Expected interruption")
                    } catch (_: InterruptedException) {
                        /* wait was interrupted, load remains live */
                    } finally {
                        Thread.interrupted()
                    }
                }
                .get(5, TimeUnit.SECONDS)
            release.countDown()
            assertEquals(42, owner.get(5, TimeUnit.SECONDS).toInt())
            assertEquals(42, cache.get("a") { error("Shared load was discarded") })
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
