#!/usr/bin/env bash
# Installs or updates the posedmcp-guard Magisk module on a connected device.
#
# Not a Magisk zip, and not pretending to be one: a zip is an installer for an
# installer, and all it would add here is a second copy of files that already
# live in this repository. Copying the directory into /data/adb/modules is what
# Magisk ends up doing with a zip anyway.
set -euo pipefail

# Git Bash rewrites anything that looks like a path into a Windows one, and
# every path this script handles is on the device - /data/local/tmp/ becomes
# C:/Program Files/Git/data/local/tmp/ and the push fails, or lands under a
# mangled name.
export MSYS_NO_PATHCONV=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# adb is a native Windows binary here and cannot resolve an MSYS path such as
# /e/posedmcp, so the source side is converted too. On a Unix host pwd -W does
# not exist and the path is already correct.
REPO_ROOT_WIN="$(cd "${REPO_ROOT}" && { pwd -W 2>/dev/null || pwd; })"
SRC="${REPO_ROOT_WIN}/magisk/posedmcp-guard"
DEST=/data/adb/modules/posedmcp_guard
FILES="module.prop guard.sh post-fs-data.sh service.sh"

if [[ ! -f "${SRC}/module.prop" ]]; then
    echo "module source not found at ${SRC}" >&2
    exit 1
fi

if ! adb shell 'su -c id' >/dev/null 2>&1; then
    echo "need a connected device with working root (adb shell su -c id)" >&2
    exit 1
fi

adb shell "su -c 'mkdir -p ${DEST}'"
for f in ${FILES}; do
    adb push "${SRC}/${f}" "/data/local/tmp/${f}" >/dev/null
    adb shell "su -c 'cp -f /data/local/tmp/${f} ${DEST}/${f} && rm -f /data/local/tmp/${f}'"
done
adb shell "su -c 'chmod 0755 ${DEST}/*.sh; chmod 0644 ${DEST}/module.prop'"
adb shell "su -c 'ls -l ${DEST}'"

cat <<'EOF'

Installed; it does its work from the next boot onward.

To arm it now, without rebooting, so it can be tried out:

    adb shell su -c 'sh /data/adb/modules/posedmcp_guard/post-fs-data.sh'
    adb shell su -c 'sh /data/adb/modules/posedmcp_guard/service.sh'

Its state - the counters, what it did, and any library it moved aside:

    adb shell su -c 'ls -l /data/adb/posedmcp-guard /data/adb/posedmcp-guard/backup'

To remove it, and take the app out of the suspended state it may have set:

    adb shell su -c 'rm -rf /data/adb/modules/posedmcp_guard'
    adb shell su -c 'setprop persist.posedmcp.guard off'
    adb shell su -c 'setprop persist.posedmcp.no_system_hooks off'
    adb shell su -c 'rm -f /sdcard/Android/media/dev.posedmcp/NO-SYSTEM-HOOKS'
EOF
