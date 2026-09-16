package dev.nfssaf

import android.system.ErrnoException
import androidx.test.platform.app.InstrumentationRegistry
import dev.nfssaf.core.NfsException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Contract implemented separately by actual SAF descriptors and typed JNI handles. */
internal interface FsxFile : AutoCloseable {
    fun read(offset: Long, size: Int, data: ByteArray): Int

    fun write(offset: Long, size: Int, data: ByteArray): Int

    fun size(): Long
}

internal object FsxRunner {
    enum class Backend {
        Saf,
        Jni,
        CorruptionOracle,
    }

    data class Result(val exitCode: Int, val log: String, val transportCalls: Int)

    // Keep the six-operation wire protocol and process cleanup auditable in one dispatcher.
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    fun run(
        backend: Backend,
        seed: Int,
        operations: Int,
        maxOperation: Int,
        corruptReads: Boolean = false,
        open: () -> FsxFile,
    ): Result {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val executable = File(instrumentation.context.applicationInfo.nativeLibraryDir, "libfsx.so")
        check(executable.canExecute()) { "Test-only fsx executable missing: $executable" }
        val logs =
            File(instrumentation.targetContext.cacheDir, "fsx-$seed-${System.nanoTime()}").apply {
                mkdirs()
            }
        val process =
            ProcessBuilder(
                    executable.path,
                    "-L",
                    "-R",
                    "-W",
                    "-c",
                    "100",
                    "-N",
                    operations.toString(),
                    "-S",
                    seed.toString(),
                    "-o",
                    maxOperation.toString(),
                    "-P",
                    "${logs.path}/",
                    "nfssaf-target",
                )
                .start()
        val deadline = Executors.newSingleThreadScheduledExecutor()
        val timeout = deadline.schedule({ process.destroyForcibly() }, 180, TimeUnit.SECONDS)
        val errors = Executors.newSingleThreadExecutor()
        val errorText =
            errors.submit<String> { process.errorStream.bufferedReader().use { it.readText() } }
        var target: FsxFile? = null
        var offset = 0L
        var calls = 0
        val input = DataInputStream(BufferedInputStream(process.inputStream))
        val output = DataOutputStream(BufferedOutputStream(process.outputStream))
        try {
            while (true) {
                val op =
                    try {
                        input.readLong().toInt()
                    } catch (_: EOFException) {
                        break
                    }
                val a = input.readLong()
                val b = input.readLong()
                calls++
                // Always consume write payload, even if the backend reports an error.
                val payload =
                    if (op == 5) ByteArray(a.toInt()).also { input.readFully(it) } else null
                var data: ByteArray? = null
                val result =
                    try {
                        when (op) {
                            1 -> {
                                check(target == null)
                                target = open()
                                offset = 0
                                0L
                            }
                            2 -> {
                                target!!.close()
                                target = null
                                0L
                            }
                            3 -> {
                                val next =
                                    when (b.toInt()) {
                                        0 -> a
                                        1 -> offset + a
                                        2 -> target!!.size() + a
                                        else -> error("Unknown seek origin $b")
                                    }
                                require(next >= 0)
                                offset = next
                                next
                            }
                            4 -> {
                                data = ByteArray(a.toInt())
                                val count = target!!.read(offset, a.toInt(), data)
                                if (corruptReads && count > 0)
                                    data[0] = (data[0].toInt() xor 0x40).toByte()
                                offset += count
                                count.toLong()
                            }
                            5 ->
                                target!!
                                    .write(offset, payload!!.size, payload)
                                    .also { offset += it }
                                    .toLong()
                            6 -> target!!.size()
                            else -> error("Unknown fsx transport operation $op")
                        }
                    } catch (e: ErrnoException) {
                        -e.errno.toLong()
                    } catch (e: NfsException) {
                        -e.errno.toLong()
                    }
                output.writeLong(result)
                if (op == 4 && result > 0) output.write(data!!, 0, result.toInt())
                output.flush()
            }
            check(process.waitFor(5, TimeUnit.SECONDS)) { "fsx did not exit" }
            val result = Result(process.exitValue(), errorText.get(5, TimeUnit.SECONDS), calls)
            android.util.Log.i(
                "NfsSafFsx",
                "backend=$backend seed=$seed operations=$operations " +
                    "max_operation=$maxOperation corrupt=$corruptReads " +
                    "exit=${result.exitCode} transport_calls=$calls logs=$logs",
            )
            return result
        } finally {
            try {
                target?.close()
            } finally {
                timeout.cancel(false)
                deadline.shutdownNow()
                process.destroyForcibly()
                input.close()
                output.close()
                errors.shutdownNow()
            }
        }
    }
}
