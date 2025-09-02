package com.mzgs.ffmpegx

import android.util.Log

/**
 * JNI interface for FFmpeg library execution
 * This approach works on Android 10+ by calling FFmpeg functions directly through JNI
 * instead of trying to execute a binary file
 */
object FFmpegJNI {
    private const val TAG = "FFmpegJNI"
    
    init {
        try {
            System.loadLibrary("ffmpeg_lib_jni")
            Log.i(TAG, "FFmpeg JNI library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load ffmpeg_lib_jni library", e)
        }
    }
    
    /**
     * Load FFmpeg library from file path
     * @param libPath Path to the FFmpeg binary/library
     * @return true if loaded successfully
     */
    external fun nativeLoadFFmpeg(libPath: String): Boolean
    
    /**
     * Run FFmpeg command through loaded library
     * @param args Command arguments (without "ffmpeg" prefix)
     * @return Exit code (0 for success)
     */
    external fun nativeRunCommand(args: Array<String>): Int
    
    /**
     * Execute FFmpeg directly (fallback method)
     * @param binaryPath Path to FFmpeg binary
     * @param args Command arguments
     * @return Exit code
     */
    external fun nativeExecuteDirect(binaryPath: String, args: Array<String>): Int
    
    /**
     * Unload FFmpeg library
     */
    external fun nativeUnloadFFmpeg()
    
    /**
     * High-level function to execute FFmpeg command
     */
    fun executeFFmpeg(binaryPath: String, command: String): Int {
        Log.d(TAG, "Executing FFmpeg: $command")
        
        // Use the popen wrapper which works on Android 10+
        return FFmpegWrapper.execute(binaryPath, command, object : FFmpegWrapper.Callback {
            override fun onOutput(line: String) {
                Log.d(TAG, "FFmpeg: $line")
            }
            
            override fun onError(line: String) {
                Log.e(TAG, "FFmpeg error: $line")
            }
            
            override fun onComplete(exitCode: Int) {
                Log.i(TAG, "FFmpeg completed with exit code: $exitCode")
            }
        })
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
