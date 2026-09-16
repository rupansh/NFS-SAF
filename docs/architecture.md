# Architecture and source notes

Research checked 2026-09-16. Implementation is original; RSAF was reviewed for
patterns, not copied. In particular it identifies a vold watchdog hazard when
network IO is performed by a shared proxy callback thread during open/release.

Sources:
- https://developer.android.com/guide/topics/providers/create-document-provider
- https://developer.android.com/reference/android/provider/DocumentsProvider
- https://developer.android.com/reference/android/os/ProxyFileDescriptorCallback
- https://github.com/chenxiaolong/RSAF/blob/master/app/src/main/java/com/chiller3/rsaf/rclone/RcloneProvider.kt
- https://mill-build.org/mill/android/compose-samples.html
- https://github.com/sahlberg/libnfs (pinned submodule; API v2)

## Storage

Compose configures roots; DocumentsProvider is the storage entry point. A pure
Kotlin module holds validated paths, configuration, typed storage contracts and
bounded leases. A C++ libnfs adapter performs synchronous operations on exclusive
contexts. This gives concurrency between files without cross-thread access to a
context. JNI translates errors to errno-bearing exceptions and UTF-16 strings to
real UTF-8 (JNI modified UTF-8 cannot represent NFS emoji filenames correctly).

Metadata contexts are separate from the bounded file pool. A leased file context
and a dedicated handler survive until the proxy descriptor closes. File size is
cached at open and updated on writes, so onGetSize never requires the network.
Short reads fill the requested range until EOF; short writes loop or fail.
No automatic reconnect/replay of uncertain writes. Native contexts time out.

SQLite assigns persistent opaque IDs to paths and records inode/device identity.
Known replacements invalidate previous IDs. Provider rename preserves its ID.
External rename discovery and protection against inode reuse after unobserved
unlink/recreate require server filehandle persistence, beyond path APIs; these
are explicit limitations. Configuration endpoint edits replace a root identity.

## SAF surface

Roots and metadata cursors respect requested projections. Roots remain visible
while offline. Tree ancestry checks use components, never raw string prefixes.
Create uses exclusive creation. File rename uses LINK then UNLINK, because NFS
RENAME can overwrite another client's destination. Directory rename is not
advertised. A failed unlink may leave a second hard link after a crash; this is
preferable to clobbering an unrelated file. Only regular files and directories
are exposed. Symbolic links and special files are excluded; ancestors are
checked before access and O_NOFOLLOW protects the final file. Path-based NFS
APIs cannot eliminate ancestor replacement races by another server-side writer;
use an export dedicated to the intended trust boundary.

AUTH_SYS sends configured UID/GID and up to 16 supplementary groups. It does not
authenticate a user cryptographically or encrypt traffic. Kerberos/TLS is not
implemented. Android uses unprivileged source ports; servers requiring reserved
ports need an administrator to allow unprivileged clients. NFSv4 exports use the
server's pseudo-root path, which can differ from its local filesystem path.
