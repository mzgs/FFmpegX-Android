/*
 * FFmpeg Wrapper for Android 10+
 * This wrapper executes FFmpeg using popen() which works on Android 10+
 * Unlike exec(), popen() is allowed because it doesn't spawn a new process
 * from writable storage - it uses the shell interpreter
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <android/log.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/wait.h>

#define LOG_TAG "FFmpegWrapper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Java callback interface
static JavaVM* g_jvm = NULL;
static jobject g_callback = NULL;
static jmethodID g_onOutput = NULL;
static jmethodID g_onError = NULL;
static jmethodID g_onComplete = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_mzgs_ffmpegx_FFmpegWrapper_nativeInit(JNIEnv *env, jobject thiz, jobject callback) {
    if (g_callback) {
        (*env)->DeleteGlobalRef(env, g_callback);
    }
    
    g_callback = (*env)->NewGlobalRef(env, callback);
    
    jclass clazz = (*env)->GetObjectClass(env, callback);
    g_onOutput = (*env)->GetMethodID(env, clazz, "onOutput", "(Ljava/lang/String;)V");
    g_onError = (*env)->GetMethodID(env, clazz, "onError", "(Ljava/lang/String;)V");
    g_onComplete = (*env)->GetMethodID(env, clazz, "onComplete", "(I)V");
}

JNIEXPORT jint JNICALL
Java_com_mzgs_ffmpegx_FFmpegWrapper_nativeExecute(JNIEnv *env, jobject thiz, 
                                                     jstring binaryPath, jstring command) {
    const char *path = (*env)->GetStringUTFChars(env, binaryPath, NULL);
    const char *cmd = (*env)->GetStringUTFChars(env, command, NULL);
    
    LOGI("Executing FFmpeg: %s %s", path, cmd);
    
    // Make sure binary is executable
    chmod(path, 0755);
    
    // Build full command
    char fullCommand[8192];
    snprintf(fullCommand, sizeof(fullCommand), "%s %s 2>&1", path, cmd);
    
    // Use popen instead of exec - this works on Android 10+
    FILE *pipe = popen(fullCommand, "r");
    if (!pipe) {
        LOGE("Failed to execute command");
        (*env)->ReleaseStringUTFChars(env, binaryPath, path);
        (*env)->ReleaseStringUTFChars(env, command, cmd);
        return -1;
    }
    
    // Read output
    char buffer[512];
    while (fgets(buffer, sizeof(buffer), pipe) != NULL) {
        // Remove newline
        size_t len = strlen(buffer);
        if (len > 0 && buffer[len-1] == '\n') {
            buffer[len-1] = '\0';
        }
        
        // Send to Java callback
        if (g_callback && g_onOutput) {
            JNIEnv *callbackEnv;
            int attached = 0;
            
            if ((*g_jvm)->GetEnv(g_jvm, (void**)&callbackEnv, JNI_VERSION_1_6) != JNI_OK) {
                if ((*g_jvm)->AttachCurrentThread(g_jvm, &callbackEnv, NULL) == JNI_OK) {
                    attached = 1;
                }
            }
            
            if (callbackEnv) {
                jstring jstr = (*callbackEnv)->NewStringUTF(callbackEnv, buffer);
                (*callbackEnv)->CallVoidMethod(callbackEnv, g_callback, g_onOutput, jstr);
                (*callbackEnv)->DeleteLocalRef(callbackEnv, jstr);
                
                if (attached) {
                    (*g_jvm)->DetachCurrentThread(g_jvm);
                }
            }
        }
        
        LOGI("FFmpeg: %s", buffer);
    }
    
    // Get exit status
    int exitCode = pclose(pipe);
    exitCode = WEXITSTATUS(exitCode);
    
    LOGI("FFmpeg completed with exit code: %d", exitCode);
    
    // Send completion callback
    if (g_callback && g_onComplete) {
        JNIEnv *callbackEnv;
        int attached = 0;
        
        if ((*g_jvm)->GetEnv(g_jvm, (void**)&callbackEnv, JNI_VERSION_1_6) != JNI_OK) {
            if ((*g_jvm)->AttachCurrentThread(g_jvm, &callbackEnv, NULL) == JNI_OK) {
                attached = 1;
            }
        }
        
        if (callbackEnv) {
            (*callbackEnv)->CallVoidMethod(callbackEnv, g_callback, g_onComplete, exitCode);
            
            if (attached) {
                (*g_jvm)->DetachCurrentThread(g_jvm);
            }
        }
    }
    
    (*env)->ReleaseStringUTFChars(env, binaryPath, path);
    (*env)->ReleaseStringUTFChars(env, command, cmd);
    
    return exitCode;
}

JNIEXPORT void JNICALL
Java_com_mzgs_ffmpegx_FFmpegWrapper_nativeCleanup(JNIEnv *env, jobject thiz) {
    if (g_callback) {
        (*env)->DeleteGlobalRef(env, g_callback);
        g_callback = NULL;
    }
    g_onOutput = NULL;
    g_onError = NULL;
    g_onComplete = NULL;
}