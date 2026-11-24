#!/usr/bin/env bash
# NDK build of the SAME source the ROM build uses (rom/virtual_keyboard).
# Needs an Android NDK (r25+): $ANDROID_NDK_HOME, or the newest one under $ANDROID_HOME/ndk.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-$HOME}/Android/Sdk}}"
NDK="${ANDROID_NDK_HOME:-$(printf '%s
' "$SDK"/ndk/* | sort -V | tail -1)}"
[ -d "$NDK" ] || { echo "Android NDK not found; set ANDROID_NDK_HOME" >&2; exit 1; }
CXX=
for f in "$NDK"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android31-clang++*; do CXX=$f; break; done
[ -e "$CXX" ] || { echo "aarch64 clang++ not found in $NDK" >&2; exit 1; }
OUT="$ROOT/module/virtual_keyboard/build"
mkdir -p "$OUT"
"$CXX" -std=c++17 -O2 -Wall -Wextra -Werror -fPIE -pie -static-libstdc++ -s \
    -o "$OUT/ltw_virtual_keyboard" "$ROOT/rom/virtual_keyboard/ltw_virtual_keyboard.cpp" \
    -lbinder_ndk -llog -ldl
echo "$OUT/ltw_virtual_keyboard"
