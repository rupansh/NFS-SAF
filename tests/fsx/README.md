# Upstream fsx adapter

`vendor/fsx.c` is unmodified FreeBSD fsx, revision
`1fb9c5ffe25fcba0857c4fdf6ed19ca5bf6bc858`, from
https://github.com/freebsd/freebsd-src/blob/1fb9c5ffe25fcba0857c4fdf6ed19ca5bf6bc858/tools/regression/fsx/fsx.c
(APSL 2.0; license included). Its randomized operation generator, expected-byte
model, byte comparisons, size checks and failure history run as upstream wrote
them. This is a real upstream runner, not a new randomized test called fsx.

Source SHA-256: `b064208bec8519e80038ee1da8cb9c0f7c512a3242bbf4c06809a88ce15ae019`.

The test-only executable replaces target-file syscalls with a small binary stdio
protocol. Android instrumentation serves that protocol with either real SAF
proxy descriptor operations or the typed JNI backend. Logs and fsx's failure
images remain in the app's private test directory. Other file IO stays local.
The executable and upstream source are not linked into the application APK.

Profile: `-L -R -W -c 100 -N 10000 -S SEED`, 512 KiB file, maximum operation
sizes 4096, 65536 and 131072 bytes. Both adapters run seeds 1, 42 and 20260916.
Byte alignment stays at 1. `-L` excludes file creation and size changes;
`-R -W` exclude mapped IO. SAF has no arbitrary ftruncate or mmap callback.
Our separate integration tests cover create, truncate-at-open, extension,
sparse offsets above 4 GiB, append, rename, identity and lifecycle contracts.
Do not describe this subset as full POSIX or full xfstests conformance.

The bridge preserves short-transfer results and errors, rather than filling
reads itself or weakening the oracle. Reopen uses the same provider URI/native
node. Close explicitly fsyncs in both adapters. A corruption injection test
requires the upstream oracle to fail, validating the adapter's reporting path.
