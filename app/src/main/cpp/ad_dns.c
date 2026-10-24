#define _GNU_SOURCE
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <stdatomic.h>
#include <pthread.h>
#include <netdb.h>
#include <dlfcn.h>
#include "dns_rules.h"

/* LSPosed's native hook ABI; no inline patcher or instruction relocation here.
 * https://github.com/LSPosed/LSPosed/wiki/Native-Hook */
typedef int (*HookFunType)(void *, void *, void **);
typedef void (*NativeOnModuleLoaded)(const char *, void *);
typedef struct {
    uint32_t version;
    HookFunType hook_func;
    int (*unhook_func)(void *);
} NativeAPIEntries;

static HookFunType framework_hook;
static int (*original_getaddrinfo)(const char *, const char *, const struct addrinfo *, struct addrinfo **);
static pthread_mutex_t install_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_rwlock_t rules_lock = PTHREAD_RWLOCK_INITIALIZER;
static char rules[256][256];
static size_t rule_count;
static _Atomic unsigned long blocked_count;
static _Atomic int installed;
static int hook_error;

static int filtered_getaddrinfo(const char *node, const char *service,
        const struct addrinfo *hints, struct addrinfo **result) {
    int block = 0;
    // Numeric-only lookups are not DNS, and must preserve libc's flags behavior.
    if (!hints || !(hints->ai_flags & AI_NUMERICHOST)) {
        pthread_rwlock_rdlock(&rules_lock);
        for (size_t i = 0; i < rule_count; ++i) {
            if (dns_rule_matches(node, rules[i])) { block = 1; break; }
        }
        pthread_rwlock_unlock(&rules_lock);
    }
    if (block) {
        if (result) *result = NULL;
        atomic_fetch_add(&blocked_count, 1);
        return EAI_NONAME;
    }
    return original_getaddrinfo(node, service, hints, result);
}

static void install_filter(void) {
    pthread_mutex_lock(&install_lock);
    pthread_rwlock_rdlock(&rules_lock);
    int wanted = rule_count != 0;
    pthread_rwlock_unlock(&rules_lock);
    if (wanted && framework_hook && !atomic_load(&installed)) {
        void *target = dlsym(RTLD_DEFAULT, "getaddrinfo");
        hook_error = target ? framework_hook(target, (void *)filtered_getaddrinfo,
                (void **)&original_getaddrinfo) : -1;
        if (hook_error == 0 && original_getaddrinfo) atomic_store(&installed, 1);
    }
    pthread_mutex_unlock(&install_lock);
}

static void library_loaded(const char *name, void *handle) {
    (void)name; (void)handle;
    install_filter();
}

__attribute__((visibility("default"), used))
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    pthread_mutex_lock(&install_lock);
    if (entries && entries->version >= 1) framework_hook = entries->hook_func;
    pthread_mutex_unlock(&install_lock);
    // Capture only. Java configures the filter inside an allowed app scope.
    return library_loaded;
}

JNIEXPORT jstring JNICALL Java_dev_posedmcp_xposed_NativeRuntime_configureAdDns(
        JNIEnv *env, jclass clazz, jobjectArray domains) {
    (void)clazz;
    jsize count = domains ? (*env)->GetArrayLength(env, domains) : 0;
    if (count > 256) return (*env)->NewStringUTF(env, "too many DNS rules");
    char next[256][256] = {{0}};
    for (jsize i = 0; i < count; ++i) {
        jstring value = (*env)->GetObjectArrayElement(env, domains, i);
        if (!value) return (*env)->NewStringUTF(env, "null DNS rule");
        const char *text = (*env)->GetStringUTFChars(env, value, NULL);
        if (!text) { (*env)->DeleteLocalRef(env, value); return NULL; }
        size_t length = strlen(text);
        if (length == 0 || length >= sizeof(next[i])) {
            (*env)->ReleaseStringUTFChars(env, value, text);
            (*env)->DeleteLocalRef(env, value);
            return (*env)->NewStringUTF(env, "invalid DNS rule length");
        }
        memcpy(next[i], text, length + 1);
        (*env)->ReleaseStringUTFChars(env, value, text);
        (*env)->DeleteLocalRef(env, value);
    }
    pthread_rwlock_wrlock(&rules_lock);
    memcpy(rules, next, sizeof rules);
    rule_count = (size_t)count;
    pthread_rwlock_unlock(&rules_lock);
    install_filter();
    char status[192];
    pthread_mutex_lock(&install_lock);
    snprintf(status, sizeof status, count == 0 ? "disabled" : atomic_load(&installed)
            ? "active: %d domain rules" : !framework_hook
            ? "unavailable: framework native hook API was not supplied"
            : "unavailable: getaddrinfo hook failed (%d)",
            atomic_load(&installed) ? (int)count : hook_error);
    pthread_mutex_unlock(&install_lock);
    return (*env)->NewStringUTF(env, status);
}

JNIEXPORT jstring JNICALL Java_dev_posedmcp_xposed_NativeRuntime_adDnsStatus(
        JNIEnv *env, jclass clazz) {
    (void)clazz;
    char status[192];
    pthread_rwlock_rdlock(&rules_lock);
    snprintf(status, sizeof status, "installed=%d rules=%zu blocked=%lu",
            atomic_load(&installed), rule_count, atomic_load(&blocked_count));
    pthread_rwlock_unlock(&rules_lock);
    return (*env)->NewStringUTF(env, status);
}
