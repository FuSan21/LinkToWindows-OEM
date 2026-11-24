#!/system/bin/sh
# Shared by customize.sh and action.sh (sourced): put Microsoft's APKs into the module, unmodified
# (never re-signed). Each one is taken from, in order:
#   1. /sdcard/Download/LinkToWindowsOEM/<Name>.apk
#   2. a previous install of this module (updates)
#   3. the copy installed as a normal app (Link to Windows from Play: base.apk + split APKs, which
#      Android loads from a system folder too). Same version as the installed one, so the Play
#      copy simply stays installed as the update of the system copy.

LTW_SRC=/sdcard/Download/LinkToWindowsOEM

# A usable APK is a zip with a manifest.
ltw_valid_apk() {
    unzip -l "$1" 2>/dev/null | grep -q ' AndroidManifest.xml$'
}

ltw_has_apk() {
    for f in "$1"/*.apk; do [ -f "$f" ] && return 0; done
    return 1
}

# ltw_provide <dest dir> <Name> <package> [<previous module's dir>]
# Returns 0 if the APK is (now) in <dest dir>; LTW_FROM says from where ("" = it already was).
ltw_provide() {
    dest=$1 name=$2 pkg=$3 old=$4
    LTW_FROM=
    ltw_has_apk "$dest" && return 0
    if [ -f "$LTW_SRC/$name.apk" ] && ltw_valid_apk "$LTW_SRC/$name.apk"; then
        mkdir -p "$dest" && cp "$LTW_SRC/$name.apk" "$dest/" && LTW_FROM=$LTW_SRC/$name.apk
    elif [ -n "$old" ] && ltw_has_apk "$old"; then
        mkdir -p "$dest" && cp "$old"/*.apk "$dest/" && LTW_FROM=$old
    else
        paths=$(pm path "$pkg" 2>/dev/null | sed -n 's/^package://p')
        [ -n "$paths" ] || return 1
        for p in $paths; do
            case "$p" in /data/app/*) ;; *) return 1 ;; esac   # only a normal (user) install
        done
        mkdir -p "$dest" || return 1
        for p in $paths; do
            cp "$p" "$dest/" || { rm -rf "$dest"; return 1; }
        done
        LTW_FROM="installed $pkg ($(echo "$paths" | wc -l) APK file(s))"
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

ltw_reboot_pending() {
    [ -f "$1/.reboot_pending" ] \
        && [ "$(cat "$1/.reboot_pending")" = "$(cat /proc/sys/kernel/random/boot_id)" ]
}
