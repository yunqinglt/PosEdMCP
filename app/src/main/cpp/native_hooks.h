#pragma once
#include <stdint.h>
typedef int (*HookFunType)(void *, void *, void **);
typedef void (*NativeOnModuleLoaded)(const char *, void *);
typedef struct {
    uint32_t version;
    HookFunType hook_func;
    int (*unhook_func)(void *);
} NativeAPIEntries;
void concealment_hook_api(HookFunType hook);
void concealment_install(void);
