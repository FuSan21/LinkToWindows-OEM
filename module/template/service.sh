#!/system/bin/sh
# KernelSU / Magisk late_start.
MODDIR=${0%/*}
BIN=$MODDIR/bin/ltw_virtual_keyboard
DIS=com.microsoft.deviceintegrationservice
EMU=me.ayra.ltw.emu
LSPD_CLI=/data/adb/lspd/cli
chmod 0755 "$BIN"

# SELinux: sepolicy.rule covers priv_app and priv_app_36. Android 17 runs priv-apps in
# priv_app_<targetSdk>, so if a newer deviceintegrationservice targets a newer SDK, allow that
# domain too (it must find cross_device_service).
dis_domain_rule() {
    sdk=$(dumpsys package "$DIS" 2>/dev/null | sed -n 's/.*targetSdk=\([0-9]*\).*/\1/p' | head -1)
    [ -n "$sdk" ] && [ "$sdk" != 36 ] || return 0
    rule="allow priv_app_$sdk default_android_service service_manager find"
    if command -v ksud >/dev/null 2>&1; then
        ksud sepolicy patch "$rule"
    elif command -v magiskpolicy >/dev/null 2>&1; then
        magiskpolicy --live "$rule"
    fi
}

# Vector: enable LtwEmu (shipped as a system app) and set its scope once. Hooks load on the next
# boot; the marker holds this boot's id, so action.sh knows a reboot is due.
enable_ltwemu() {
    [ -x "$LSPD_CLI" ] && [ ! -f "$MODDIR/.ltwemu_enabled" ] || return 0
    "$LSPD_CLI" modules enable "$EMU" \
        && "$LSPD_CLI" scope set "$EMU" system/0 "$DIS/0" \
        && cat /proc/sys/kernel/random/boot_id > "$MODDIR/.ltwemu_enabled" \
        && log -t ltw_module "LtwEmu enabled in Vector; reboot once more"
}

(
  until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
  dis_domain_rule
  enable_ltwemu
  # vendor.virtual_keyboard (emulates OnePlus /system/bin/virtual_keyboard, which init starts on
  # the OEM build). Only deviceintegrationservice's UID may attach.
  while true; do
    DIS_UID=$(stat -c %u /data/data/$DIS 2>/dev/null)
    if [ -n "$DIS_UID" ]; then
      "$BIN" "$DIS_UID"
      log -t ltw_virtual_keyboard "daemon exited ($?), restarting"
    fi
    sleep 5
  done
) </dev/null >/dev/null 2>&1 &
