#!/usr/bin/env bash
# NDK build of the SAME source the ROM build uses (rom/virtual_keyboard).
# Needs an Android NDK (r25+): $ANDROID_NDK_HOME, or the newest one under $ANDROID_HOME/ndk.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-$HOME}/Android/Sdk}}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "$NDK" ] || { echo "Android NDK not found; set ANDROID_NDK_HOME" >&2; exit 1; }
CXX=$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android31-clang++* | head -1)
OUT="$ROOT/module/virtual_keyboard/build"
mkdir -p "$OUT"
"$CXX" -std=c++17 -O2 -Wall -Wextra -Werror -fPIE -pie -static-libstdc++ -s \
    -o "$OUT/ltw_virtual_keyboard" "$ROOT/rom/virtual_keyboard/ltw_virtual_keyboard.cpp" \
    -lbinder_ndk -llog -ldl
echo "$OUT/ltw_virtual_keyboard"
