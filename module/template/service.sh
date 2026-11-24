#!/system/bin/sh
# KernelSU late_start: run vendor.virtual_keyboard (emulates OnePlus /system/bin/virtual_keyboard,
# which init starts on the OEM build). Only deviceintegrationservice's UID may attach.
MODDIR=${0%/*}
BIN=$MODDIR/bin/ltw_virtual_keyboard
chmod 0755 "$BIN"
(
  until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
  while true; do
    DIS_UID=$(stat -c %u /data/data/com.microsoft.deviceintegrationservice 2>/dev/null)
    if [ -n "$DIS_UID" ]; then
      "$BIN" "$DIS_UID"
      log -t ltw_virtual_keyboard "daemon exited ($?), restarting"
    fi
    sleep 5
  done
) </dev/null >/dev/null 2>&1 &
