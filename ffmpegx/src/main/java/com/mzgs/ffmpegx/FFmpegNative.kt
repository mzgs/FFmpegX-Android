package com.mzgs.ffmpegx

import android.util.Log

/**
 * JNI interface for executing FFmpeg binaries on Android 10+
 * This bypasses the W^X restrictions by using native code
 */
object FFmpegNative {
    private const val TAG = "FFmpegNative"
    
    init {
        try {
            System.loadLibrary("ffmpeg_wrapper")
            Log.i(TAG, "FFmpeg wrapper native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load ffmpeg_wrapper library", e)
        }
    }
    
    /**
     * Execute FFmpeg binary through JNI
     * @param binaryPath Full path to the FFmpeg binary
     * @param args Array of command line arguments
     * @return Exit code (0 for success)
     */
    external fun nativeExecute(binaryPath: String, args: Array<String>): Int
    
    /**
     * Make a file executable through JNI
     * @param filePath Full path to the file
     * @return true if successful
     */
    external fun nativeMakeExecutable(filePath: String): Boolean
    
    /**
     * Execute FFmpeg command
     */
    fun execute(binaryPath: String, command: String): Int {
        Log.d(TAG, "Executing: $binaryPath $command")
        
        // Parse command into arguments
        val args = parseCommand(command)
        
        return try {
            nativeExecute(binaryPath, args.toTypedArray())
        } catch (e: Exception) {
            Log.e(TAG, "Native execution failed", e)
            -1
        }
    }
    
    private fun parseCommand(command: String): List<String> {
        val args = mutableListOf<String>()
        val regex = Regex("""[^\s"]+|"[^"]*"""")
        regex.findAll(command).forEach { matchResult ->
            val arg = matchResult.value.trim('"')
            args.add(arg)
        }
        return args
    }
}