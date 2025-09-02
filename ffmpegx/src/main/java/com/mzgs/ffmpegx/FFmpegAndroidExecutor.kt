package com.mzgs.ffmpegx

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Android-specific FFmpeg executor that works within Android's security constraints
 */
object FFmpegAndroidExecutor {
    private const val TAG = "FFmpegAndroidExecutor"
    
    /**
     * Execute FFmpeg using Android-compatible method
     * This uses app_process to bypass execution restrictions
     */
    fun executeFFmpeg(
        context: Context,
        command: String,
        callback: FFmpegExecutor.ExecutorCallback?
    ): Long {
        Log.d(TAG, "Executing FFmpeg with Android-compatible method")
        
        // Get the FFmpeg binary path
        val ffmpegFile = File(context.filesDir, "ffmpeg")
        
        // First, ensure the binary is extracted
        if (!ffmpegFile.exists() || ffmpegFile.length() < 1000) {
            Log.d(TAG, "FFmpeg not found or invalid, extracting...")
            val abi = FFmpegLibraryLoader.getArchitectureAbi()
            val extracted = FFmpegLibraryLoader.extractToAppFilesDir(
                context,
                "ffmpeg/$abi/libffmpeg.so",
                "ffmpeg"
            )
            if (extracted == null) {
                Log.e(TAG, "Failed to extract FFmpeg")
                callback?.onComplete(127)
                return -1L
            }
        }
        
        // For Android 10+, we need to use a different approach
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            executeUsingAppProcess(context, ffmpegFile.absolutePath, command, callback)
        } else {
            executeUsingRuntime(ffmpegFile.absolutePath, command, callback)
        }
    }
    
    private fun executeUsingAppProcess(
        context: Context,
        ffmpegPath: String,
        command: String,
        callback: FFmpegExecutor.ExecutorCallback?
    ): Long {
        Log.d(TAG, "Using app_process method for Android 10+")
        
        try {
            // Create a wrapper script
            val scriptFile = File(context.filesDir, "ffmpeg_wrapper.sh")
            scriptFile.writeText("""
                #!/system/bin/sh
                # FFmpeg wrapper script
                export LD_LIBRARY_PATH=/system/lib64:/system/lib:${'$'}LD_LIBRARY_PATH
                exec $ffmpegPath $command
            """.trimIndent())
            
            // Make script executable
            scriptFile.setExecutable(true, false)
            
            // Execute using sh
            val process = ProcessBuilder("/system/bin/sh", scriptFile.absolutePath)
                .redirectErrorStream(false)
                .start()
            
            // Handle output
            Thread {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        Log.v(TAG, "stdout: $line")
                        callback?.onOutput(line)
                    }
                }
            }.start()
            
            Thread {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        Log.v(TAG, "stderr: $line")
                        callback?.onError(line)
                    }
                }
            }.start()
            
            Thread {
                val exitCode = process.waitFor()
                Log.i(TAG, "Process completed with exit code: $exitCode")
                callback?.onComplete(exitCode)
            }.start()
            
            return System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "app_process execution failed", e)
            callback?.onComplete(127)
            return -1L
        }
    }
    
    private fun executeUsingRuntime(
        ffmpegPath: String,
        command: String,
        callback: FFmpegExecutor.ExecutorCallback?
    ): Long {
        Log.d(TAG, "Using Runtime.exec for older Android")
        
        try {
            // Parse command
            val args = parseCommand(command)
            val fullCommand = mutableListOf(ffmpegPath)
            fullCommand.addAll(args)
            
            val process = Runtime.getRuntime().exec(fullCommand.toTypedArray())
            
            // Handle output
            Thread {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        callback?.onOutput(line)
                    }
                }
            }.start()
            
            Thread {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        callback?.onError(line)
                    }
                }
            }.start()
            
            Thread {
                val exitCode = process.waitFor()
                callback?.onComplete(exitCode)
            }.start()
            
            return System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Runtime execution failed", e)
            
            // Try shell fallback
            return executeUsingShell(ffmpegPath, command, callback)
        }
    }
    
    private fun executeUsingShell(
        ffmpegPath: String,
        command: String,
        callback: FFmpegExecutor.ExecutorCallback?
    ): Long {
        Log.d(TAG, "Using shell fallback")
        
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", "$ffmpegPath $command")
            )
            
            Thread {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        callback?.onOutput(line)
                    }
                }
            }.start()
            
            Thread {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        callback?.onError(line)
                    }
                }
            }.start()
            
            Thread {
                val exitCode = process.waitFor()
                callback?.onComplete(exitCode)
            }.start()
            
            return System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Shell execution failed", e)
            callback?.onComplete(127)
            return -1L
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
}