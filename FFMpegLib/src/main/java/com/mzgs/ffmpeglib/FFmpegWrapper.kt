package com.mzgs.ffmpeglib

import android.util.Log

/**
 * FFmpeg Wrapper that uses popen() to execute FFmpeg on Android 10+
 * This approach works because popen() uses the shell interpreter
 * instead of directly executing from writable storage
 */
object FFmpegWrapper {
    private const val TAG = "FFmpegWrapper"
    
    init {
        try {
            System.loadLibrary("ffmpeg_wrapper")
            Log.i(TAG, "FFmpeg wrapper library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load ffmpeg_wrapper library", e)
        }
    }
    
    interface Callback {
        fun onOutput(line: String)
        fun onError(line: String)
        fun onComplete(exitCode: Int)
    }
    
    external fun nativeInit(callback: Callback)
    external fun nativeExecute(binaryPath: String, command: String): Int
    external fun nativeCleanup()
    
    /**
     * Execute FFmpeg command using popen() which works on Android 10+
     */
    fun execute(binaryPath: String, command: String, callback: Callback?): Int {
        return try {
            if (callback != null) {
                nativeInit(callback)
            }
            
            Log.d(TAG, "Executing: $binaryPath $command")
            val result = nativeExecute(binaryPath, command)
            
            if (callback != null) {
                nativeCleanup()
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Execution failed", e)
            callback?.onError("Execution failed: ${e.message}")
            callback?.onComplete(-1)
            -1
        }
    }
}