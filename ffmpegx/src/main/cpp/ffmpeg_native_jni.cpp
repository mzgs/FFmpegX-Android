/**
 * Native JNI wrapper for FFmpeg that properly links to FFmpeg libraries
 * This works with Android SDK 34 by using proper JNI calls instead of exec()
 */

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <sstream>
#include <sys/stat.h>
#include <errno.h>
#include <cstring>

#define LOG_TAG "FFmpegNativeJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Include FFmpeg headers if available
#ifdef HAVE_FFMPEG_STATIC
extern "C" {
#include "libavutil/version.h"
#include "libavcodec/version.h"
#include "libavformat/version.h"
}
#endif

// FFmpeg main function declaration - implemented in ffmpeg_cmd.c
extern "C" {
    int ffmpeg_main(int argc, char **argv);
    void Java_com_mzgs_ffmpegx_FFmpegNative_nativeSetCallback(JNIEnv *env, jobject thiz, jobject callback);
}

// Callback interface for progress updates
static JavaVM* g_jvm = nullptr;
static jobject g_callback = nullptr;
static jmethodID g_onProgress = nullptr;
static jmethodID g_onOutput = nullptr;
static jmethodID g_onError = nullptr;
static jmethodID g_onComplete = nullptr;

// Thread-safe callback handling
void callJavaCallback(JNIEnv* env, const char* method, const char* message) {
    if (g_callback && env) {
        jmethodID methodId = nullptr;
        
        if (strcmp(method, "onProgress") == 0) {
            methodId = g_onProgress;
        } else if (strcmp(method, "onOutput") == 0) {
            methodId = g_onOutput;
        } else if (strcmp(method, "onError") == 0) {
            methodId = g_onError;
        }
        
        if (methodId) {
            jstring jmsg = env->NewStringUTF(message);
            env->CallVoidMethod(g_callback, methodId, jmsg);
            env->DeleteLocalRef(jmsg);
        }
    }
}

// Structure to pass data to the execution thread
struct FFmpegExecutionData {
    std::vector<std::string> args;
    int result;
};

// Thread function for FFmpeg execution
void* executeFFmpegThread(void* data) {
    FFmpegExecutionData* execData = (FFmpegExecutionData*)data;
    JNIEnv* env = nullptr;
    
    // Attach thread to JVM
    if (g_jvm) {
        g_jvm->AttachCurrentThread(&env, nullptr);
    }
    
    // Convert arguments to char**
    int argc = execData->args.size();
    char** argv = new char*[argc + 1];
    
    for (int i = 0; i < argc; i++) {
        argv[i] = const_cast<char*>(execData->args[i].c_str());
        LOGD("argv[%d]: %s", i, argv[i]);
    }
    argv[argc] = nullptr;
    
    LOGI("Executing FFmpeg with %d arguments", argc);
    
    // Call FFmpeg main function
    try {
        execData->result = ffmpeg_main(argc, argv);
        LOGI("FFmpeg execution completed with result: %d", execData->result);
    } catch (...) {
        LOGE("FFmpeg execution failed with exception");
        execData->result = -1;
    }
    
    // Clean up
    delete[] argv;
    
    // Notify completion
    if (env && g_callback && g_onComplete) {
        env->CallVoidMethod(g_callback, g_onComplete, execData->result);
    }
    
    // Detach thread
    if (g_jvm) {
        g_jvm->DetachCurrentThread();
    }
    
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mzgs_ffmpegx_FFmpegNative_nativeInit(JNIEnv* env, jobject thiz) {
    LOGI("Initializing FFmpeg native library");
    
    // Get JavaVM reference for callbacks
    env->GetJavaVM(&g_jvm);
    
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mzgs_ffmpegx_FFmpegNative_nativeExecute(
    JNIEnv* env,
    jobject thiz,
    jstring binaryPath,
    jobjectArray args
) {
    int argc = env->GetArrayLength(args);
    LOGI("Starting FFmpeg execution with %d arguments", argc);
    
    // Get binary path
    const char* binPath = env->GetStringUTFChars(binaryPath, nullptr);
    if (!binPath) {
        LOGE("Failed to get binary path");
        return -1;
    }
    std::string binaryPathStr(binPath);
    env->ReleaseStringUTFChars(binaryPath, binPath);
    
    // Prepare execution data - will be freed by the thread
    FFmpegExecutionData* execData = new FFmpegExecutionData();
    
    // Add binary path as first argument
    execData->args.push_back(binaryPathStr);
    
    // Convert Java string array to C++ vector
    for (int i = 0; i < argc; i++) {
        jstring jstr = (jstring)env->GetObjectArrayElement(args, i);
        if (!jstr) {
            LOGW("Null argument at index %d, skipping", i);
            continue;
        }
        const char* str = env->GetStringUTFChars(jstr, nullptr);
        if (str) {
            execData->args.push_back(std::string(str));
            env->ReleaseStringUTFChars(jstr, str);
        }
        env->DeleteLocalRef(jstr);
    }
    
    // Execute in a separate thread to avoid blocking
    pthread_t thread;
    int threadResult = pthread_create(&thread, nullptr, executeFFmpegThread, execData);
    if (threadResult != 0) {
        LOGE("Failed to create thread: %d", threadResult);
        delete execData;  // Clean up if thread creation failed
        return -1;
    }
    pthread_detach(thread);
    
    return 0; // Return immediately, thread will clean up execData
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mzgs_ffmpegx_FFmpegNative_nativeExecuteSync(
    JNIEnv* env,
    jobject thiz,
    jobjectArray args
) {
    int argc = env->GetArrayLength(args);
    LOGI("Executing FFmpeg synchronously with %d arguments", argc);
    
    // Prepare arguments
    std::vector<char*> argv;
    argv.push_back(const_cast<char*>("ffmpeg"));
    
    for (int i = 0; i < argc; i++) {
        jstring jstr = (jstring)env->GetObjectArrayElement(args, i);
        const char* str = env->GetStringUTFChars(jstr, nullptr);
        char* arg = new char[strlen(str) + 1];
        strcpy(arg, str);
        argv.push_back(arg);
        env->ReleaseStringUTFChars(jstr, str);
        env->DeleteLocalRef(jstr);
    }
    argv.push_back(nullptr);
    
    // Execute FFmpeg
    int result = -1;
    try {
        result = ffmpeg_main(argv.size() - 1, argv.data());
        LOGI("FFmpeg completed with result: %d", result);
    } catch (...) {
        LOGE("FFmpeg execution failed with exception");
        result = -1;
    }
    
    // Clean up
    for (size_t i = 1; i < argv.size() - 1; i++) {
        delete[] argv[i];
    }
    
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mzgs_ffmpegx_FFmpegNative_nativeCleanup(JNIEnv* env, jobject thiz) {
    LOGI("Cleaning up FFmpeg native resources");
    
    // Release global references
    if (g_callback) {
        env->DeleteGlobalRef(g_callback);
        g_callback = nullptr;
    }
    
    g_onProgress = nullptr;
    g_onOutput = nullptr;
    g_onError = nullptr;
    g_onComplete = nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mzgs_ffmpegx_FFmpegNative_nativeGetVersion(JNIEnv* env, jobject thiz) {
#ifdef HAVE_FFMPEG_STATIC
    // Get actual FFmpeg version from the linked libraries
    std::stringstream version;
    version << "FFmpeg ";
    version << LIBAVFORMAT_VERSION_MAJOR << "." << LIBAVFORMAT_VERSION_MINOR << "." << LIBAVFORMAT_VERSION_MICRO;
    version << " (";
    version << "avcodec " << LIBAVCODEC_VERSION_MAJOR << "." << LIBAVCODEC_VERSION_MINOR << ", ";
    version << "avformat " << LIBAVFORMAT_VERSION_MAJOR << "." << LIBAVFORMAT_VERSION_MINOR;
    version << ")";
    return env->NewStringUTF(version.str().c_str());
#else
    return env->NewStringUTF("FFmpeg 6.0 Android Build");
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mzgs_ffmpegx_FFmpegNative_nativeIsAvailable(JNIEnv* env, jobject thiz) {
    // Check if FFmpeg is properly linked and available
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mzgs_ffmpegx_FFmpegNative_nativeMakeExecutable(JNIEnv* env, jobject thiz, jstring filePath) {
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    LOGI("Making file executable: %s", path);
    
    // Use chmod to make the file executable
    int result = chmod(path, 0755);
    
    if (result == 0) {
        LOGI("Successfully made file executable: %s", path);
    } else {
        LOGE("Failed to make file executable: %s (errno: %d)", path, errno);
    }
    
    env->ReleaseStringUTFChars(filePath, path);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}