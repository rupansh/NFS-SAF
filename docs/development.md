# Build and contribute

NFS SAF uses **Mill**, Kotlin, Jetpack Compose Material 3, CMake and libnfs.
There is no Gradle build. Read [the storage contracts](architecture.md) and
[AGENTS.md](../AGENTS.md) before changing the provider or native backend.

## Prerequisites

The current native build scripts target Linux x86-64 hosts. Install JDK 21
(Mill can provision its JVM), Python 3, CMake, Ninja, Clang, and these Android
SDK components:

```sh
git clone --recurse-submodules https://github.com/rupansh/NFS-SAF.git
cd NFS-SAF
export ANDROID_HOME="$HOME/Android/Sdk"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  'platforms;android-36' 'build-tools;36.1.0' 'ndk;29.0.14206865' 'platform-tools' \
  'cmdline-tools;19.0'
```

For an existing clone, run `git submodule update --init --recursive`.
Use the checked-in `./mill` launcher and pinned `.mill-version`.
Mill 1.1.9's SDK downloader requests the obsolete `tools` package, so this
build resolves installed SDK components directly. Android Studio project
metadata can be generated with `./mill mill.idea/`.

## Build APKs

```sh
scripts/build-apks.sh
./mill app.androidTest.androidTestApk
```

Both app variants contain ARM64 and x86-64 native libraries. The debug artifact
is `out/artifacts/debug/nfs-saf-debug-universal.apk`, with `SHA256SUMS` beside it.
Install it with:

```sh
"$ANDROID_HOME/platform-tools/adb" install --no-incremental -r \
  out/artifacts/debug/nfs-saf-debug-universal.apk
```

The release variant disables debuggability, uses D8 release mode and strips
native symbols. Its aligned, unsigned intermediate lives at
`out/release/androidAlignedUnsignedApk.dest/app.aligned.apk`. It is not an
installable distribution: follow [release signing](releasing.md) to create
`out/artifacts/release/nfs-saf-release-universal.apk`.
The build does not currently run R8 code shrinking or obfuscation.

`scripts/verify-apks.py` checks the package ID, actual debuggable flag,
ARM64+x86-64 libraries, absence of the fsx test executable, ZIP/16 KiB ELF alignment and
signing state. Native compilation targets API 28 with 16 KiB ELF alignment.

## Test

Format before committing, then run all three code quality checks:

```sh
./mill quality.spotless
./mill quality.spotless --check + quality.detekt + app.androidLintRun
```

Mill's [Spotless integration](https://mill-build.org/mill/kotlinlib/linting.html)
formats Kotlin with ktfmt 0.53, the Mill build with Scalafmt 3.11.5, native code
with clang-format 23.1.1, and Python with Ruff 0.16.8. ktfmt is pinned to the
version supported by Mill 1.1.9's bundled Spotless. Formatting excludes generated
output and both vendor trees. Python's `venv` and `pip` must be available; Mill
installs the native/Python formatters into its output directory.

Detekt 1.23.8 checks production and test Kotlin with type resolution. The standard
[Android Lint task](https://mill-build.org/mill/android/android-linting.html)
uses SDK command-line tools 19.0 (Lint 8.9.0) and a provisioned Java 21 runtime.
Its project description includes the merged release manifest, app/core sources,
compiled classes, runtime dependencies and AAR lint rules, including Compose.
Warnings fail the check. Reports are in `out/quality/detekt.dest/` and
`out/app/androidLintRun.dest/` (HTML/XML, plus Lint text).

There is no baseline. Keep exception suppressions local and documented: JNI
signatures, resource cleanup, declarative form orchestration, and the fsx wire
dispatcher have constraints that generic style thresholds do not describe.
The `Tests` workflow runs these checks; APK packaging stays independent.

```sh
./mill core.test + core.contractCheck + native.test
python3 scripts/network-tests.py
scripts/sanitizer-test.sh
# After scripts/build-apks.sh, validate signing using a disposable test key:
python3 scripts/test-signing.py
```

These checks run without an external NFS server. For live native, SAF/JNI,
foreground-service, lease-renewal and upstream fsx coverage, follow
[Storage verification](testing.md). Live tests only mutate newly created
`.nfssaf-test-*` directories on an explicitly supplied export.
The fsx executable is packaged only in the instrumentation APK.

## Artwork and screenshots

The original artwork is [docs/logo.svg](logo.svg). Regenerate the adaptive,
Android 13+ themed and README icons after changing it:

```sh
python3 scripts/generate-icons.py
```

The generated Android vector has separate background/foreground layers and
stays inside Android's [adaptive icon safe zone](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).
The README uses `docs/logo-readme.svg`, a rounded, tightly cropped derivative;
the original source is preserved unchanged. README screenshots are actual
Android 16 emulator captures in `docs/screenshots/`. Capture replacements with
`adb exec-out screencap -p`; do not include private server addresses, share names
or files.
