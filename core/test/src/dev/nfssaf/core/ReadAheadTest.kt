package dev.nfssaf.core
import org.junit.Test
import org.junit.Assert.*

class ReadAheadTest {
    private class Source : FileIo {
        val bytes=ByteArray(20000) { (it%251).toByte() }
        var calls=0
        val requests=mutableListOf<Int>()
        override fun read(offset: Long, size: Int, data: ByteArray): Int {
            calls++; requests.add(size)
            val n=minOf(size,(bytes.size-offset).coerceAtLeast(0).toInt())
            if(n>0) bytes.copyInto(data,0,offset.toInt(),offset.toInt()+n)
            return n
        }
        override fun write(offset: Long, size: Int, data: ByteArray)=error("Read-only")
        override fun sync() {}
        override fun close() {}
    }
    @Test fun sequentialReadsCollapseRpcsAndPreserveBytes() {
        val s=Source(); val r=ReadAhead(s,{0},windowBytes=4096)
        repeat(20) { val data=ByteArray(128); assertEquals(128,r.read(it*128L,128,data)); assertArrayEquals(s.bytes.copyOfRange(it*128,it*128+128),data) }
        assertEquals(listOf(128,4096),s.requests)
    }
    @Test fun randomAccessDoesNotSpeculativelyFetch() {
        val s=Source(); val r=ReadAhead(s,{0},windowBytes=4096)
        listOf(1000L,2000L,500L,18000L).forEach { r.read(it,10,ByteArray(10)) }
        assertEquals(listOf(10,10,10,10),s.requests)
    }
    @Test fun writesAndExpiryInvalidateCachedBytes() {
        var time=0L; var revision=0L; val s=Source(); val r=ReadAhead(s,{revision},{time},4096,250)
        val data=ByteArray(100); r.read(0,100,data); r.read(100,100,data)
        s.bytes[200]=99; revision++; r.read(200,100,data); assertEquals(99,data[0].toInt())
        s.bytes[300]=88; time=251; r.read(300,100,data); assertEquals(88,data[0].toInt())
        assertEquals(4,s.calls)
    }
    @Test fun eofAndBufferBoundaryRemainCorrect() {
        val s=Source(); val r=ReadAhead(s,{0},windowBytes=4096)
        r.read(19800,100,ByteArray(100)); assertEquals(100,r.read(19900,100,ByteArray(100)))
        assertEquals(0,r.read(20000,100,ByteArray(100)))
        assertEquals(10,r.read(19990,100,ByteArray(100)))
        assertEquals(0,r.read(Long.MAX_VALUE,0,ByteArray(0)))
    }
}
