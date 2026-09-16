package dev.nfssaf.core

private const val DEFAULT_WINDOW_BYTES = 256 * 1024
private const val MIN_WINDOW_BYTES = 4096
private const val MAX_WINDOW_BYTES = 1024 * 1024
private const val NANOS_PER_MILLI = 1_000_000

/**
 * Bounded sequential prefetch for read-only handles; no disk or whole-file cache. The first/random
 * read remains exact. Local writes bump revision; external modifications may be cached for at most
 * maxAgeMillis, as documented.
 */
class ReadAhead(
    private val source: FileIo,
    private val revision: () -> Long,
    private val clockMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val windowBytes: Int = DEFAULT_WINDOW_BYTES,
    private val maxAgeMillis: Long = 250,
) : FileIo {
    private sealed interface Cache {
        data object Empty : Cache

        data class Window(val offset: Long, val count: Int, val revision: Long, val until: Long) :
            Cache
    }

    private val bytes = ByteArray(windowBytes)
    private var cache: Cache = Cache.Empty
    private var nextOffset = -1L

    init {
        require(windowBytes in MIN_WINDOW_BYTES..MAX_WINDOW_BYTES && maxAgeMillis > 0)
    }

    override fun read(offset: Long, size: Int, data: ByteArray): Int {
        require(offset >= 0 && size in 0..data.size && offset <= Long.MAX_VALUE - size)
        return if (size == 0) 0 else readNonEmpty(offset, size, data)
    }

    private fun readNonEmpty(offset: Long, size: Int, data: ByteArray): Int {
        val rev = revision()
        val now = clockMillis()
        val current = cache
        if (current is Cache.Window && current.covers(offset, size, rev, now)) {
            bytes.copyInto(
                data,
                0,
                (offset - current.offset).toInt(),
                (offset - current.offset).toInt() + size,
            )
            nextOffset = offset + size
            return size
        }
        cache = Cache.Empty
        try {
            val count =
                if (
                    offset == nextOffset &&
                        size < windowBytes &&
                        offset <= Long.MAX_VALUE - windowBytes
                ) {
                    val read = source.read(offset, windowBytes, bytes)
                    if (read !in 0..windowBytes)
                        throw NfsException(Errno.EIO, "Invalid prefetch length")
                    cache = Cache.Window(offset, read, rev, now + maxAgeMillis)
                    minOf(size, read).also { bytes.copyInto(data, 0, 0, it) }
                } else source.read(offset, size, data)
            nextOffset = offset + count
            return count
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            nextOffset = -1
            cache = Cache.Empty
            throw t
        }
    }

    private fun Cache.Window.covers(
        position: Long,
        size: Int,
        currentRevision: Long,
        now: Long,
    ): Boolean {
        if (revision != currentRevision || now >= until) return false
        return position >= offset && position - offset <= count - size
    }

    override fun write(offset: Long, size: Int, data: ByteArray): WriteReceipt =
        throw NfsException(Errno.EBADF, "Read-ahead handles are read-only")

    override fun sync() = Unit

    override fun close() {
        cache = Cache.Empty
        source.close()
    }
}
