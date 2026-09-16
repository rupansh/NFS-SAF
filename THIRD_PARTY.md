# Third-party software

- libnfs: https://github.com/sahlberg/libnfs, pinned by the Git submodule.
  Library LGPL-2.1-or-later; protocol definitions and generated RPC code BSD.
  Complete corresponding source and license texts are in `vendor/libnfs`.
  The native bridge currently links libnfs statically. This repository includes
  all application/native sources and reproducible build instructions so users
  can modify libnfs, relink, and install their own APK. Distributors must supply
  these sources (including the submodule) with the corresponding binary and
  comply with LGPL relinking requirements.
- AndroidX/Jetpack Compose: Apache-2.0, Google/Android Open Source Project.
- Kotlin and kotlinx.coroutines: Apache-2.0, JetBrains and contributors.
- Mill: MIT, https://github.com/com-lihaoyi/mill. `mill` is its pinned launcher.
- JUnit (tests): EPL-1.0.

RSAF was consulted for architecture and platform gotchas; no RSAF code is
included. RSAF itself is GPL-3.0-only.
