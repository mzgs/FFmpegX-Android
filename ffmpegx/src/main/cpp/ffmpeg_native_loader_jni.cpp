/**
 * JNI implementation for FFmpegNativeLoader
 */

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <unistd.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <signal.h>
#include <cstring>
#include <memory>
#include <dlfcn.h>
#include <sys/stat.h>
#include <errno.h>

#define LOG_TAG "FFmpegNativeLoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Function pointer for FFmpeg main
typedef int (*ffmpeg_main_func)(int argc, char **argv);

// Try to get FFmpeg main from various sources
static ffmpeg_main_func getFFmpegMain() {
    // First try the statically linked version
    extern int ffmpeg_main(int argc, char **argv) __attribute__((weak));
    if (ffmpeg_main) {
        LOGI("Using statically linked ffmpeg_main");
        return ffmpeg_main;
    }
    
    // Try to load from shared library
    void* handle = dlopen("libffmpeg.so", RTLD_NOW | RTLD_LOCAL);
    if (handle) {
        ffmpeg_main_func func = (ffmpeg_main_func)dlsym(handle, "ffmpeg_main");
        if (func) {
            LOGI("Using ffmpeg_main from libffmpeg.so");
            return func;
        }
        
        // Try alternative names
        func = (ffmpeg_main_func)dlsym(handle, "main");
        if (func) {
            LOGI("Using main from libffmpeg.so");
            return func;
        }
    }
    
    LOGE("Could not find FFmpeg main function");
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mzgs_ffmpegx_FFmpegNativeLoader_executeFFmpegNative(
    JNIEnv* env,
    jobject thiz,
    jobjectArray args
) {
    if (!args) {
        LOGE("Arguments array is null");
        return -1;
    }
    
    int argc = env->GetArrayLength(args);
    LOGI("Executing FFmpeg with %d arguments", argc);
    
    // Get the FFmpeg main function
    ffmpeg_main_func ffmpeg_func = getFFmpegMain();
    if (!ffmpeg_func) {
        LOGE("FFmpeg main function not available");
        
        // Fallback: try to execute as external binary
        // Get the first argument as the binary path
        if (argc > 0) {
            jstring firstArg = (jstring)env->GetObjectArrayElement(args, 0);
            const char* binaryPath = env->GetStringUTFChars(firstArg, nullptr);
            
            // Check if it's a path to an executable
            struct stat fileStat;
            if (stat(binaryPath, &fileStat) == 0) {
                LOGI("Attempting to execute as binary: %s", binaryPath);
                
                // Fork and execute
                pid_t pid = fork();
                if (pid == 0) {
                    // Child process
                    // Build argument array
                    std::vector<char*> argv;
                    
                    // First argument is the binary path itself
                    argv.push_back(strdup(binaryPath));
                    
                    // Add remaining arguments
                    for (int i = 1; i < argc; i++) {
                        jstring jstr = (jstring)env->GetObjectArrayElement(args, i);
                        const char* str = env->GetStringUTFChars(jstr, nullptr);
                        argv.push_back(strdup(str));
                        env->ReleaseStringUTFChars(jstr, str);
                        env->DeleteLocalRef(jstr);
                    }
                    argv.push_back(nullptr);
                    
                    // Execute
                    execv(binaryPath, argv.data());
                    
                    // If we get here, exec failed
                    LOGE("Failed to execute binary: %s", strerror(errno));
                    _exit(127);
                } else if (pid > 0) {
                    // Parent process - wait for completion
                    int status;
                    waitpid(pid, &status, 0);
                    
                    env->ReleaseStringUTFChars(firstArg, binaryPath);
                    env->DeleteLocalRef(firstArg);
                    
                    if (WIFEXITED(status)) {
                        int exitCode = WEXITSTATUS(status);
                        LOGI("Process exited with code: %d", exitCode);
                        return exitCode;
                    }
                    return -1;
                } else {
                    LOGE("Fork failed");
                    env->ReleaseStringUTFChars(firstArg, binaryPath);
                    env->DeleteLocalRef(firstArg);
                    return -1;
                }
            }
            
            env->ReleaseStringUTFChars(firstArg, binaryPath);
            env->DeleteLocalRef(firstArg);
        }
        
        return -1;
    }
    
    // Prepare arguments for FFmpeg
    // Using unique_ptr array for automatic cleanup
    std::unique_ptr<char*[]> argv(new char*[argc + 2]);
    std::vector<std::unique_ptr<char[]>> argStorage;
    
    // Add "ffmpeg" as first argument
    const char* ffmpegStr = "ffmpeg";
    argv[0] = new char[strlen(ffmpegStr) + 1];
    strcpy(argv[0], ffmpegStr);
    
    // Convert Java strings to C strings with proper memory management
    for (int i = 0; i < argc; i++) {
        jstring jstr = (jstring)env->GetObjectArrayElement(args, i);
        if (!jstr) {
            LOGE("Null argument at index %d", i);
            // Clean up previously allocated strings
            for (int j = 0; j <= i; j++) {
                delete[] argv[j];
            }
            return -1;
        }
        
        const char* str = env->GetStringUTFChars(jstr, nullptr);
        if (!str) {
            LOGE("Failed to get string at index %d", i);
            env->DeleteLocalRef(jstr);
            // Clean up previously allocated strings
            for (int j = 0; j <= i; j++) {
                delete[] argv[j];
            }
            return -1;
        }
        
        // Allocate and copy string
        size_t len = strlen(str) + 1;
        argv[i + 1] = new char[len];
        strncpy(argv[i + 1], str, len);
        argv[i + 1][len - 1] = '\0'; // Ensure null termination
        
        LOGD("argv[%d]: %s", i + 1, argv[i + 1]);
        
        // Release Java string
        env->ReleaseStringUTFChars(jstr, str);
        env->DeleteLocalRef(jstr);
    }
    
    argv[argc + 1] = nullptr;
    
    // Execute FFmpeg
    int result = -1;
    try {
        LOGI("Calling FFmpeg main function");
        result = ffmpeg_func(argc + 1, argv.get());
        LOGI("FFmpeg completed with result: %d", result);
    } catch (const std::exception& e) {
        LOGE("FFmpeg execution failed with exception: %s", e.what());
        result = -1;
    } catch (...) {
        LOGE("FFmpeg execution failed with unknown exception");
        result = -1;
    }
    
    // Clean up allocated memory
    for (int i = 0; i <= argc; i++) {
        delete[] argv[i];
    }
    
    return result;
}