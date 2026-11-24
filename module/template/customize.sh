#!/system/bin/sh
# KernelSU / Magisk install-time script.
#
# Microsoft's APKs are not redistributed with this module. Personal builds may bundle them;
# otherwise they are taken from /sdcard/Download/LinkToWindowsOEM/, the previous install of this
# module, or the copy installed as a normal app (see ltw_apks.sh). A missing one does not stop the
# install: the Action button finishes the setup later (it opens Play for Link to Windows).

. "$MODPATH/ltw_apks.sh"
LTW_CHECKER=$MODPATH/system/app/LtwEmu/LtwEmu.apk

# Without these the module would install and silently do nothing.
MISSING_REQ=$(ltw_missing_requirements)
if [ -n "$MISSING_REQ" ]; then
    ui_print "! This module needs, and could not find:"
    echo "$MISSING_REQ" | while read -r line; do ui_print "!   - $line"; done
    abort "! Install and enable them, reboot, then install this module again."
fi
OLD=/data/adb/modules/$(grep_prop id "$MODPATH/module.prop")/system/priv-app

place() {
    name=$1 pkg=$2 pattern=$3
    LTW_REJECTED=
    if ltw_provide "$MODPATH/system/priv-app/$name" "$name" "$pkg" "$OLD/$name" "$pattern"; then
        ui_print "- $name ($pkg): ${LTW_FROM:-bundled in the zip}"
    else
        MISSING="$MISSING $name"
        ui_print "! $name ($pkg): not found yet"
    fi
    for f in $LTW_REJECTED; do
        ui_print "! $f: not Microsoft's $pkg, ignored"
    done
}

ui_print "- Link to Windows OEM integration (rooted-stock module)"
MISSING=
place DeviceIntegrationService com.microsoft.deviceintegrationservice 'device.*integration.*service'
place LinktoWindows com.microsoft.appmanager

set_perm_recursive "$MODPATH/system" 0 0 0755 0644
set_perm "$MODPATH/bin/ltw_virtual_keyboard" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755

if [ -n "$MISSING" ]; then
    ui_print "! After the reboot, tap this module's Action button to finish the"
    ui_print "  setup:$MISSING (it opens the download pages)."
fi
ui_print "- LtwEmu (the LSPosed/Vector part) is included as a system app."
if [ -x /data/adb/lspd/cli ]; then
    ui_print "  Vector found: LtwEmu is enabled and scoped on the next boot;"
    ui_print "  then tap the Action button: it reboots once more for you."
else
    ui_print "  Enable it in LSPosed with scope 'System Framework' +"
    ui_print "  'com.microsoft.deviceintegrationservice', then reboot."
fi
