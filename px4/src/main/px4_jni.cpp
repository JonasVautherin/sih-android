/*
  JNI entry for running PX4 SITL inside an Android app.

  For Android, PX4 is built as a shared library whose px4_main() is the main()
  of the px4 program on other systems. This calls it on the calling thread;
  the call does not return.
 */
#include <jni.h>

#include <android/log.h>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unistd.h>

#define TAG "px4_jni"

// PX4's entry point, see platforms/posix/CMakeLists.txt
int px4_main(int argc, char **argv);

extern "C" JNIEXPORT jint JNICALL
Java_ch_vautherin_px4_1sih_SitlNative_run(JNIEnv *env, jclass, jstring j_log_path,
                                       jobjectArray j_environment, jobjectArray j_args)
{
    // Android discards stdout and stderr, send them to a file the app can show
    const char *log_path = env->GetStringUTFChars(j_log_path, nullptr);
    if (freopen(log_path, "w", stdout) == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "freopen(%s): %s", log_path, strerror(errno));
        return -1;
    }
    env->ReleaseStringUTFChars(j_log_path, log_path);
    setvbuf(stdout, nullptr, _IOLBF, 0);
    dup2(fileno(stdout), STDERR_FILENO);
    setvbuf(stderr, nullptr, _IONBF, 0);

    // PX4 and its startup scripts are configured through the environment
    const int environment_size = env->GetArrayLength(j_environment);
    for (int i = 0; i + 1 < environment_size; i += 2) {
        auto j_name = (jstring)env->GetObjectArrayElement(j_environment, i);
        auto j_value = (jstring)env->GetObjectArrayElement(j_environment, i + 1);
        const char *name = env->GetStringUTFChars(j_name, nullptr);
        const char *value = env->GetStringUTFChars(j_value, nullptr);
        setenv(name, value, 1);
        env->ReleaseStringUTFChars(j_name, name);
        env->ReleaseStringUTFChars(j_value, value);
        env->DeleteLocalRef(j_name);
        env->DeleteLocalRef(j_value);
    }

    const int argc = env->GetArrayLength(j_args);
    char **argv = (char **)calloc(argc + 1, sizeof(char *));
    for (int i = 0; i < argc; i++) {
        auto j_arg = (jstring)env->GetObjectArrayElement(j_args, i);
        const char *arg = env->GetStringUTFChars(j_arg, nullptr);
        argv[i] = strdup(arg);
        env->ReleaseStringUTFChars(j_arg, arg);
        env->DeleteLocalRef(j_arg);
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "starting %s with %d arguments", argv[0], argc);
    return px4_main(argc, argv);
}
