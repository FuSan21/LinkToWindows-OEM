#!/system/bin/sh
# KernelSU / Magisk install-time script.
#
# Microsoft's APKs are not redistributed with this module. If the zip does not already contain
# them (personal builds do), copy them from /sdcard/Download/LinkToWindowsOEM/:
#   DeviceIntegrationService.apk  - com.microsoft.deviceintegrationservice
#   LinktoWindows.apk             - com.microsoft.appmanager, no newer than your Play version
# They are installed unmodified (never re-signed).

SRC=/sdcard/Download/LinkToWindowsOEM

place() {
    name="$1"
    pkg="$2"
    dst="$MODPATH/system/priv-app/$name/$name.apk"
    if [ -f "$dst" ]; then
        ui_print "- $name.apk: bundled in the zip"
        return 0
    fi
    if [ ! -f "$SRC/$name.apk" ]; then
        ui_print "! Missing $SRC/$name.apk ($pkg)"
        ui_print "! Copy it there and install the module again."
        abort "! $name.apk not found"
    fi
    mkdir -p "$MODPATH/system/priv-app/$name"
    cp "$SRC/$name.apk" "$dst" || abort "! copying $name.apk failed"
    ui_print "- $name.apk: copied from $SRC"
}

ui_print "- Link to Windows OEM integration (rooted-stock module)"
place DeviceIntegrationService com.microsoft.deviceintegrationservice
place LinktoWindows com.microsoft.appmanager

set_perm_recursive "$MODPATH/system" 0 0 0755 0644
set_perm "$MODPATH/bin/ltw_virtual_keyboard" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755

ui_print "- Also install LtwEmu.apk, enable it in LSPosed/Vector with scope"
ui_print "  'System Framework' + 'com.microsoft.deviceintegrationservice', then reboot."
