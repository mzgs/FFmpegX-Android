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
#include <errno.h>
#include <sys/types.h>

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
    
    // Parse command arguments
    char *args[256];
    int argc = 0;
    
    // First argument is the binary path
    args[argc++] = strdup(path);
    
    // Parse command into arguments
    char *cmdCopy = strdup(cmd);
    char *token = strtok(cmdCopy, " ");
    while (token != NULL && argc < 255) {
        args[argc++] = strdup(token);
        token = strtok(NULL, " ");
    }
    args[argc] = NULL;
    
    // Create pipe for communication
    int pipefd[2];
    if (pipe(pipefd) == -1) {
        LOGE("Failed to create pipe");
        free(cmdCopy);
        for (int i = 0; i < argc; i++) {
            free(args[i]);
        }
        (*env)->ReleaseStringUTFChars(env, binaryPath, path);
        (*env)->ReleaseStringUTFChars(env, command, cmd);
        return -1;
    }
    
    // Fork process for direct execution
    pid_t pid = fork();
    if (pid == 0) {
        // Child process
        close(pipefd[0]); // Close read end
        dup2(pipefd[1], STDOUT_FILENO); // Redirect stdout to pipe
        dup2(pipefd[1], STDERR_FILENO); // Redirect stderr to pipe
        close(pipefd[1]);
        
        // Execute directly
        execv(path, args);
        
        // If execv fails, exit with error
        LOGE("execv failed: %s", strerror(errno));
        exit(127);
    } else if (pid > 0) {
        // Parent process
        close(pipefd[1]); // Close write end
        
        // Read output from pipe
        char buffer[512];
        ssize_t bytesRead;
        while ((bytesRead = read(pipefd[0], buffer, sizeof(buffer) - 1)) > 0) {
            buffer[bytesRead] = '\0';
            
            // Split by lines and send to callback
            char *line = strtok(buffer, "\n");
            while (line != NULL) {
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
                        jstring jstr = (*callbackEnv)->NewStringUTF(callbackEnv, line);
                        (*callbackEnv)->CallVoidMethod(callbackEnv, g_callback, g_onOutput, jstr);
                        (*callbackEnv)->DeleteLocalRef(callbackEnv, jstr);
                        
                        if (attached) {
                            (*g_jvm)->DetachCurrentThread(g_jvm);
                        }
                    }
                }
                
                LOGI("FFmpeg: %s", line);
                line = strtok(NULL, "\n");
            }
        }
        
        close(pipefd[0]);
        
        // Wait for child process to finish
        int status;
        waitpid(pid, &status, 0);
        int exitCode = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
        
        // Clean up
        free(cmdCopy);
        for (int i = 0; i < argc; i++) {
            free(args[i]);
        }
    } else {
        // Fork failed
        LOGE("Fork failed: %s", strerror(errno));
        close(pipefd[0]);
        close(pipefd[1]);
        free(cmdCopy);
        for (int i = 0; i < argc; i++) {
            free(args[i]);
        }
        (*env)->ReleaseStringUTFChars(env, binaryPath, path);
        (*env)->ReleaseStringUTFChars(env, command, cmd);
        return -1;
    }
    
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