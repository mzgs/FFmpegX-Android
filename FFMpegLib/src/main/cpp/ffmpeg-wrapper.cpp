#include <jni.h>
#include <string>
#include <cstdlib>
#include <unistd.h>
#include <sys/wait.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <string.h>

#define LOG_TAG "FFmpegWrapper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

// Method 1: Direct execution with proper setup
JNIEXPORT jint JNICALL
Java_com_mzgs_ffmpeglib_FFmpegJNI_executeCommand(
        JNIEnv *env,
        jobject /* this */,
        jstring binaryPath,
        jobjectArray args) {
    
    const char *binary = env->GetStringUTFChars(binaryPath, nullptr);
    int argc = env->GetArrayLength(args);
    
    LOGI("Attempting to execute: %s", binary);
    
    // Build command array
    char **argv = (char **) malloc(sizeof(char *) * (argc + 2));
    argv[0] = strdup(binary);
    
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) env->GetObjectArrayElement(args, i);
        const char *argStr = env->GetStringUTFChars(arg, nullptr);
        argv[i + 1] = strdup(argStr);
        env->ReleaseStringUTFChars(arg, argStr);
    }
    argv[argc + 1] = nullptr;
    
    // Method 1: Try using system() first
    std::string cmd = std::string(binary);
    for (int i = 0; i < argc; i++) {
        cmd += " ";
        cmd += argv[i + 1];
    }
    
    LOGI("Executing via system(): %s", cmd.c_str());
    int result = system(cmd.c_str());
    
    // Clean up
    for (int i = 0; i <= argc; i++) {
        free(argv[i]);
    }
    free(argv);
    env->ReleaseStringUTFChars(binaryPath, binary);
    
    if (result != -1) {
        LOGI("Execution successful, exit code: %d", WEXITSTATUS(result));
        return WEXITSTATUS(result);
    }
    
    LOGE("system() failed: %s", strerror(errno));
    return 127;
}

// Method 2: Load and execute as library
JNIEXPORT jint JNICALL
Java_com_mzgs_ffmpeglib_FFmpegJNI_loadAndExecute(
        JNIEnv *env,
        jobject /* this */,
        jstring libraryPath,
        jobjectArray args) {
    
    const char *libPath = env->GetStringUTFChars(libraryPath, nullptr);
    
    LOGI("Attempting to load library: %s", libPath);
    
    // Try to load as a shared library
    void *handle = dlopen(libPath, RTLD_NOW | RTLD_LOCAL);
    if (handle != nullptr) {
        LOGI("Library loaded successfully");
        
        // Look for main function
        typedef int (*main_func)(int, char**);
        main_func ffmpeg_main = (main_func) dlsym(handle, "main");
        
        if (ffmpeg_main == nullptr) {
            // Try alternative names
            ffmpeg_main = (main_func) dlsym(handle, "ffmpeg_main");
        }
        
        if (ffmpeg_main != nullptr) {
            LOGI("Found main function, executing...");
            
            // Build argv
            int argc = env->GetArrayLength(args) + 1;
            char **argv = (char **) malloc(sizeof(char *) * (argc + 1));
            argv[0] = strdup("ffmpeg");
            
            for (int i = 1; i < argc; i++) {
                jstring arg = (jstring) env->GetObjectArrayElement(args, i - 1);
                const char *argStr = env->GetStringUTFChars(arg, nullptr);
                argv[i] = strdup(argStr);
                env->ReleaseStringUTFChars(arg, argStr);
            }
            argv[argc] = nullptr;
            
            // Execute
            int result = ffmpeg_main(argc, argv);
            
            // Clean up
            for (int i = 0; i < argc; i++) {
                free(argv[i]);
            }
            free(argv);
            dlclose(handle);
            env->ReleaseStringUTFChars(libraryPath, libPath);
            
            return result;
        } else {
            LOGE("Could not find main function in library");
            dlclose(handle);
        }
    } else {
        LOGE("Failed to load library: %s", dlerror());
    }
    
    env->ReleaseStringUTFChars(libraryPath, libPath);
    return 127;
}

// Simple version check
JNIEXPORT jstring JNICALL
Java_com_mzgs_ffmpeglib_FFmpegJNI_getVersion(
        JNIEnv *env,
        jobject /* this */) {
    return env->NewStringUTF("FFmpeg Android Wrapper 1.0");
}

}