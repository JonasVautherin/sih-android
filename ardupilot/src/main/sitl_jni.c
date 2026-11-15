/*
  JNI entry for running ArduPilot SITL inside an Android app.

  The vehicle is built as a shared library by the "android" waf board, with
  its entry point named ardupilot_main instead of main. This loads it and
  calls that entry point on the calling thread; the call does not return.
 */
#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define TAG "sitl_jni"

typedef int (*ardupilot_main_fn)(int argc, char *const argv[]);

JNIEXPORT jint JNICALL
Java_ch_vautherin_ap_1sitl_SitlNative_run(JNIEnv *env, jclass clazz, jstring j_library,
                                              jstring j_work_dir, jstring j_log_path,
                                              jobjectArray j_args)
{
    (void)clazz;

    // SITL keeps eeprom.bin, logs/ and terrain/ in the working directory
    const char *work_dir = (*env)->GetStringUTFChars(env, j_work_dir, NULL);
    if (chdir(work_dir) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "chdir(%s): %s", work_dir, strerror(errno));
        return -1;
    }
    (*env)->ReleaseStringUTFChars(env, j_work_dir, work_dir);

    // Android discards stdout and stderr, send them to a file the app can show
    const char *log_path = (*env)->GetStringUTFChars(env, j_log_path, NULL);
    if (freopen(log_path, "w", stdout) == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "freopen(%s): %s", log_path, strerror(errno));
        return -1;
    }
    (*env)->ReleaseStringUTFChars(env, j_log_path, log_path);
    setvbuf(stdout, NULL, _IOLBF, 0);
    dup2(fileno(stdout), STDERR_FILENO);
    setvbuf(stderr, NULL, _IONBF, 0);

    const char *library = (*env)->GetStringUTFChars(env, j_library, NULL);
    void *handle = dlopen(library, RTLD_NOW);
    if (handle == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "dlopen(%s): %s", library, dlerror());
        return -1;
    }
    (*env)->ReleaseStringUTFChars(env, j_library, library);

    ardupilot_main_fn ardupilot_main = (ardupilot_main_fn)dlsym(handle, "ardupilot_main");
    if (ardupilot_main == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "dlsym(ardupilot_main): %s", dlerror());
        return -1;
    }

    const int argc = (*env)->GetArrayLength(env, j_args);
    char **argv = calloc(argc + 1, sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring j_arg = (jstring)(*env)->GetObjectArrayElement(env, j_args, i);
        const char *arg = (*env)->GetStringUTFChars(env, j_arg, NULL);
        argv[i] = strdup(arg);
        (*env)->ReleaseStringUTFChars(env, j_arg, arg);
        (*env)->DeleteLocalRef(env, j_arg);
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "starting %s with %d arguments", argv[0], argc);
    return ardupilot_main(argc, argv);
}
