#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 3 ]]; then
  echo "Usage: $0 ADB_SERIAL NFS_HOST NFS_EXPORT [test-class]" >&2
  exit 2
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
cd "$(dirname "$0")/.."
./mill core.test + core.contractCheck + native.test + app.androidApk + app.androidTest.androidTestApk
adb -s "$1" install --no-incremental -r out/app/androidApk.dest/app.apk
adb -s "$1" shell pm grant dev.nfssaf android.permission.POST_NOTIFICATIONS
adb -s "$1" install --no-incremental -r out/app/androidTest/androidApk.dest/app.apk
mkdir -p out/reports
args=(-w -r -e nfsHost "$2" -e nfsExport "$3")
if [[ $# -gt 3 ]]; then args+=(-e class "$4"); fi
start_time=$(adb -s "$1" shell date +%s.%N | tr -d '\r')
adb -s "$1" shell am instrument "${args[@]}" \
  dev.nfssaf.test/androidx.test.runner.AndroidJUnitRunner | tee out/reports/instrumentation.txt
adb -s "$1" logcat -d -T "$start_time" -s NfsSafBenchmark:I '*:S' > out/reports/benchmark.txt
adb -s "$1" logcat -d -T "$start_time" -s NfsSafFsx:I '*:S' > out/reports/fsx.txt
# am instrument returns shell success even when JUnit fails.
rg -q '^OK \([0-9]+ tests?\)' out/reports/instrumentation.txt
if rg -q 'FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[234]' out/reports/instrumentation.txt; then exit 1; fi
