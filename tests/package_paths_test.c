#include <assert.h>
#include <stdio.h>
#include "../app/src/main/cpp/package_paths.h"
int main(void) {
    const char names[][256] = {"dev.posedmcp", "dev.display", "dev.deploy"};
    const char *hidden[] = {"/data/data/dev.posedmcp", "/data/user/0/dev.deploy/files",
        "/data/user_de/10/dev.display", "/storage/emulated/0/Android/media/dev.posedmcp",
        "/storage/emulated/10/Android/obb/dev.deploy/file", "/sdcard/Android/data/dev.display",
        "/data//data/./dev.posedmcp/child", "/data/data/normal/../dev.deploy",
        "/system/framework/XposedBridge.jar", "/system/framework/./XposedBridge.jar",
        "/system/xposed.prop", "/system/lib64/libxposed_art.so"};
    const char *visible[] = {"/data/data/dev.posedmcp.fixture", "/data/data/normal.app",
        "/data/data/dev.deploy/../normal.app", "/storage/emulated/0/Android/media",
        "/proc/self/maps", "data/data/dev.deploy", "/sdcard/dev.deploy", "/data/system",
        "/system/framework/framework.jar", "/system/framework/XposedBridge.jar.backup"};
    for (size_t i=0;i<sizeof hidden/sizeof *hidden;i++) assert(package_path_hidden(hidden[i],names,3));
    for (size_t i=0;i<sizeof visible/sizeof *visible;i++) assert(!package_path_hidden(visible[i],names,3));
    assert(!package_path_hidden("/system/framework/XposedBridge.jar", names, 0));
    puts("PASS native package paths (23 assertions)"); return 0;
}
