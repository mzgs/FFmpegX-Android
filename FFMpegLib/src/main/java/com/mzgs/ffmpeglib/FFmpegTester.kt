package com.mzgs.ffmpeglib

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Simple FFmpeg tester that tries different execution methods
 */
object FFmpegTester {
    private const val TAG = "FFmpegTester"
    
    suspend fun testFFmpeg(context: Context): TestResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting FFmpeg test...")
        
        // First ensure FFmpeg is installed
        if (!FFmpegInstaller.isFFmpegInstalled(context)) {
            Log.d(TAG, "FFmpeg not installed, attempting installation...")
            val installer = FFmpegInstaller(context)
            val installed = installer.installFFmpeg()
            if (!installed) {
                return@withContext TestResult(
                    success = false,
                    message = "Failed to install FFmpeg",
                    version = null
                )
            }
        }
        
        // Try to get the FFmpeg path
        val abi = FFmpegLibraryLoader.getArchitectureAbi()
        val ffmpegPath = FFmpegLibraryLoader.extractToAppFilesDir(
            context,
            "ffmpeg/$abi/libffmpeg.so",
            "ffmpeg"
        )
        
        if (ffmpegPath == null) {
            return@withContext TestResult(
                success = false,
                message = "Could not extract FFmpeg binary",
                version = null
            )
        }
        
        Log.d(TAG, "FFmpeg path: $ffmpegPath")
        
        // Try different execution methods
        val methods = listOf(
            "Native loader" to { testNativeLoader(context) },
            "Executable wrapper" to { testExecutableWrapper(context) },
            "JNI execution" to { testJNIExecution(ffmpegPath) },
            "Direct execution" to { testDirectExecution(ffmpegPath) },
            "Shell execution" to { testShellExecution(ffmpegPath) },
            "ProcessBuilder" to { testProcessBuilder(ffmpegPath) }
        )
        
        for ((methodName, method) in methods) {
            Log.d(TAG, "Trying $methodName...")
            try {
                val result = method()
                if (result != null) {
                    Log.i(TAG, "Success with $methodName: $result")
                    return@withContext TestResult(
                        success = true,
                        message = "FFmpeg works with $methodName",
                        version = result
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "$methodName failed: ${e.message}")
            }
        }
        
        return@withContext TestResult(
            success = false,
            message = "All execution methods failed",
            version = null
        )
    }
    
    private fun testNativeLoader(context: Context): String? {
        return try {
            val loaded = FFmpegNativeLoader.loadFFmpegLibrary(context)
            if (loaded) {
                "FFmpeg loaded as native library"
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Native loader failed", e)
            null
        }
    }
    
    private fun testExecutableWrapper(context: Context): String? {
        return try {
            val execPath = FFmpegNativeLoader.createExecutableWrapper(context)
            if (execPath != null) {
                val process = Runtime.getRuntime().exec(arrayOf(execPath, "-version"))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val firstLine = reader.readLine()
                process.waitFor()
                if (process.exitValue() == 0 && firstLine != null) {
                    firstLine
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Executable wrapper failed", e)
            null
        }
    }
    
    private fun testJNIExecution(ffmpegPath: String): String? {
        return try {
            val result = FFmpegJNI.executeFFmpeg(ffmpegPath, "-version")
            if (result == 0) {
                "FFmpeg via JNI"
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "JNI execution failed", e)
            null
        }
    }
    
    private fun testDirectExecution(ffmpegPath: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(ffmpegPath, "-version"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val firstLine = reader.readLine()
            process.waitFor()
            if (process.exitValue() == 0 && firstLine != null) {
                firstLine
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct execution failed", e)
            null
        }
    }
    
    private fun testShellExecution(ffmpegPath: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$ffmpegPath -version"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val firstLine = reader.readLine()
            process.waitFor()
            if (process.exitValue() == 0 && firstLine != null) {
                firstLine
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shell execution failed", e)
            null
        }
    }
    
    private fun testProcessBuilder(ffmpegPath: String): String? {
        return try {
            val pb = ProcessBuilder("sh", "-c", "$ffmpegPath -version")
            pb.redirectErrorStream(true)
            val process = pb.start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val firstLine = reader.readLine()
            process.waitFor()
            if (process.exitValue() == 0 && firstLine != null) {
                firstLine
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "ProcessBuilder execution failed", e)
            null
        }
    }
    
    data class TestResult(
        val success: Boolean,
        val message: String,
        val version: String?
    )
}