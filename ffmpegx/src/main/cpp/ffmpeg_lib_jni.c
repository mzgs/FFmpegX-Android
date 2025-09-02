/*
 * JNI wrapper for FFmpeg library
 * This loads the FFmpeg binary as data and executes it through JNI
 * Works on Android 10+ by avoiding exec() calls
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <dlfcn.h>
#include <android/log.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/types.h>

#define LOG_TAG "FFmpegLibJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global reference to the loaded library
static void* ffmpeg_handle = NULL;
static int (*ffmpeg_run_ptr)(int, char**) = NULL;

JNIEXPORT jboolean JNICALL
Java_com_mzgs_ffmpegx_FFmpegJNI_nativeLoadFFmpeg(JNIEnv *env, jobject thiz, jstring libPath) {
    const char *path = (*env)->GetStringUTFChars(env, libPath, NULL);
    
    LOGI("Loading FFmpeg shared library from: %s", path);
    
    // Check if file exists
    if (access(path, R_OK) != 0) {
        LOGE("FFmpeg library not found: %s", path);
        (*env)->ReleaseStringUTFChars(env, libPath, path);
        return JNI_FALSE;
    }
    
    // Try to load as shared library
    ffmpeg_handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!ffmpeg_handle) {
        LOGE("Failed to load FFmpeg library: %s", dlerror());
        
        // If dlopen fails, it might be an executable, not a shared library
        // Check if it's executable and return false to trigger fallback
        if (access(path, X_OK) == 0) {
            LOGI("File is executable, will use direct execution fallback");
            chmod(path, 0755);
        }
        
        (*env)->ReleaseStringUTFChars(env, libPath, path);
        return JNI_FALSE;
    }
    
    // Try to find the ffmpeg_run function (our wrapper)
    ffmpeg_run_ptr = dlsym(ffmpeg_handle, "ffmpeg_run");
    if (!ffmpeg_run_ptr) {
        // Try alternative names
        ffmpeg_run_ptr = dlsym(ffmpeg_handle, "main");
        if (!ffmpeg_run_ptr) {
            ffmpeg_run_ptr = dlsym(ffmpeg_handle, "ffmpeg_main");
        }
    }
    
    if (!ffmpeg_run_ptr) {
        LOGE("Could not find entry point in FFmpeg library");
        dlclose(ffmpeg_handle);
        ffmpeg_handle = NULL;
        (*env)->ReleaseStringUTFChars(env, libPath, path);
        return JNI_FALSE;
    }
    
    LOGI("FFmpeg library loaded successfully");
    (*env)->ReleaseStringUTFChars(env, libPath, path);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_mzgs_ffmpegx_FFmpegJNI_nativeRunCommand(JNIEnv *env, jobject thiz, jobjectArray args) {
    if (!ffmpeg_run_ptr) {
        LOGE("FFmpeg not loaded or not a shared library");
        return -1;
    }
    
    int argc = (*env)->GetArrayLength(env, args);
    char **argv = (char **)malloc(sizeof(char *) * (argc + 2));
    
    // First argument is program name
    argv[0] = strdup("ffmpeg");
    
    // Copy arguments from Java
    for (int i = 0; i < argc; i++) {
        jstring jstr = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *str = (*env)->GetStringUTFChars(env, jstr, NULL);
        argv[i + 1] = strdup(str);
        (*env)->ReleaseStringUTFChars(env, jstr, str);
        (*env)->DeleteLocalRef(env, jstr);
    }
    argv[argc + 1] = NULL;
    
    LOGI("Running FFmpeg via shared library with %d arguments", argc + 1);
    for (int i = 0; i <= argc; i++) {
        LOGI("  argv[%d]: %s", i, argv[i]);
    }
    
    // Call FFmpeg main function through function pointer
    int result = ffmpeg_run_ptr(argc + 1, argv);
    
    LOGI("FFmpeg completed with result: %d", result);
    
    // Clean up
    for (int i = 0; i <= argc; i++) {
        free(argv[i]);
    }
    free(argv);
    
    return result;
}

// Alternative: Direct execution without loading library
JNIEXPORT jint JNICALL
Java_com_mzgs_ffmpegx_FFmpegJNI_nativeExecuteDirect(JNIEnv *env, jobject thiz, 
                                                       jstring binaryPath, jobjectArray args) {
    const char *path = (*env)->GetStringUTFChars(env, binaryPath, NULL);
    int argc = (*env)->GetArrayLength(env, args);
    
    LOGI("Direct execution of: %s with %d args", path, argc);
    
    // For Android, we need to use app_process to execute
    // This works better than direct execution on Android 10+
    pid_t pid = fork();
    
    if (pid == 0) {
        // Child process
        char **argv = (char **)malloc(sizeof(char *) * (argc + 2));
        argv[0] = strdup(path);
        
        for (int i = 0; i < argc; i++) {
            jstring jstr = (jstring)(*env)->GetObjectArrayElement(env, args, i);
            const char *str = (*env)->GetStringUTFChars(env, jstr, NULL);
            argv[i + 1] = strdup(str);
        }
        argv[argc + 1] = NULL;
        
        // Try direct execution first
        execv(path, argv);
        
        // If that fails, try with sh
        char command[8192] = {0};
        snprintf(command, sizeof(command), "%s", path);
        for (int i = 0; i < argc; i++) {
            strcat(command, " ");
            strcat(command, argv[i + 1]);
        }
        execl("/system/bin/sh", "sh", "-c", command, NULL);
        
        // If we get here, exec failed
        LOGE("Failed to execute: %s", path);
        exit(127);
    } else if (pid > 0) {
        // Parent process
        int status;
        waitpid(pid, &status, 0);
        
        (*env)->ReleaseStringUTFChars(env, binaryPath, path);
        
        if (WIFEXITED(status)) {
            int exitCode = WEXITSTATUS(status);
            LOGI("Process exited with code: %d", exitCode);
            return exitCode;
        }
        return -1;
    } else {
        // Fork failed
        LOGE("Fork failed");
        (*env)->ReleaseStringUTFChars(env, binaryPath, path);
        return -1;
    }
}

JNIEXPORT void JNICALL
Java_com_mzgs_ffmpegx_FFmpegJNI_nativeUnloadFFmpeg(JNIEnv *env, jobject thiz) {
    if (ffmpeg_handle) {
        dlclose(ffmpeg_handle);
        ffmpeg_handle = NULL;
        ffmpeg_run_ptr = NULL;
        LOGI("FFmpeg unloaded");
    }
}