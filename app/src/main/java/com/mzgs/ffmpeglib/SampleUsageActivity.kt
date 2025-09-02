package com.mzgs.ffmpeglib

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import com.mzgs.ffmpeglib.FFmpegOperations.VideoQuality
import com.mzgs.ffmpeglib.FFmpegOperations.AudioFormat
import com.mzgs.ffmpeglib.FFmpegOperations.VideoFormat

class SampleUsageActivity : AppCompatActivity() {
    
    private lateinit var ffmpeg: FFmpeg
    
    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processVideo(it) }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize FFmpeg
        ffmpeg = FFmpeg.initialize(this)
        
        checkPermissions()
        
        // Install FFmpeg binaries on first launch
        lifecycleScope.launch {
            val installListener = object : FFmpegInstaller.InstallProgressListener {
                override fun onProgressUpdate(progress: Int, message: String) {
                    runOnUiThread {
                        showToast("Installing: $message ($progress%)")
                    }
                }
                
                override fun onInstallComplete(success: Boolean, message: String) {
                    runOnUiThread {
                        showToast(message)
                        if (success) {
                            demonstrateUsage()
                        }
                    }
                }
            }
            
            if (!ffmpeg.isInstalled()) {
                ffmpeg.install(installListener)
            } else {
                demonstrateUsage()
            }
        }
    }
    
    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                1001
            )
        }
    }
    
    private fun demonstrateUsage() {
        // Example 1: Compress video
        compressVideoExample()
        
        // Example 2: Extract audio from video
        extractAudioExample()
        
        // Example 3: Convert video format
        convertVideoExample()
        
        // Example 4: Trim video
        trimVideoExample()
        
        // Example 5: Resize video
        resizeVideoExample()
        
        // Example 6: Create GIF from video
        createGifExample()
        
        // Example 7: Get media information
        getMediaInfoExample()
        
        // Example 8: Build custom command
        customCommandExample()
    }
    
    private fun compressVideoExample() {
        lifecycleScope.launch {
            val inputPath = "/storage/emulated/0/Movies/input.mp4"
            val outputPath = FFmpegUtils.generateOutputPath(inputPath, "compressed", "mp4")
            
            val success = ffmpeg.operations().compressVideo(
                inputPath = inputPath,
                outputPath = outputPath,
                quality = VideoQuality.MEDIUM,
                callback = object : FFmpegHelper.FFmpegCallback {
                    override fun onStart() {
                        showToast("Compression started")
                    }
                    
                    override fun onProgress(progress: Float, time: Long) {
                        runOnUiThread {
                            // Update UI with progress
                            showToast("Progress: ${progress.toInt()}%")
                        }
                    }
                    
                    override fun onOutput(line: String) {
                        // Log output if needed
                    }
                    
                    override fun onSuccess(output: String?) {
                        showToast("Compression completed successfully")
                    }
                    
                    override fun onFailure(error: String) {
                        showToast("Compression failed: $error")
                    }
                    
                    override fun onFinish() {
                        // Cleanup or final UI updates
                    }
                }
            )
            
            if (success) {
                showToast("Video compressed to: $outputPath")
            }
        }
    }
    
    private fun extractAudioExample() {
        lifecycleScope.launch {
            val inputPath = "/storage/emulated/0/Movies/input.mp4"
            val outputPath = FFmpegUtils.changeFileExtension(inputPath, "mp3")
            
            val success = ffmpeg.operations().extractAudio(
                inputPath = inputPath,
                outputPath = outputPath,
                audioFormat = AudioFormat.MP3,
                callback = createSimpleCallback("Audio extraction")
            )
            
            if (success) {
                showToast("Audio extracted to: $outputPath")
            }
        }
    }
    
    private fun convertVideoExample() {
        lifecycleScope.launch {
            val inputPath = "/storage/emulated/0/Movies/input.mp4"
            val outputPath = FFmpegUtils.changeFileExtension(inputPath, "avi")
            
            val success = ffmpeg.operations().convertVideo(
                inputPath = inputPath,
                outputPath = outputPath,
                outputFormat = VideoFormat.AVI,
                callback = createSimpleCallback("Video conversion")
            )
            
            if (success) {
                showToast("Video converted to: $outputPath")
            }
        }
    }
    
    private fun trimVideoExample() {
        lifecycleScope.launch {
            val inputPath = "/storage/emulated/0/Movies/input.mp4"
            val outputPath = FFmpegUtils.generateOutputPath(inputPath, "trimmed", "mp4")
            
            val success = ffmpeg.operations().trimVideo(
                inputPath = inputPath,
                outputPath = outputPath,
                startTimeSeconds = 10.0,
                durationSeconds = 30.0,
                callback = createSimpleCallback("Video trimming")
            )
            
            if (success) {
                showToast("Video trimmed: 30 seconds from 00:00:10")
            }
        }
    }
    
    private fun resizeVideoExample() {
        lifecycleScope.launch {
            val inputPath = "/storage/emulated/0/Movies/input.mp4"
            val outputPath = FFmpegUtils.generateOutputPath(inputPath, "resized", "mp4")
            
            val success = ffmpeg.operations().resizeVideo(
                inputPath = inputPath,
                outputPath = outputPath,
                width = 720,
                height = 480,
                maintainAspectRatio = true,
                callback = createSimpleCallback("Video resizing")
            )
            
            if (success) {
                showToast("Video resized to 720x480")
            }
        }
    }
    
    private fun createGifExample() {
        lifecycleScope.launch {
            val inputPath = "/storage/emulated/0/Movies/input.mp4"
            val outputPath = FFmpegUtils.changeFileExtension(inputPath, "gif")
            
            val success = ffmpeg.operations().createGif(
                inputPath = inputPath,
                outputPath = outputPath,
                width = 320,
                fps = 10,
                startTime = 0.0,
                duration = 5.0,
                callback = createSimpleCallback("GIF creation")
            )
            
            if (success) {
                showToast("GIF created: 5 seconds at 10 fps")
            }
        }
    }
    
    private fun getMediaInfoExample() {
        val inputPath = "/storage/emulated/0/Movies/input.mp4"
        
        lifecycleScope.launch {
            // Get media information
            val mediaInfo = ffmpeg.getMediaInfo(inputPath)
            mediaInfo?.let {
                val duration = it.duration
                val videoStream = it.videoStreams.firstOrNull()
                val audioStream = it.audioStreams.firstOrNull()
                val bitrate = it.bitrate
                val frameRate = videoStream?.frameRate ?: 0.0
                
                val info = """
                    Duration: ${FFmpegUtils.formatDuration(duration)}
                    Resolution: ${videoStream?.width}x${videoStream?.height}
                    Video Codec: ${videoStream?.codec}
                    Audio Codec: ${audioStream?.codec}
                    Bitrate: ${bitrate / 1000} kbps
                    Frame Rate: $frameRate fps
                """.trimIndent()
                
                showToast(info)
            }
        }
    }
    
    private fun customCommandExample() {
        lifecycleScope.launch {
            val inputPath = "/storage/emulated/0/Movies/input.mp4"
            val outputPath = FFmpegUtils.generateOutputPath(inputPath, "custom", "mp4")
            
            // Build a custom FFmpeg command
            val command = ffmpeg.commandBuilder()
                .input(inputPath)
                .overwriteOutput()
                .videoCodec("libx264")
                .preset("medium")
                .crf(23)
                .videoFilter("scale=1280:720")
                .audioCodec("aac")
                .audioBitrate("128k")
                .customOption("-movflags", "+faststart")
                .output(outputPath)
                .build()
            
            val success = ffmpeg.execute(
                command = command,
                callback = createSimpleCallback("Custom command")
            )
            
            if (success) {
                showToast("Custom command executed successfully")
            }
        }
    }
    
    private fun processVideo(uri: Uri) {
        lifecycleScope.launch {
            // Copy URI to a temporary file
            val tempFile = FFmpegUtils.createTempFile(this@SampleUsageActivity, "video", "mp4")
            
            if (FFmpegUtils.copyUriToFile(this@SampleUsageActivity, uri, tempFile)) {
                // Process the video
                val outputPath = FFmpegUtils.generateOutputPath(
                    tempFile.absolutePath,
                    "processed",
                    "mp4"
                )
                
                val success = ffmpeg.operations().compressVideo(
                    inputPath = tempFile.absolutePath,
                    outputPath = outputPath,
                    quality = VideoQuality.MEDIUM
                )
                
                if (success) {
                    showToast("Video processed successfully")
                }
                
                // Clean up temp file
                tempFile.delete()
            }
        }
    }
    
    private fun createSimpleCallback(operationName: String): FFmpegHelper.FFmpegCallback {
        return object : FFmpegHelper.FFmpegCallback {
            override fun onStart() {
                showToast("$operationName started")
            }
            
            override fun onProgress(progress: Float, time: Long) {
                // Update progress
            }
            
            override fun onOutput(line: String) {
                // Log output if needed
            }
            
            override fun onSuccess(output: String?) {
                showToast("$operationName completed")
            }
            
            override fun onFailure(error: String) {
                showToast("$operationName failed: $error")
            }
            
            override fun onFinish() {
                // Cleanup
            }
        }
    }
    
    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Cancel all running FFmpeg sessions
        ffmpeg.cancelAll()
        // Clean up temp files
        FFmpegUtils.cleanupTempFiles(this)
    }
}