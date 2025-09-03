package com.mzgs.ffmpegx

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class FFmpegInstaller(private val context: Context) {
    
    companion object {
        private const val TAG = "FFmpegInstaller"
        private const val FFMPEG_BINARY = "ffmpeg"
        private const val FFPROBE_BINARY = "ffprobe"
        
        fun isFFmpegInstalled(context: Context): Boolean {
            val ffmpegFile = File(context.filesDir, FFMPEG_BINARY)
            val exists = ffmpegFile.exists()
            val canExecute = ffmpegFile.canExecute()
            Log.d(TAG, "FFmpeg file exists: $exists, can execute: $canExecute at ${ffmpegFile.absolutePath}")
            return exists && canExecute
        }
        
        fun getFFmpegPath(context: Context): String {
            return File(context.filesDir, FFMPEG_BINARY).absolutePath
        }
        
        fun getFFprobePath(context: Context): String {
            return File(context.filesDir, FFPROBE_BINARY).absolutePath
        }
        
        fun getDeviceABI(): String {
            val supportedAbis = Build.SUPPORTED_ABIS
            Log.d(TAG, "Device supported ABIs: ${supportedAbis.joinToString(", ")}")
            
            val selectedAbi = when {
                supportedAbis.contains("arm64-v8a") -> "arm64-v8a"
                supportedAbis.contains("armeabi-v7a") -> "armeabi-v7a"
                supportedAbis.contains("x86_64") -> "x86_64"
                supportedAbis.contains("x86") -> "x86"
                else -> "armeabi-v7a" // Default fallback
            }
            Log.i(TAG, "Selected ABI: $selectedAbi")
            return selectedAbi
        }
    }
    
    interface InstallProgressListener {
        fun onProgressUpdate(progress: Int, message: String)
        fun onInstallComplete(success: Boolean, message: String)
    }
    
    suspend fun installFFmpeg(listener: InstallProgressListener? = null): Boolean = 
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Starting FFmpeg installation process")
            try {
                listener?.onProgressUpdate(0, "Checking for existing installation...")
                
                // Check if already installed
                if (isFFmpegInstalled(context)) {
                    Log.i(TAG, "FFmpeg is already installed")
                    listener?.onProgressUpdate(100, "FFmpeg is already installed")
                    listener?.onInstallComplete(true, "FFmpeg is already installed")
                    return@withContext true
                }
                Log.d(TAG, "FFmpeg not found, proceeding with installation")
                
                listener?.onProgressUpdate(20, "Detecting device architecture...")
                val abi = getDeviceABI()
                
                listener?.onProgressUpdate(40, "Extracting FFmpeg binaries for $abi...")
                
                // Extract from assets
                val success = extractFFmpegFromAssets(abi, listener)
                
                if (success) {
                    listener?.onProgressUpdate(100, "Installation complete")
                    listener?.onInstallComplete(true, "FFmpeg installed successfully")
                } else {
                    listener?.onInstallComplete(false, "Failed to install FFmpeg")
                }
                
                success
            } catch (e: Exception) {
                Log.e(TAG, "FFmpeg installation failed", e)
                listener?.onInstallComplete(false, "Installation failed: ${e.message}")
                false
            }
        }
    
    private fun extractFFmpegFromAssets(abi: String, listener: InstallProgressListener?): Boolean {
        Log.d(TAG, "Extracting FFmpeg binaries for ABI: $abi")
        return try {
            listener?.onProgressUpdate(50, "Extracting ffmpeg binary...")
            
            // For Android 10+, try to extract to native lib directory first
            val targetFile = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // Try to use app's native library directory
                val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
                if (!nativeLibDir.exists()) {
                    nativeLibDir.mkdirs()
                }
                File(context.filesDir, FFMPEG_BINARY)
            } else {
                File(context.filesDir, FFMPEG_BINARY)
            }
            
            // Try to extract ffmpeg binary (might be named libffmpeg.so)
            val ffmpegAssetPaths = listOf("ffmpeg/$abi/libffmpeg.so", "ffmpeg/$abi/ffmpeg")
            var extracted = false
            for (assetPath in ffmpegAssetPaths) {
                try {
                    Log.d(TAG, "Trying to extract asset: $assetPath")
                    extractAsset(assetPath, FFMPEG_BINARY)
                    extracted = true
                    break
                } catch (e: Exception) {
                    Log.d(TAG, "Asset not found: $assetPath, trying next...")
                }
            }
            if (!extracted) {
                throw Exception("Could not find FFmpeg binary in assets")
            }
            
            listener?.onProgressUpdate(70, "Extracting ffprobe binary...")
            
            // Try to extract ffprobe binary (might not exist)
            val ffprobeAssetPath = "ffmpeg/$abi/ffprobe"
            try {
                Log.d(TAG, "Extracting asset: $ffprobeAssetPath")
                extractAsset(ffprobeAssetPath, FFPROBE_BINARY)
            } catch (e: Exception) {
                Log.w(TAG, "ffprobe not available, skipping: ${e.message}")
                // ffprobe is optional, continue without it
            }
            
            listener?.onProgressUpdate(90, "Setting permissions...")
            
            // Make binaries executable
            makeExecutable()
            
            Log.i(TAG, "FFmpeg binaries extracted successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract FFmpeg for $abi", e)
            // If the exact ABI is not found, try with fallback
            if (abi != "armeabi-v7a") {
                Log.w(TAG, "Trying fallback architecture: armeabi-v7a")
                listener?.onProgressUpdate(60, "Trying fallback architecture...")
                try {
                    // Try both naming conventions for fallback
                    try {
                        extractAsset("ffmpeg/armeabi-v7a/libffmpeg.so", FFMPEG_BINARY)
                    } catch (e: Exception) {
                        extractAsset("ffmpeg/armeabi-v7a/ffmpeg", FFMPEG_BINARY)
                    }
                    try {
                        extractAsset("ffmpeg/armeabi-v7a/ffprobe", FFPROBE_BINARY)
                    } catch (e: Exception) {
                        Log.w(TAG, "ffprobe not available in fallback, skipping")
                    }
                    makeExecutable()
                    Log.i(TAG, "Fallback extraction successful")
                    true
                } catch (fallbackException: Exception) {
                    Log.e(TAG, "Fallback extraction failed", fallbackException)
                    false
                }
            } else {
                false
            }
        }
    }
    
    private fun extractAsset(assetPath: String, outputFileName: String) {
        val outputFile = File(context.filesDir, outputFileName)
        Log.d(TAG, "Extracting $assetPath to ${outputFile.absolutePath}")
        
        // Delete existing file if present
        if (outputFile.exists()) {
            outputFile.delete()
        }
        
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outputFile).use { output ->
                    val bytesCopied = input.copyTo(output, bufferSize = 8192)
                    Log.d(TAG, "Extracted $bytesCopied bytes to ${outputFile.name}")
                    
                    // Validate that this is a real binary, not a placeholder
                    if (bytesCopied < 1000) { // Real FFmpeg binaries are at least several MB
                        Log.e(TAG, "WARNING: Extracted file is too small ($bytesCopied bytes). This appears to be a placeholder.")
                        throw FFmpegException.InstallationException(
                            "FFmpeg binary is a placeholder. Please download real FFmpeg binaries. " +
                            "See: FFMpegLib/src/main/assets/ffmpeg/SETUP_INSTRUCTIONS.md"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract asset: $assetPath", e)
            throw e
        }
    }
    
    private fun makeExecutable() {
        val ffmpegFile = File(context.filesDir, FFMPEG_BINARY)
        val ffprobeFile = File(context.filesDir, FFPROBE_BINARY)
        Log.d(TAG, "Setting executable permissions for binaries")
        
        // First set readable and executable permissions for ffmpeg
        ffmpegFile.setReadable(true, false)
        ffmpegFile.setExecutable(true, false)
        
        // Set permissions for ffprobe if it exists
        if (ffprobeFile.exists()) {
            ffprobeFile.setReadable(true, false) 
            ffprobeFile.setExecutable(true, false)
        }
        
        val ffmpegExecutable = ffmpegFile.canExecute()
        val ffprobeExecutable = if (ffprobeFile.exists()) ffprobeFile.canExecute() else false
        Log.d(TAG, "FFmpeg executable: $ffmpegExecutable, FFprobe exists: ${ffprobeFile.exists()}, executable: $ffprobeExecutable")
        
        // Try multiple approaches to set executable permissions
        try {
            // Try chmod with ProcessBuilder for better control
            val chmodFFmpeg = ProcessBuilder("chmod", "755", ffmpegFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            chmodFFmpeg.waitFor()
            
            if (ffprobeFile.exists()) {
                val chmodFFprobe = ProcessBuilder("chmod", "755", ffprobeFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                chmodFFprobe.waitFor()
            }
            
            Log.d(TAG, "chmod 755 executed for binaries via ProcessBuilder")
        } catch (e: Exception) {
            Log.w(TAG, "chmod via ProcessBuilder failed", e)
            
            // Fallback to Runtime.exec
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", ffmpegFile.absolutePath)).waitFor()
                if (ffprobeFile.exists()) {
                    Runtime.getRuntime().exec(arrayOf("chmod", "755", ffprobeFile.absolutePath)).waitFor()
                }
                Log.d(TAG, "chmod 755 executed for binaries via Runtime.exec")
            } catch (e2: Exception) {
                Log.w(TAG, "chmod via Runtime.exec also failed", e2)
            }
        }
        
        // Final check
        Log.i(TAG, "Final permissions - FFmpeg: canRead=${ffmpegFile.canRead()}, canExecute=${ffmpegFile.canExecute()}")
        if (ffprobeFile.exists()) {
            Log.i(TAG, "Final permissions - FFprobe: canRead=${ffprobeFile.canRead()}, canExecute=${ffprobeFile.canExecute()}")
        }
    }
    
    fun uninstallFFmpeg() {
        File(context.filesDir, FFMPEG_BINARY).delete()
        File(context.filesDir, FFPROBE_BINARY).delete()
    }
    
    fun getInstalledVersion(): String? {
        // First try to get version from native JNI if available
        try {
            if (FFmpegNative.isDirectJNIAvailable()) {
                val version = FFmpegNative.nativeGetVersion()
                if (version.isNotEmpty()) {
                    return version
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get version from native library", e)
        }
        
        // Fallback: check if binary is installed (for display purposes)
        return if (isFFmpegInstalled(context)) {
            // On Android 10+ we can't execute binaries, so return a generic version
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                "FFmpeg 6.0 (JNI)"
            } else {
                // Try to execute on older Android versions
                try {
                    val process = ProcessBuilder(getFFmpegPath(context), "-version")
                        .redirectErrorStream(true)
                        .start()
                    
                    val output = process.inputStream.bufferedReader().use { it.readLine() }
                    process.waitFor()
                    
                    output?.substringAfter("version ")?.substringBefore(" ")
                } catch (e: Exception) {
                    "FFmpeg (installed)"
                }
            }
        } else {
            null
        }
    }
    
    fun getInstalledSize(): Long {
        val ffmpegFile = File(context.filesDir, FFMPEG_BINARY)
        val ffprobeFile = File(context.filesDir, FFPROBE_BINARY)
        
        return (if (ffmpegFile.exists()) ffmpegFile.length() else 0L) +
               (if (ffprobeFile.exists()) ffprobeFile.length() else 0L)
    }
    
    fun verifyInstallation(): Boolean {
        Log.d(TAG, "Verifying FFmpeg installation")
        if (!isFFmpegInstalled(context)) {
            Log.w(TAG, "FFmpeg not installed")
            return false
        }
        
        return try {
            // Try to run ffmpeg with -version flag
            val process = ProcessBuilder(getFFmpegPath(context), "-version")
                .redirectErrorStream(true)
                .start()
            
            val exitCode = process.waitFor()
            Log.i(TAG, "FFmpeg version check exit code: $exitCode")
            if (exitCode == 0) {
                Log.i(TAG, "FFmpeg installation verified successfully")
            } else {
                Log.e(TAG, "FFmpeg verification failed with exit code: $exitCode")
            }
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify FFmpeg installation", e)
            false
        }
    }
}