package dev.nfssaf

import android.database.Cursor
import android.database.MatrixCursor
import android.os.*
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import dev.nfssaf.core.*
import java.io.FileNotFoundException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class NfsDocumentsProvider : DocumentsProvider() {
    private val services get() = Services.get(context!!)
    private val queries=Executors.newFixedThreadPool(4)
    private sealed interface Listing {
        class Loading : Listing
        data class Ready(val nodes: List<Node>, val time: Long) : Listing
        data class Failed(val message: String, val time: Long) : Listing
    }
    private val listings=object : LinkedHashMap<DocumentId,Listing>(32,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DocumentId,Listing>) = size > 32
    }
    override fun onCreate() = true
    private fun root(share: Share) = Node.Directory(DocumentId(share.id.value),share.id,RemotePath.Root,Entry(share.name,0,0,0x4000,0,0))
    private fun locate(id: DocumentId): Node = services.shares.all().firstOrNull { it.id.value==id.value }?.let(::root) ?: services.catalog.find(id)
    private fun refresh(node: Node, session: NativeSession): Node {
        val e=session.stat(node.path)
        if(node.path!=RemotePath.Root && (e.inode!=node.entry.inode || e.device!=node.entry.device)) {
            services.catalog.forget(node.share,node.path).forEach { revokeDocumentPermission(it.value) }
            throw NfsException(116,"This document was replaced on the server; choose it again")
        }
        return services.catalog.register(node.share,node.path,e)
    }
    private inline fun <T> documentCall(block: () -> T): T = try { block() } catch(e: NfsException) {
        throw FileNotFoundException(ConnectionProblem.from(e).message).apply { initCause(e) }
    }
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns=projection ?: ROOT_COLUMNS
        return MatrixCursor(columns).apply {
            services.shares.all().forEach { s -> addRow(columns.map { c -> when(c) {
                Root.COLUMN_ROOT_ID -> s.id.value
                Root.COLUMN_DOCUMENT_ID -> s.id.value
                Root.COLUMN_TITLE -> s.name
                Root.COLUMN_SUMMARY -> "${s.host} · ${s.protocol.label}"
                Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_IS_CHILD or (if(s.readOnly) 0 else Root.FLAG_SUPPORTS_CREATE)
                Root.COLUMN_ICON -> dev.nfssaf.R.mipmap.ic_launcher
                Root.COLUMN_MIME_TYPES -> "*/*"
                else -> null
            } }) }
            setNotificationUri(context!!.contentResolver,DocumentsContract.buildRootsUri(AUTHORITY))
        }
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = documentCall {
        val node=locate(DocumentId(documentId)); val share=services.shares.get(node.share)
        val current=services.backend.metadata(share) { refresh(node,it) }
        cursor(projection,listOf(current),share)
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = documentCall {
        val id=DocumentId(parentDocumentId)
        val node=locate(id) as? Node.Directory ?: throw NfsException(20,"Not a directory")
        val share=services.shares.get(node.share)
        val state=synchronized(listings) {
            val old=listings[id]
            val now=SystemClock.elapsedRealtime()
            val stale=old==null || (old is Listing.Ready && now-old.time>2000) || (old is Listing.Failed && now-old.time>5000)
            if(stale) {
                val pending=Listing.Loading()
                listings[id]=pending
                queries.execute {
                    val result=try {
                        val nodes=services.backend.metadata(share) { s -> refresh(node,s); services.catalog.registerChildren(node.share,node.path,s.list(node.path)) }
                        Listing.Ready(nodes,SystemClock.elapsedRealtime())
                    } catch(t: Exception) { Listing.Failed(ConnectionProblem.from(t).message,SystemClock.elapsedRealtime()) }
                    synchronized(listings) { if(listings[id] === pending) listings[id]=result }
                    context!!.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY,id.value),null)
                }
                pending
            } else old!!
        }
        val nodes=if(state is Listing.Ready) sorted(state.nodes,sortOrder) else emptyList()
        cursor(projection,nodes,share).apply {
            extras=Bundle().apply {
                putBoolean(DocumentsContract.EXTRA_LOADING,state is Listing.Loading)
                if(state is Listing.Failed) putString(DocumentsContract.EXTRA_ERROR,state.message)
            }
            setNotificationUri(context!!.contentResolver,DocumentsContract.buildChildDocumentsUri(AUTHORITY,parentDocumentId))
        }
    }
    private fun sorted(nodes: List<Node>, order: String?): List<Node> {
        val comparator=when(order?.substringBefore(' ')?.lowercase(Locale.ROOT)) {
            Document.COLUMN_SIZE -> compareBy<Node> { it.entry.size }
            Document.COLUMN_LAST_MODIFIED -> compareBy { it.entry.modifiedMillis }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.entry.name }
        }
        return nodes.sortedWith(if(order?.endsWith(" DESC",true)==true) comparator.reversed() else comparator)
    }
    private fun cursor(projection: Array<out String>?, nodes: List<Node>, share: Share): MatrixCursor {
        val columns=projection ?: DOCUMENT_COLUMNS
        return MatrixCursor(columns).apply {
            nodes.forEach { n -> addRow(columns.map { c -> when(c) {
                Document.COLUMN_DOCUMENT_ID -> n.id.value
                Document.COLUMN_DISPLAY_NAME -> if(n.path==RemotePath.Root) share.name else n.entry.name
                Document.COLUMN_MIME_TYPE -> mime(n)
                Document.COLUMN_SIZE -> if(n is Node.File) n.entry.size else null
                Document.COLUMN_LAST_MODIFIED -> n.entry.modifiedMillis.takeIf { it>0 }
                Document.COLUMN_FLAGS -> flags(n,share)
                else -> null
            } }) }
        }
    }
    private fun mime(node: Node): String = when(node) {
        is Node.Directory -> Document.MIME_TYPE_DIR
        is Node.File -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(node.entry.name.substringAfterLast('.',"").lowercase(Locale.ROOT)) ?: "application/octet-stream"
    }
    private fun flags(node: Node, share: Share): Int {
        if(share.readOnly) return 0
        return when(node) {
            is Node.Directory -> Document.FLAG_DIR_SUPPORTS_CREATE or if(node.path==RemotePath.Root) 0 else Document.FLAG_SUPPORTS_DELETE
            is Node.File -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = documentCall {
        val parent=locate(DocumentId(parentDocumentId)); val child=locate(DocumentId(documentId))
        services.shares.get(parent.share)
        parent is Node.Directory && parent.share==child.share && parent.path.contains(child.path)
    }
    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path = documentCall {
        val child=locate(DocumentId(childDocumentId)); val share=services.shares.get(child.share)
        val parent=parentDocumentId?.let { locate(DocumentId(it)) } ?: root(share)
        if(parent !is Node.Directory || parent.share!=child.share || !parent.path.contains(child.path)) throw NfsException(2,"Document is outside the tree")
        val ids=mutableListOf(child.id.value); var path=child.path
        services.backend.metadata(share) { s -> while(path!=parent.path) { path=path.parent; ids.add(if(path==RemotePath.Root) share.id.value else services.catalog.register(share.id,path,s.stat(path)).id.value) } }
        DocumentsContract.Path(if(parentDocumentId==null) share.id.value else null,ids.reversed())
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String = documentCall {
        val parent=locate(DocumentId(parentDocumentId)) as? Node.Directory ?: throw NfsException(20,"Not a directory")
        val share=services.shares.get(parent.share); val name=FileName(displayName)
        requireWritable(share)
        synchronized(services.lock(share.id)) {
            val node=services.backend.metadata(share) { s ->
                refresh(parent,s)
                var result: Node?=null
                for(index in 0..999) {
                    val candidate=if(index==0) name else FileName(conflictName(name.value,index))
                    val path=parent.path.child(candidate)
                    try {
                        if(mimeType==Document.MIME_TYPE_DIR) s.mkdir(path) else s.create(path)
                        result=services.catalog.register(share.id,path,s.stat(path)); break
                    } catch(e: NfsException) { if(e.errno!=17) throw e }
                }
                result ?: throw NfsException(17,"Too many files with this name")
            }
            changed(parent); node.id.value
        }
    }
    override fun renameDocument(documentId: String, displayName: String): String? = documentCall {
        val node=locate(DocumentId(documentId)) as? Node.File ?: throw NfsException(95,"Directory rename is not supported")
        val share=services.shares.get(node.share); requireWritable(share)
        val target=node.path.parent.child(FileName(displayName))
        if(target==node.path) return@documentCall null
        synchronized(services.lock(share.id)) {
            services.backend.metadata(share) { s -> refresh(node,s); s.rename(node.path,target); services.catalog.rename(node,target) }
            changed(node); null
        }
    }
    override fun deleteDocument(documentId: String) = documentCall {
        val node=locate(DocumentId(documentId)); val share=services.shares.get(node.share); requireWritable(share)
        if(node.path==RemotePath.Root) throw NfsException(1,"Remove a share from NFS SAF settings; its export root cannot be deleted")
        synchronized(services.lock(share.id)) {
            services.backend.metadata(share) { s ->
                refresh(node,s)
                var count=0
                fun remove(path: RemotePath, directory: Boolean, depth: Int) {
                    if(services.backend.gate.state()!=OperationGate.State.Running) throw NfsException(125,"Deletion interrupted while stopping connections")
                    if(depth>128 || ++count>100000) throw NfsException(7,"Directory is too large to delete in one operation")
                    if(directory) s.list(path).forEach { remove(path.child(FileName(it.name)),it.directory,depth+1) }
                    s.remove(path,directory)
                }
                remove(node.path,node is Node.Directory,0)
            }
            services.catalog.forget(node.share,node.path).forEach { revokeDocumentPermission(it.value) }
            changed(node)
        }
    }
    private fun requireWritable(share: Share) { if(share.readOnly) throw NfsException(30,"Share is read-only") }
    private fun changed(node: Node) {
        synchronized(listings) { listings.clear() }
        context!!.contentResolver.notifyChange(DocumentsContract.buildDocumentUri(AUTHORITY,node.id.value),null)
        // Notify observers of every known parent cursor; descendants can have changed as well.
        context!!.contentResolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY),null)
        val parentId=services.catalog.pathId(node.share,node.path.parent)
        parentId?.let { context!!.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY,it.value),null) }
        if(node is Node.Directory) context!!.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY,node.id.value),null)
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = documentCall {
        signal?.throwIfCanceled()
        val node=locate(DocumentId(documentId)) as? Node.File ?: throw NfsException(21,"Cannot open a directory")
        val share=services.shares.get(node.share); val access=OpenMode.parse(mode)
        val file=services.backend.open(share,node,access)
        val thread=HandlerThread("nfs-file").apply { start() }
        val callback=object : ProxyFileDescriptorCallback() {
            private val released=AtomicBoolean()
            private inline fun <T> io(block: () -> T): T = try {
                if(signal?.isCanceled==true) throw ErrnoException("NFS",OsConstants.ECANCELED)
                block()
            } catch(e: NfsException) { throw ErrnoException("NFS",e.errno,e) }
            override fun onGetSize() = io { file.size() }
            override fun onRead(offset: Long, size: Int, data: ByteArray) = io { when(file) {
                is OpenedFile.ReadOnly -> file.handle.read(offset,size,data)
                is OpenedFile.ReadWrite -> file.handle.read(offset,size,data)
                is OpenedFile.WriteOnly -> throw ErrnoException("read",OsConstants.EBADF)
            } }
            override fun onWrite(offset: Long, size: Int, data: ByteArray) = io {
                synchronized(services.fileLock(node.id)) { when(file) {
                    is OpenedFile.ReadOnly -> throw ErrnoException("write",OsConstants.EBADF)
                    is OpenedFile.WriteOnly -> file.handle.write(offset,size,data)
                    is OpenedFile.ReadWrite -> file.handle.write(offset,size,data)
                } }
            }
            override fun onFsync() = io { when(file) {
                is OpenedFile.ReadOnly -> Unit
                is OpenedFile.WriteOnly -> file.handle.fsync()
                is OpenedFile.ReadWrite -> file.handle.fsync()
            } }
            override fun onRelease() {
                if(!released.compareAndSet(false,true)) return
                // Return immediately to FUSE/vold. Work stays on this file's existing thread.
                Handler(thread.looper).post {
                    try { file.close(); if(access.writable) changed(node) }
                    catch(e: Exception) { services.errors.edit().putString("last", "${share.name}: ${ConnectionProblem.from(e).message}").apply() }
                    finally { thread.quitSafely() }
                }
            }
        }
        try {
            signal?.throwIfCanceled()
            val proxy=context!!.getSystemService(StorageManager::class.java).openProxyFileDescriptor(ParcelFileDescriptor.parseMode(mode),callback,Handler(thread.looper))
            // Android creates a distinct FUSE inode for each proxy. Bypass its
            // per-open page cache so another descriptor's writes are visible.
            try { NativeBridge.directProxy(proxy.fd); proxy } catch(t: Throwable) { proxy.close(); throw t }
        } catch(t: Throwable) { callback.onRelease(); throw t }
    }
    companion object {
        val ROOT_COLUMNS=arrayOf(Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_SUMMARY,Root.COLUMN_FLAGS,Root.COLUMN_ICON)
        val DOCUMENT_COLUMNS=arrayOf(Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE,Document.COLUMN_LAST_MODIFIED)
        private fun conflictName(name: String, index: Int): String {
            val dot=name.lastIndexOf('.')
            return if(dot>0) "${name.substring(0,dot)} ($index)${name.substring(dot)}" else "$name ($index)"
        }
    }
}
