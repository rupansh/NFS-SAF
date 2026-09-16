package dev.nfssaf.core
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ContractsTest {
    private inline fun <reified T : Throwable> fails(block: () -> Unit) {
        try { block(); fail("Expected ${T::class}") } catch (t: Throwable) { if (t !is T) throw t }
    }
    @Test fun pathsAndTreeBoundaries() {
        listOf("", "x", "//a", "/a/", "/a//b", "/../x", "/a/./b", "/x\u0000y").forEach { fails<IllegalArgumentException> { RemotePath(it) } }
        listOf(".", "..", "", "a/b", "a\u0000b", "🙂".repeat(64)).forEach { fails<IllegalArgumentException> { FileName(it) } }
        assertEquals(RemotePath("/日本/🙂"), RemotePath("/日本").child(FileName("🙂")))
        assertFalse(RemotePath("/a").contains(RemotePath("/ab")))
        assertTrue(RemotePath("/a").contains(RemotePath("/a/b")))
        assertTrue(RemotePath.Root.contains(RemotePath("/a")))
        assertEquals(RemotePath.Root, RemotePath("/a").parent)
    }
    @Test fun configurationBoundaries() {
        val s = Share(name="NAS", host="::1", export=RemotePath.Root)
        s.validate(); s.copy(uid=0xffffffffL, gid=0, groups=List(16) { it.toLong() }).validate()
        listOf(s.copy(uid=-1), s.copy(gid=0x100000000), s.copy(groups=List(17) { 0L }), s.copy(port=0), s.copy(host="nfs://nas"), s.copy(timeoutSeconds=0)).forEach { fails<IllegalArgumentException> { it.validate() } }
    }
    @Test fun modesAreExhaustive() {
        assertEquals(OpenMode.WRITE, OpenMode.parse("wt")); assertEquals(OpenMode.REPLACE, OpenMode.parse("rwt"))
        assertEquals(OpenMode.APPEND, OpenMode.parse("wa")); assertFalse(OpenMode.parse("r").writable)
        fails<IllegalArgumentException> { OpenMode.parse("r+") }
    }
    private class Fake : FileIo {
        var bytes = ByteArray(64); var closes = 0; var syncs = 0; var failClose = false
        override fun read(offset: Long, size: Int, data: ByteArray): Int { bytes.copyInto(data, 0, offset.toInt(), offset.toInt()+size); return size }
        override fun write(offset: Long, size: Int, data: ByteArray): Int { data.copyInto(bytes, offset.toInt(), 0, size); return size }
        override fun sync() { syncs++ }
        override fun close() { closes++; if (failClose) throw NfsException(5, "Close failed") }
    }
    @Test fun handleStateAndIo() {
        val io=Fake(); val file=OpenedFile.own(io, OpenMode.READ_WRITE, 4) as OpenedFile.ReadWrite
        file.handle.write(10, 3, byteArrayOf(1,2,3)); assertEquals(13, file.size())
        val bytes=ByteArray(3); file.handle.read(10,3,bytes); assertArrayEquals(byteArrayOf(1,2,3),bytes)
        file.handle.fsync(); assertEquals(1,io.syncs)
        fails<IllegalArgumentException> { file.handle.read(-1,1,bytes) }
        fails<IllegalArgumentException> { file.handle.write(Long.MAX_VALUE,1,bytes) }
        fails<IllegalArgumentException> { file.handle.read(0,4,bytes) }
        file.close(); file.close(); assertEquals(1,io.closes)
        fails<NfsException> { file.handle.read(0,1,bytes) }; fails<NfsException> { file.size() }
    }
    @Test fun failedCloseStillTransitionsToClosed() {
        val io=Fake().apply { failClose=true }; val f=OpenedFile.own(io,OpenMode.WRITE,0)
        fails<NfsException> { f.close() }; f.close(); assertEquals(1,io.closes)
    }
    @Test fun appendIgnoresSuppliedPosition() {
        val io=Fake(); val f=OpenedFile.own(io,OpenMode.APPEND,10) as OpenedFile.WriteOnly
        f.handle.write(0,1,byteArrayOf(9)); assertEquals(9,io.bytes[10].toInt()); assertEquals(11,f.size())
    }
    @Test fun leasesAreBoundedAndIdempotent() {
        val created=AtomicInteger(); val closed=AtomicInteger()
        val p=LeasePool(2) { created.incrementAndGet(); AutoCloseable { closed.incrementAndGet() } }
        val a=p.acquire(); val b=p.acquire(); fails<NfsException> { p.acquire(1) }
        a.close(); a.close(); p.acquire().close(); assertEquals(2,created.get())
        b.reusable=false; b.close(); assertEquals(1,closed.get()); p.close(); assertEquals(2,closed.get())
        fails<IllegalStateException> { p.acquire() }
    }
    @Test fun poolSurvivesFactoryFailureAndClosesActiveLeases() {
        var fail=true; var closed=0
        val p=LeasePool(1) { if (fail) throw NfsException(5,"connect"); AutoCloseable { closed++ } }
        fails<NfsException> { p.acquire() }; fail=false; val a=p.acquire(); p.close(); a.close(); assertEquals(1,closed)
    }
    @Test fun concurrentLeasesHaveExclusiveOwners() {
        val active=AtomicInteger(); val peak=AtomicInteger(); val p=LeasePool(3) { AutoCloseable {} }
        val threads=Executors.newFixedThreadPool(12)
        val jobs=(1..100).map { threads.submit { p.acquire(5000).use { val n=active.incrementAndGet(); peak.accumulateAndGet(n,::maxOf); Thread.yield(); active.decrementAndGet() } } }
        jobs.forEach { it.get(10,TimeUnit.SECONDS) }; threads.shutdown(); p.close(); assertTrue(peak.get() <= 3); assertEquals(0,active.get())
    }
    @Test fun drainRejectsNewOperationsAndWaitsForExistingOnes() {
        val gate=OperationGate()
        fails<NfsException> { gate.enter() }
        gate.start(); val operation=gate.enter()
        assertTrue(gate.beginDrain()); assertFalse(gate.beginDrain())
        fails<NfsException> { gate.enter() }; fails<IllegalStateException> { gate.start() }
        val worker=Executors.newSingleThreadExecutor()
        val waiting=worker.submit { gate.awaitIdle(); gate.finishDrain() }
        Thread.sleep(20); assertFalse(waiting.isDone)
        operation.close(); operation.close(); waiting.get(2,TimeUnit.SECONDS)
        assertEquals(OperationGate.State.Stopped,gate.state()); gate.start(); gate.enter().close(); worker.shutdown()
    }
    @Test fun mountErrorExplainsUnprivilegedPortsWithoutMisdiagnosingFileAccess() {
        assertTrue(ConnectionProblem.from(NfsException(1,"Mount export: NFS4ERR_PERM")) is ConnectionProblem.MountDenied)
        assertTrue(ConnectionProblem.MountDenied.message.contains("insecure"))
        assertTrue(ConnectionProblem.from(NfsException(13,"Open file: denied")) is ConnectionProblem.Other)
        assertTrue(ConnectionProblem.from(NfsException(5,"NFS4ERR_MINOR_VERS_MISMATCH")) is ConnectionProblem.ProtocolMismatch)
        assertTrue(ConnectionProblem.from(NfsException(110,"timed out")) is ConnectionProblem.Timeout)
    }
}
