package dev.nfssaf.core

/** Linux errno values shared by Android and the libnfs JNI boundary. */
object Errno {
    const val EPERM = 1
    const val ENOENT = 2
    const val EINTR = 4
    const val EIO = 5
    const val E2BIG = 7
    const val EBADF = 9
    const val EACCES = 13
    const val EEXIST = 17
    const val ENOTDIR = 20
    const val EISDIR = 21
    const val EMFILE = 24
    const val EROFS = 30
    const val EPIPE = 32
    const val EOPNOTSUPP = 95
    const val ENETDOWN = 100
    const val ENETUNREACH = 101
    const val ENETRESET = 102
    const val ECONNABORTED = 103
    const val ECONNRESET = 104
    const val ENOTCONN = 107
    const val ETIMEDOUT = 110
    const val EHOSTDOWN = 112
    const val EHOSTUNREACH = 113
    const val ESTALE = 116
    const val ECANCELED = 125
}
