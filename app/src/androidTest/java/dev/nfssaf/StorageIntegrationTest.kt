package dev.nfssaf

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract as DC
import android.provider.DocumentsContract.Document as D
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nfssaf.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StorageIntegrationTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver=context.contentResolver
    private val services=Services.get(context)
    private lateinit var share: Share
    private lateinit var root: Uri
    private lateinit var directory: Uri
    private fun uri(id: String) = DC.buildDocumentUri(AUTHORITY,id)
    private fun id(uri: Uri) = DC.getDocumentId(uri)
    @Before fun setup() {
        val args=InstrumentationRegistry.getArguments()
        val host=args.getString("nfsHost")
        Assume.assumeTrue("Supply nfsHost and nfsExport to run live storage tests",host!=null && args.getString("nfsExport")!=null)
        share=Share(name="Integration test",host=host!!,export=RemotePath(args.getString("nfsExport")!!),uid=1000,gid=1000)
        services.shares.save(share)
        // Tests start from a visible activity, the same user-start path as the app.
        context.startActivity(android.content.Intent(context,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        ConnectionService.start(context)
        val serviceDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(ConnectionService.status.value != OperationGate.State.Running) {
            if(System.nanoTime()>serviceDeadline) error("Foreground service did not start")
            Thread.sleep(50)
        }
        root=uri(share.id.value)
        directory=DC.createDocument(resolver,root,D.MIME_TYPE_DIR,".nfssaf-test-${UUID.randomUUID()}")!!
    }
    @After fun cleanup() {
        if(::directory.isInitialized) DC.deleteDocument(resolver,directory)
        if(::share.isInitialized) { services.shares.remove(share.id); services.backend.invalidate(share); services.catalog.forget(share.id,RemotePath.Root) }
        if(ConnectionService.status.value==OperationGate.State.Running) ConnectionService.stop(context)
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        while(ConnectionService.status.value!=OperationGate.State.Stopped) { if(System.nanoTime()>deadline) error("Service did not stop"); Thread.sleep(50) }
    }
    private inline fun <reified T: Throwable> fails(block: () -> Unit) {
        try { block(); fail("Expected ${T::class}") } catch(t: Throwable) { if(t !is T) throw t }
    }
    private fun create(name: String="data.bin", parent: Uri=directory, type: String="application/octet-stream") = DC.createDocument(resolver,parent,type,name)!!
    private fun write(uri: Uri, data: ByteArray, mode: String="wt") {
        resolver.openFileDescriptor(uri,mode)!!.use { pfd ->
            var offset=0
            while(offset<data.size) { offset+=Os.write(pfd.fileDescriptor,data,offset,data.size-offset) }
            Os.fsync(pfd.fileDescriptor)
        }
    }
    private fun read(uri: Uri): ByteArray = resolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun children(parent: Uri, projection: Array<String>?=null): List<Map<String,Any?>> {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        while(System.nanoTime()<deadline) {
            resolver.query(DC.buildChildDocumentsUri(AUTHORITY,id(parent)),projection,null,null,null)!!.use { c ->
                val err=c.extras.getString(DC.EXTRA_ERROR); if(err!=null) throw AssertionError(err)
                if(!c.extras.getBoolean(DC.EXTRA_LOADING)) return buildList {
                    while(c.moveToNext()) add(c.columnNames.associateWith { col -> val i=c.getColumnIndexOrThrow(col); if(c.isNull(i)) null else c.getString(i) })
                }
            }
            Thread.sleep(50)
        }
        throw AssertionError("Directory listing timed out")
    }
    @Test fun unicodeCreateReadWriteRenameAndProjection() {
        val f=create("日本🙂.txt"); val bytes="NFS through Android SAF 🙂".toByteArray()
        write(f,bytes); assertArrayEquals(bytes,read(f))
        val rows=children(directory,arrayOf(D.COLUMN_DISPLAY_NAME,D.COLUMN_SIZE,"unknown"))
        assertEquals(1,rows.size); assertEquals("日本🙂.txt",rows[0][D.COLUMN_DISPLAY_NAME]); assertNull(rows[0]["unknown"])
        val renamed=DC.renameDocument(resolver,f,"renamed.txt")!!
        assertEquals(id(f),id(renamed)); assertArrayEquals(bytes,read(f))
        assertTrue(DC.deleteDocument(resolver,f)); assertEquals(0,children(directory).size)
    }
    @Test fun seekSparseTruncateAndAppendModes() {
        val f=create(); write(f,byteArrayOf(1,2,3,4))
        resolver.openFileDescriptor(f,"rw")!!.use { p ->
            val offset=(1L shl 32)+7
            assertEquals(3,Os.pwrite(p.fileDescriptor,byteArrayOf(7,8,9),0,3,offset))
            Os.fsync(p.fileDescriptor)
            val data=ByteArray(3); assertEquals(3,Os.pread(p.fileDescriptor,data,0,3,offset)); assertArrayEquals(byteArrayOf(7,8,9),data)
            assertEquals(offset+3,Os.lseek(p.fileDescriptor,0,OsConstants.SEEK_END))
            assertEquals(0,Os.pread(p.fileDescriptor,data,0,3,offset+3))
        }
        write(f,byteArrayOf(5),"rwt"); assertArrayEquals(byteArrayOf(5),read(f))
        write(f,byteArrayOf(6),"wa"); assertArrayEquals(byteArrayOf(5,6),read(f))
        write(f,byteArrayOf(8),"w"); assertArrayEquals(byteArrayOf(8),read(f))
    }
    @Test fun collidingCreatesAndRenameDoNotOverwrite() {
        val pool=Executors.newFixedThreadPool(4)
        val jobs=(1..8).map { pool.submit<Uri> { create("same.txt") } }
        val files=jobs.map { it.get(30,TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(8,files.map(::id).toSet().size)
        files.forEachIndexed { i,f -> write(f,byteArrayOf(i.toByte())) }
        files.forEachIndexed { i,f -> assertArrayEquals(byteArrayOf(i.toByte()),read(f)) }
        fails<FileNotFoundException> { DC.renameDocument(resolver,files[1],"same.txt") }
        assertArrayEquals(byteArrayOf(0),read(files[0]))
    }
    @Test fun treeContainmentAndRootProtection() {
        val child=create("sub",type=D.MIME_TYPE_DIR); val file=create(parent=child)
        assertTrue(DC.isChildDocument(resolver,directory,file)); assertFalse(DC.isChildDocument(resolver,child,directory))
        fails<FileNotFoundException> { DC.deleteDocument(resolver,root) }
        fails<IllegalArgumentException> { create("../escape") }
        val tree=DC.buildTreeDocumentUri(AUTHORITY,id(directory))
        val outside=DC.buildDocumentUriUsingTree(tree,id(root))
        fails<SecurityException> { resolver.query(outside,null,null,null,null)?.close() }
        assertTrue(DC.deleteDocument(resolver,child)); assertEquals(0,children(directory).size)
    }
    @Test fun readonlyEnforcedAndIdentityReplacementRejected() {
        val f=create(); write(f,byteArrayOf(1))
        val node=services.catalog.find(DocumentId(id(f))) as Node.File
        // Hold the old inode allocated under another name to avoid server inode reuse.
        services.backend.metadata(share) { s -> s.rename(node.path,node.path.parent.child(FileName("old"))); s.create(node.path) }
        fails<FileNotFoundException> { resolver.openFileDescriptor(f,"r")?.close() }
        val ro=share.copy(readOnly=true)
        fails<NfsException> { services.backend.open(ro,node,OpenMode.WRITE) }
        services.backend.metadata(ro) { s -> fails<NfsException> { s.mkdir(node.path.parent.child(FileName("blocked"))) } }
        services.backend.invalidate(ro)
    }
    @Test fun cancellationAndPoolExhaustionReleaseResources() {
        val f=create(); val canceled=CancellationSignal().apply { cancel() }
        fails<OperationCanceledException> { resolver.openFileDescriptor(f,"r",canceled)?.close() }
        val handles=(1..12).map { resolver.openFileDescriptor(f,"r")!! }
        try { fails<FileNotFoundException> { resolver.openFileDescriptor(f,"r")?.close() } }
        finally { handles.forEach { it.close() } }
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(true) {
            try { resolver.openFileDescriptor(f,"r")!!.close(); break }
            catch(e: FileNotFoundException) { if(System.nanoTime()>deadline) throw e; Thread.sleep(50) }
        }
    }
    @Test fun foregroundStopDrainsOpenFilesAndCanRestart() {
        val f=create("service-stop.bin")
        val p=resolver.openFileDescriptor(f,"rw")!!
        Os.write(p.fileDescriptor,byteArrayOf(4,5,6),0,3)
        val manager=context.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(manager.activeNotifications.any { it.id==ConnectionService.NOTIFICATION_ID && it.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0 })
        ConnectionService.stop(context)
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        while(ConnectionService.status.value!=OperationGate.State.Stopped) { if(System.nanoTime()>deadline) error("Drain timed out"); Thread.sleep(50) }
        assertEquals(0,services.backend.openCount)
        assertEquals(0,NativeBridge.liveSessions())
        fails<android.system.ErrnoException> { Os.pread(p.fileDescriptor,ByteArray(1),0,1,0) }
        p.close()
        assertFalse(manager.activeNotifications.any { it.id==ConnectionService.NOTIFICATION_ID })
        ConnectionService.start(context)
        while(ConnectionService.status.value!=OperationGate.State.Running) Thread.sleep(50)
        assertArrayEquals(byteArrayOf(4,5,6),read(f))
    }
    @Test fun localWritesInvalidateOtherHandlesReadAhead() {
        services.shares.save(share.copy(readPolicy=ReadPolicy.ReadAhead))
        val f=create("coherence.bin"); write(f,ByteArray(512*1024) { 1 })
        resolver.openFileDescriptor(f,"r")!!.use { reader ->
            val data=ByteArray(4096)
            Os.pread(reader.fileDescriptor,data,0,data.size,0)
            Os.pread(reader.fileDescriptor,data,0,data.size,4096)
            resolver.openFileDescriptor(f,"rw")!!.use { writer ->
                Os.pwrite(writer.fileDescriptor,ByteArray(4096) { 7 },0,4096,8192)
                Os.fsync(writer.fileDescriptor)
            }
            assertEquals(4096,Os.pread(reader.fileDescriptor,data,0,data.size,8192))
            assertArrayEquals(ByteArray(4096) { 7 },data)
        }
    }
    @Test fun serviceDestructionPerformsBestEffortCleanup() {
        val f=create("destroy-service.bin")
        val p=resolver.openFileDescriptor(f,"rw")!!
        Os.write(p.fileDescriptor,byteArrayOf(8,9,10),0,3)
        assertTrue(context.stopService(android.content.Intent(context,ConnectionService::class.java)))
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        while(ConnectionService.status.value!=OperationGate.State.Stopped) { if(System.nanoTime()>deadline) error("onDestroy cleanup timed out"); Thread.sleep(25) }
        assertEquals(0,services.backend.openCount); assertEquals(0,NativeBridge.liveSessions())
        p.close()
        assertFalse(context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id==ConnectionService.NOTIFICATION_ID })
        ConnectionService.start(context)
        while(ConnectionService.status.value!=OperationGate.State.Running) Thread.sleep(25)
        assertArrayEquals(byteArrayOf(8,9,10),read(f))
    }
    @Test fun stopWaitsForAdmittedOperationsAndRejectsNewOnes() {
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val worker=Executors.newSingleThreadExecutor()
        val pending=worker.submit { services.backend.metadata(share) { entered.countDown(); release.await(10,TimeUnit.SECONDS) } }
        assertTrue(entered.await(10,TimeUnit.SECONDS))
        try {
            ConnectionService.stop(context)
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(services.backend.gate.state()!=OperationGate.State.Draining) { if(System.nanoTime()>deadline) error("Did not enter draining"); Thread.sleep(20) }
            fails<NfsException> { services.backend.metadata(share) { it.stat(RemotePath.Root) } }
            assertEquals(OperationGate.State.Draining,ConnectionService.status.value)
        } finally { release.countDown(); pending.get(10,TimeUnit.SECONDS); worker.shutdown() }
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        while(ConnectionService.status.value!=OperationGate.State.Stopped) { if(System.nanoTime()>deadline) error("Drain did not finish"); Thread.sleep(20) }
        assertEquals(0,NativeBridge.liveSessions())
        ConnectionService.start(context)
        while(ConnectionService.status.value!=OperationGate.State.Running) Thread.sleep(20)
    }
    @Test fun compareReadPoliciesWithIdenticalStreamWorkload() {
        val f=create("cache-comparison.bin")
        val data=ByteArray(16*1024*1024).also { java.util.Random(91).nextBytes(it) }
        write(f,data)
        val timings=ReadPolicy.entries.associateWith { mutableListOf<Double>() }
        // Warm each mode, then alternate the same shape three times.
        repeat(4) { round -> ReadPolicy.entries.forEach { policy ->
            val config=share.copy(readPolicy=policy)
            services.shares.save(config)
            val start=System.nanoTime(); val actual=read(f); val end=System.nanoTime()
            assertArrayEquals(data,actual)
            if(round>0) timings.getValue(policy).add(16/((end-start)/1e9))
        } }
        timings.forEach { (policy,runs) -> android.util.Log.i("NfsSafBenchmark","stream_AB policy=$policy bytes=${data.size} runs_MiB_s=$runs median_MiB_s=${runs.sorted()[1]} verified=true") }
        services.shares.save(share)
    }
    @Test fun compareJniAndProxyTransferShapes() {
        val f=create("shapes.bin")
        val node=services.catalog.find(DocumentId(id(f))) as Node.File
        val block=ByteArray(128*1024) { (it*31).toByte() }
        val start=System.nanoTime()
        val direct=services.backend.open(share,node,OpenMode.READ_WRITE) as OpenedFile.ReadWrite
        direct.use { h -> repeat(128) { h.handle.write(it.toLong()*block.size,block.size,block) }; h.handle.fsync() }
        val written=System.nanoTime()
        val directRead=services.backend.open(share,node,OpenMode.READ) as OpenedFile.ReadOnly
        directRead.use { h -> val actual=ByteArray(block.size); repeat(128) { assertEquals(block.size,h.handle.read(it.toLong()*block.size,block.size,actual)); assertArrayEquals(block,actual) } }
        val read=System.nanoTime()
        resolver.openFileDescriptor(f,"r")!!.use { h -> val actual=ByteArray(block.size); repeat(128) { assertEquals(block.size,Os.pread(h.fileDescriptor,actual,0,actual.size,it.toLong()*block.size)); assertArrayEquals(block,actual) } }
        val proxy=System.nanoTime()
        android.util.Log.i("NfsSafBenchmark","shape=128KiB bytes=16777216 direct_write_fsync_MiB_s=${16/((written-start)/1e9)} direct_read_MiB_s=${16/((read-written)/1e9)} proxy_read_MiB_s=${16/((proxy-read)/1e9)} verified=true")
    }
    @Test fun sequentialTransferBenchmarkWithContentVerification() {
        val f=create("benchmark.bin")
        val data=ByteArray(16*1024*1024).also { java.util.Random(42).nextBytes(it) }
        val start=System.nanoTime(); write(f,data); val written=System.nanoTime(); val actual=read(f); val end=System.nanoTime()
        assertArrayEquals(data,actual)
        val writeMiB=16.0/((written-start)/1e9); val readMiB=16.0/((end-written)/1e9)
        android.util.Log.i("NfsSafBenchmark","protocol=4.2 bytes=${data.size} write_fsync_MiB_s=$writeMiB warm_read_MiB_s=$readMiB verified=true")
    }
    private fun upstreamFsx(saf: Boolean) {
        val f=create(if(saf) "fsx-saf.bin" else "fsx-jni.bin")
        write(f,ByteArray(512*1024))
        val node=services.catalog.find(DocumentId(id(f))) as Node.File
        for((seed,maxOperation) in listOf(1 to 4096,42 to 65536,20260916 to 131072)) {
            val result=FsxRunner.run(if(saf) FsxRunner.Backend.Saf else FsxRunner.Backend.Jni,seed,10000,maxOperation) {
                if(saf) {
                    val fd=resolver.openFileDescriptor(f,"rw")!!
                    object : FsxFile {
                        override fun read(offset: Long,size: Int,data: ByteArray) = Os.pread(fd.fileDescriptor,data,0,size,offset)
                        override fun write(offset: Long,size: Int,data: ByteArray) = Os.pwrite(fd.fileDescriptor,data,0,size,offset)
                        override fun size() = Os.fstat(fd.fileDescriptor).st_size
                        override fun close() { try { Os.fsync(fd.fileDescriptor) } finally { fd.close() } }
                    }
                } else {
                    val file=services.backend.open(share,node,OpenMode.READ_WRITE) as OpenedFile.ReadWrite
                    object : FsxFile {
                        override fun read(offset: Long,size: Int,data: ByteArray) = file.handle.read(offset,size,data)
                        override fun write(offset: Long,size: Int,data: ByteArray) = file.handle.write(offset,size,data)
                        override fun size() = file.size()
                        override fun close() { try { file.handle.fsync() } finally { file.close() } }
                    }
                }
            }
            assertEquals("fsx saf=$saf seed=$seed:\n${result.log}",0,result.exitCode)
            assertTrue(result.log,result.log.contains("All operations completed A-OK!"))
        }
    }
    @Test fun upstreamFsxThroughSafDescriptors() = upstreamFsx(true)
    @Test fun upstreamFsxThroughTypedJniHandles() = upstreamFsx(false)
    @Test fun upstreamFsxDetectsInjectedCorruption() {
        val backing=java.io.File(context.cacheDir,"fsx-corruption-oracle.bin")
        java.io.RandomAccessFile(backing,"rw").use { it.setLength(512*1024) }
        try {
            val result=FsxRunner.run(FsxRunner.Backend.CorruptionOracle,42,100,4096,corruptReads=true) {
                val file=java.io.RandomAccessFile(backing,"rw")
                object : FsxFile {
                    override fun read(offset: Long,size: Int,data: ByteArray): Int { file.seek(offset); return file.read(data,0,size).coerceAtLeast(0) }
                    override fun write(offset: Long,size: Int,data: ByteArray): Int { file.seek(offset); file.write(data,0,size); return size }
                    override fun size() = file.length()
                    override fun close() = file.close()
                }
            }
            assertNotEquals(result.log,0,result.exitCode)
            assertTrue(result.log,result.log.contains("BAD DATA"))
        } finally { backing.delete() }
    }
}
