package dev.nfssaf.core

import org.junit.Assert.*
import org.junit.Test

class MetadataReadsTest {
    private val entry = Entry("existing",1,1,0x4000,0,0)
    private class Reader(private val response: () -> Entry) : MetadataReader {
        override fun stat(path: RemotePath) = response()
        override fun list(path: RemotePath) = listOf(response())
    }
    @Test fun retryDiscardsBrokenLeaseAndReturnsRealResultFromReplacement() {
        val reuse = mutableListOf<Boolean>(); var attempts = 0; var retries = 0
        val reads = MetadataReads(acquire = {
            val attempt = ++attempts
            Lease(Reader { if(attempt==1) throw NfsException(104,"reset"); entry }) { reuse.add(it) }
        }, onRetry = { retries++; assertEquals(104,it.errno) })
        assertEquals(listOf(entry), reads.run { it.list(RemotePath.Root) })
        assertEquals(2, attempts); assertEquals(1, retries); assertEquals(listOf(false,true), reuse)
    }
    @Test fun persistentFailureHasExactlyTwoAttemptsAndNoReusableLeases() {
        var attempts = 0; val reuse = mutableListOf<Boolean>()
        val reads = MetadataReads(acquire = {
            attempts++; Lease(Reader { throw NfsException(110,"timeout") }) { reuse.add(it) }
        })
        try { reads.run { it.stat(RemotePath.Root) }; fail("Expected timeout") }
        catch(e: NfsException) { assertEquals(110,e.errno) }
        assertEquals(2,attempts); assertEquals(listOf(false,false),reuse)
    }
    @Test fun semanticErrorsAndStoppedServiceNeverRetry() {
        for(errno in listOf(2,4,13,20,24,107,116,125)) {
            var attempts = 0
            val reads = MetadataReads(acquire = { attempts++; Lease(Reader { throw NfsException(errno,"failure") }) {} })
            try { reads.run { it.stat(RemotePath.Root) }; fail("Expected errno $errno") }
            catch(e: NfsException) { assertEquals(errno,e.errno) }
            assertEquals("errno $errno must not retry",1,attempts)
        }
    }
    @Test fun failedConnectionEstablishmentCanRecover() {
        var attempts = 0
        val reads = MetadataReads(acquire = { if(++attempts==1) throw NfsException(5,"mount RPC failed"); Lease(Reader { entry }) {} })
        assertEquals(entry,reads.run { it.stat(RemotePath.Root) }); assertEquals(2,attempts)
    }
    @Test fun stopBetweenAttemptsPreventsReconnectAndStillReleasesLease() {
        val gate = OperationGate().apply { start() }
        var connections = 0; var closed = 0
        val reads = MetadataReads(acquire = {
            gate.enter().close()
            connections++; Lease(Reader { throw NfsException(104,"reset") }) { closed++ }
        }, onRetry = { gate.beginDrain()!!.also { it.awaitIdle(); it.finish() } })
        try { reads.run { it.stat(RemotePath.Root) }; fail("Expected stopped service") }
        catch(e: NfsException) { assertEquals(107,e.errno) }
        assertEquals(1,connections); assertEquals(1,closed)
    }
    @Test fun cancellationDoesNotRetry() {
        var attempts = 0
        val reads = MetadataReads(acquire = { attempts++; Lease(Reader { throw InterruptedException() }) {} })
        try { reads.run { it.list(RemotePath.Root) }; fail("Expected interruption") }
        catch(_: InterruptedException) {}
        assertEquals(1,attempts)
    }
}
