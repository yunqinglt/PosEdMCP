#include <jni.h>
#include <stdatomic.h>
#include <pthread.h>
#include <dlfcn.h>
#include <stdio.h>
#include <unistd.h>
#include "native_hooks.h"
#include "mount_command.h"

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static HookFunType hook_api;
static int (*original_system)(const char *);
static _Atomic int enabled, installed;
static _Atomic unsigned long matched;
static int attempted, result, awk_available;
static _Thread_local int installing;

static int filtered_system(const char *command) {
    const char *replacement = atomic_load(&enabled) ? mount_replacement(command) : NULL;
    if (replacement) atomic_fetch_add(&matched, 1);
    return original_system(replacement ? replacement : command);
}

void mount_command_hook_api(HookFunType hook) {
    pthread_mutex_lock(&lock); hook_api = hook; pthread_mutex_unlock(&lock);
}

void mount_command_install(void) {
    if (installing || !atomic_load(&enabled)) return;
    installing = 1;
    pthread_mutex_lock(&lock);
    if (atomic_load(&enabled) && hook_api && !attempted) {
        attempted = 1;
        void *symbol = dlsym(RTLD_DEFAULT, "system");
        result = symbol ? hook_api(symbol, (void *) filtered_system, (void **) &original_system) : -1;
        if (!result && !original_system) result = -1;
        if (!result) atomic_store(&installed, 1);
    }
    pthread_mutex_unlock(&lock);
    installing = 0;
}

JNIEXPORT jstring JNICALL Java_dev_posedmcp_xposed_NativeRuntime_nativeMountStatus(
        JNIEnv *env, jclass type) {
    (void) type;
    char state[160];
    pthread_mutex_lock(&lock);
    snprintf(state, sizeof state, "active=%d installed=%d api=%d awk=%d result=%d matched=%lu",
            atomic_load(&enabled) && atomic_load(&installed), atomic_load(&installed),
            hook_api != NULL, awk_available, result, atomic_load(&matched));
    pthread_mutex_unlock(&lock);
    return (*env)->NewStringUTF(env, state);
}

JNIEXPORT void JNICALL Java_dev_posedmcp_xposed_NativeRuntime_configureNativeMount(
        JNIEnv *env, jclass type, jboolean active) {
    (void) env; (void) type;
    pthread_mutex_lock(&lock);
    awk_available = active && access("/system/bin/awk", X_OK) == 0;
    atomic_store(&enabled, awk_available);
    pthread_mutex_unlock(&lock);
    mount_command_install();
}
