#pragma once
#include <string.h>
#include <stddef.h>

/* Lexical normalization, not realpath: failed probes must not reveal existence.
 * Returns only exact package directory matches, including descendants. */
static int package_path_hidden(const char *path, const char packages[][256], size_t count) {
    if (!path || path[0] != '/' || strlen(path) >= 4096) return 0;
    char normalized[4096]; size_t ends[2048], depth = 0, length = 0;
    const char *at = path;
    while (*at) {
        while (*at == '/') at++;
        const char *start = at;
        while (*at && *at != '/') at++;
        size_t size = (size_t)(at - start);
        if (!size || (size == 1 && start[0] == '.')) continue;
        if (size == 2 && start[0] == '.' && start[1] == '.') {
            if (depth) length = ends[--depth];
            continue;
        }
        ends[depth++] = length;
        normalized[length++] = '/'; memcpy(normalized + length, start, size); length += size;
    }
    normalized[length] = 0;
    const char *artifacts[] = {"/system/framework/XposedBridge.jar", "/system/xposed.prop",
        "/system/lib/libxposed_art.so", "/system/lib64/libxposed_art.so",
        "/system/bin/app_process_xposed", "/system/bin/app_process32_xposed",
        "/system/bin/app_process64_xposed"};
    if (count) for (size_t i = 0; i < sizeof artifacts / sizeof *artifacts; i++) {
        if (!strcmp(normalized, artifacts[i])) return 1;
    }
    char *parts[16]; size_t n = 0;
    char *cursor = normalized;
    while (*cursor && n < 16) {
        if (*cursor == '/') *cursor++ = 0;
        if (!*cursor) break;
        parts[n++] = cursor;
        while (*cursor && *cursor != '/') cursor++;
    }
    const char *package = NULL;
    if (n >= 3 && !strcmp(parts[0], "data") && !strcmp(parts[1], "data")) package = parts[2];
    if (n >= 4 && !strcmp(parts[0], "data")
            && (!strcmp(parts[1], "user") || !strcmp(parts[1], "user_de"))) package = parts[3];
    size_t android = n >= 4 && !strcmp(parts[0], "sdcard") ? 1
            : n >= 6 && !strcmp(parts[0], "storage") && !strcmp(parts[1], "emulated") ? 3 : 0;
    if (android && n > android + 2 && !strcmp(parts[android], "Android")
            && (!strcmp(parts[android + 1], "data") || !strcmp(parts[android + 1], "media")
                || !strcmp(parts[android + 1], "obb"))) package = parts[android + 2];
    if (!package) return 0;
    for (size_t i = 0; i < count; i++) if (!strcmp(package, packages[i])) return 1;
    return 0;
}
