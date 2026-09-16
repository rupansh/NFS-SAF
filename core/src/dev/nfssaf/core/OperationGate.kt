package dev.nfssaf.core

/** Linearization point for starting/stopping a runtime. No IO happens under this monitor. */
class OperationGate {
    sealed interface State {
        data object Stopped : State
        data object Running : State
        data object Draining : State
    }
    private val monitor=Object()
    private var state: State=State.Stopped
    private var operations=0
    fun state(): State = synchronized(monitor) { state }
    fun start() = synchronized(monitor) {
        check(state != State.Draining) { "Connections are still stopping" }
        state=State.Running
    }
    fun enter(): AutoCloseable = synchronized(monitor) {
        if(state != State.Running) throw NfsException(107,"Connections are stopped. Open NFS SAF and tap Start connections.")
        operations++
        val done=java.util.concurrent.atomic.AtomicBoolean()
        AutoCloseable { if(done.compareAndSet(false,true)) synchronized(monitor) { operations--; monitor.notifyAll() } }
    }
    class Drain internal constructor(private val gate: OperationGate) {
        fun awaitIdle() = gate.awaitIdle()
        fun finish() = gate.finishDrain()
    }
    /** Only the caller receiving this ticket owns cleanup. */
    fun beginDrain(): Drain? = synchronized(monitor) {
        if(state != State.Running) return null
        state=State.Draining; Drain(this)
    }
    private fun awaitIdle() = synchronized(monitor) { while(operations>0) monitor.wait() }
    private fun finishDrain() = synchronized(monitor) {
        check(state == State.Draining && operations==0)
        state=State.Stopped; monitor.notifyAll()
    }
}
