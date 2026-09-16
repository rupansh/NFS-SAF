package dev.nfssaf.core

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * Complete snapshots only. The first caller loads on its own worker thread; concurrent callers
 * share that load without holding the cache monitor during IO. Eviction/invalidation detaches a
 * load so its completion cannot repopulate the cache.
 */
class SnapshotCache<K, V>(
    private val capacity: Int,
    private val ttlMillis: Long,
    private val clock: () -> Long = {
        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
    },
) {
    private sealed interface State<V> {
        data class Ready<V>(val value: V, val time: Long) : State<V>

        class Loading<V>(val task: FutureTask<Ready<V>>) : State<V>
    }

    init {
        require(capacity > 0 && ttlMillis > 0)
    }

    private val entries =
        object : LinkedHashMap<K, State<V>>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, State<V>>) =
                size > capacity
        }

    fun get(key: K, load: () -> V): V {
        val pending =
            synchronized(entries) {
                when (val old = entries[key]) {
                    is State.Ready -> if (clock() - old.time < ttlMillis) return old.value
                    is State.Loading -> return@synchronized old
                    null -> Unit
                }
                State.Loading(FutureTask { State.Ready(load(), clock()) }).also {
                    entries[key] = it
                }
            }
        // FutureTask runs at most once, even when several callers arrive together.
        pending.task.run()
        val result =
            try {
                pending.task.get()
            } catch (e: ExecutionException) {
                synchronized(entries) { if (entries[key] === pending) entries.remove(key) }
                throw e.cause ?: e
            }
        synchronized(entries) { if (entries[key] === pending) entries[key] = result }
        return result.value
    }

    fun clear() = synchronized(entries) { entries.clear() }
}
