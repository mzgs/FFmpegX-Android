/**
 * JNI implementation for FFmpegNativeExecutor
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
#include <sstream>
#include <errno.h>
#include <memory>

#define LOG_TAG "FFmpegNativeExecutor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// External FFmpeg main function
extern "C" {
    int ffmpeg_main(int argc, char **argv);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mzgs_ffmpegx_FFmpegNativeExecutor_nativeExecuteFFmpeg(
    JNIEnv* env,
    jobject thiz,
    jstring command
) {
    if (!command) {
        LOGE("Command string is null");
        return -1;
    }
    
    const char* cmdStr = env->GetStringUTFChars(command, nullptr);
    if (!cmdStr) {
        LOGE("Failed to get command string");
        return -1;
    }
    
    // Create a copy of the command string to avoid issues with const
    std::string commandCopy(cmdStr);
    env->ReleaseStringUTFChars(command, cmdStr);
    
    LOGI("Executing FFmpeg command: %s", commandCopy.c_str());
    
    // Parse command into arguments
    std::vector<std::string> args;
    std::istringstream iss(commandCopy);
    std::string token;
    
    // Add ffmpeg as the first argument
    args.push_back("ffmpeg");
    
    // Parse the rest of the command
    bool inQuotes = false;
    std::string currentArg;
    
    for (size_t i = 0; i < commandCopy.length(); i++) {
        char c = commandCopy[i];
        
        if (c == '"') {
            inQuotes = !inQuotes;
            // Don't include the quote in the argument
        } else if (c == ' ' && !inQuotes) {
            if (!currentArg.empty()) {
                args.push_back(currentArg);
                currentArg.clear();
            }
        } else {
            currentArg += c;
        }
    }
    
    // Add the last argument
    if (!currentArg.empty()) {
        args.push_back(currentArg);
    }
    
    // Convert to char** for ffmpeg_main
    // Using unique_ptr array to ensure proper cleanup
    int argc = args.size();
    std::unique_ptr<char*[]> argv(new char*[argc + 1]);
    
    for (int i = 0; i < argc; i++) {
        // Allocate memory for each argument
        argv[i] = new char[args[i].length() + 1];
        strcpy(argv[i], args[i].c_str());
        LOGD("argv[%d]: %s", i, argv[i]);
    }
    argv[argc] = nullptr;
    
    // Execute FFmpeg
    int result = -1;
    try {
        result = ffmpeg_main(argc, argv.get());
        LOGI("FFmpeg execution completed with result: %d", result);
    } catch (const std::exception& e) {
        LOGE("FFmpeg execution failed with exception: %s", e.what());
        result = -1;
    } catch (...) {
        LOGE("FFmpeg execution failed with unknown exception");
        result = -1;
    }
    
    // Clean up allocated memory
    for (int i = 0; i < argc; i++) {
        delete[] argv[i];
    }
    
    return result;
}