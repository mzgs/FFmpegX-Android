package com.mzgs.ffmpegx

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong

/**
 * Direct FFmpeg executor using ProcessBuilder instead of JNI
 */
object FFmpegExecutor {
    private const val TAG = "FFmpegExecutor"
    private val sessionCounter = AtomicLong(0)
    private val runningProcesses = mutableMapOf<Long, Process>()
    
    interface ExecutorCallback {
        fun onOutput(output: String)
        fun onError(error: String)
        fun onComplete(exitCode: Int)
    }
    
    fun execute(
        binaryPath: String,
        command: String,
        callback: ExecutorCallback?
    ): Long {
        val sessionId = sessionCounter.incrementAndGet()
        Log.d(TAG, "Starting session $sessionId with command: $command")
        
        return try {
            // Parse command into arguments
            val args = parseCommand(command)
            val fullCommand = mutableListOf(binaryPath)
            fullCommand.addAll(args)
            
            Log.d(TAG, "Full command: ${fullCommand.joinToString(" ")}")
            
            // Create process
            val processBuilder = ProcessBuilder(fullCommand)
            processBuilder.redirectErrorStream(false)
            
            val process = processBuilder.start()
            runningProcesses[sessionId] = process
            
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
                    Log.i(TAG, "Session $sessionId completed with exit code: $exitCode")
                    runningProcesses.remove(sessionId)
                    callback?.onComplete(exitCode)
                } catch (e: Exception) {
                    Log.e(TAG, "Error waiting for process", e)
                    runningProcesses.remove(sessionId)
                    callback?.onComplete(-1)
                }
            }.start()
            
            sessionId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FFmpeg process", e)
            callback?.onComplete(127) // Command not found
            -1L
        }
    }
    
    fun cancel(sessionId: Long): Boolean {
        val process = runningProcesses[sessionId]
        return if (process != null) {
            try {
                process.destroy()
                runningProcesses.remove(sessionId)
                Log.i(TAG, "Cancelled session $sessionId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel session $sessionId", e)
                false
            }
        } else {
            false
        }
    }
    
    fun cancelAll() {
        runningProcesses.forEach { (sessionId, process) ->
            try {
                process.destroy()
                Log.i(TAG, "Cancelled session $sessionId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel session $sessionId", e)
            }
        }
        runningProcesses.clear()
    }
    
    fun isRunning(sessionId: Long): Boolean {
        return runningProcesses.containsKey(sessionId)
    }
    
    private fun parseCommand(command: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var escapeNext = false
        
        for (char in command) {
            when {
                escapeNext -> {
                    current.append(char)
                    escapeNext = false
                }
                char == '\\' -> {
                    escapeNext = true
                }
                char == '"' -> {
                    inQuotes = !inQuotes
                }
                char == ' ' && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        args.add(current.toString())
                        current.clear()
                    }
                }
                else -> {
                    current.append(char)
                }
            }
        }
        
        if (current.isNotEmpty()) {
            args.add(current.toString())
        }
        
        return args
    }
}