#include <jni.h>
#include <netdb.h>
#include <stdio.h>
#include <stdlib.h>
JNIEXPORT jint JNICALL Java_dev_deploy_fixture_Results_resolve(JNIEnv*e,jclass c,jstring host,jboolean numeric){const char*h=(*e)->GetStringUTFChars(e,host,0); struct addrinfo hints={0},*out=0; hints.ai_socktype=SOCK_STREAM; if(numeric) hints.ai_flags=AI_NUMERICHOST; int r=getaddrinfo(h,"80",&hints,&out); if(out) freeaddrinfo(out); (*e)->ReleaseStringUTFChars(e,host,h); return r;}
JNIEXPORT jstring JNICALL Java_dev_deploy_fixture_Results_rawMaps(JNIEnv*e,jclass c){FILE*f=fopen("/proc/self/maps","r"); char*b=calloc(1,2097152); if(f){fread(b,1,2097151,f);fclose(f);} jstring s=(*e)->NewStringUTF(e,b);free(b);return s;}
