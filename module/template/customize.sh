#!/system/bin/sh
# KernelSU / Magisk install-time script.
#
# Microsoft's APKs are not redistributed with this module. Unless the zip already contains them
# (personal builds do), each one is taken from, in order:
#   1. /sdcard/Download/LinkToWindowsOEM/<name>.apk
#   2. the currently installed version of this module (so updates need no extra steps)
#   DeviceIntegrationService.apk  - com.microsoft.deviceintegrationservice
#   LinktoWindows.apk             - com.microsoft.appmanager, no newer than your Play version
# They are installed unmodified (never re-signed).

SRC=/sdcard/Download/LinkToWindowsOEM
OLD=/data/adb/modules/$(grep_prop id "$MODPATH/module.prop")

# A usable APK is a zip with a manifest and code.
valid_apk() {
    unzip -l "$1" 2>/dev/null | grep -q ' AndroidManifest.xml$' \
        && unzip -l "$1" 2>/dev/null | grep -q ' classes.dex$'
}

place() {
    name="$1"
    pkg="$2"
    dst="$MODPATH/system/priv-app/$name/$name.apk"
    if [ -f "$dst" ]; then
        ui_print "- $name.apk: bundled in the zip"
        return 0
    fi
    for src in "$SRC/$name.apk" "$OLD/system/priv-app/$name/$name.apk"; do
        [ -f "$src" ] || continue
        valid_apk "$src" || { ui_print "! $src is not a valid APK, skipped"; continue; }
        mkdir -p "$MODPATH/system/priv-app/$name"
        cp "$src" "$dst" || abort "! copying $name.apk failed"
        ui_print "- $name.apk ($pkg): from $src"
        return 0
    done
    ui_print "! Missing $SRC/$name.apk ($pkg)"
    ui_print "! Copy it there and install the module again."
    abort "! $name.apk not found"
}

ui_print "- Link to Windows OEM integration (rooted-stock module)"
place DeviceIntegrationService com.microsoft.deviceintegrationservice
place LinktoWindows com.microsoft.appmanager

set_perm_recursive "$MODPATH/system" 0 0 0755 0644
set_perm "$MODPATH/bin/ltw_virtual_keyboard" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755

ui_print "- LtwEmu (the LSPosed/Vector part) is included as a system app."
if [ -x /data/adb/lspd/cli ]; then
    ui_print "  Vector found: LtwEmu is enabled and scoped on the next boot;"
    ui_print "  then reboot once more. The Action button shows the status."
else
    ui_print "  Enable it in LSPosed with scope 'System Framework' +"
    ui_print "  'com.microsoft.deviceintegrationservice', then reboot."
fi
