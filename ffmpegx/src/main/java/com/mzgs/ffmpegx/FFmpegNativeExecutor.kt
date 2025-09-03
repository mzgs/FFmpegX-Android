package com.mzgs.ffmpegx

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Native executor that handles FFmpeg execution through JNI
 * This works around Android's execution restrictions
 */
object FFmpegNativeExecutor {
    private const val TAG = "FFmpegNativeExecutor"
    
    init {
        try {
            // Load the wrapper library if it exists
            System.loadLibrary("ffmpeg-wrapper")
            Log.i(TAG, "FFmpeg wrapper library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "FFmpeg wrapper library not found, will use fallback")
        }
    }
    
    /**
     * Execute FFmpeg using the most appropriate method for the device
     */
    fun executeFFmpeg(
        context: Context,
        command: String,
        callback: FFmpegExecutor.ExecutorCallback?
    ): Long {
        Log.d(TAG, "Attempting to execute FFmpeg command: $command")
        
        // Try different execution strategies based on Android version
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
                // Android 9 and below - can execute from app data directory
                executeFromDataDir(context, command, callback)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10+ - need to use native library directory or JNI
                executeFromNativeLibDir(context, command, callback)
            }
            else -> {
                executeWithFallback(context, command, callback)
            }
        }
    }
    
    private fun executeFromDataDir(
        context: Context,
        command: String,
        callback: FFmpegExecutor.ExecutorCallback?
    ): Long {
        Log.d(TAG, "Executing from data directory (Android 9 and below)")
        
        // First ensure FFmpeg is extracted
        val abi = FFmpegLibraryLoader.getArchitectureAbi()
        val ffmpegPath = FFmpegLibraryLoader.extractToAppFilesDir(
            context,
            "ffmpeg/$abi/libffmpeg.so",
            "ffmpeg"
        )
        
        if (ffmpegPath == null) {
            Log.e(TAG, "Could not extract FFmpeg binary")
            callback?.onComplete(127)
            return -1L
        }
        
        Log.d(TAG, "FFmpeg binary path: $ffmpegPath")
        
        // Try direct execution
        val result = FFmpegExecutor.execute(ffmpegPath, command, callback)
        if (result != -1L) {
            return result
        }
        
        // If direct execution fails, try shell wrapper
        return executeWithProcessBuilder(ffmpegPath, command, callback)
    }
    
    private fun executeFromNativeLibDir(
        context: Context,
        command: String,
        callback: FFmpegExecutor.ExecutorCallback?
    ): Long {
        Log.d(TAG, "Handling Android 10+ execution restrictions using native library loading")
        
        // First check if the new JNI implementation is available
        if (FFmpegNative.isDirectJNIAvailable()) {
            Log.d(TAG, "Using direct JNI implementation with static FFmpeg libraries")
            return executeWithDirectJNI(command, callback)
        }
        
        // Try to load FFmpeg as a native library
        val loaded = FFmpegNativeLoader.loadFFmpegLibrary(context)
        
        if (!loaded) {
            Log.e(TAG, "Failed to load FFmpeg as native library, trying JNI fallback")
            // Fallback to JNI approach
            val abi = FFmpegLibraryLoader.getArchitectureAbi()
            val ffmpegPath = FFmpegLibraryLoader.extractToAppFilesDir(
                context,
                "ffmpeg/$abi/libffmpeg.so",
                "ffmpeg"
            )
            
            if (ffmpegPath == null) {
                Log.e(TAG, "Could not extract FFmpeg binary")
                callback?.onComplete(127)
                return -1L
            }
            
            return executeWithJNI(ffmpegPath, command, callback)
        }
        
        // Use the loaded native library
        return executeWithLoadedNativeLib(command, callback)
    }
    
    private fun executeWithProcessBuilder(
        binaryPath: String,
        command: String,
        callback: FFmpegExecutor.ExecutorCallback?
    ): Long {
        Log.d(TAG, "Executing with ProcessBuilder wrapper")
        
        return try {
            // Parse the command arguments
            val args = parseCommand(command)
            val fullCommand = mutableListOf<String>()
            
            // Try direct execution
            fullCommand.add(binaryPath)
            fullCommand.addAll(args)
            
            Log.d(TAG, "Executing command: ${fullCommand.joinToString(" ")}")
            
            val processBuilder = ProcessBuilder(fullCommand)
            processBuilder.redirectErrorStream(false)
            val process = processBuilder.start()
            
            // Handle output
            Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { callback?.onOutput(it) }
                }
            }.start()
            
            Thread {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { callback?.onError(it) }
                }
            }.start()
            
            Thread {
                val exitCode = process.waitFor()
                callback?.onComplete(exitCode)
            }.start()
            
            System.currentTimeMillis() // Return a session ID
        } catch (e: Exception) {
            Log.e(TAG, "ProcessBuilder execution failed", e)
            callback?.onComplete(127)
            -1L
        }
    }
    
    private fun parseCommand(command: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        
        for (char in command) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ' ' && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        args.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(char)
            }
        }
        
        if (current.isNotEmpty()) {
            args.add(current.toString())
        }
        
        return args
    }
    
    private fun executeWithShellWrapper(
        binaryPath: String,
        command: String,
        callback: FFmpegExecutor.ExecutorCallback?
    ): Long {
        Log.d(TAG, "Executing with shell wrapper")
        
        try {
            // Create a shell script that executes FFmpeg
            val scriptContent = """
                #!/system/bin/sh
                $binaryPath $command
            """.trimIndent()
            
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", scriptContent))
            
            // Handle output
            Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { callback?.onOutput(it) }
                }
            }.start()
            
            Thread {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { callback?.onError(it) }
                }
            }.start()
            
            Thread {
                val exitCode = process.waitFor()
                callback?.onComplete(exitCode)
            }.start()
            
            return System.currentTimeMillis() // Return a session ID
        } catch (e: Exception) {
            Log.e(TAG, "Shell wrapper execution failed", e)
            callback?.onComplete(127)
            return -1L
        }
    }
    
    private fun executeWithFallback(
        context: Context,
        command: String,
        callback: FFmpegExecutor.ExecutorCallback?
    ): Long {
        // Try system FFmpeg first
        val systemFFmpeg = FFmpegLibraryLoader.findSystemFFmpeg()
        if (systemFFmpeg != null) {
            Log.d(TAG, "Using system FFmpeg")
            return FFmpegExecutor.execute(systemFFmpeg, command, callback)
        }
        
        // Fall back to data directory
        return executeFromDataDir(context, command, callback)
    }
    
    /**
     * Native method to execute FFmpeg (requires JNI implementation)
     */
    external fun nativeExecuteFFmpeg(command: String): Int
    
    /**
     * Execute using the loaded native library
     */
    private fun executeWithLoadedNativeLib(
        command: String,
        callback: FFmpegExecutor.ExecutorCallback?
    ): Long {
        Log.d(TAG, "Executing FFmpeg via loaded native library")
        
        Thread {
            try {
                // Parse command into args array
                val args = parseCommand(command).toTypedArray()
                
                // Call the native method from loaded library
                val exitCode = FFmpegNativeLoader.executeFFmpegNative(args)
                
                Log.d(TAG, "Native execution completed with code: $exitCode")
                callback?.onComplete(exitCode)
            } catch (e: Exception) {
                Log.e(TAG, "Native library execution failed", e)
                callback?.onError("Native execution failed: ${e.message}")
                callback?.onComplete(-1)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Native method not found", e)
                callback?.onError("Native method not found: ${e.message}")
                callback?.onComplete(-1)
            }
        }.start()
        
        return System.currentTimeMillis() // Return a session ID
    }
    
    private fun executeWithDirectJNI(
        command: String,
        callback: FFmpegExecutor.ExecutorCallback?
    ): Long {
        Log.d(TAG, "Executing via direct JNI with static FFmpeg libraries")
        
        Thread {
            try {
                // Use the executeDirectJNI method which already exists
                val exitCode = FFmpegNative.executeDirectJNI(command)
                
                Log.d(TAG, "Direct JNI execution completed with code: $exitCode")
                callback?.onComplete(exitCode)
            } catch (e: Exception) {
                Log.e(TAG, "Direct JNI execution failed", e)
                callback?.onError("Direct JNI execution failed: ${e.message}")
                callback?.onComplete(-1)
            }
        }.start()
        
        return System.currentTimeMillis() // Return a session ID
    }
    
    private fun executeWithJNI(
        binaryPath: String,
        command: String,
        callback: FFmpegExecutor.ExecutorCallback?
    ): Long {
        Log.d(TAG, "Executing via new JNI approach: $binaryPath $command")
        
        // Execute in a thread
        Thread {
            try {
                // Use the new JNI approach that calls FFmpeg functions directly
                val exitCode = FFmpegJNI.executeFFmpeg(binaryPath, command)
                Log.d(TAG, "FFmpeg JNI execution completed with code: $exitCode")
                callback?.onComplete(exitCode)
            } catch (e: Exception) {
                Log.e(TAG, "JNI execution failed", e)
                callback?.onError("JNI execution failed: ${e.message}")
                callback?.onComplete(-1)
            }
        }.start()
        
        return System.currentTimeMillis() // Return a session ID
    }
}