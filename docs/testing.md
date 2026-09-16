# Storage verification

## Reproduce

Set `ANDROID_HOME` to the installed SDK (on the development machine,
`/home/rupansh/Android/Sdk`, not the shell's `/opt/android-sdk` default).
Use `+` between Mill targets; space-separated task names are not equivalent.

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
./mill core.test + core.contractCheck + native.test
./mill native.host
out/native/host.dest/storage_tests SERVER /EXPORT 42 95
python3 scripts/network-tests.py --host SERVER --export /EXPORT
scripts/sanitizer-test.sh SERVER /EXPORT
scripts/android-test.sh EMULATOR_SERIAL SERVER /EXPORT
```

The Android script builds and installs both APKs, grants notification permission
on the test device, runs instrumentation, and fails on JUnit failures even when
`am instrument` itself returns shell success. Reports are in `out/reports/`.
An optional fourth argument selects a test class or `Class#method`.
Tests mutate only fresh `.nfssaf-test-*` subdirectories of the supplied export.
Native failure diagnostics identify retained artifacts; inspect those exact
paths rather than deleting an entire export or every matching prefix.

For upstream fsx alone:

```sh
scripts/android-test.sh EMULATOR_SERIAL SERVER /EXPORT \
  'dev.nfssaf.StorageIntegrationTest#upstreamFsxThroughSafDescriptors'
scripts/android-test.sh EMULATOR_SERIAL SERVER /EXPORT \
  'dev.nfssaf.StorageIntegrationTest#upstreamFsxThroughTypedJniHandles'
```

## Upstream suite adaptation

FreeBSD's unmodified fsx executable runs its own randomized operation generator,
byte model, comparisons, size checks and failure operation history. A test-only
stdio adapter dispatches its target-file syscalls to either actual SAF proxy
file descriptors or typed JNI handles. There are three seeds per backend:
1, 42 and 20260916, with 10,000 operations each (60,000 total), on a 512 KiB
file. Maximum IO sizes are 4, 64 and 128 KiB; alignment is one byte, with
random close/reopen at probability 1/100. Each adapter fsyncs before close.
An intentional corruption test must produce fsx's BAD DATA failure (exit 110),
so an adapter that never reads data or ignores the child exit status fails.

The profile is `-L -R -W`: fixed size and no mapped IO. SAF's public proxy
callback API has no arbitrary ftruncate/mmap callback. Separate integration
tests cover truncation at open, file extension, sparse offsets above 4 GiB,
append, directory operations and service shutdown. This is not full POSIX or
full xfstests conformance. pjdfstest was also considered; its root-oriented
chmod/chown/link/mknod POSIX surface is a poor direct fit for a DocumentsProvider.
See [the adapter and provenance](../tests/fsx/README.md).

Primary references:
- https://github.com/freebsd/freebsd-src/tree/main/tools/regression/fsx
- https://github.com/pjd/pjdfstest
- https://android.googlesource.com/platform/cts/+/2d7144b53f96b0eebb0f18130dc2cc64aeb97c3f/tests/tests/os/src/android/os/storage/cts/StorageManagerTest.java

## Verified environment (2026-09-16)

Linux NFS server `192.168.1.18`, export `/media/rupansh/wdblack/sd`, NFS 4.2
(minor version 2), AUTH_SYS, TCP. The owner enabled unprivileged clients through
`insecure`; test traffic uses only newly created test directories. This export
squashes identities, so successful IO alone cannot validate UID/GID encoding.
A local TCP RPC proxy independently checked UID 4000000000, GID 3000000000,
and supplementary GIDs 17, 42, 4000000001 on the wire.

Android: API 36 Google APIs x86-64 image, emulator 37.1.11, 4 GiB RAM,
SwiftShader, headless, host KVM. Task-owned AVD `NfsSafStable`, serial
`emulator-5580`. An unrelated existing AVD was not modified. The installed
API 36.1 Play Store image crashed SurfaceFlinger before app tests; the stable
API 36 image was installed separately. NDK 29.0.14206865 builds ARM64 and
x86-64 with 16 KiB ELF alignment. ARM64 builds have not run on a physical phone.

Coverage:
- Final full instrumentation run: **20/20 passed in 123.139 seconds**; all six
  upstream fsx runs returned zero, and deliberate corruption returned 110.
- 16 Kotlin unit tests: path/Unicode/tree validation, modes, unsigned identity
  bounds, generic handle ownership, concurrent bounded leases, cleanup failure
  aggregation, drain admission and read-ahead coherence/expiry/EOF.
- Compiler contract checks: one valid capability program plus four invalid
  read/write/node/ID combinations that must not compile.
- 16 native deterministic checks; 35 checks against the live NFS 4.2 export,
  including short transfers, errno, Unicode, sparse 64-bit offsets, exclusive
  create, rename collision, identity-before-truncate and readonly enforcement.
- Native idle-open lease test held a handle for 95 seconds before verified IO.
- Blackhole TCP peer: mount failure in approximately 3 seconds with a 3-second
  timeout. RPC proxy verifies the configured AUTH_SYS identities independently.
- 20 Android instrumentation tests exercise real ContentResolver/DocumentsContract/FUSE/JNI:
  projections, async listings, Unicode, create/read/write/rename/delete, tree
  grants, root protection, modes, cancellation, pool exhaustion, replacement
  identity, catalog persistence, concurrent writers and cache invalidation.
- Foreground-service tests check ongoing notification, synchronous admission
  closure, waiting for admitted work, open-file draining, zero native sessions,
  notification removal, restart/readback and best-effort onDestroy cleanup.
- Upstream fsx: six seeded 10,000-operation runs plus the failing corruption
  oracle check described above. Its binary exists only in the test APK.

Native ASan/UBSan runs use Clang and `-fsanitize=address,undefined
-fno-omit-frame-pointer`. All 35 live checks passed with halt-on-error. The
upstream unaligned FSID access was fixed through the checked generated-source
patch in `native/libnfs-fixes.cmake`. Clang's function-pointer-type sanitizer
is disabled only for libnfs's legacy RPCGEN callback ABI; address, alignment,
and other undefined-behavior checks remain enabled. This does not mean the
entire Android application was run under ASan.

## Release packaging verification (2026-09-16)

Both universal variants built successfully with ARM64 and x86-64 only. APK
inspection confirmed debug signing/debuggability for the development variant,
disabled debuggability for release, 16 KiB-compatible ELF load segments in all
native libraries, aligned ZIP entries and no fsx executable in either app APK.

`scripts/test-signing.py` exercised an isolated disposable key: missing/partial
secrets, invalid/empty base64, incorrect store and key passwords, missing alias,
successful signing with the expected certificate, artifact checksum, unchanged
unsigned input and cleanup of temporary keystores. This test creates no
production signing identity and leaves no test-signed distribution artifact.

The **nondebug release APK** and instrumentation APK were then signed with a
separate disposable matching certificate and installed on the same API 36
emulator. **20/20 tests passed in 119.586 seconds**, including all 60,000 fsx
operations and the exit-110 corruption oracle. This validates the release
variant locally; it is not a GitHub Actions run or physical ARM64 validation.
The signing keys were removed after installation. The workflow passed
actionlint 1.7.12 locally; a hosted run requires repository signing secrets.

## Performance evidence

Content is verified, not merely timed. The stream benchmark uses a seeded
random 16 MiB payload. A/B reads use the same file, one warm-up per policy, then
three alternating rounds with fresh descriptors. Server/page caches are warm;
Android's per-open FUSE cache is bypassed with O_DIRECT to prevent stale reads
across descriptors. No whole-file staging or network IO on the UI thread.

Initial A/B with coherent proxy IO: Direct median 28.73 MiB/s, ReadAhead median
45.57 MiB/s. This justified the default 256 KiB bounded prefetch window. Before
bypassing Android's per-open cache, apparent throughput was higher but a
concurrent-writer test proved stale reads; those numbers are not acceptance.
Final repeated A/B: Direct 30.76 MiB/s; ReadAhead 46.02 MiB/s. The 128 KiB
shape measured direct JNI reads at 71.87 MiB/s and SAF reads at 65.04 MiB/s;
direct writes including fsync reached 11.93 MiB/s. The latest timings are
recorded by the test script in `out/reports/benchmark.txt`.
Writes include fsync; small-stream and 128 KiB-block shapes are measured
separately. Emulator timings are not predictions for physical-device Wi-Fi,
server latency, WAN links or cold server caches.

## Limits

Runtime validation covers NFS 4.2 and Android API 36 x86-64. NFS 3, NFS 4.0
(including its RENEW helper), Android 9 through 15, and physical ARM64 devices
still need their own matrix. The local server rejects NFS 4.0 with
MINOR_VERS_MISMATCH. No kernel mount/POSIX conformance, remote-server reboot
recovery, network handover, cross-client atomic append, Kerberos, or TLS claims.
Force-stop/process kill cannot promise a callback or successful commit.
Close errors remain visible in app state instead of being silently retried.
