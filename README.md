# NFS SAF

An Android Storage Access Framework provider backed by libnfs, with a Kotlin
Jetpack Compose / Material 3 connection manager. Android 9+; ARM64 and x86-64.
Built using **Mill**, CMake and the Android NDK, without Gradle.

Add a connection, set its export path and numeric UID/GID, then tap **Save &
connect**. Allow notifications: the foreground connection service shows a
persistent notification while running. Select the connection in another app's
Android Open/Save picker. **Stop connections** drains operations, closes files,
and disconnects; reopening files requires starting connections again.

NFS 4.2 is the default. NFS 3 and 4.0 are selectable for compatible servers.
UID/GID and supplementary groups use AUTH_SYS; use a trusted network or VPN.
Android uses unprivileged source ports: configure `insecure` for the intended
client on the server export if required, then reload exports. The app explains
this when the server denies a mount. NFSv4 pseudo-root export paths may differ
from the server's local paths. Read-only mode is enforced by the backend.

## Build

Prerequisites: JDK 21 (Mill can provision its JVM), CMake, Ninja, Android SDK
platform 36, build-tools 36.1.0, NDK 29.0.14206865, and platform-tools.

```sh
git submodule update --init --recursive
export ANDROID_HOME="$HOME/Android/Sdk"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  'platforms;android-36' 'build-tools;36.1.0' 'ndk;29.0.14206865' 'platform-tools'
./mill core.test + native.test
./mill app.androidApk
```

APK: `out/app/androidApk.dest/app.apk` (development/debug signing).

```sh
adb install --no-incremental -r out/app/androidApk.dest/app.apk
```

Mill 1.1.9's SDK downloader requests the obsolete `tools` package. The build
resolves installed SDK components directly to avoid that upstream issue.
Native compilation explicitly uses API 28 and 16 KiB ELF segment alignment.
For Android Studio, generate project metadata with `./mill mill.idea/`.

## Tests

Native unit tests exercise partial reads/writes, EOF, errno and path validation.
Kotlin tests cover capability-typed handles, state transitions, bounds, pool
ownership, tree containment and connection diagnostics. To run live native
storage checks, supply an authorized export; only a new `.nfssaf-test-*`
directory is mutated. The optional final argument holds a file idle to test
lease renewal.

```sh
./mill native.host
out/native/host.dest/storage_tests SERVER /EXPORT 42 95
./mill app.androidTest.androidTestApk
```

Android test instructions and measured results are recorded in `docs/testing.md`.
See `docs/architecture.md` for SAF contracts, lifecycle and explicit limitations.

Source: MIT. Bundled libnfs: LGPL-2.1-or-later; see `THIRD_PARTY.md`.
