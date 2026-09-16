package dev.nfssaf

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Test-owned TCP relay: disrupt only this test's NFS connections, never the host's server, export,
 * firewall or other clients. Bound sockets/worker lifetime.
 */
internal class NfsFaultProxy(private val host: String, private val upstreamPort: Int) :
    AutoCloseable {
    private sealed interface Fault {
        data object None : Fault

        data object DropNext : Fault

        data object Blackhole : Fault
    }

    private val fault = AtomicReference<Fault>(Fault.None)
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newFixedThreadPool(8)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val port
        get() = listener.localPort

    val accepted = AtomicInteger()
    val dropped = AtomicInteger()

    init {
        workers.execute {
            try {
                while (!listener.isClosed) {
                    val client = listener.accept()
                    sockets.add(client)
                    accepted.incrementAndGet()
                    workers.execute { relay(client) }
                }
            } catch (_: SocketException) {
                /* close() releases accept */
            }
        }
    }

    fun dropNextExchange() {
        fault.set(Fault.DropNext)
    }

    fun blackhole() {
        fault.set(Fault.Blackhole)
    }

    fun restore() {
        fault.set(Fault.None)
    }

    private fun relay(client: Socket) {
        val upstream = Socket()
        sockets.add(upstream)
        try {
            upstream.connect(InetSocketAddress(host, upstreamPort), 3000)
            workers.execute {
                try {
                    upstream.getInputStream().copyTo(client.getOutputStream())
                } catch (_: java.io.IOException) {} finally {
                    client.close()
                    upstream.close()
                }
            }
            val buffer = ByteArray(64 * 1024)
            val input = client.getInputStream()
            val output = upstream.getOutputStream()
            // Separate EOF and close paths keep the fault relay's ownership explicit.
            @Suppress("LoopWithTooManyJumpStatements")
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                when {
                    fault.compareAndSet(Fault.DropNext, Fault.None) -> {
                        dropped.incrementAndGet()
                        break
                    }
                    fault.get() == Fault.Blackhole -> Unit
                    else -> output.write(buffer, 0, count)
                }
            }
        } catch (_: java.io.IOException) {} finally {
            client.close()
            upstream.close()
            sockets.remove(client)
            sockets.remove(upstream)
        }
    }

    override fun close() {
        listener.close()
        sockets.forEach { it.close() }
        workers.shutdownNow()
        check(workers.awaitTermination(5, TimeUnit.SECONDS)) { "NFS fault relay did not stop" }
    }
}
