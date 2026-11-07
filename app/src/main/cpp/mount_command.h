#pragma once
#include <string.h>

/* Toybox's mount listing format. Keep exact fields and path boundaries in
 * sync with MountViews.java; never evaluate the caller's command text. */
#define MOUNT_FILTER "/system/bin/awk '" \
    "function hidden(s) { return s ~ \"^(magisk|lsposed|lspd|zygisk)$\" || " \
    "s ~ \"^(/data/adb/(magisk|lspd|lsposed)|/data/adb/modules/zygisk_lsposed|/adb/modules/zygisk_lsposed|/(sbin|debug_ramdisk|dev)/[.]magisk)(/|$)\" || " \
    "s ~ \"^(/debug_ramdisk/(magisk|magisk32|magisk64|su)|/sbin/(magisk|su)|/system/bin/(magisk|magiskpolicy|su)|/system/xbin/su)$\" } " \
    "!(NF >= 6 && $2 == \"on\" && $4 == \"type\" && $6 ~ /^[(]/ && $0 ~ /[)]$/ && (hidden($1) || hidden($3)))'"

/* Preserve the original wait status for a failing mount. Old/vendor shells
 * without pipefail, and devices without awk, execute the original command. */
#define MOUNT_LIST(cmd) \
    "if ! (set -o pipefail) 2>/dev/null || [ ! -x /system/bin/awk ]; then exec " cmd "; fi; " \
    "set -o pipefail; " cmd " | " MOUNT_FILTER

static inline const char *mount_replacement(const char *command) {
    if (!command) return NULL;
    if (!strcmp(command, "mount")) {
        return "if [ \"$(command -v mount)\" != /system/bin/mount ]; then exec mount; fi; "
                MOUNT_LIST("mount");
    }
    if (!strcmp(command, "/system/bin/mount")) return MOUNT_LIST("/system/bin/mount");
    return NULL;
}

#undef MOUNT_LIST
#undef MOUNT_FILTER
