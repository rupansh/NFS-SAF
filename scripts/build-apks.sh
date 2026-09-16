#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
cd "$(dirname "$0")/.."
./mill app.androidApk + release.androidAlignedUnsignedApk
python3 scripts/verify-apks.py \
  --debug out/app/androidApk.dest/app.apk \
  --release out/release/androidAlignedUnsignedApk.dest/app.aligned.apk
mkdir -p out/artifacts/debug
cp out/app/androidApk.dest/app.apk out/artifacts/debug/nfs-saf-debug-universal.apk
(
  cd out/artifacts/debug
  sha256sum nfs-saf-debug-universal.apk > SHA256SUMS
)
echo 'Debug APK: out/artifacts/debug/nfs-saf-debug-universal.apk'
echo 'Release compiled. Run scripts/sign-release.py with the signing environment to create the signed release artifact.'
