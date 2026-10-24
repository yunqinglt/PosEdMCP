#define _GNU_SOURCE
#include <jni.h>
#include <stdatomic.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdlib.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <dirent.h>
#include <dlfcn.h>
#include <stdio.h>
#include "native_hooks.h"
#include "package_paths.h"

static HookFunType hook_api;
static pthread_mutex_t install_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_rwlock_t config_lock = PTHREAD_RWLOCK_INITIALIZER;
static char packages[64][256];
static size_t package_count;
static _Atomic int active;
static _Atomic unsigned long blocked;
static unsigned attempted, installed, failed;
static _Thread_local int installing;
static int (*real_access)(const char *, int);
static int (*real_faccessat)(int, const char *, int, int);
static int (*real_stat)(const char *, struct stat *);
static int (*real_lstat)(const char *, struct stat *);
static int (*real_fstat)(int, struct stat *);
static int (*real_fstatat)(int, const char *, struct stat *, int);
static int (*real_open)(const char *, int, ...);
static int (*real_openat)(int, const char *, int, ...);
static DIR *(*real_opendir)(const char *);
static void *original_syscall __attribute__((used));

static int hidden_at(int fd, const char *path) {
    if (!atomic_load(&active) || !path) return 0;
    int saved_errno = errno, hide = 0;
    char full[4096], base[4096];
    if (path[0] != '/') {
        if (fd == AT_FDCWD) {
            if (!getcwd(base, sizeof base)) goto done;
        } else {
            char link[64]; snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
            ssize_t n = readlink(link, base, sizeof base - 1);
            if (n <= 0) goto done;
            base[n] = 0;
        }
        if (snprintf(full, sizeof full, "%s/%s", base, path) >= (int)sizeof full) goto done;
        path = full;
    }
    pthread_rwlock_rdlock(&config_lock);
    hide = package_path_hidden(path, packages, package_count);
    pthread_rwlock_unlock(&config_lock);
done:
    errno = saved_errno;
    if (hide) atomic_fetch_add(&blocked, 1);
    return hide;
}

static int hidden_fd(int fd) {
    if (!atomic_load(&active)) return 0;
    char link[64], path[4096]; int saved_errno = errno;
    snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
    ssize_t n = readlink(link, path, sizeof path - 1);
    errno = saved_errno;
    if (n <= 0) return 0;
    path[n] = 0;
    return hidden_at(AT_FDCWD, path);
}

#define HIDE_PATH(fd, path) do { if (hidden_at(fd, path)) { errno = ENOENT; return -1; } } while (0)
static int filtered_access(const char *p, int mode) { HIDE_PATH(AT_FDCWD, p); return real_access(p, mode); }
static int filtered_faccessat(int fd, const char *p, int mode, int flags) {
    HIDE_PATH(fd, p); return real_faccessat(fd, p, mode, flags);
}
static int filtered_stat(const char *p, struct stat *s) { HIDE_PATH(AT_FDCWD, p); return real_stat(p, s); }
static int filtered_lstat(const char *p, struct stat *s) { HIDE_PATH(AT_FDCWD, p); return real_lstat(p, s); }
static int filtered_fstat(int fd, struct stat *s) {
    if (hidden_fd(fd)) { errno = ENOENT; return -1; } return real_fstat(fd, s);
}
static int filtered_fstatat(int fd, const char *p, struct stat *s, int flags) {
    HIDE_PATH(fd, p); return real_fstatat(fd, p, s, flags);
}
static int needs_mode(int flags) {
    return (flags & O_CREAT) || ((flags & O_TMPFILE) == O_TMPFILE);
}
static int filtered_open(const char *p, int flags, ...) {
    HIDE_PATH(AT_FDCWD, p);
    if (needs_mode(flags)) { va_list ap; va_start(ap, flags); int mode = va_arg(ap, int); va_end(ap);
        return real_open(p, flags, mode); }
    return real_open(p, flags);
}
static int filtered_openat(int fd, const char *p, int flags, ...) {
    HIDE_PATH(fd, p);
    if (needs_mode(flags)) { va_list ap; va_start(ap, flags); int mode = va_arg(ap, int); va_end(ap);
        return real_openat(fd, p, flags, mode); }
    return real_openat(fd, p, flags);
}
static DIR *filtered_opendir(const char *p) {
    if (hidden_at(AT_FDCWD, p)) { errno = ENOENT; return NULL; } return real_opendir(p);
}

/* The exported libc syscall wrapper is variadic. Save its argument registers
 * instead of reading nonexistent varargs from arbitrary callers. This does not
 * intercept an app's own inline SVC instructions. APK ABI is arm64-v8a only. */
__attribute__((used)) static int syscall_block(long number, long a1, long a2) {
    int hide = 0;
    if (atomic_load(&active)) {
        if (number == __NR_openat || number == __NR_faccessat || number == __NR_newfstatat
#ifdef __NR_faccessat2
                || number == __NR_faccessat2
#endif
#ifdef __NR_statx
                || number == __NR_statx
#endif
                ) hide = hidden_at((int)a1, (const char *)a2);
        else if (number == __NR_fstat) hide = hidden_fd((int)a1);
    }
    if (hide) errno = ENOENT;
    return hide;
}

__attribute__((naked)) static void filtered_syscall(void) {
    __asm__ volatile(
        "stp x29, x30, [sp, #-80]!\n"
        "stp x0, x1, [sp, #16]\n" "stp x2, x3, [sp, #32]\n"
        "stp x4, x5, [sp, #48]\n" "stp x6, x7, [sp, #64]\n"
        "bl syscall_block\n" "cbnz w0, 1f\n"
        "ldp x0, x1, [sp, #16]\n" "ldp x2, x3, [sp, #32]\n"
        "ldp x4, x5, [sp, #48]\n" "ldp x6, x7, [sp, #64]\n"
        "ldp x29, x30, [sp], #80\n"
        "adrp x16, original_syscall\n" "ldr x16, [x16, :lo12:original_syscall]\n" "br x16\n"
        "1: ldp x29, x30, [sp], #80\n" "mov x0, #-1\n" "ret\n");
}

void concealment_hook_api(HookFunType hook) {
    pthread_mutex_lock(&install_mutex); hook_api = hook; pthread_mutex_unlock(&install_mutex);
}

void concealment_install(void) {
    if (installing) return;
    installing = 1;
    pthread_mutex_lock(&install_mutex);
    if (!hook_api || !atomic_load(&active)) {
        pthread_mutex_unlock(&install_mutex); installing = 0; return;
    }
#define INSTALL(bit, name, replacement, original) do { \
    if (!(attempted & (1u << bit))) { \
        attempted |= 1u << bit; \
        void *symbol = dlsym(RTLD_DEFAULT, name); \
        int result = symbol ? hook_api(symbol, (void *)(replacement), (void **)(original)) : -1; \
        if (!result && *(void **)(original)) installed++; else failed++; \
    } \
} while (0)
    INSTALL(0, "access", filtered_access, &real_access);
    INSTALL(1, "faccessat", filtered_faccessat, &real_faccessat);
    INSTALL(2, "stat", filtered_stat, &real_stat);
    INSTALL(3, "lstat", filtered_lstat, &real_lstat);
    INSTALL(4, "fstat", filtered_fstat, &real_fstat);
    INSTALL(5, "fstatat", filtered_fstatat, &real_fstatat);
    INSTALL(6, "open", filtered_open, &real_open);
    INSTALL(7, "openat", filtered_openat, &real_openat);
    INSTALL(8, "opendir", filtered_opendir, &real_opendir);
    INSTALL(9, "syscall", filtered_syscall, &original_syscall);
#undef INSTALL
    pthread_mutex_unlock(&install_mutex);
    installing = 0;
}

JNIEXPORT jstring JNICALL Java_dev_posedmcp_xposed_NativeRuntime_configureConcealmentFiles(
        JNIEnv *env, jclass type, jobjectArray names) {
    (void)type;
    jsize count = names ? (*env)->GetArrayLength(env, names) : 0;
    if (count > 64) return (*env)->NewStringUTF(env, "unavailable: too many hidden packages");
    char next[64][256] = {{0}};
    for (jsize i = 0; i < count; i++) {
        jstring value = (*env)->GetObjectArrayElement(env, names, i);
        if (!value) return (*env)->NewStringUTF(env, "unavailable: null package");
        const char *text = (*env)->GetStringUTFChars(env, value, NULL);
        if (!text) { (*env)->DeleteLocalRef(env, value); return NULL; }
        size_t size = strlen(text);
        int valid = size && size < 256 && !strchr(text, '/') && !strchr(text, '\\');
        if (valid) memcpy(next[i], text, size + 1);
        (*env)->ReleaseStringUTFChars(env, value, text); (*env)->DeleteLocalRef(env, value);
        if (!valid) return (*env)->NewStringUTF(env, "unavailable: invalid package");
    }
    pthread_rwlock_wrlock(&config_lock);
    memcpy(packages, next, sizeof packages); package_count = count;
    atomic_store(&active, count != 0);
    pthread_rwlock_unlock(&config_lock);
    concealment_install();
    char state[192]; pthread_mutex_lock(&install_mutex);
    snprintf(state, sizeof state, "active=%d installed=%u failed=%u packages=%d blocked=%lu api=%d",
            atomic_load(&active), installed, failed, (int)count, atomic_load(&blocked), hook_api != NULL);
    pthread_mutex_unlock(&install_mutex);
    return (*env)->NewStringUTF(env, state);
}

JNIEXPORT jstring JNICALL Java_dev_posedmcp_xposed_NativeRuntime_concealmentFilesStatus(
        JNIEnv *env, jclass type) {
    (void)type;
    char state[192]; pthread_mutex_lock(&install_mutex);
    snprintf(state, sizeof state, "active=%d installed=%u failed=%u blocked=%lu api=%d",
            atomic_load(&active), installed, failed, atomic_load(&blocked), hook_api != NULL);
    pthread_mutex_unlock(&install_mutex);
    return (*env)->NewStringUTF(env, state);
}
