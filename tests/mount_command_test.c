#include <assert.h>
#include <stdio.h>
#include "../app/src/main/cpp/mount_command.h"

int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "--emit")) {
        puts(mount_replacement("/system/bin/mount"));
        return 0;
    }
    const char *bare = mount_replacement("mount");
    const char *absolute = mount_replacement("/system/bin/mount");
    assert(bare && absolute);
    assert(strstr(bare, "command -v mount"));
    assert(!mount_replacement(NULL));
    assert(!mount_replacement(""));
    assert(!mount_replacement("mount -o remount,rw /system"));
    assert(!mount_replacement("/vendor/bin/mount"));
    assert(!mount_replacement("mount | cat"));
    assert(!mount_replacement("mount\n"));
    assert(!mount_replacement("echo mount"));
    puts("PASS native mount command selection (9 assertions)");
    return 0;
}
