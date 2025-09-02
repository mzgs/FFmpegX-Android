package com.mzgs.ffmpeglib

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Alternative FFmpeg executor that uses shell to run the binary
 * This can work around some permission issues on certain Android versions
 */
object FFmpegShellExecutor {
    private const val TAG = "FFmpegShellExecutor"
    
    fun executeWithShell(
        binaryPath: String,
        command: String,
        callback: FFmpegExecutor.ExecutorCallback?
    ): Long {
        Log.d(TAG, "Executing FFmpeg via shell: $binaryPath $command")
        
        return try {
            // Build the full command with shell
            val fullCommand = "sh -c \"$binaryPath $command\""
            Log.d(TAG, "Shell command: $fullCommand")
            
            // Execute using shell
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$binaryPath $command"))
            
            // Read output in separate threads
            Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        reader.lineSequence().forEach { line ->
                            Log.v(TAG, "FFmpeg stdout: $line")
                            callback?.onOutput(line)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading stdout", e)
                }
            }.start()
            
            Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        reader.lineSequence().forEach { line ->
                            Log.v(TAG, "FFmpeg stderr: $line")
                            callback?.onError(line)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading stderr", e)
                }
            }.start()
            
            // Wait for process to complete
            Thread {
                try {
                    val exitCode = process.waitFor()
                    Log.i(TAG, "Shell execution completed with exit code: $exitCode")
                    callback?.onComplete(exitCode)
                } catch (e: Exception) {
                    Log.e(TAG, "Error waiting for process", e)
                    callback?.onComplete(-1)
                }
            }.start()
            
            1L // Return a dummy session ID for shell execution
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute FFmpeg via shell", e)
            callback?.onComplete(127)
            -1L
        }
    }
    
    /**
     * Try to execute directly first, fall back to shell if that fails
     */
    fun executeWithFallback(
        binaryPath: String,
        command: String,
        callback: FFmpegExecutor.ExecutorCallback?
    ): Long {
        // First try direct execution
        val sessionId = FFmpegExecutor.execute(binaryPath, command, callback)
        
        // If direct execution failed, try shell execution
        if (sessionId == -1L) {
            Log.w(TAG, "Direct execution failed, trying shell execution")
            return executeWithShell(binaryPath, command, callback)
        }
        
        return sessionId
    }
}