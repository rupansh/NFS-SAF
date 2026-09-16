package dev.nfssaf.core

@JvmInline value class ShareId(val value: String) {
    init { require(java.util.UUID.fromString(value).toString() == value) }
    companion object { fun random() = ShareId(java.util.UUID.randomUUID().toString()) }
}
@JvmInline value class DocumentId(val value: String) {
    init { require(java.util.UUID.fromString(value).toString() == value) }
    companion object { fun random() = DocumentId(java.util.UUID.randomUUID().toString()) }
}
@JvmInline value class RemotePath(val value: String) {
    init { Paths.validate(value) }
    fun child(name: FileName) = RemotePath(Paths.child(value, name.value))
    val parent get() = RemotePath(Paths.parent(value))
    fun contains(other: RemotePath) = Paths.contains(value, other.value)
    companion object { val Root = RemotePath("/") }
}
@JvmInline value class FileName(val value: String) { init { Paths.name(value) } }
enum class ReadPolicy { Direct, ReadAhead }
enum class Protocol(val wire: Int, val label: String) { V3(3, "NFS 3"), V4(4, "NFS 4.0"), V42(42, "NFS 4.2") }

sealed interface Node {
    val id: DocumentId
    val share: ShareId
    val path: RemotePath
    val entry: Entry
    data class Directory(override val id: DocumentId, override val share: ShareId, override val path: RemotePath, override val entry: Entry) : Node
    data class File(override val id: DocumentId, override val share: ShareId, override val path: RemotePath, override val entry: Entry) : Node
}

sealed interface Access
sealed interface Readable : Access
sealed interface Writable : Access
data object ReadAccess : Readable
data object WriteAccess : Writable
data object ReadWriteAccess : Readable, Writable

data class WriteReceipt(val count: Int, val endOffset: Long)

/** Implementations own raw native handles; callers only receive capability-typed handles. */
interface FileIo : AutoCloseable {
    fun read(offset: Long, size: Int, data: ByteArray): Int
    fun write(offset: Long, size: Int, data: ByteArray): WriteReceipt
    fun sync()
}
class FileHandle<A : Access> internal constructor(private val io: FileIo, initialSize: Long, private val append: Boolean = false) : AutoCloseable {
    private sealed interface State {
        data class Open(val size: Long) : State
        data object Closed : State
    }
    private var state: State = State.Open(initialSize)
    private fun live(): State.Open = state as? State.Open ?: throw NfsException(9, "File is closed")
    @Synchronized fun size(): Long = live().size
    @Synchronized internal fun readAt(offset: Long, size: Int, data: ByteArray): Int {
        live(); validateRange(offset, size, data)
        if (size == 0) return 0
        return io.read(offset, size, data)
    }
    @Synchronized internal fun writeAt(offset: Long, size: Int, data: ByteArray): Int {
        val current = live(); validateRange(offset, size, data)
        if (size == 0) return 0
        val position = if (append) current.size else offset
        require(position <= Long.MAX_VALUE - size)
        val receipt = io.write(position, size, data)
        if (receipt.count != size || receipt.endOffset < size || (!append && receipt.endOffset != position + size)) throw NfsException(5, "Backend returned an invalid write receipt")
        state = State.Open(maxOf(current.size, receipt.endOffset))
        return receipt.count
    }
    @Synchronized internal fun flush() { live(); io.sync() }
    @Synchronized override fun close() {
        if (state is State.Closed) return
        state = State.Closed
        io.close()
    }
    private fun validateRange(offset: Long, size: Int, data: ByteArray) {
        require(offset >= 0 && size in 0..data.size && offset <= Long.MAX_VALUE - size) { "Invalid IO range" }
    }
}
fun <A : Readable> FileHandle<A>.read(offset: Long, size: Int, data: ByteArray) = readAt(offset, size, data)
fun <A : Writable> FileHandle<A>.write(offset: Long, size: Int, data: ByteArray) = writeAt(offset, size, data)
fun <A : Writable> FileHandle<A>.fsync() = flush()

/** Exhaustive dispatch is required when Android supplies a dynamic mode string. */
sealed interface OpenedFile : AutoCloseable {
    fun size(): Long
    data class ReadOnly(val handle: FileHandle<ReadAccess>) : OpenedFile {
        override fun size() = handle.size(); override fun close() = handle.close()
    }
    data class WriteOnly(val handle: FileHandle<WriteAccess>) : OpenedFile {
        override fun size() = handle.size(); override fun close() = handle.close()
    }
    data class ReadWrite(val handle: FileHandle<ReadWriteAccess>) : OpenedFile {
        override fun size() = handle.size(); override fun close() = handle.close()
    }
    companion object {
        fun own(io: FileIo, mode: OpenMode, size: Long): OpenedFile = when (mode) {
            OpenMode.READ -> ReadOnly(FileHandle(io, size))
            OpenMode.WRITE, OpenMode.APPEND -> WriteOnly(FileHandle(io, size, mode.append))
            OpenMode.READ_WRITE, OpenMode.REPLACE -> ReadWrite(FileHandle(io, size))
        }
    }
}
sealed interface ConnectionStatus {
    data object Untested : ConnectionStatus
    data object Connecting : ConnectionStatus
    data class Connected(val elapsedMillis: Long) : ConnectionStatus
    data class Failed(val reason: String) : ConnectionStatus
}
