package com.mzgs.ffmpegx

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Specialized audio extraction with multiple fallback strategies
 */
class FFmpegAudioExtractor(private val ffmpegHelper: FFmpegHelper) {
    
    companion object {
        private const val TAG = "FFmpegAudioExtractor"
    }
    
    /**
     * Extract audio with multiple fallback strategies
     */
    suspend fun extractAudioWithFallback(
        inputPath: String,
        outputPath: String,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Attempting audio extraction from: $inputPath to: $outputPath")
        
        // Strategy 1: Try with libmp3lame encoder
        if (tryExtractWithEncoder(inputPath, outputPath, "libmp3lame", callback)) {
            Log.i(TAG, "Successfully extracted audio using libmp3lame")
            return@withContext true
        }
        
        // Strategy 2: Try with mp3 encoder (generic)
        if (tryExtractWithEncoder(inputPath, outputPath, "mp3", callback)) {
            Log.i(TAG, "Successfully extracted audio using mp3 encoder")
            return@withContext true
        }
        
        // Strategy 3: Try extracting to AAC format first, then convert
        val aacPath = outputPath.replace(".mp3", "_temp.aac")
        if (tryExtractToAAC(inputPath, aacPath, callback)) {
            Log.i(TAG, "Successfully extracted audio to AAC, now converting to MP3")
            // If AAC extraction worked, try to convert to MP3
            if (tryConvertAudioFormat(aacPath, outputPath, callback)) {
                File(aacPath).delete() // Clean up temp file
                return@withContext true
            }
            // If conversion failed, rename AAC to MP3 (some players can handle it)
            File(aacPath).renameTo(File(outputPath))
            Log.w(TAG, "Could not convert to MP3, using AAC with .mp3 extension")
            return@withContext true
        }
        
        // Strategy 4: Try simple audio copy
        if (trySimpleAudioCopy(inputPath, outputPath, callback)) {
            Log.i(TAG, "Successfully copied audio stream")
            return@withContext true
        }
        
        // Strategy 5: Use the most basic extraction
        if (tryBasicExtraction(inputPath, outputPath, callback)) {
            Log.i(TAG, "Successfully extracted audio using basic method")
            return@withContext true
        }
        
        Log.e(TAG, "All audio extraction strategies failed")
        false
    }
    
    private suspend fun tryExtractWithEncoder(
        inputPath: String,
        outputPath: String,
        encoder: String,
        callback: FFmpegHelper.FFmpegCallback?
    ): Boolean {
        val command = "-i \"$inputPath\" -vn -acodec $encoder -ab 192k -ar 44100 -y \"$outputPath\""
        
        Log.d(TAG, "Trying extraction with encoder: $encoder")
        val result = ffmpegHelper.execute(command, callback)
        
        // Check if output file was created and has content
        val outputFile = File(outputPath)
        return result && outputFile.exists() && outputFile.length() > 0
    }
    
    private suspend fun tryExtractToAAC(
        inputPath: String,
        outputPath: String,
        callback: FFmpegHelper.FFmpegCallback?
    ): Boolean {
        val command = "-i \"$inputPath\" -vn -acodec aac -ab 192k -ar 44100 -y \"$outputPath\""
        
        Log.d(TAG, "Trying extraction to AAC format")
        val result = ffmpegHelper.execute(command, callback)
        
        val outputFile = File(outputPath)
        return result && outputFile.exists() && outputFile.length() > 0
    }
    
    private suspend fun tryConvertAudioFormat(
        inputPath: String,
        outputPath: String,
        callback: FFmpegHelper.FFmpegCallback?
    ): Boolean {
        val command = "-i \"$inputPath\" -acodec libmp3lame -ab 192k -ar 44100 -y \"$outputPath\""
        
        Log.d(TAG, "Trying to convert audio format")
        val result = ffmpegHelper.execute(command, callback)
        
        val outputFile = File(outputPath)
        return result && outputFile.exists() && outputFile.length() > 0
    }
    
    private suspend fun trySimpleAudioCopy(
        inputPath: String,
        outputPath: String,
        callback: FFmpegHelper.FFmpegCallback?
    ): Boolean {
        // Try to copy audio stream without re-encoding
        val command = "-i \"$inputPath\" -vn -acodec copy -y \"$outputPath\""
        
        Log.d(TAG, "Trying simple audio copy")
        val result = ffmpegHelper.execute(command, callback)
        
        val outputFile = File(outputPath)
        return result && outputFile.exists() && outputFile.length() > 0
    }
    
    private suspend fun tryBasicExtraction(
        inputPath: String,
        outputPath: String,
        callback: FFmpegHelper.FFmpegCallback?
    ): Boolean {
        // Most basic extraction - let FFmpeg decide everything
        val command = "-i \"$inputPath\" -vn -y \"$outputPath\""
        
        Log.d(TAG, "Trying basic extraction (FFmpeg auto-detect)")
        val result = ffmpegHelper.execute(command, callback)
        
        val outputFile = File(outputPath)
        return result && outputFile.exists() && outputFile.length() > 0
    }
    
    /**
     * Check available audio codecs
     */
    suspend fun getAvailableAudioCodecs(): List<String> = withContext(Dispatchers.IO) {
        val codecs = mutableListOf<String>()
        val output = StringBuilder()
        
        val callback = object : FFmpegHelper.FFmpegCallback {
            override fun onStart() {}
            override fun onProgress(progress: Float, time: Long) {}
            override fun onOutput(line: String) {
                output.append(line).append("\n")
            }
            override fun onSuccess(output: String?) {}
            override fun onFailure(error: String) {}
            override fun onFinish() {}
        }
        
        ffmpegHelper.execute("-codecs", callback)
        
        // Parse output for audio encoders
        output.toString().lines().forEach { line ->
            if (line.contains(" EA ") || line.contains(" A ")) {
                // Extract codec name
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val codec = parts[1]
                    if (codec.isNotEmpty() && !codec.startsWith("=")) {
                        codecs.add(codec)
                    }
                }
            }
        }
        
        Log.d(TAG, "Available audio codecs: $codecs")
        codecs
    }
}