# Third-party software

- libnfs: https://github.com/sahlberg/libnfs, pinned by the Git submodule.
  Library LGPL-2.1-or-later; protocol definitions and generated RPC code BSD.
  Complete corresponding source and license texts are in `vendor/libnfs`.
  Local generated-source corrections are in `native/libnfs-fixes.cmake`.
  The native bridge currently links libnfs statically. This repository includes
  all application/native sources and reproducible build instructions so users
  can modify libnfs, relink, and install their own APK. Distributors must supply
  these sources (including the submodule) with the corresponding binary and
  comply with LGPL relinking requirements.
- AndroidX/Jetpack Compose: Apache-2.0, Google/Android Open Source Project.
- Kotlin and kotlinx.coroutines: Apache-2.0, JetBrains and contributors.
- Mill: MIT, https://github.com/com-lihaoyi/mill. `mill` is its pinned launcher.
- JUnit (tests): EPL-1.0.
- FreeBSD fsx (test APK only): APSL-2.0, Apple and contributors. Unmodified
  source, pinned provenance and license are in `tests/fsx/vendor` and
  `tests/fsx/README.md`. It is not included in the application APK.

RSAF was consulted for architecture and platform gotchas; no RSAF code is
included. RSAF itself is GPL-3.0-only.
Material Files was inspected for SAF query compatibility; no Material Files
code is included. Its resolver behavior is reproduced by original tests.
