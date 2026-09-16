# NFS SAF development

Build with the pinned `./mill`; do not add Gradle. Kotlin/Compose Material 3 is the
Android front end; pinned `vendor/libnfs` supplies NFS 3, 4.0 and 4.2 through JNI.
Read `docs/architecture.md` for contracts and limitations before changing storage.
Preserve the supplied `docs/logo.svg`; regenerate adaptive icon resources with
`python3 scripts/generate-icons.py` instead of editing generated path data.

## Contracts
- Prefer sealed sum types, validated value types and generic capability bounds.
  Raw JNI handles and protocol integers belong only at the native boundary.
  Never offer read/write/create through a handle lacking that capability.
- Model lifecycle transitions explicitly. A libnfs context has one owner; never
  call it concurrently. File release must be idempotent, including failed opens.
- Network IO cannot run on the main thread. Each proxy descriptor has its own
  handler; onGetSize must return cached metadata without network access because
  Android can hold the global vold lock while opening a proxy.
- Preserve errno, handle short transfers/EOF/64-bit offsets, commit on fsync,
  never silently retry uncertain mutations, and never report failed writes as success.
- Directory queries return complete snapshots, not empty loading cursors. Keep
  cache loads coalesced and bounded; invalidate detached completions safely.
  Only capability-restricted metadata reads may retry once after transport
  failure. Preserve timeout versus cancellation and operational versus missing-file errors.
- Document IDs are opaque and persistent. Root IDs are share UUIDs. Guard root
  deletion, tree boundaries, replacement identity, path traversal and symlinks.
- Do not introduce whole-file staging, unbounded pools, recursive background
  search, or per-entry metadata RPCs for directory listings.
- Prefer collision errors over overwriting another file. Never use an unchecked
  NFS RENAME for a destination which might already exist.

## Verification and workflow
- `./mill core.test + core.contractCheck + native.test` checks runtime and compile-time contracts.
- `scripts/build-apks.sh` builds and verifies universal debug/release variants.
  `./mill app.androidTest.androidTestApk` builds instrumentation.
- Release signing uses `scripts/sign-release.py`; keep credentials outside Mill
  tasks, logs, caches and artifacts. See `docs/releasing.md` for Actions secrets.
- Keep tests in `.github/workflows/tests.yml`, separate from APK packaging in
  `android.yml`. Upload single APKs with `archive: false`, without an outer ZIP.
- Integration tests may only mutate newly created `.nfssaf-test-*` directories
  on an explicitly supplied export. Never traverse/delete unrelated test data.
- Do not change the host NFS export policy, ownership, or restart system services
  without discussing the concrete required change with the user.
- Keep timings reproducible: record protocol, path, payload, transport and cache
  conditions. Emulator throughput is not physical-device Wi-Fi throughput.
- Preserve upstream fsx unchanged; adapt the transport in `tests/fsx/bridge.c`
  and instrumentation. Record exclusions and retain the corruption detection test.
- Lookup primary documentation when unsure. Ask the user if specific required
  documentation cannot be found.
- Commit appropriate tested checkpoints (authorized by the user). Do not push.
