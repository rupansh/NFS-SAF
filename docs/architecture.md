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
Metadata reads have a capability-restricted interface exposing only stat/list.
A transport failure discards its leased context and retries once through the
metadata pool. A stopped/draining service, cancellation, absent path, permission
denial, stale identity or exhausted pool does not retry. Failed new mounts also
consume an attempt. A failed attempt's cleanup and the second attempt can each
consume RPC timeout intervals; this is not a single end-to-end timeout setting.
The native `autoreconnect=0` and `retrans=0` settings remain deliberate: libnfs's
nonzero retrans mode can retry indefinitely, including pending mutations.

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

## Foreground service and shutdown

The user starts a `connectedDevice` foreground service from the visible app.
Android's documented type covers interaction with network-connected devices;
the manifest declares FOREGROUND_SERVICE_CONNECTED_DEVICE and
CHANGE_NETWORK_STATE. Android 13+ notification permission is requested from the
activity. The service posts an ongoing notification with a Stop action. There
is no background-start exception, boot auto-start, or sticky restart. SAF
operations while stopped explain how to open the app and start connections.

Sources:
- https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/develop/ui/views/notifications/notification-permission

`OperationGate` has Stopped -> Running -> Draining -> Stopped transitions.
Only one caller obtains a drain ticket. Stopping closes admission synchronously,
waits for admitted metadata/open operations, serializes close behind file IO,
commits/closes files, destroys pools and native sessions, stops each native pump,
and attempts NFS unmount/session destruction. Only then does the service release
its wake lock and remove the notification. Failure on file close is retained
for display in the app; uncertain mutations are never silently replayed.

The pump services each libnfs context every 250 ms under the same native mutex as
sync calls. libnfs sends its own idle NFS 4.2 SEQUENCE renewals. NFS 4.0 RENEW is
implemented by a tiny isolated C helper using the pinned private client-id field;
that dialect's renewal is not validated against the owner's 4.2-only server.
A partial wake lock is held while files are open. Network loss and Android's
power-management policy can still interrupt a connection.

A normal stop invalidates still-open proxy callbacks with EBADF. Android client
processes own their descriptors: callback threads remain available until clients
release those descriptors, while native file/session resources are already
closed. This lets FUSE process RELEASE and reclaim its callback buffers correctly.
`onDestroy` attempts the same cleanup when Android calls it. Force-stop/process
kill provides no callback guarantee. There is no offline write journal.

## Performance and consistency

Directory queries return a complete snapshot on the caller's query worker.
Material Files' name-to-document-ID lookup ignores EXTRA_LOADING; an empty
loading cursor can therefore become NoSuchFileException for an existing folder.
Its ordinary listing waits for a cursor notification, which can arrive before
observer registration. Complete cursors avoid both loading behaviors.

Snapshots expire after two seconds and use a 32-entry LRU keyed by share
configuration and document ID. Concurrent queries for a tracked key share one
load; no network IO holds the cache monitor. Eviction or mutation detaches the
old load so it cannot repopulate the cache. Failures propagate and are not
cached as empty/stale success. Operational query errors use an IPC-supported
IllegalStateException with a user-facing reason and errno: DocumentsProvider
otherwise catches FileNotFoundException and returns a null cursor. Actual
missing paths retain its normal not-found behavior. Notification URIs still
announce mutations. READDIR metadata avoids per-child stat RPCs. Pools separate
metadata from open files; contexts are never used concurrently.

References for these compatibility decisions:
- https://github.com/zhanghai/MaterialFiles/blob/master/app/src/main/java/me/zhanghai/android/files/provider/document/resolver/DocumentResolver.kt
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/DocumentsProvider.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/database/DatabaseUtils.java
- https://github.com/sahlberg/libnfs#readme

Proxy descriptors use `fcntl(F_SETFL, O_DIRECT)` through the NDK to bypass
Android's per-open FUSE page cache. Without this, a descriptor can return old
bytes after another descriptor writes, without calling our callback. Failure to
enable it fails the open rather than silently giving weaker consistency. The
unaligned read/write, sparse-offset and upstream fsx suites exercise this path.
This is direct IO on the Android FUSE descriptor, not O_DIRECT on the NFS server.

A bounded 256 KiB sequential read-ahead window is enabled by default; it expires
after 250 ms and local writes invalidate other handles' windows. Read/write
handles never use that cache. After fixing kernel cache coherence, the measured
warm stream improved from 28.73 to 45.57 MiB/s with prefetch enabled (same file,
alternating policies; emulator evidence, not a phone throughput promise).
External writers cannot invalidate the window instantly; disable Read ahead in
advanced settings when that matters. File size is cached per open, so reopen
after another client extends/truncates a file. Append is serialized within this
process, but NFS has no atomic append primitive across clients. Shared writable
mmap, file locking, and arbitrary ftruncate are not SAF callback contracts.

Sources:
- https://android.googlesource.com/platform/system/core/+/refs/heads/main/libappfuse/FuseAppLoop.cc
- https://docs.kernel.org/filesystems/fuse/fuse-io.html
- https://developer.android.com/reference/android/os/ProxyFileDescriptorCallback

## Native dependency fix

`native/libnfs-fixes.cmake` generates corrected upstream translation units.
It uses `memcpy` for an otherwise unaligned FSID load, preserving its byte layout.
The common NFS 3/4 RPC error callbacks map timeouts to ETIMEDOUT instead of EINTR
and transport failures to EIO instead of EFAULT; they also populate the context
error string used by synchronous stat. Cancellation stays distinct. These
branches were found with the pinned source and an NFS 4.2 blackhole regression;
NFS 3 runtime recovery remains unvalidated. The pinned submodule stays unmodified.
Replacements check their exact upstream text and occurrence count and fail on
drift. Clang UBSan's function-pointer
checker is disabled only for libnfs's legacy RPCGEN callback ABI, not for app or
bridge code. Address/alignment/other undefined-behavior checks remain active.
