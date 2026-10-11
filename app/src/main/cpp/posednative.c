/*
 * The native half of injection.
 *
 * The module is loaded inside a target application's process, so this code runs
 * with that process's privileges and, more to the point, inside its address
 * space. What it adds over the Java layer is the part Java cannot reach: the
 * application's own .so files, libc, and anything else a symbol name resolves
 * to.
 *
 * The surface is deliberately small - open a library, find a symbol, call a
 * function pointer, read or write memory. Arguments and results are machine
 * words, so a function taking or returning a float, a double, or a struct by
 * value cannot be called through here. That is a real limit of a word-only
 * bridge rather than something to work around later.
 */
#define _GNU_SOURCE
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <android/dlext.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <pthread.h>
#include <errno.h>
#include <stdlib.h>

#define TAG "PosEdNative"

typedef int64_t (*fn0_t)(void);
typedef int64_t (*fn1_t)(int64_t);
typedef int64_t (*fn2_t)(int64_t, int64_t);
typedef int64_t (*fn3_t)(int64_t, int64_t, int64_t);
typedef int64_t (*fn4_t)(int64_t, int64_t, int64_t, int64_t);
typedef int64_t (*fn5_t)(int64_t, int64_t, int64_t, int64_t, int64_t);
typedef int64_t (*fn6_t)(int64_t, int64_t, int64_t, int64_t, int64_t, int64_t);

JNIEXPORT jstring JNICALL
Java_dev_posedmcp_xposed_NativeRuntime_probe(JNIEnv *env, jclass clazz) {
    char buf[192];
    snprintf(buf, sizeof buf, "native runtime ready: %d-bit pointers, pid %d",
             (int) (sizeof(void *) * 8), getpid());
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_dev_posedmcp_xposed_NativeRuntime_lastError(JNIEnv *env, jclass clazz) {
    const char *error = dlerror();
    return (*env)->NewStringUTF(env, error == NULL ? "" : error);
}

/*
 * A dlopen handle on this platform is not always an address: for a library in a
 * non-default namespace the linker hands back a synthetic odd value that it
 * resolves through an internal map. Sending that out through Java and Lua and
 * back got it mangled - a dlsym with the returned value faulted inside the
 * linker's own namespace lookup - so handles never leave here. A caller gets a
 * small id instead, and this table is what it means.
 */
#define MAX_HANDLES 64
static void *g_handles[MAX_HANDLES];
static pthread_mutex_t g_handles_lock = PTHREAD_MUTEX_INITIALIZER;

// Reserve a slot without holding a lock while ELF constructors execute.
static int reserve_handle(void) {
    pthread_mutex_lock(&g_handles_lock);
    int slot = -1;
    for (int i = 0; i < MAX_HANDLES; ++i) {
        if (g_handles[i] == NULL) {
            slot = i;
            g_handles[i] = (void *)(uintptr_t)-1;
            break;
        }
    }
    pthread_mutex_unlock(&g_handles_lock);
    return slot;
}

static void save_handle(int slot, void *handle) {
    pthread_mutex_lock(&g_handles_lock);
    g_handles[slot] = handle;
    pthread_mutex_unlock(&g_handles_lock);
}

static jstring load_error(JNIEnv *env, const char *message) {
    // Keep linker diagnostics as data, escaping quotes, backslashes and controls.
    char out[4096];
    size_t n = (size_t)snprintf(out, sizeof out, "{\"error\":\"");
    for (const unsigned char *p = (const unsigned char *)message; *p && n < sizeof(out) - 9; ++p) {
        if (*p == '"' || *p == '\\') out[n++] = '\\';
        out[n++] = *p < 32 ? ' ' : (char)*p;
    }
    memcpy(out + n, "\"}", 3);
    return (*env)->NewStringUTF(env, out);
}

JNIEXPORT jstring JNICALL
Java_dev_posedmcp_xposed_NativeRuntime_loadSnapshot(JNIEnv *env, jclass clazz,
        jbyteArray bytes, jstring sha256) {
    jsize length = bytes == NULL ? 0 : (*env)->GetArrayLength(env, bytes);
    if (length < 64 || length > 8 * 1024 * 1024) return load_error(env, "invalid library length");
    int slot = reserve_handle();
    if (slot < 0) return load_error(env, "native library handle limit reached");
    int fd = memfd_create("posedmcp-native", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (fd < 0) { save_handle(slot, NULL); return load_error(env, strerror(errno)); }
    jbyte buffer[32768];
    const char *failure = NULL;
    for (jsize offset = 0; offset < length && failure == NULL;) {
        jsize n = length - offset;
        if (n > sizeof buffer) n = sizeof buffer;
        (*env)->GetByteArrayRegion(env, bytes, offset, n, buffer);
        if ((*env)->ExceptionCheck(env)) { close(fd); save_handle(slot, NULL); return NULL; }
        size_t written = 0;
        while (written < (size_t)n) {
            ssize_t step = write(fd, buffer + written, n - written);
            if (step < 0 && errno == EINTR) continue;
            if (step <= 0) { failure = "could not write native snapshot"; break; }
            written += step;
        }
        offset += n;
    }
    if (failure == NULL && fcntl(fd, F_ADD_SEALS,
            F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL) != 0)
        failure = "could not seal native snapshot";
    if (failure != NULL) { close(fd); save_handle(slot, NULL); return load_error(env, failure); }
    const char *hash = (*env)->GetStringUTFChars(env, sha256, NULL);
    if (hash == NULL) { close(fd); save_handle(slot, NULL); return NULL; }
    char name[100];
    snprintf(name, sizeof name, "deploy-%s.so", hash);
    (*env)->ReleaseStringUTFChars(env, sha256, hash);
    android_dlextinfo ext = {0};
    ext.flags = ANDROID_DLEXT_USE_LIBRARY_FD | ANDROID_DLEXT_FORCE_LOAD;
    ext.library_fd = fd;
    dlerror();
    void *handle = android_dlopen_ext(name, RTLD_NOW | RTLD_LOCAL, &ext);
    jstring result;
    if (handle == NULL) {
        const char *error = dlerror();
        result = load_error(env, error == NULL ? "android_dlopen_ext failed" : error);
    } else {
        char output[64];
        snprintf(output, sizeof output, "{\"handleId\":%d}", slot + 1);
        result = (*env)->NewStringUTF(env, output);
    }
    close(fd);
    save_handle(slot, handle);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_posedmcp_xposed_NativeRuntime_openLibrary(JNIEnv *env, jclass clazz, jstring path) {
    if (path == NULL) {
        return 0;
    }
    const char *cPath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cPath == NULL) {
        return 0;
    }
    dlerror();
    int slot = reserve_handle();
    if (slot < 0) { (*env)->ReleaseStringUTFChars(env, path, cPath); return 0; }
    void *handle = dlopen(cPath, RTLD_NOW);
    if (handle == NULL) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "dlopen(%s) failed: %s", cPath, dlerror());
    }
    save_handle(slot, handle);
    (*env)->ReleaseStringUTFChars(env, path, cPath);
    return handle == NULL ? 0 : slot + 1;
}

JNIEXPORT jlong JNICALL
Java_dev_posedmcp_xposed_NativeRuntime_findSymbol(JNIEnv *env, jclass clazz, jint id,
        jstring name) {
    if (name == NULL || id <= 0 || id > MAX_HANDLES) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "no library for handle id %d", id);
        return 0;
    }
    pthread_mutex_lock(&g_handles_lock);
    void *handle = g_handles[id - 1];
    pthread_mutex_unlock(&g_handles_lock);
    if (handle == NULL || handle == (void *)(uintptr_t)-1) return 0;
    const char *cName = (*env)->GetStringUTFChars(env, name, NULL);
    if (cName == NULL) {
        return 0;
    }
    dlerror();
    void *symbol = dlsym(handle, cName);
    __android_log_print(ANDROID_LOG_INFO, TAG, "dlsym(id %d, %s) -> %p", id, cName, symbol);
    (*env)->ReleaseStringUTFChars(env, name, cName);
    return (jlong) (intptr_t) symbol;
}

/*
 * Calls a function pointer. The arity is passed explicitly rather than inferred
 * because the cast has to match the real signature: reading six argument
 * registers for a function that takes none happens to work on this ABI, but it
 * is undefined behaviour and not something to rely on.
 */
JNIEXPORT jlong JNICALL
Java_dev_posedmcp_xposed_NativeRuntime_call(JNIEnv *env, jclass clazz, jlong address, jint arity,
        jlong a0, jlong a1, jlong a2, jlong a3, jlong a4, jlong a5) {
    if (address == 0) {
        return 0;
    }
    void *fn = (void *) (intptr_t) address;
    switch (arity) {
        case 0:
            return (jlong) ((fn0_t) fn)();
        case 1:
            return (jlong) ((fn1_t) fn)(a0);
        case 2:
            return (jlong) ((fn2_t) fn)(a0, a1);
        case 3:
            return (jlong) ((fn3_t) fn)(a0, a1, a2);
        case 4:
            return (jlong) ((fn4_t) fn)(a0, a1, a2, a3);
        case 5:
            return (jlong) ((fn5_t) fn)(a0, a1, a2, a3, a4);
        case 6:
            return (jlong) ((fn6_t) fn)(a0, a1, a2, a3, a4, a5);
        default:
            return 0;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_dev_posedmcp_xposed_NativeRuntime_readMemory(JNIEnv *env, jclass clazz, jlong address,
        jint length) {
    if (address == 0 || length <= 0) {
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, length);
    if (out == NULL) {
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, out, 0, length, (const jbyte *) (intptr_t) address);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_dev_posedmcp_xposed_NativeRuntime_writeMemory(JNIEnv *env, jclass clazz, jlong address,
        jbyteArray bytes) {
    if (address == 0 || bytes == NULL) {
        return JNI_FALSE;
    }
    jsize length = (*env)->GetArrayLength(env, bytes);
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *) (intptr_t) address);
    return JNI_TRUE;
}

/* /proc/self/mem returns EIO for unmapped addresses instead of dereferencing an
 * unchecked pointer. Verify read permission as procfs can otherwise bypass it. */
static int memory_mapping(uintptr_t address, size_t size, int *protection, int *private_map) {
    if (!address || size > UINTPTR_MAX - address) return 0;
    FILE *maps = fopen("/proc/self/maps", "r");
    if (!maps) return 0;
    char line[1024], permissions[5]; unsigned long long begin, end;
    int found = 0;
    while (fgets(line, sizeof line, maps)) {
        if (sscanf(line, "%llx-%llx %4s", &begin, &end, permissions) == 3
                && address >= begin && address + size <= end) {
            *protection = (permissions[0]=='r'?PROT_READ:0) | (permissions[1]=='w'?PROT_WRITE:0)
                    | (permissions[2]=='x'?PROT_EXEC:0);
            *private_map = permissions[3]=='p'; found=1; break;
        }
    }
    fclose(maps); return found;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_posedmcp_xposed_NativeRuntime_readSnapshot(JNIEnv *env, jclass clazz, jlong value, jint length) {
    uintptr_t address = (uintptr_t)value; int protection, private_map;
    const char *failure = NULL; unsigned char bytes[4096];
    if (length < 1 || length > sizeof bytes || !memory_mapping(address,length,&protection,&private_map)
            || !(protection & PROT_READ)) failure="range is not inside one readable mapping";
    else {
        int fd=open("/proc/self/mem",O_RDONLY|O_CLOEXEC);
        if(fd<0) failure="cannot open this process memory";
        else {
            ssize_t n; do { n=pread(fd,bytes,length,(off_t)address); } while(n<0 && errno==EINTR);
            close(fd); if(n!=length) failure="memory mapping changed or read was incomplete";
        }
    }
    if(failure) { (*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),failure); return NULL; }
    jbyteArray out=(*env)->NewByteArray(env,length);
    if(out) (*env)->SetByteArrayRegion(env,out,0,length,(jbyte*)bytes);
    return out;
}

static pthread_mutex_t patch_lock = PTHREAD_MUTEX_INITIALIZER;
JNIEXPORT jstring JNICALL
Java_dev_posedmcp_xposed_NativeRuntime_patchInstruction(JNIEnv *env,jclass clazz,jlong value,
        jbyteArray expected,jbyteArray replacement) {
    uintptr_t address=(uintptr_t)value;
    if(!expected || !replacement || (*env)->GetArrayLength(env,expected)!=4
            || (*env)->GetArrayLength(env,replacement)!=4 || !address || address%4)
        return load_error(env,"patch requires one aligned AArch64 instruction");
    uint32_t before,after;
    (*env)->GetByteArrayRegion(env,expected,0,4,(jbyte*)&before);
    (*env)->GetByteArrayRegion(env,replacement,0,4,(jbyte*)&after);
    if((*env)->ExceptionCheck(env))return NULL;
    pthread_mutex_lock(&patch_lock);
    int protection,private_map;
    if(!memory_mapping(address,4,&protection,&private_map) || !private_map
            || (protection&(PROT_READ|PROT_EXEC))!=(PROT_READ|PROT_EXEC)) {
        pthread_mutex_unlock(&patch_lock); return load_error(env,"patch requires private readable executable memory");
    }
    long page_size=sysconf(_SC_PAGESIZE);
    if(page_size<=0) { pthread_mutex_unlock(&patch_lock); return load_error(env,"cannot determine page size"); }
    uintptr_t page=address & ~((uintptr_t)page_size-1);
    if(mprotect((void*)page,page_size,protection|PROT_WRITE)!=0) {
        pthread_mutex_unlock(&patch_lock); return load_error(env,"mprotect refused; no bytes were changed");
    }
    int changed=__atomic_compare_exchange_n((uint32_t*)address,&before,after,0,__ATOMIC_SEQ_CST,__ATOMIC_SEQ_CST);
    if(changed) __builtin___clear_cache((char*)address,(char*)address+4);
    int restored=mprotect((void*)page,page_size,protection)==0;
    pthread_mutex_unlock(&patch_lock);
    char output[512];
    snprintf(output,sizeof output,"{\"patched\":%s,\"permissionsRestored\":%s,\"address\":\"0x%llx\"%s}",
            changed?"true":"false",restored?"true":"false",(unsigned long long)address,
            !restored?",\"error\":\"page protection restore failed; inspect target state before retrying\"":
            !changed?",\"error\":\"expected bytes mismatch; no bytes were changed\"":"");
    return (*env)->NewStringUTF(env,output);
}
