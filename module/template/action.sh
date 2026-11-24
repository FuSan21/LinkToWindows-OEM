#!/system/bin/sh
# KernelSU / Magisk "Action" button. First finishes the setup (Microsoft's APKs, see ltw_apks.sh),
# one step per tap, then checks every piece the integration needs.
MODDIR=${0%/*}
DIS=com.microsoft.deviceintegrationservice
LTW=com.microsoft.appmanager
BROKER=com.microsoftsdk.crossdeviceservicebroker
EMU=me.ayra.ltw.emu
FAIL=0

. "$MODDIR/ltw_apks.sh"
LTW_CHECKER=$MODDIR/system/app/LtwEmu/LtwEmu.apk

ok()  { echo "[ OK ] $*"; }
bad() { echo "[FAIL] $*"; FAIL=1; }
todo() { echo "[TODO] $*"; TODO=1; }

countdown_reboot() {
    echo
    for i in 10 9 8 7 6 5 4 3 2 1; do
        echo "Rebooting in $i s..."
        sleep 1
    done
    svc power reboot 2>/dev/null || reboot
}

version() {
    dumpsys package "$1" 2>/dev/null | sed -n 's/^ *versionName=//p' | head -1
}

echo "Link to Windows OEM integration $(sed -n 's/^version=//p' "$MODDIR/module.prop")"
echo

# ---- setup: Microsoft's APKs ----
TODO=0
COPIED=0
DIS_URL=https://www.apkmirror.com/apk/microsoft-corporation/device-integration-service/
OPENED=
open_url() {
    [ -z "$OPENED" ] || return 0   # one page per tap
    OPENED=1
    am start -a android.intent.action.VIEW -d "$1" >/dev/null 2>&1         || { [ -n "$2" ] && am start -a android.intent.action.VIEW -d "$2" >/dev/null 2>&1; }
}
setup() {
    name=$1 pkg=$2 pattern=$3
    LTW_REJECTED=
    ltw_provide "$MODDIR/system/priv-app/$name" "$name" "$pkg" "" "$pattern"
    rc=$?
    for f in $LTW_REJECTED; do
        echo "[WARN] $f is not Microsoft's $pkg (signature check failed), ignored."
    done
    [ $rc = 0 ] || return 1
    if [ -n "$LTW_FROM" ]; then
        echo "[ NEW] $name: copied from $LTW_FROM"
        COPIED=1
    fi
    return 0
}
if ! setup DeviceIntegrationService "$DIS" 'device.*integration.*service'; then
    todo "Download Device Integration Service (APK file, not the bundle) to Download/;"
    echo "       its download page is opening now. Then tap Action again: the APK is"
    echo "       found there and checked for Microsoft's certificate."
    open_url "$DIS_URL"
fi
if ! setup LinktoWindows "$LTW"; then
    todo "Install Link to Windows from Play, then tap Action again."
    open_url "market://details?id=$LTW" "https://play.google.com/store/apps/details?id=$LTW"
fi
[ $COPIED = 1 ] && ltw_mark_reboot "$MODDIR"
if [ $TODO = 1 ]; then
    exit 0
fi
if ltw_reboot_pending "$MODDIR"; then
    echo
    echo "Setup step done; it takes effect after a reboot. Tap Action again afterwards"
    echo "to continue or to check everything."
    countdown_reboot
    exit 0
fi

MISSING_REQ=$(ltw_missing_requirements)
if [ -n "$MISSING_REQ" ]; then
    echo "$MISSING_REQ" | while read -r line; do bad "missing: $line"; done
    FAIL=1
else
    ok "metamodule, Zygisk and Vector/LSPosed present"
fi
if dumpsys package "$DIS" 2>/dev/null | grep -q "codePath=/system/priv-app/DeviceIntegrationService"; then
    ok "deviceintegrationservice $(version "$DIS") is a priv-app"
else
    bad "deviceintegrationservice has no /system/priv-app copy"
fi
if dumpsys package "$LTW" 2>/dev/null | grep -q "codePath=/system/priv-app/LinktoWindows"; then
    ok "Link to Windows $(version "$LTW") is a priv-app (Play updates go on top)"
else
    bad "Link to Windows has no /system/priv-app copy"
fi
if [ "$(pm path "$BROKER" 2>/dev/null | wc -l)" = 1 ]; then
    ok "broker $(version "$BROKER") installed once"
else
    bad "broker missing or installed more than once"
fi

if service check cross_device_service 2>/dev/null | grep -q ': found'; then
    ok "cross_device_service registered (LtwEmu active in system_server)"
else
    bad "cross_device_service not found: LtwEmu is not active"
    if [ -x /data/adb/lspd/cli ]; then
        echo "       Vector: $(/data/adb/lspd/cli modules ls 2>/dev/null | grep "$EMU" || echo "$EMU not listed")"
    else
        echo "       Enable LtwEmu in LSPosed (scope: System Framework + $DIS) and reboot."
    fi
fi

if pidof ltw_virtual_keyboard >/dev/null; then
    ok "vendor.virtual_keyboard running (PC typing)"
else
    bad "vendor.virtual_keyboard not running (starts once deviceintegrationservice has data)"
fi

pid=$(pidof "$DIS")
if [ -n "$pid" ]; then
    ok "deviceintegrationservice running as $(cut -d: -f3 /proc/"$pid"/attr/current 2>/dev/null)"
fi

echo
[ $FAIL = 0 ] && echo "All good." || echo 'Some checks failed; see "Check that it is active" in the README.'
