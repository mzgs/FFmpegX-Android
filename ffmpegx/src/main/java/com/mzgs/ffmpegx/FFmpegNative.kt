package com.mzgs.ffmpegx

import android.util.Log

/**
 * Native FFmpeg implementation using JNI
 * This works with Android SDK 34 by calling FFmpeg functions directly through JNI
 * instead of executing binaries (which is blocked by W^X restrictions)
 */
object FFmpegNative {
    private const val TAG = "FFmpegNative"
    
    init {
        try {
            // Try to load the new native FFmpeg JNI library first
            System.loadLibrary("ffmpeg_native_jni")
            Log.i(TAG, "FFmpeg native JNI library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Failed to load ffmpeg_native_jni, trying ffmpeg_wrapper", e)
            try {
                System.loadLibrary("ffmpeg_wrapper")
                Log.i(TAG, "FFmpeg wrapper native library loaded")
            } catch (e2: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load any FFmpeg native library", e2)
            }
        }
    }
    
    // New JNI methods for direct FFmpeg function calls
    /**
     * Initialize the native FFmpeg library
     */
    external fun nativeInit(): Int
    
    /**
     * Execute FFmpeg command synchronously
     */
    external fun nativeExecuteSync(args: Array<String>): Int
    
    /**
     * Get FFmpeg version
     */
    external fun nativeGetVersion(): String
    
    /**
     * Check if FFmpeg is available
     */
    external fun nativeIsAvailable(): Boolean
    
    // Legacy methods for compatibility
    /**
     * Execute FFmpeg binary through JNI (legacy)
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
     * Execute FFmpeg command using new JNI approach (for SDK 34+)
     */
    fun executeDirectJNI(command: String): Int {
        Log.d(TAG, "Executing via direct JNI: $command")
        
        // Parse command into arguments
        val args = parseCommand(command)
        
        return try {
            // Initialize if needed
            nativeInit()
            // Execute using direct JNI call
            nativeExecuteSync(args.toTypedArray())
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Direct JNI execution not available", e)
            -1
        } catch (e: Exception) {
            Log.e(TAG, "Direct JNI execution failed", e)
            -1
        }
    }
    
    /**
     * Execute FFmpeg command (legacy approach)
     */
    fun execute(binaryPath: String, command: String): Int {
        Log.d(TAG, "Executing: $binaryPath $command")
        
        // First try direct JNI if available
        if (isDirectJNIAvailable()) {
            val result = executeDirectJNI(command)
            if (result != -1) {
                return result
            }
        }
        
        // Fall back to legacy approach
        val args = parseCommand(command)
        
        return try {
            nativeExecute(binaryPath, args.toTypedArray())
        } catch (e: Exception) {
            Log.e(TAG, "Native execution failed", e)
            -1
        }
    }
    
    /**
     * Check if direct JNI execution is available
     */
    fun isDirectJNIAvailable(): Boolean {
        return try {
            nativeIsAvailable()
        } catch (e: UnsatisfiedLinkError) {
            false
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