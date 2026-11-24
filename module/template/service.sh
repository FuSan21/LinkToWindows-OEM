#!/system/bin/sh
# KernelSU / Magisk late_start.
MODDIR=${0%/*}
BIN=$MODDIR/bin/ltw_virtual_keyboard
DIS=com.microsoft.deviceintegrationservice
EMU=me.ayra.ltw.emu
LSPD_CLI=/data/adb/lspd/cli
chmod 0755 "$BIN"

# KernelSU Next can start the service stage twice in one boot; run once per boot (/dev is tmpfs).
mkdir /dev/ltw_oem_service 2>/dev/null || exit 0

# SELinux: sepolicy.rule covers priv_app and priv_app_36. Android 17+ maps priv-apps to
# per-targetSdk domains through seapp_contexts (e.g. targetSdk >= 37 -> priv_app, below ->
# priv_app_36). A newer platform may define more such domains: allow those too (deviceintegration
# service must find cross_device_service). Only domains named in seapp_contexts, i.e. types that
# exist: patching a type that does not exist fails, and on some 4.x kernels (KernelSU Next 3.3,
# DerpFest nabu 4.14) that failure hangs the whole device.
dis_domain_rule() {
    for f in /system/etc/selinux/plat_seapp_contexts /system_ext/etc/selinux/system_ext_seapp_contexts \
        /product/etc/selinux/product_seapp_contexts /vendor/etc/selinux/vendor_seapp_contexts; do
        [ -f "$f" ] && grep 'isPrivApp=true' "$f"
    done | sed -n 's/.*domain=\(priv_app_[0-9]*\).*/\1/p' | sort -u | while read -r domain; do
        [ "$domain" = priv_app_36 ] && continue
        rule="allow $domain default_android_service service_manager find"
        if command -v ksud >/dev/null 2>&1; then
            ksud sepolicy patch "$rule"
        elif command -v magiskpolicy >/dev/null 2>&1; then
            magiskpolicy --live "$rule"
        fi
    done
}

# Vector: enable LtwEmu (shipped as a system app) and set its scope once. Hooks load on the next
# boot; the marker holds this boot's id, so action.sh knows a reboot is due.
enable_ltwemu() {
    [ -x "$LSPD_CLI" ] && [ ! -f "$MODDIR/.ltwemu_enabled" ] || return 0
    # Already enabled (e.g. after a module update): nothing takes effect later, no reboot is due.
    if "$LSPD_CLI" modules ls 2>/dev/null | grep "$EMU" | grep -q enabled; then
        echo already > "$MODDIR/.ltwemu_enabled"
        return 0
    fi
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
