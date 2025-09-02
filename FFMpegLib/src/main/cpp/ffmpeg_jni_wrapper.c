/*
 * JNI wrapper that calls FFmpeg's main() function directly
 * This is how ffmpeg-kit and similar libraries work on Android 10+
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>
#include <pthread.h>

#define LOG_TAG "FFmpegJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// FFmpeg's main function (we'll link against the FFmpeg library)
extern int ffmpeg_main(int argc, char **argv);

// Structure to pass data to thread
typedef struct {
    JNIEnv *env;
    jobject callback;
    int argc;
    char **argv;
} ffmpeg_thread_data;

// Thread function to run FFmpeg
void* run_ffmpeg_thread(void* arg) {
    ffmpeg_thread_data *data = (ffmpeg_thread_data*)arg;
    
    LOGI("Starting FFmpeg with %d arguments", data->argc);
    
    // Call FFmpeg's main function
    int result = ffmpeg_main(data->argc, data->argv);
    
    LOGI("FFmpeg finished with result: %d", result);
    
    // Clean up
    for (int i = 0; i < data->argc; i++) {
        free(data->argv[i]);
    }
    free(data->argv);
    free(data);
    
    return (void*)(intptr_t)result;
}

JNIEXPORT jint JNICALL
Java_com_mzgs_ffmpeglib_FFmpegJNI_runFFmpeg(JNIEnv *env, jobject thiz, jobjectArray args) {
    int argc = (*env)->GetArrayLength(env, args) + 1;
    char **argv = (char **)malloc(sizeof(char *) * argc);
    
    // First argument is the program name
    argv[0] = strdup("ffmpeg");
    
    // Copy arguments from Java
    for (int i = 1; i < argc; i++) {
        jstring jstr = (jstring)(*env)->GetObjectArrayElement(env, args, i - 1);
        const char *str = (*env)->GetStringUTFChars(env, jstr, NULL);
        argv[i] = strdup(str);
        (*env)->ReleaseStringUTFChars(env, jstr, str);
        (*env)->DeleteLocalRef(env, jstr);
    }
    
    // Log the command
    LOGI("Executing FFmpeg command:");
    for (int i = 0; i < argc; i++) {
        LOGI("  argv[%d]: %s", i, argv[i]);
    }
    
    // Create thread data
    ffmpeg_thread_data *thread_data = malloc(sizeof(ffmpeg_thread_data));
    thread_data->env = env;
    thread_data->callback = NULL;
    thread_data->argc = argc;
    thread_data->argv = argv;
    
    // Run FFmpeg in a separate thread to avoid blocking
    pthread_t thread;
    if (pthread_create(&thread, NULL, run_ffmpeg_thread, thread_data) != 0) {
        LOGE("Failed to create thread");
        // Clean up on failure
        for (int i = 0; i < argc; i++) {
            free(argv[i]);
        }
        free(argv);
        free(thread_data);
        return -1;
    }
    
    // Wait for thread to complete
    void *thread_result;
    pthread_join(thread, &thread_result);
    
    return (jint)(intptr_t)thread_result;
}

// Alternative: Run FFmpeg synchronously
JNIEXPORT jint JNICALL
Java_com_mzgs_ffmpeglib_FFmpegJNI_runFFmpegSync(JNIEnv *env, jobject thiz, jobjectArray args) {
    int argc = (*env)->GetArrayLength(env, args) + 1;
    char **argv = (char **)malloc(sizeof(char *) * argc);
    
    argv[0] = strdup("ffmpeg");
    
    for (int i = 1; i < argc; i++) {
        jstring jstr = (jstring)(*env)->GetObjectArrayElement(env, args, i - 1);
        const char *str = (*env)->GetStringUTFChars(env, jstr, NULL);
        argv[i] = strdup(str);
        (*env)->ReleaseStringUTFChars(env, jstr, str);
    }
    
    // Call FFmpeg directly
    int result = ffmpeg_main(argc, argv);
    
    // Clean up
    for (int i = 0; i < argc; i++) {
        free(argv[i]);
    }
    free(argv);
    
    return result;
}