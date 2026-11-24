#!/usr/bin/env bash
# Assemble the KernelSU/Magisk module zip.
#
# Usage: module/build-module.sh [<DeviceIntegrationService.apk> <LinktoWindows.apk>]
#
#   Without arguments the zip contains NO Microsoft APKs (that is what the GitHub release ships);
#   customize.sh then copies them from /sdcard/Download/LinkToWindowsOEM/ when the module is
#   installed. With both arguments they are bundled into the zip (personal builds).
#
# Prereq: ./gradlew :device-private:deviceprivateJar :broker:assembleRelease :ltwemu:assembleRelease
#         module/virtual_keyboard/build.sh
set -euo pipefail
DIS_APK="${1:-}"
LTW_APK="${2:-}"
if [ -n "$DIS_APK" ] && [ -z "$LTW_APK" ]; then
    echo "give both APKs or none" >&2
    exit 2
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/module"
OUT="$MOD/build"
STAGE="$OUT/stage"
VERSION="${LTW_VERSION:-$(git -C "$ROOT" describe --tags --always 2>/dev/null || echo dev)}"
VERSION_CODE="${LTW_VERSION_CODE:-1}"

rm -rf "$STAGE" && mkdir -p "$STAGE"
cp -r "$MOD/template/." "$STAGE/"
sed -i "s/^version=.*/version=$VERSION/; s/^versionCode=.*/versionCode=$VERSION_CODE/" \
    "$STAGE/module.prop"
mkdir -p "$STAGE/system/framework" \
         "$STAGE/system/priv-app/CrossDeviceServiceBroker" \
         "$STAGE/system/app/LtwEmu" \
         "$STAGE/bin"
cp "$MOD/device-private/build/ltw/com.microsoft.device-private.jar" "$STAGE/system/framework/"
cp "$MOD/virtual_keyboard/build/ltw_virtual_keyboard" "$STAGE/bin/"
cp "$MOD"/broker/build/outputs/apk/release/*.apk \
   "$STAGE/system/priv-app/CrossDeviceServiceBroker/CrossDeviceServiceBroker.apk"
# The LSPosed/Vector module ships as a system app: one zip to install.
cp "$MOD"/ltwemu/build/outputs/apk/release/*.apk "$STAGE/system/app/LtwEmu/LtwEmu.apk"
if [ -n "$DIS_APK" ]; then
    mkdir -p "$STAGE/system/priv-app/DeviceIntegrationService" "$STAGE/system/priv-app/LinktoWindows"
    cp "$DIS_APK" "$STAGE/system/priv-app/DeviceIntegrationService/DeviceIntegrationService.apk"
    cp "$LTW_APK" "$STAGE/system/priv-app/LinktoWindows/LinktoWindows.apk"
    NAME="ltw_oem_module-$VERSION-with-apks.zip"
else
    NAME="ltw_oem_module-$VERSION.zip"
fi

mkdir -p "$OUT"
rm -f "$OUT/$NAME"
PY=$(command -v python3 || command -v python)
(cd "$STAGE" && "$PY" - "$OUT/$NAME" <<'EOF'
import os, sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'w', zipfile.ZIP_DEFLATED) as z:
    for base, dirs, files in os.walk('.'):
        dirs.sort()
        for f in sorted(files):
            p = os.path.join(base, f)
            z.write(p, os.path.relpath(p, '.'))
EOF
)
echo "$OUT/$NAME"
