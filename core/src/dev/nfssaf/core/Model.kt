package dev.nfssaf.core

import java.io.IOException
import java.util.UUID

data class Share(
    val id: ShareId = ShareId.random(),
    val name: String,
    val host: String,
    val export: RemotePath,
    val protocol: Protocol = Protocol.V42,
    val port: Int = 2049,
    val uid: Long = 65534,
    val gid: Long = 65534,
    val groups: List<Long> = emptyList(),
    val readOnly: Boolean = false,
    val timeoutSeconds: Int = 10,
    val readPolicy: ReadPolicy = ReadPolicy.ReadAhead,
) {
    fun validate(): Share = apply {
        require(name.isNotBlank() && name.length <= 120) { "Enter a name (up to 120 characters)" }
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it in "/?@\u0000" }) { "Enter a hostname or IP address, without nfs://" }
        require(port in 1..65535) { "Port must be 1–65535" }
        require(uid in 0..0xffffffffL && gid in 0..0xffffffffL) { "UID and GID must be unsigned 32-bit numbers" }
        require(groups.size <= 16 && groups.all { it in 0..0xffffffffL }) { "AUTH_SYS supports up to 16 supplementary groups" }
        require(timeoutSeconds in 3..30) { "Timeout must be 3–30 seconds" }
    }
}

object Paths {
    fun validate(path: String): String {
        require(Charsets.UTF_8.newEncoder().canEncode(path)) { "Path contains invalid Unicode" }
        require(path.startsWith('/') && '\u0000' !in path && path.toByteArray(Charsets.UTF_8).size <= 4096) { "Use an absolute export path" }
        require(path == "/" || path.split('/').drop(1).all { it.isNotEmpty() && it != "." && it != ".." }) { "Path must not contain empty, . or .. components" }
        return path
    }
    fun name(name: String): String {
        require(Charsets.UTF_8.newEncoder().canEncode(name)) { "File name contains invalid Unicode" }
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name && name.toByteArray(Charsets.UTF_8).size <= 255) { "Invalid file name" }
        return name
    }
    fun child(parent: String, name: String) = if (validate(parent) == "/") "/${name(name)}" else "$parent/${name(name)}"
    fun parent(path: String): String = validate(path).substringBeforeLast('/').ifEmpty { "/" }
    fun contains(parent: String, child: String): Boolean {
        validate(parent); validate(child)
        return parent == child || parent == "/" || child.startsWith("$parent/")
    }
}

data class Entry(val name: String, val inode: Long, val device: Long, val mode: Int, val size: Long, val modifiedMillis: Long) {
    val directory get() = mode and 0xf000 == 0x4000
    val regular get() = mode and 0xf000 == 0x8000
}
class NfsException(val errno: Int, message: String) : IOException(message)

enum class OpenMode(val readable: Boolean, val writable: Boolean, val truncate: Boolean = false, val append: Boolean = false) {
    READ(true, false), WRITE(false, true, true), APPEND(false, true, append = true), READ_WRITE(true, true), REPLACE(true, true, true);
    companion object {
        fun parse(mode: String): OpenMode = when (mode) {
            "r" -> READ; "w", "wt" -> WRITE; "wa" -> APPEND; "rw" -> READ_WRITE; "rwt" -> REPLACE
            else -> throw IllegalArgumentException("Unsupported document mode: $mode")
        }
    }
}

/** Never share a libnfs context concurrently. A lease has one owner until release. */
class LeasePool<T : AutoCloseable>(private val limit: Int, private val create: () -> T) : AutoCloseable {
    private val permits = java.util.concurrent.Semaphore(limit, true)
    private val idle = java.util.ArrayDeque<T>()
    private var closed = false
    init { require(limit > 0) }
    fun acquire(timeoutMillis: Long = 1000): Lease<T> {
        if (!permits.tryAcquire(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) throw NfsException(24, "Too many open files; close another document and retry")
        try {
            val value = synchronized(this) { check(!closed) { "Pool is closed" }; idle.pollFirst() } ?: create()
            return Lease(value) { reusable ->
                try {
                    val keep=synchronized(this) { if(closed || !reusable) false else { idle.addLast(value); true } }
                    if(!keep) value.close()
                }
                finally { permits.release() }
            }
        } catch (t: Throwable) { permits.release(); throw t }
    }
    override fun close() {
        val values=synchronized(this) { closed=true; idle.toList().also { idle.clear() } }
        var failure: Exception?=null
        values.forEach { value -> try { value.close() } catch(e: Exception) { if(failure==null) failure=e else failure!!.addSuppressed(e) } }
        failure?.let { throw it }
    }
}
class Lease<T>(val value: T, private val release: (Boolean) -> Unit) : AutoCloseable {
    private val released = java.util.concurrent.atomic.AtomicBoolean()
    var reusable = true
    override fun close() { if (released.compareAndSet(false, true)) release(reusable) }
}
