#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 0 && $# != 2 ]]; then
  echo "Usage: $0 [NFS_HOST NFS_EXPORT]" >&2
  exit 2
fi
cd "$(dirname "$0")/.."
cmake -S native -B out/sanitizers -G Ninja \
  -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
  -DCMAKE_BUILD_TYPE=Debug \
  '-DCMAKE_C_FLAGS=-fsanitize=address,undefined -fno-omit-frame-pointer' \
  '-DCMAKE_CXX_FLAGS=-fsanitize=address,undefined -fno-omit-frame-pointer'
cmake --build out/sanitizers -j 8
args=()
if [[ $# == 2 ]]; then args=("$1" "$2" 42); fi
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1 \
  out/sanitizers/storage_tests "${args[@]}"
