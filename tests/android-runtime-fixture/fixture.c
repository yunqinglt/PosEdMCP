#include <jni.h>
#include <netdb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
JNIEXPORT jint JNICALL Java_dev_deploy_fixture_Results_resolve(JNIEnv*e,jclass c,jstring host,jboolean numeric){const char*h=(*e)->GetStringUTFChars(e,host,0); struct addrinfo hints={0},*out=0; hints.ai_socktype=SOCK_STREAM; if(numeric) hints.ai_flags=AI_NUMERICHOST; int r=getaddrinfo(h,"80",&hints,&out); if(out) freeaddrinfo(out); (*e)->ReleaseStringUTFChars(e,host,h); return r;}
JNIEXPORT jstring JNICALL Java_dev_deploy_fixture_Results_rawMaps(JNIEnv*e,jclass c){FILE*f=fopen("/proc/self/maps","r"); char*b=calloc(1,2097152); if(f){fread(b,1,2097151,f);fclose(f);} jstring s=(*e)->NewStringUTF(e,b);free(b);return s;}
JNIEXPORT void JNICALL Java_dev_deploy_fixture_Results_createJarMappings(JNIEnv*e,jclass c){
 int fd=(int)syscall(__NR_memfd_create,"XposedBridge.jar",0);if(fd>=0){ftruncate(fd,4096);mmap(0,4096,PROT_READ,MAP_PRIVATE,fd,0);close(fd);}
}
JNIEXPORT jstring JNICALL Java_dev_deploy_fixture_Results_probeFiles(JNIEnv*e,jclass c,jstring path){
 const char*p=(*e)->GetStringUTFChars(e,path,0); struct stat s;
 errno=0;int a=access(p,F_OK),ae=errno;
 errno=0;int st=stat(p,&s),se=errno;
 errno=0;int fd=open(p,O_PATH),oe=errno;if(fd>=0)close(fd);
 errno=0;long call=syscall(__NR_faccessat,AT_FDCWD,p,F_OK),ce=errno;
 long pid=syscall(__NR_getpid);
 char out[512];snprintf(out,sizeof out,"{\"access\":%d,\"accessErrno\":%d,\"stat\":%d,\"statErrno\":%d,\"open\":%d,\"openErrno\":%d,\"syscall\":%ld,\"syscallErrno\":%ld,\"getpid\":%ld}",a,ae,st,se,fd,oe,call,ce,pid);
 (*e)->ReleaseStringUTFChars(e,path,p);return (*e)->NewStringUTF(e,out);
}

JNIEXPORT jstring JNICALL Java_dev_deploy_fixture_Results_swapPath(JNIEnv* e,jclass c,jstring path) {
    (void)c;
    const char* original = getenv("PATH");
    jstring saved = original ? (*e)->NewStringUTF(e, original) : NULL;
    const char* value = path ? (*e)->GetStringUTFChars(e, path, NULL) : NULL;
    if (value) {
        setenv("PATH", value, 1);
        (*e)->ReleaseStringUTFChars(e, path, value);
    } else if (!path) unsetenv("PATH");
    return saved;
}

JNIEXPORT jstring JNICALL Java_dev_deploy_fixture_Results_systemOutput(JNIEnv* e,jclass c,jstring command) {
    (void)c;
    int fd = (int)syscall(__NR_memfd_create, "fixture-stdout", 0), saved = dup(STDOUT_FILENO);
    if (fd < 0 || saved < 0 || dup2(fd, STDOUT_FILENO) < 0) {
        if (saved >= 0) close(saved);
        if (fd >= 0) close(fd);
        return (*e)->NewStringUTF(e, "-1\n");
    }
    const char* cmd = (*e)->GetStringUTFChars(e, command, NULL);
    int status = cmd ? system(cmd) : -1;
    dup2(saved, STDOUT_FILENO);
    close(saved);
    if (cmd) (*e)->ReleaseStringUTFChars(e, command, cmd);
    char* output = calloc(1, 1024 * 1024);
    if (!output) { close(fd); return (*e)->NewStringUTF(e, "-1\n"); }
    int offset = snprintf(output, 64, "%d\n", status);
    lseek(fd, 0, SEEK_SET);
    ssize_t count;
    while (offset < 1024 * 1024 - 1
            && (count = read(fd, output + offset, 1024 * 1024 - 1 - offset)) > 0) offset += count;
    close(fd);
    jstring view = (*e)->NewStringUTF(e, output);
    free(output);
    return view;
}
