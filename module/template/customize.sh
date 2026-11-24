#!/system/bin/sh
# KernelSU / Magisk install-time script.
#
# Microsoft's APKs are not redistributed with this module. Personal builds may bundle them;
# otherwise they are taken from /sdcard/Download/LinkToWindowsOEM/, the previous install of this
# module, or the copy installed as a normal app (see ltw_apks.sh). A missing one does not stop the
# install: the Action button finishes the setup later (it opens Play for Link to Windows).

. "$MODPATH/ltw_apks.sh"
OLD=/data/adb/modules/$(grep_prop id "$MODPATH/module.prop")/system/priv-app

place() {
    name=$1 pkg=$2
    if ltw_provide "$MODPATH/system/priv-app/$name" "$name" "$pkg" "$OLD/$name"; then
        ui_print "- $name ($pkg): ${LTW_FROM:-bundled in the zip}"
    else
        MISSING="$MISSING $name"
        ui_print "! $name ($pkg): not found yet"
    fi
}

ui_print "- Link to Windows OEM integration (rooted-stock module)"
MISSING=
place DeviceIntegrationService com.microsoft.deviceintegrationservice
place LinktoWindows com.microsoft.appmanager

set_perm_recursive "$MODPATH/system" 0 0 0755 0644
set_perm "$MODPATH/bin/ltw_virtual_keyboard" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755

if [ -n "$MISSING" ]; then
    ui_print "! After the reboot, tap this module's Action button to finish the"
    ui_print "  setup:$MISSING (it opens Play for Link to Windows)."
fi
ui_print "- LtwEmu (the LSPosed/Vector part) is included as a system app."
if [ -x /data/adb/lspd/cli ]; then
    ui_print "  Vector found: LtwEmu is enabled and scoped on the next boot;"
    ui_print "  then reboot once more. The Action button shows the status."
else
    ui_print "  Enable it in LSPosed with scope 'System Framework' +"
    ui_print "  'com.microsoft.deviceintegrationservice', then reboot."
fi
