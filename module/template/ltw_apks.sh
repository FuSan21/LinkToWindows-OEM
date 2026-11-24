#!/system/bin/sh
# Shared by customize.sh and action.sh (sourced): put Microsoft's APKs into the module, unmodified
# (never re-signed). Each one is taken from, in order:
#   1. the copy installed as a normal app (Link to Windows from Play: base.apk + split APKs, which
#      Android loads from a system folder too). Same version as the installed one, so the installed
#      copy simply stays installed as the update of the system copy.
#   2. a previous install of this module (updates)
#   3. /sdcard/Download/LinkToWindowsOEM/<Name>.apk
#   4. a download in /sdcard/Download (deviceintegrationservice, e.g. from an APK mirror)
# Whatever goes into the module becomes a privileged system app, so every new APK is checked by
# LtwEmu's ApkCheck (run with app_process): Android's own signature verifier must accept it, its
# signing certificate must be Microsoft's (SHA-256 pinned below) and its package name must match.
# If the check cannot run, the APK is not used.
#
# Callers set LTW_CHECKER to LtwEmu.apk (it contains ApkCheck).

LTW_SRC=/sdcard/Download/LinkToWindowsOEM
LTW_DOWNLOADS=/sdcard/Download

# Microsoft's signing certificates (apksigner verify --print-certs), unchanged since at least 2023.
ltw_pins() {
    case "$1" in
        # CN=Microsoft Device Experiences for Android, O=Microsoft Corporation
        com.microsoft.deviceintegrationservice)
            echo 7c863c9f63d735c9ca1e8eeba100dd858b3b5bb8c58160e7a66f890d9d1688af ;;
        # CN=Microsoft Corporation Third Party Marketplace, OU=Microsoft Next Screen Lock for Android
        com.microsoft.appmanager)
            echo e843ee3da1195d6afb89caa3ce7427b08fc01fd878a24fa56e962c35c71d5e70 ;;
    esac
}

# ltw_microsoft_apk <apk> <package>: Microsoft's <package>, with a valid Microsoft signature.
ltw_microsoft_apk() {
    [ -f "$1" ] && [ -f "$LTW_CHECKER" ] || return 1
    pins=$(ltw_pins "$2")
    [ -n "$pins" ] || return 1
    # shellcheck disable=SC2086
    CLASSPATH=$LTW_CHECKER app_process /system/bin me.ayra.ltw.emu.ApkCheck "$1" "$2" $pins \
        >/dev/null 2>&1
}

# The base APK of a module's app folder: base.apk (copied split install) or <Name>.apk.
ltw_base_apk() {
    for f in "$1/base.apk" "$1/$2.apk"; do
        [ -f "$f" ] && { echo "$f"; return 0; }
    done
    return 1
}

ltw_has_apk() {
    for f in "$1"/*.apk; do [ -f "$f" ] && return 0; done
    return 1
}

# Newest APK in /sdcard/Download whose name matches <pattern> (case-insensitive regex).
ltw_download() {
    # shellcheck disable=SC2010
    ls -t "$LTW_DOWNLOADS"/*.apk 2>/dev/null | grep -i "$1" | head -1
}

# ltw_provide <dest dir> <Name> <package> [<previous module's dir>] [<download pattern>]
# Returns 0 if the APK is (now) in <dest dir>; LTW_FROM says from where ("" = it already was).
# LTW_REJECTED lists files that were found but are not Microsoft's.
ltw_provide() {
    dest=$1 name=$2 pkg=$3 old=$4 pattern=$5
    LTW_FROM=
    ltw_has_apk "$dest" && return 0
    paths=$(pm path "$pkg" 2>/dev/null | sed -n 's/^package://p')
    # Hide My Applist and similar can hide the package from queries; the files are still there.
    [ -n "$paths" ] || paths=$(ls -d /data/app/*/"$pkg"-*/*.apk 2>/dev/null)
    user=
    if [ -n "$paths" ]; then
        user=1
        for p in $paths; do
            case "$p" in /data/app/*) ;; *) user= ;; esac   # only a normal (user) install
        done
    fi
    if [ -n "$user" ] && ltw_microsoft_apk "$(echo "$paths" | grep '/base.apk$' | head -1)" "$pkg"; then
        mkdir -p "$dest" || return 1
        for p in $paths; do
            cp "$p" "$dest/" || { rm -rf "$dest"; return 1; }
        done
        LTW_FROM="installed $pkg ($(echo "$paths" | wc -l) APK file(s))"
    elif [ -n "$old" ] && ltw_has_apk "$old" \
        && ltw_microsoft_apk "$(ltw_base_apk "$old" "$name")" "$pkg"; then
        mkdir -p "$dest" && cp "$old"/*.apk "$dest/" && LTW_FROM=$old
    else
        for f in "$LTW_SRC/$name.apk" ${pattern:+"$(ltw_download "$pattern")"}; do
            [ -f "$f" ] || continue
            if ltw_microsoft_apk "$f" "$pkg"; then
                mkdir -p "$dest" && cp "$f" "$dest/$name.apk" && LTW_FROM=$f
                break
            fi
            LTW_REJECTED="$LTW_REJECTED $f"
        done
    fi
    [ -n "$LTW_FROM" ] || return 1
    chown -R 0:0 "$dest" && chmod 0755 "$dest" && chmod 0644 "$dest"/*.apk
    chcon -R u:object_r:system_file:s0 "$dest" 2>/dev/null
    return 0
}

# Something was copied in this boot: it takes effect after a reboot.
ltw_mark_reboot() {
    cat /proc/sys/kernel/random/boot_id > "$1/.reboot_pending" 2>/dev/null
}

# A reboot is due: APKs were copied, or Vector enabled LtwEmu, during this boot. Never true again
# after that reboot, so a failure can't cause a reboot loop.
ltw_reboot_pending() {
    boot=$(cat /proc/sys/kernel/random/boot_id)
    for m in "$1/.reboot_pending" "$1/.ltwemu_enabled"; do
        [ -f "$m" ] && [ "$(cat "$m")" = "$boot" ] && return 0
    done
    return 1
}

# ---- requirements (checked by customize.sh, which refuses to install without them) ----

# Enabled modules: "<id> <name>" per line, with a trailing "/zygisk" if the module carries Zygisk
# code itself (a Zygisk user such as Vector, not a provider).
ltw_modules() {
    for d in /data/adb/modules/*/; do
        [ -f "$d/module.prop" ] && [ ! -f "$d/disable" ] && [ ! -f "$d/remove" ] || continue
        id=$(sed -n 's/^id=//p' "$d/module.prop" | head -1)
        name=$(sed -n 's/^name=//p' "$d/module.prop" | head -1)
        meta=$(sed -n 's/^metamodule=//p' "$d/module.prop" | head -1 | tr -d ' ')
        z=
        [ -d "$d/zygisk" ] && z=/zygisk
        echo "$id $name meta=$meta$z"
    done
}

# Root manager family: apatch, ksu (KernelSU, KernelSU Next, SukiSU...), magisk (incl. forks such as
# Kitsune/Delta and Alpha). APatch also sets KSU=true for compatibility, so it is checked first.
ltw_manager() {
    if [ "$APATCH" = true ] || [ -d /data/adb/ap ]; then echo apatch
    elif [ "$KSU" = true ] || [ -d /data/adb/ksu ]; then echo ksu
    elif command -v magisk >/dev/null 2>&1; then echo magisk
    else echo unknown
    fi
}

# Evidence that modules' system/ files really get mounted: a file of another enabled module shows
# up, identical, at its /system path. Covers managers that mount by themselves.
ltw_mount_works() {
    for d in /data/adb/modules/*/system; do
        m=${d%/system}
        [ "${m##*/}" != ltw_oem_emulation ] && [ ! -f "$m/disable" ] && [ ! -f "$m/remove" ]             || continue
        f=$(find "$d" -type f 2>/dev/null | head -1)
        [ -n "$f" ] && cmp -s "$f" "${f#"$m"}" && return 0
    done
    return 1
}

# Module system/ files get mounted. Magisk and APatch do it themselves. KernelSU does it through a
# metamodule: any module declaring metamodule=1 or =true (KernelSU docs), e.g. meta-overlayfs,
# meta-magicmount, mountify-rs; Mountify and meta-* are also accepted by id. Older KernelSU builds
# and some forks mount by themselves: accepted when it demonstrably works.
ltw_has_mount() {
    case $(ltw_manager) in
        magisk|apatch) return 0 ;;
    esac
    ltw_modules | grep -qiE 'meta=(1|true)|^(mountify|meta-)' || ltw_mount_works
}

# Zygisk: built into Magisk and its forks (same setting); on KernelSU and APatch a provider module
# (ReZygisk, NeoZygisk, Zygisk Next...). Providers don't ship a zygisk/ folder; Zygisk users do.
ltw_has_zygisk() {
    if [ "$(ltw_manager)" = magisk ]         && magisk --sqlite "SELECT value FROM settings WHERE key='zygisk'" 2>/dev/null             | grep -q 'value=1'; then
        return 0
    fi
    ltw_modules | grep -v '/zygisk$' | grep -qi 'zygisk'
}

# LSPosed / Vector (both keep their data in /data/adb/lspd).
ltw_has_xposed() {
    [ -d /data/adb/lspd ] && ltw_modules | grep -qiE 'lsposed|vector'
}

# Prints what is missing, one per line; nothing if all is there.
ltw_missing_requirements() {
    ltw_has_mount || echo "module mounting: on KernelSU a metamodule (any module with metamodule=1), e.g. meta-overlayfs, meta-magicmount, Mountify, mountify-rs"
    ltw_has_zygisk || echo "Zygisk: Magisk's own (enable it), or ReZygisk / NeoZygisk / Zygisk Next on KernelSU and APatch"
    ltw_has_xposed || echo "Vector (or LSPosed): it runs LtwEmu, the system_server part"
}
