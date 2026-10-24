#include <jni.h>
#include <netdb.h>
#include <stdio.h>
#include <stdlib.h>
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
