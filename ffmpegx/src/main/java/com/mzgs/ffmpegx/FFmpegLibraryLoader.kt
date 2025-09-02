package com.mzgs.ffmpegx

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Handles loading FFmpeg binaries as native libraries
 * This approach works on all Android versions including API 29+
 */
object FFmpegLibraryLoader {
    private const val TAG = "FFmpegLibraryLoader"
    
    /**
     * Get the native library directory where we can execute binaries
     */
    fun getNativeLibraryDir(context: Context): File {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            File(context.applicationInfo.nativeLibraryDir)
        } else {
            File(context.applicationInfo.dataDir, "lib")
        }
    }
    
    /**
     * Extract FFmpeg binary to app files directory
     * For Android 10+, we'll need to use app_process or dalvikvm to execute
     */
    fun extractToAppFilesDir(
        context: Context,
        assetPath: String,
        outputName: String
    ): String? {
        try {
            val targetFile = File(context.filesDir, outputName)
            
            Log.d(TAG, "Extracting $assetPath to ${targetFile.absolutePath}")
            
            // If file already exists, check if it's valid and up-to-date
            if (targetFile.exists()) {
                val existingSize = targetFile.length()
                
                // Check asset size to detect updates
                var assetSize = 0L
                try {
                    context.assets.open(assetPath.replace("ffmpeg", "libffmpeg.so")).use { 
                        assetSize = it.available().toLong()
                    }
                } catch (e: Exception) {
                    // Try alternative paths
                    try {
                        context.assets.open("$assetPath/libffmpeg.so").use { 
                            assetSize = it.available().toLong()
                        }
                    } catch (e2: Exception) {
                        Log.d(TAG, "Could not determine asset size")
                    }
                }
                
                // If sizes differ significantly (new build is much larger), re-extract
                if (assetSize > 0 && (assetSize - existingSize) > 5_000_000) {
                    Log.d(TAG, "New FFmpeg build detected (old: ${existingSize/1024/1024}MB, new: ${assetSize/1024/1024}MB)")
                    Log.d(TAG, "Deleting old binary and extracting new one...")
                    targetFile.delete()
                } else if (existingSize > 1000) {
                    Log.d(TAG, "Binary already exists: ${targetFile.absolutePath} (${existingSize/1024/1024}MB)")
                    makeExecutable(targetFile)
                    return targetFile.absolutePath
                }
            }
            
            // Try different asset paths
            val assetPaths = listOf(
                assetPath,
                assetPath.replace("ffmpeg", "libffmpeg.so"),
                assetPath.replace("ffmpeg/", "ffmpeg/").plus(".so")
            )
            
            var extracted = false
            for (path in assetPaths) {
                try {
                    // Extract from assets
                    context.assets.open(path).use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val bytesCopied = input.copyTo(output, bufferSize = 8192)
                            Log.d(TAG, "Extracted $bytesCopied bytes from $path to ${targetFile.name}")
                            if (bytesCopied > 1000) {
                                extracted = true
                            }
                        }
                    }
                    if (extracted) break
                } catch (e: Exception) {
                    Log.d(TAG, "Asset not found at $path, trying next...")
                }
            }
            
            if (!extracted) {
                Log.e(TAG, "Failed to extract FFmpeg from any asset path")
                return null
            }
            
            // Make executable
            makeExecutable(targetFile)
            
            // Verify
            if (targetFile.exists()) {
                Log.i(TAG, "Successfully extracted: ${targetFile.absolutePath}")
                return targetFile.absolutePath
            } else {
                Log.e(TAG, "File doesn't exist after extraction")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract to app files dir", e)
            return null
        }
    }
    
    private fun makeExecutable(file: File) {
        try {
            // Try multiple methods to make executable
            file.setReadable(true, false)
            file.setExecutable(true, false)
            
            // Try chmod
            try {
                val p = ProcessBuilder("chmod", "755", file.absolutePath).start()
                p.waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "chmod failed: ${e.message}")
            }
            
            Log.d(TAG, "File permissions - readable: ${file.canRead()}, executable: ${file.canExecute()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set permissions", e)
        }
    }
    
    /**
     * Get the architecture-specific subdirectory name
     */
    fun getArchitectureAbi(): String {
        val abis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS
        } else {
            @Suppress("DEPRECATION")
            arrayOf(Build.CPU_ABI, Build.CPU_ABI2)
        }
        
        Log.d(TAG, "Device ABIs: ${abis.joinToString(", ")}")
        
        // Priority order for ABI selection
        return when {
            abis.contains("arm64-v8a") -> "arm64-v8a"
            abis.contains("armeabi-v7a") -> "armeabi-v7a"
            abis.contains("x86_64") -> "x86_64"
            abis.contains("x86") -> "x86"
            else -> "armeabi-v7a" // fallback
        }
    }
    
    /**
     * Try to use the system's FFmpeg if available
     */
    fun findSystemFFmpeg(): String? {
        val possiblePaths = listOf(
            "/system/bin/ffmpeg",
            "/system/xbin/ffmpeg",
            "/data/local/tmp/ffmpeg"
        )
        
        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                Log.i(TAG, "Found system FFmpeg at: $path")
                return path
            }
        }
        
        return null
    }
}