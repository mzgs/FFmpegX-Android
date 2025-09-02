#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <android/log.h>
#include <unistd.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <signal.h>
#include <cstring>
#include <sstream>
#include <errno.h>
#include <sys/stat.h>

#define LOG_TAG "FFmpegJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

struct FFmpegSession {
    pid_t pid;
    int outputPipe[2];
    int errorPipe[2];
    bool isRunning;
    long sessionId;
    std::string command;
    jobject callback;
    JavaVM* jvm;
};

static std::mutex sessionMutex;
static std::vector<FFmpegSession*> activeSessions;
static long nextSessionId = 1;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_mzgs_ffmpeglib_NativeFFmpeg_nativeExecute(
        JNIEnv *env,
        jobject thiz,
        jstring binaryPath,
        jstring command,
        jobject callback) {
    
    const char *bin = env->GetStringUTFChars(binaryPath, nullptr);
    const char *cmd = env->GetStringUTFChars(command, nullptr);
    std::string binaryPathStr(bin);
    std::string commandStr(cmd);
    env->ReleaseStringUTFChars(binaryPath, bin);
    env->ReleaseStringUTFChars(command, cmd);
    
    LOGI("Executing FFmpeg: %s %s", binaryPathStr.c_str(), commandStr.c_str());
    
    // Check if binary exists and is executable
    struct stat fileStat;
    if (stat(binaryPathStr.c_str(), &fileStat) != 0) {
        LOGE("FFmpeg binary does not exist at: %s", binaryPathStr.c_str());
        return -1;
    }
    
    if (!(fileStat.st_mode & S_IXUSR)) {
        LOGE("FFmpeg binary is not executable: %s", binaryPathStr.c_str());
        return -1;
    }
    
    auto* session = new FFmpegSession();
    session->sessionId = nextSessionId++;
    session->command = commandStr;
    session->isRunning = true;
    
    if (callback != nullptr) {
        session->callback = env->NewGlobalRef(callback);
        env->GetJavaVM(&session->jvm);
    } else {
        session->callback = nullptr;
        session->jvm = nullptr;
    }
    
    // Create pipes for output and error streams
    if (pipe(session->outputPipe) == -1 || pipe(session->errorPipe) == -1) {
        LOGE("Failed to create pipes");
        delete session;
        return -1;
    }
    
    // Make pipes non-blocking
    fcntl(session->outputPipe[0], F_SETFL, O_NONBLOCK);
    fcntl(session->errorPipe[0], F_SETFL, O_NONBLOCK);
    
    pid_t pid = fork();
    
    if (pid == -1) {
        LOGE("Failed to fork process");
        close(session->outputPipe[0]);
        close(session->outputPipe[1]);
        close(session->errorPipe[0]);
        close(session->errorPipe[1]);
        delete session;
        return -1;
    }
    
    if (pid == 0) {
        // Child process
        close(session->outputPipe[0]);
        close(session->errorPipe[0]);
        
        // Redirect stdout and stderr to pipes
        dup2(session->outputPipe[1], STDOUT_FILENO);
        dup2(session->errorPipe[1], STDERR_FILENO);
        
        close(session->outputPipe[1]);
        close(session->errorPipe[1]);
        
        // Parse command into arguments
        std::vector<char*> args;
        std::istringstream iss(commandStr);
        std::string token;
        std::vector<std::string> tokens;
        
        // Store strings to keep them alive
        tokens.push_back(binaryPathStr);
        
        while (iss >> token) {
            tokens.push_back(token);
        }
        
        for (auto& t : tokens) {
            args.push_back(const_cast<char*>(t.c_str()));
        }
        args.push_back(nullptr);
        
        // Execute ffmpeg from the specified path using execv (not execvp)
        // execv requires absolute path, which we have
        execv(binaryPathStr.c_str(), args.data());
        
        // If we get here, exec failed
        LOGE("Failed to execute ffmpeg: %s (errno=%d)", strerror(errno), errno);
        fprintf(stderr, "Failed to execute %s: %s (errno=%d)\n", binaryPathStr.c_str(), strerror(errno), errno);
        _exit(127);
    }
    
    // Parent process
    session->pid = pid;
    close(session->outputPipe[1]);
    close(session->errorPipe[1]);
    
    {
        std::lock_guard<std::mutex> lock(sessionMutex);
        activeSessions.push_back(session);
    }
    
    // Start monitoring thread
    std::thread monitorThread([session]() {
        char buffer[4096];
        fd_set readSet;
        struct timeval timeout;
        
        JNIEnv* env = nullptr;
        bool attached = false;
        
        // Only attach to JVM if we have a callback
        if (session->callback && session->jvm) {
            int getEnvStat = session->jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
            if (getEnvStat == JNI_EDETACHED) {
                if (session->jvm->AttachCurrentThread(&env, nullptr) == 0) {
                    attached = true;
                } else {
                    LOGE("Failed to attach thread to JVM");
                    env = nullptr;
                }
            } else if (getEnvStat == JNI_OK) {
                // Already attached
                attached = false; // Don't detach later
            } else {
                LOGE("Failed to get JNI environment");
                env = nullptr;
            }
        }
        
        while (session->isRunning) {
            FD_ZERO(&readSet);
            FD_SET(session->outputPipe[0], &readSet);
            FD_SET(session->errorPipe[0], &readSet);
            
            timeout.tv_sec = 0;
            timeout.tv_usec = 100000; // 100ms
            
            int maxFd = std::max(session->outputPipe[0], session->errorPipe[0]) + 1;
            int result = select(maxFd, &readSet, nullptr, nullptr, &timeout);
            
            if (result > 0) {
                // Read from output pipe
                if (FD_ISSET(session->outputPipe[0], &readSet)) {
                    ssize_t bytesRead = read(session->outputPipe[0], buffer, sizeof(buffer) - 1);
                    if (bytesRead > 0) {
                        buffer[bytesRead] = '\0';
                        
                        if (env && session->callback) {
                            jclass callbackClass = env->GetObjectClass(session->callback);
                            if (callbackClass) {
                                jmethodID onOutputMethod = env->GetMethodID(callbackClass, 
                                    "onOutput", "(Ljava/lang/String;)V");
                                if (onOutputMethod) {
                                    jstring output = env->NewStringUTF(buffer);
                                    if (output) {
                                        env->CallVoidMethod(session->callback, onOutputMethod, output);
                                        env->DeleteLocalRef(output);
                                    }
                                }
                                env->DeleteLocalRef(callbackClass);
                            }
                        }
                    }
                }
                
                // Read from error pipe
                if (FD_ISSET(session->errorPipe[0], &readSet)) {
                    ssize_t bytesRead = read(session->errorPipe[0], buffer, sizeof(buffer) - 1);
                    if (bytesRead > 0) {
                        buffer[bytesRead] = '\0';
                        
                        if (env && session->callback) {
                            jclass callbackClass = env->GetObjectClass(session->callback);
                            if (callbackClass) {
                                jmethodID onErrorMethod = env->GetMethodID(callbackClass, 
                                    "onError", "(Ljava/lang/String;)V");
                                if (onErrorMethod) {
                                    jstring error = env->NewStringUTF(buffer);
                                    if (error) {
                                        env->CallVoidMethod(session->callback, onErrorMethod, error);
                                        env->DeleteLocalRef(error);
                                    }
                                }
                                env->DeleteLocalRef(callbackClass);
                            }
                        }
                    }
                }
            }
            
            // Check if process is still running
            int status;
            pid_t result_pid = waitpid(session->pid, &status, WNOHANG);
            if (result_pid == session->pid) {
                session->isRunning = false;
                
                if (env && session->callback) {
                    jclass callbackClass = env->GetObjectClass(session->callback);
                    if (callbackClass) {
                        jmethodID onCompleteMethod = env->GetMethodID(callbackClass, 
                            "onComplete", "(I)V");
                        if (onCompleteMethod) {
                            int exitCode = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
                            env->CallVoidMethod(session->callback, onCompleteMethod, exitCode);
                        }
                        env->DeleteLocalRef(callbackClass);
                    }
                }
            }
        }
        
        close(session->outputPipe[0]);
        close(session->errorPipe[0]);
        
        // Clean up callback reference before detaching
        if (session->callback && env) {
            env->DeleteGlobalRef(session->callback);
            session->callback = nullptr;
        }
        
        if (attached && session->jvm) {
            session->jvm->DetachCurrentThread();
        }
        
        {
            std::lock_guard<std::mutex> lock(sessionMutex);
            auto it = std::find(activeSessions.begin(), activeSessions.end(), session);
            if (it != activeSessions.end()) {
                activeSessions.erase(it);
            }
        }
        
        delete session;
    });
    
    monitorThread.detach();
    
    return session->sessionId;
}

JNIEXPORT jboolean JNICALL
Java_com_mzgs_ffmpeglib_NativeFFmpeg_nativeCancel(
        JNIEnv *env,
        jobject thiz,
        jlong sessionId) {
    
    std::lock_guard<std::mutex> lock(sessionMutex);
    
    for (auto* session : activeSessions) {
        if (session->sessionId == sessionId) {
            if (session->isRunning && session->pid > 0) {
                kill(session->pid, SIGTERM);
                session->isRunning = false;
                return JNI_TRUE;
            }
        }
    }
    
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_mzgs_ffmpeglib_NativeFFmpeg_nativeCancelAll(
        JNIEnv *env,
        jobject thiz) {
    
    std::lock_guard<std::mutex> lock(sessionMutex);
    
    for (auto* session : activeSessions) {
        if (session->isRunning && session->pid > 0) {
            kill(session->pid, SIGTERM);
            session->isRunning = false;
        }
    }
}

JNIEXPORT jboolean JNICALL
Java_com_mzgs_ffmpeglib_NativeFFmpeg_nativeIsRunning(
        JNIEnv *env,
        jobject thiz,
        jlong sessionId) {
    
    std::lock_guard<std::mutex> lock(sessionMutex);
    
    for (auto* session : activeSessions) {
        if (session->sessionId == sessionId) {
            return session->isRunning ? JNI_TRUE : JNI_FALSE;
        }
    }
    
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_mzgs_ffmpeglib_NativeFFmpeg_nativeGetFFmpegVersion(
        JNIEnv *env,
        jobject thiz) {
    
    FILE* pipe = popen("ffmpeg -version", "r");
    if (!pipe) {
        return env->NewStringUTF("Unknown");
    }
    
    char buffer[256];
    std::string result;
    
    if (fgets(buffer, sizeof(buffer), pipe) != nullptr) {
        result = buffer;
    }
    
    pclose(pipe);
    
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_mzgs_ffmpeglib_NativeFFmpeg_nativeIsFFmpegAvailable(
        JNIEnv *env,
        jobject thiz) {
    
    return system("which ffmpeg > /dev/null 2>&1") == 0 ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"