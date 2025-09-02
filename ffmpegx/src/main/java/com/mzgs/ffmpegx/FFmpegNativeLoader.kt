package com.mzgs.ffmpegx

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Loads FFmpeg binaries as native libraries
 * This works around Android's execution restrictions
 */
object FFmpegNativeLoader {
    private const val TAG = "FFmpegNativeLoader"
    private const val FFMPEG_LIB_NAME = "ffmpeg_exec"
    
    /**
     * Load FFmpeg as a native library
     */
    fun loadFFmpegLibrary(context: Context): Boolean {
        try {
            // First, try to load directly if it's already in the native libs
            return try {
                System.loadLibrary(FFMPEG_LIB_NAME)
                Log.i(TAG, "FFmpeg library loaded from native libs")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.d(TAG, "Library not in native libs, extracting from assets")
                extractAndLoadFromAssets(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load FFmpeg library", e)
            return false
        }
    }
    
    /**
     * Extract from assets and load - with architecture fallback
     */
    private fun extractAndLoadFromAssets(context: Context): Boolean {
        val abi = getDeviceABI()
        
        // Use the correct architecture binary
        val tryPaths = when (abi) {
            "arm64-v8a" -> listOf(
                "ffmpeg/arm64-v8a/libffmpeg.so",     // Try 64-bit first (we have a real one now!)
                "ffmpeg/armeabi-v7a/libffmpeg.so"    // Fallback to 32-bit if needed
            )
            "x86_64" -> listOf(
                "ffmpeg/x86_64/libffmpeg.so",        // Try 64-bit first
                "ffmpeg/x86/libffmpeg.so"            // Fallback to 32-bit
            )
            else -> listOf("ffmpeg/$abi/libffmpeg.so")
        }
        
        Log.d(TAG, "Device is $abi, will try: ${tryPaths.joinToString(", ")}")
        
        // Android expects libraries in specific locations
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val libsDir = File(context.filesDir, "libs")
        libsDir.mkdirs()
        
        // Target file with proper lib naming
        val targetFile = File(libsDir, "lib${FFMPEG_LIB_NAME}.so")
        
        // Try each path until one works
        for (assetPath in tryPaths) {
            Log.d(TAG, "Trying to extract $assetPath to ${targetFile.absolutePath}")
            
            try {
                // Extract from assets
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val bytes = input.copyTo(output)
                        Log.d(TAG, "Extracted $bytes bytes from $assetPath")
                    }
                }
                
                // If extraction succeeded, break the loop
                break
            } catch (e: Exception) {
                Log.d(TAG, "Failed to extract $assetPath: ${e.message}")
                // Continue to next path
            }
        }
        
        // Check if file was extracted
        if (!targetFile.exists() || targetFile.length() < 1000) {
            Log.e(TAG, "Failed to extract any FFmpeg binary")
            return false
        }
        
        try {
            
            // Make executable
            targetFile.setExecutable(true, false)
            targetFile.setReadable(true, false)
            
            // Try to load the extracted library
            return try {
                System.load(targetFile.absolutePath)
                Log.i(TAG, "Successfully loaded FFmpeg from: ${targetFile.absolutePath}")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load extracted library", e)
                // Try alternative loading method
                loadUsingRuntime(targetFile.absolutePath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract and load", e)
            return false
        }
    }
    
    /**
     * Load using Runtime.load as fallback
     */
    private fun loadUsingRuntime(libPath: String): Boolean {
        return try {
            Runtime.getRuntime().load(libPath)
            Log.i(TAG, "Loaded using Runtime.load: $libPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Runtime.load failed", e)
            false
        }
    }
    
    /**
     * Get device ABI - with better detection and fallback
     */
    private fun getDeviceABI(): String {
        val abis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS
        } else {
            @Suppress("DEPRECATION")
            arrayOf(Build.CPU_ABI, Build.CPU_ABI2)
        }
        
        Log.d(TAG, "Device ABIs: ${abis.joinToString(", ")}")
        
        // Check for 64-bit first
        return when {
            abis.contains("arm64-v8a") -> {
                Log.d(TAG, "Selected arm64-v8a (64-bit ARM)")
                "arm64-v8a"
            }
            abis.contains("x86_64") -> {
                Log.d(TAG, "Selected x86_64 (64-bit x86)")
                "x86_64"
            }
            abis.contains("armeabi-v7a") -> {
                Log.d(TAG, "Selected armeabi-v7a (32-bit ARM)")
                "armeabi-v7a"
            }
            abis.contains("x86") -> {
                Log.d(TAG, "Selected x86 (32-bit x86)")
                "x86"
            }
            else -> {
                Log.w(TAG, "No matching ABI found, defaulting to armeabi-v7a")
                "armeabi-v7a"
            }
        }
    }
    
    /**
     * Execute FFmpeg after loading
     */
    external fun executeFFmpegNative(args: Array<String>): Int
    
    /**
     * Alternative: Create a wrapper that makes the binary executable
     * This works better for 64-bit devices with 32-bit binaries
     */
    fun createExecutableWrapper(context: Context): String? {
        val abi = getDeviceABI()
        
        // Use the correct architecture binary
        val assetPath = when (abi) {
            "arm64-v8a" -> "ffmpeg/arm64-v8a/libffmpeg.so"     // We have a real 64-bit binary now!
            "x86_64" -> "ffmpeg/x86_64/libffmpeg.so"           // Use 64-bit for x86_64
            else -> "ffmpeg/$abi/libffmpeg.so"
        }
        
        Log.d(TAG, "Device ABI: $abi, using binary from: $assetPath")
        
        // Copy to app's native lib directory with a different approach
        val privateDir = context.getDir("ffmpeg", Context.MODE_PRIVATE)
        val targetFile = File(privateDir, "ffmpeg")
        
        try {
            // Extract binary
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // Make it executable using multiple methods
            makeExecutable(targetFile)
            
            Log.i(TAG, "Created executable at: ${targetFile.absolutePath}")
            return targetFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create executable wrapper", e)
            return null
        }
    }
    
    private fun makeExecutable(file: File) {
        // Method 1: File.setExecutable
        file.setExecutable(true, false)
        file.setReadable(true, false)
        
        // Method 2: ProcessBuilder chmod
        try {
            ProcessBuilder("chmod", "755", file.absolutePath).start().waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "chmod via ProcessBuilder failed", e)
        }
        
        // Method 3: Runtime.exec chmod  
        try {
            Runtime.getRuntime().exec(arrayOf("chmod", "755", file.absolutePath)).waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "chmod via Runtime failed", e)
        }
    }
}