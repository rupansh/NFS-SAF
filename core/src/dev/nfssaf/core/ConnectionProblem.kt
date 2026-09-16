package dev.nfssaf.core

sealed interface ConnectionProblem {
    val message: String

    data object MountDenied : ConnectionProblem {
        override val message =
            "The server rejected the mount. Android uses unprivileged source ports: " +
                "enable the insecure option for this client's NFS export, then reload the" +
                " exports. Also check the allowed client subnet, export path, and " +
                "UID/GID."
    }

    data object ProtocolMismatch : ConnectionProblem {
        override val message =
            "The server does not support this NFS version. Select a version enabled " +
                "on the server (for example NFS 4.2)."
    }

    data object Timeout : ConnectionProblem {
        override val message =
            "The NFS server did not respond in time. Check the address, network or " +
                "VPN, firewall, and NFS port."
    }

    data class Other(override val message: String) : ConnectionProblem

    companion object {
        fun from(error: Throwable): ConnectionProblem {
            val detail = error.message.orEmpty()
            return when {
                "MINOR_VERS_MISMATCH" in detail || "PROTONOSUPPORT" in detail -> ProtocolMismatch
                error is NfsException &&
                    error.errno in setOf(Errno.EPERM, Errno.EACCES) &&
                    ("Mount export" in detail || "mount" in detail.lowercase()) -> MountDenied
                error is NfsException &&
                    error.errno in setOf(Errno.ETIMEDOUT, Errno.EHOSTUNREACH, Errno.ENETUNREACH) ->
                    Timeout
                else -> Other(detail.ifBlank { "Connection failed" })
            }
        }
    }
}
