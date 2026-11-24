#!/system/bin/sh
# KernelSU / Magisk "Action" button: health check of every piece the integration needs.
MODDIR=${0%/*}
DIS=com.microsoft.deviceintegrationservice
LTW=com.microsoft.appmanager
BROKER=com.microsoftsdk.crossdeviceservicebroker
EMU=me.ayra.ltw.emu
FAIL=0

ok()  { echo "[ OK ] $*"; }
bad() { echo "[FAIL] $*"; FAIL=1; }

version() {
    dumpsys package "$1" 2>/dev/null | sed -n 's/^ *versionName=//p' | head -1
}

echo "Link to Windows OEM integration $(sed -n 's/^version=//p' "$MODDIR/module.prop")"
echo

if dumpsys package "$DIS" 2>/dev/null | grep -q "codePath=/system/priv-app/DeviceIntegrationService"; then
    ok "deviceintegrationservice $(version "$DIS") is a priv-app"
else
    bad "deviceintegrationservice is not running from /system/priv-app (uninstall the /data/app copy)"
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
        [ -f "$MODDIR/.ltwemu_enabled" ] && echo "       LtwEmu was enabled automatically: reboot once more."
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
