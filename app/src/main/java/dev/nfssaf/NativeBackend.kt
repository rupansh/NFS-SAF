package dev.nfssaf

import dev.nfssaf.core.*
import java.util.concurrent.ConcurrentHashMap

internal object NativeBridge {
    init { System.loadLibrary("nfssaf") }
    external fun connect(host: String, path: String, version: Int, port: Int, uid: Long, gid: Long, groups: LongArray, timeout: Int, readOnly: Boolean): Long
    external fun disconnect(session: Long)
    external fun stat(session: Long, path: String): Entry
    external fun list(session: Long, path: String): Array<Entry>
    external fun open(session: Long, path: String, mode: Int, exclusive: Boolean): Long
    external fun fstat(session: Long, file: Long): Entry
    external fun read(session: Long, file: Long, offset: Long, size: Int, data: ByteArray): Int
    external fun write(session: Long, file: Long, offset: Long, size: Int, data: ByteArray): Int
    external fun sync(session: Long, file: Long)
    external fun close(session: Long, file: Long)
    external fun mkdir(session: Long, path: String)
    external fun remove(session: Long, path: String, directory: Boolean)
    external fun rename(session: Long, from: String, to: String)
}

internal class NativeSession(share: Share) : AutoCloseable {
    private sealed interface State {
        data class Connected(val pointer: Long) : State
        data object Closed : State
    }
    private var state: State = State.Connected(share.validate().let {
        NativeBridge.connect(it.host, it.export.value, it.protocol.wire, it.port, it.uid, it.gid, it.groups.toLongArray(), it.timeoutSeconds*1000, it.readOnly)
    })
    @Synchronized fun <T> useNative(block: (Long) -> T): T = when(val s=state) {
        is State.Connected -> block(s.pointer)
        State.Closed -> throw NfsException(9,"Connection is closed")
    }
    fun stat(path: RemotePath) = useNative { NativeBridge.stat(it,path.value) }
    fun list(path: RemotePath) = useNative { NativeBridge.list(it,path.value).toList() }
    fun mkdir(path: RemotePath) = useNative { NativeBridge.mkdir(it,path.value) }
    fun remove(path: RemotePath, directory: Boolean) = useNative { NativeBridge.remove(it,path.value,directory) }
    fun rename(from: RemotePath, to: RemotePath) = useNative { NativeBridge.rename(it,from.value,to.value) }
    fun create(path: RemotePath) = useNative { s ->
        val f=NativeBridge.open(s,path.value,OpenMode.READ_WRITE.ordinal,true)
        NativeBridge.close(s,f)
    }
    fun open(node: Node.File, mode: OpenMode, release: (Boolean) -> Unit): OpenedFile = useNative { s ->
        // Verify identity before a destructive truncate.
        val actual=NativeBridge.stat(s,node.path.value)
        if(actual.inode != node.entry.inode || actual.device != node.entry.device) throw NfsException(116,"Document was replaced on the server")
        val f=NativeBridge.open(s,node.path.value,mode.ordinal,false)
        try {
            val size=NativeBridge.fstat(s,f).size
            OpenedFile.own(object : FileIo {
                private var healthy=true
                private fun <T> io(block: (Long) -> T): T = try { useNative(block) } catch(t: NfsException) { healthy=false; throw t }
                override fun read(offset: Long, size: Int, data: ByteArray) = io { NativeBridge.read(it,f,offset,size,data) }
                override fun write(offset: Long, size: Int, data: ByteArray) = io {
                    NativeBridge.write(it,f,if(mode.append) NativeBridge.fstat(it,f).size else offset,size,data)
                }
                override fun sync() = io { NativeBridge.sync(it,f) }
                override fun close() {
                    try { io { NativeBridge.close(it,f) } } finally { release(healthy) }
                }
            },mode,size)
        } catch(t: Throwable) { runCatching { NativeBridge.close(s,f) }; throw t }
    }
    @Synchronized override fun close() {
        val current=state
        state=State.Closed
        if(current is State.Connected) NativeBridge.disconnect(current.pointer)
    }
}

internal class NfsBackend {
    val gate=OperationGate()
    private val openFiles=ConcurrentHashMap<OpenedFile,Unit>()
    val openCount get()=openFiles.size
    fun start() = gate.start()
    fun drain(): List<Throwable> {
        if(!gate.beginDrain()) return emptyList()
        val errors=mutableListOf<Throwable>()
        try {
            gate.awaitIdle()
            openFiles.keys.toList().forEach { file -> try { file.close() } catch(t: Exception) { errors.add(t) } }
            pools.values.forEach { try { it.close() } catch(t: Exception) { errors.add(t) } }
            pools.clear()
        } finally { gate.finishDrain() }
        return errors
    }
    private class Pools(share: Share) : AutoCloseable {
        val metadata=LeasePool(2) { NativeSession(share) }
        val files=LeasePool(12) { NativeSession(share) }
        override fun close() { metadata.close(); files.close() }
    }
    private val pools=ConcurrentHashMap<Share,Pools>()
    private fun pools(share: Share) = pools.computeIfAbsent(share) { Pools(it) }
    fun invalidate(share: Share) { pools.remove(share)?.close() }
    fun <T> metadata(share: Share, action: (NativeSession) -> T): T = gate.enter().use { pools(share).metadata.acquire().use { lease ->
        try { action(lease.value) } catch(t: NfsException) {
            if(t.errno in setOf(5, 104, 110, 116)) lease.reusable=false
            throw t
        }
    } }
    fun open(share: Share, node: Node.File, mode: OpenMode): OpenedFile = gate.enter().use {
        if(share.readOnly && mode.writable) throw NfsException(30,"Share is read-only")
        val lease=pools(share).files.acquire()
        try {
            lateinit var file: OpenedFile
            file=lease.value.open(node,mode) { reusable ->
                openFiles.remove(file)
                lease.reusable=reusable; lease.close()
            }
            openFiles[file]=Unit
            file
        } catch(t: Throwable) { lease.reusable=false; lease.close(); throw t }
    }
}
