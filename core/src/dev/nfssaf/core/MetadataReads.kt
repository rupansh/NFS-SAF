package dev.nfssaf.core

/** The recovery path cannot create, rename, delete or open writable files. */
interface MetadataReader {
    fun stat(path: RemotePath): Entry

    fun list(path: RemotePath): List<Entry>
}

/**
 * Discard a failed session and retry once for transport failures. Never retry absence, access
 * denial, stale document identity, cancellation, or resource exhaustion.
 */
class MetadataReads(
    private val acquire: () -> Lease<MetadataReader>,
    private val onRetry: (NfsException) -> Unit = {},
) {
    fun <T> run(read: (MetadataReader) -> T): T =
        try {
            once(read)
        } catch (e: NfsException) {
            if (!e.isTransportFailure) throw e
            onRetry(e)
            once(read)
        }

    private fun <T> once(read: (MetadataReader) -> T): T =
        acquire().use { lease ->
            try {
                read(lease.value)
            } catch (e: NfsException) {
                if (e.isTransportFailure || e.errno == Errno.ESTALE) lease.reusable = false
                throw e
            }
        }
}

// libnfs reports failed RPCs as EIO as well as the more specific socket errors.
val NfsException.isTransportFailure: Boolean
    get() =
        errno in
            setOf(
                Errno.EIO,
                Errno.EPIPE,
                Errno.ENETDOWN,
                Errno.ENETUNREACH,
                Errno.ENETRESET,
                Errno.ECONNABORTED,
                Errno.ECONNRESET,
                Errno.ETIMEDOUT,
                Errno.EHOSTDOWN,
                Errno.EHOSTUNREACH,
            )
