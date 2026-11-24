#!/usr/bin/env bash
# Apply (or check) the frameworks/base patches. Run from the ROM source root:
#   vendor/ltw/rom/apply-patches.sh           # git am the patches onto frameworks/base
#   vendor/ltw/rom/apply-patches.sh --check   # dry run, change nothing
# Safe to run again after `repo sync`: patches that are already applied are skipped.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TOP="$(pwd)"
FWB="$TOP/frameworks/base"
[ -d "$FWB/.git" ] || [ -f "$FWB/.git" ] || { echo "run from the ROM source root (no frameworks/base here)" >&2; exit 1; }
CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

cd "$FWB"
if [ $CHECK = 1 ]; then
    # Later patches build on earlier ones: try them all in a throwaway worktree.
    TMP="$(mktemp -d)"
    trap 'git -C "$FWB" worktree remove --force "$TMP" >/dev/null 2>&1 || true; rm -rf "$TMP"' EXIT
    git worktree add -q --detach "$TMP" HEAD
    cd "$TMP"
fi
APPLIED="$(git log --format=%s -n 300)"
for p in "$HERE"/patches/frameworks_base/*.patch; do
    name="$(basename "$p")"
    subject="$(git mailinfo /dev/null /dev/null < "$p" | sed -n 's/^Subject: //p')"
    if grep -qxF "$subject" <<<"$APPLIED"; then
        echo "= $name (already applied)"
        continue
    fi
    if git -c user.name=ltw -c user.email=ltw@localhost am -q "$p" 2>/dev/null; then
        echo "+ $name"
    else
        git am --abort 2>/dev/null || true
        echo "! $name does NOT apply cleanly to $(git describe --always 2>/dev/null)" >&2
        exit 1
    fi
done
[ $CHECK = 1 ] && echo "check OK: all patches apply (nothing was changed)"
exit 0
