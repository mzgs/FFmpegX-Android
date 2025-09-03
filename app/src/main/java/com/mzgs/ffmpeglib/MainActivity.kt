package com.mzgs.ffmpeglib

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
// import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mzgs.ffmpeglib.ui.theme.FfmpegLibTheme
import com.mzgs.ffmpegx.FFmpeg
import com.mzgs.ffmpegx.FFmpegInstaller
import com.mzgs.ffmpegx.MediaInformation
import com.mzgs.ffmpegx.VideoStream
import com.mzgs.ffmpegx.AudioStream
import com.mzgs.ffmpegx.FFmpegOperations
import com.mzgs.ffmpegx.FFmpegHelper
import kotlinx.coroutines.launch
import java.io.File
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.content.ContentValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import android.content.Intent
import android.database.Cursor

class MainActivity : ComponentActivity() {
    
    private lateinit var ffmpeg: FFmpeg
    private var testVideoPath by mutableStateOf<String?>(null)
    private var isProcessing by mutableStateOf(false)
    private var currentTest by mutableStateOf("")
    private var outputText by mutableStateOf("FFmpeg Test Results:\n")
    private var progress by mutableStateOf(0f)
    private var customCommand by mutableStateOf("")
    private var ffmpegVersion by mutableStateOf<String?>(null)
    
    // Video picker using GetContent (Gallery)
    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { 
            // Copy to app's cache directory for testing
            copyVideoToCache(it)
        }
    }
    
    // Document picker for Downloads and other folders
    private val documentPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // Grant persistent permissions
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            // Copy to app's cache directory for testing
            copyVideoToCache(it)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // enableEdgeToEdge() // Not available in current dependency versions
        
        // Initialize FFmpeg
        ffmpeg = FFmpeg.initialize(this)
        
        // Get FFmpeg version after initialization
        lifecycleScope.launch {
            ffmpegVersion = ffmpeg.getVersion()
            if (ffmpegVersion != null) {
                outputText += "FFmpeg version: $ffmpegVersion\n"
            }
        }
        
        // Check permissions
        checkPermissions()
        
        setContent {
            FfmpegLibTheme {
                FFmpegTestScreen()
            }
        }
        
        // Install FFmpeg binaries on first launch
        lifecycleScope.launch {
            if (!ffmpeg.isInstalled()) {
                // Installation will happen automatically on first use
            }
        }
    }
    
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun FFmpegTestScreen() {
        // Use the class-level state variables instead of local ones
        
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("FFmpeg Library Test") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.primary
                    )
                )
            },
            modifier = Modifier.fillMaxSize()
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Status Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "FFmpeg Status",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Installation:")
                            Text(
                                if (ffmpeg.isInstalled()) "✓ Installed" else "✗ Not Installed",
                                color = if (ffmpeg.isInstalled()) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.error
                            )
                        }
                        
                        val version = ffmpegVersion ?: ffmpeg.getVersion()
                        version?.let { v ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Version:")
                                Text(v)
                            }
                        }
                        
                        testVideoPath?.let { path ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Test Video:")
                                Text(File(path).name)
                            }
                        }
                        
                        if (currentTest.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Current Test:")
                                Text(currentTest)
                            }
                        }
                    }
                }
                
                // Test Buttons Column
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Install FFmpeg Button
                    OutlinedButton(
                        onClick = {
                            lifecycleScope.launch {
                                isProcessing = true
                                currentTest = "Installing FFmpeg"
                                outputText += "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                                outputText += "Installing FFmpeg...\n"
                                
                                val success = ffmpeg.install(object : FFmpegInstaller.InstallProgressListener {
                                    override fun onProgressUpdate(prog: Int, message: String) {
                                        progress = prog / 100f
                                        outputText += "[$prog%] $message\n"
                                    }
                                    
                                    override fun onInstallComplete(success: Boolean, message: String) {
                                        outputText += if (success) "✓ $message\n" else "✗ $message\n"
                                        isProcessing = false
                                        currentTest = ""
                                        progress = 0f
                                    }
                                })
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("1. Install/Verify FFmpeg")
                    }
                    
                    // Select Video from Gallery Button
                    Button(
                        onClick = {
                            videoPickerLauncher.launch("video/*")
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("2. Select Video from Gallery")
                    }
                    
                    // Select Video from Downloads/Files Button
                    OutlinedButton(
                        onClick = {
                            // Open document picker with video mime types
                            documentPickerLauncher.launch(arrayOf(
                                "video/*",
                                "video/mp4",
                                "video/avi",
                                "video/mkv",
                                "video/mov",
                                "video/webm",
                                "video/3gpp",
                                "application/octet-stream" // For files without proper mime type
                            ))
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("2b. Select Video from Downloads/Files")
                    }
                    

                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Text(
                        "Test Operations",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    // Get Media Info Button
                    ElevatedButton(
                        onClick = {
                            testVideoPath?.let { path ->
                                lifecycleScope.launch {
                                    isProcessing = true
                                    currentTest = "Getting Media Info"
                                    outputText += "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                                    outputText += "Getting media information...\n"
                                    
                                    val mediaInfo = ffmpeg.getMediaInfo(path)
                                    if (mediaInfo != null) {
                                        outputText += "✓ Media Information:\n"
                                        outputText += "  Duration: ${mediaInfo.duration / 1000}s\n"
                                        outputText += "  Bitrate: ${mediaInfo.bitrate / 1000} kbps\n"
                                        
                                        mediaInfo.videoStreams.firstOrNull()?.let { video ->
                                            outputText += "  Video:\n"
                                            outputText += "    - Codec: ${video.codec}\n"
                                            outputText += "    - Resolution: ${video.width}x${video.height}\n"
                                            outputText += "    - Frame Rate: ${video.frameRate} fps\n"
                                        }
                                        
                                        mediaInfo.audioStreams.firstOrNull()?.let { audio ->
                                            outputText += "  Audio:\n"
                                            outputText += "    - Codec: ${audio.codec}\n"
                                            outputText += "    - Sample Rate: ${audio.sampleRate} Hz\n"
                                            outputText += "    - Channels: ${audio.channels}\n"
                                        }
                                    } else {
                                        outputText += "✗ Failed to get media information\n"
                                    }
                                    isProcessing = false
                                    currentTest = ""
                                }
                            } ?: run {
                                outputText += "\n⚠️ Please select a video first!\n"
                            }
                        },
                        enabled = !isProcessing && testVideoPath != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Get Media Info")
                    }
                    
                    // Compress Video Button
                    ElevatedButton(
                        onClick = {
                            testVideoPath?.let { path ->
                                lifecycleScope.launch {
                                    isProcessing = true
                                    currentTest = "Compressing Video"
                                    outputText += "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                                    outputText += "Compressing video to VERY LOW quality...\n"
                                    outputText += "Using aggressive compression settings for testing...\n"
                                    
                                    val outputPath = File(cacheDir, "compressed_${System.currentTimeMillis()}.mp4").absolutePath


                                    ffmpeg.operations().compressVideo(
                                        inputPath = path,
                                        outputPath = outputPath,
                                        quality = FFmpegOperations.VideoQuality.LOW,  // Changed to LOW quality
                                        callback = object : FFmpegHelper.FFmpegCallback {
                                            override fun onStart() {
                                                outputText += "Starting compression...\n"
                                            }
                                            
                                            override fun onProgress(prog: Float, time: Long) {
                                                progress = prog / 100f
                                            }
                                            
                                            override fun onOutput(line: String) {}
                                            
                                            override fun onSuccess(output: String?) {
                                                val inputSize = File(path).length() / 1024
                                                val outputSize = File(outputPath).length() / 1024
                                                val reduction = ((1 - outputSize.toFloat() / inputSize) * 100).toInt()
                                                outputText += "✓ Compression successful!\n"
                                                outputText += "  Original: ${inputSize}KB\n"
                                                outputText += "  Compressed: ${outputSize}KB\n"
                                                outputText += "  Reduction: $reduction%\n"
                                            }
                                            
                                            override fun onFailure(error: String) {
                                                outputText += "✗ Compression failed: $error\n"
                                            }
                                            
                                            override fun onFinish() {
                                                isProcessing = false
                                                currentTest = ""
                                                progress = 0f
                                            }
                                        }
                                    )
                                }
                            } ?: run {
                                outputText += "\n⚠️ Please select a video first!\n"
                            }
                        },
                        enabled = !isProcessing && testVideoPath != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Compress Video")
                    }
                    
                    // Extract Audio Button
                    ElevatedButton(
                        onClick = {
                            testVideoPath?.let { path ->
                                lifecycleScope.launch {
                                    isProcessing = true
                                    currentTest = "Extracting Audio"
                                    outputText += "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                                    outputText += "Extracting audio from video...\n"
                                    
                                    // Skip media info check to avoid interference
                                    // Just proceed with extraction - FFmpeg will handle if no audio exists
                                    
                                    val timestamp = System.currentTimeMillis()
                                    val outputPath = File(cacheDir, "audio_$timestamp.mp3").absolutePath
                                    
                                    outputText += "Input: ${File(path).name}\n"
                                    outputText += "Output: audio_$timestamp.mp3\n"
                                    
                                    val success = ffmpeg.operations().extractAudio(
                                        inputPath = path,
                                        outputPath = outputPath,
                                        audioFormat = FFmpegOperations.AudioFormat.MP3,
                                        callback = object : FFmpegHelper.FFmpegCallback {
                                            override fun onStart() {
                                                outputText += "Starting extraction...\n"
                                            }
                                            
                                            override fun onProgress(prog: Float, time: Long) {
                                                progress = prog / 100f
                                            }
                                            
                                            override fun onOutput(line: String) {
                                                // Show audio stream info
                                                if (line.contains("Audio:")) {
                                                    outputText += "Found: $line\n"
                                                }
                                            }
                                            
                                            override fun onSuccess(output: String?) {
                                                val outputFile = File(outputPath)
                                                if (outputFile.exists() && outputFile.length() > 0) {
                                                    val outputSize = outputFile.length() / 1024
                                                    outputText += "✓ Audio extracted successfully!\n"
                                                    outputText += "  Format: MP3\n"
                                                    outputText += "  Size: ${outputSize}KB\n"
                                                    outputText += "  Path: $outputPath\n"
                                                    
                                                    // Save to Downloads folder
                                                    lifecycleScope.launch {
                                                        val savedPath = saveAudioToDownloads(outputFile)
                                                        if (savedPath != null) {
                                                            outputText += "✓ Saved to Downloads/audio_$timestamp.mp3\n"
                                                        } else {
                                                            outputText += "⚠️ Failed to save to Downloads (audio available in cache)\n"
                                                        }
                                                    }
                                                } else {
                                                    outputText += "✗ Extraction failed: Output file not created or empty\n"
                                                }
                                            }
                                            
                                            override fun onFailure(error: String) {
                                                if (error.contains("doesn't contain an audio track") || 
                                                    error.contains("No audio stream") ||
                                                    error.contains("does not contain any stream")) {
                                                    outputText += "✗ No audio track found!\n"
                                                    outputText += "  This video doesn't have audio.\n"
                                                    outputText += "  Try with a different video that includes sound.\n"
                                                } else {
                                                    outputText += "✗ Extraction failed: $error\n"
                                                }
                                            }
                                            
                                            override fun onFinish() {
                                                isProcessing = false
                                                currentTest = ""
                                                progress = 0f
                                            }
                                        }
                                    )
                                }
                            } ?: run {
                                outputText += "\n⚠️ Please select a video first!\n"
                            }
                        },
                        enabled = !isProcessing && testVideoPath != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Extract Audio")
                    }
                    
                    // Trim Video Button
                    ElevatedButton(
                        onClick = {
                            testVideoPath?.let { path ->
                                lifecycleScope.launch {
                                    isProcessing = true
                                    currentTest = "Trimming Video"
                                    outputText += "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                                    outputText += "Trimming video (0 to 5 seconds)...\n"
                                    
                                    val timestamp = System.currentTimeMillis()
                                    val outputPath = File(cacheDir, "trimmed_${timestamp}.mp4").absolutePath
                                    
                                    outputText += "Input: ${File(path).name}\n"
                                    outputText += "Output: trimmed_${timestamp}.mp4\n"
                                    outputText += "Duration: 5 seconds\n"
                                    outputText += "Using FFmpegOperations.trimVideo() method\n"
                                    
                                    // Use FFmpeg command directly with the trim support we added
                                    val command = "-i $path -ss 0 -t 5 $outputPath"
                                    outputText += "Command: ffmpeg $command\n"
                                    
                                    val success = ffmpeg.execute(
                                        command,
                                        object : FFmpegHelper.FFmpegCallback {
                                            override fun onStart() {
                                                outputText += "Starting trim operation...\n"
                                            }
                                            
                                            override fun onProgress(prog: Float, time: Long) {
                                                progress = prog / 100f
                                            }
                                            
                                            override fun onOutput(line: String) {
                                                // Show diagnostic output
                                                if (line.contains("Duration") || 
                                                    line.contains("frame=") ||
                                                    line.contains("time=") ||
                                                    line.contains("Stream") ||
                                                    line.contains("Video:") ||
                                                    line.contains("Audio:") ||
                                                    line.contains("Error") ||
                                                    line.contains("Warning")) {
                                                    outputText += "$line\n"
                                                }
                                            }
                                            
                                            override fun onSuccess(output: String?) {
                                                val outputFile = File(outputPath)
                                                if (outputFile.exists() && outputFile.length() > 0) {
                                                    val inputSize = File(path).length() / 1024
                                                    val outputSize = outputFile.length() / 1024
                                                    outputText += "✓ Video trimmed successfully!\n"
                                                    outputText += "  Original size: ${inputSize}KB\n"
                                                    outputText += "  Trimmed size: ${outputSize}KB\n"
                                                    outputText += "  Path: $outputPath\n"
                                                    
                                                    // Save trimmed video to Downloads folder
                                                    lifecycleScope.launch {
                                                        val savedPath = saveVideoToDownloads(outputFile)
                                                        if (savedPath != null) {
                                                            outputText += "✓ Saved to Downloads/trimmed_${timestamp}.mp4\n"
                                                        } else {
                                                            outputText += "⚠️ Failed to save to Downloads (video available in cache)\n"
                                                        }
                                                    }
                                                } else {
                                                    outputText += "✗ Trim failed: Output file not created or empty\n"
                                                }
                                            }
                                            
                                            override fun onFailure(error: String) {
                                                outputText += "✗ Trim failed: $error\n"
                                                outputText += "Error details: $error\n"
                                            }
                                            
                                            override fun onFinish() {
                                                isProcessing = false
                                                currentTest = ""
                                                progress = 0f
                                            }
                                        }
                                    )
                                }
                            } ?: run {
                                outputText += "\n⚠️ Please select a video first!\n"
                            }
                        },
                        enabled = !isProcessing && testVideoPath != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Trim Video (0-5 seconds)")
                    }
                    
                    // Apply Video Filter Button
                    ElevatedButton(
                        onClick = {
                            testVideoPath?.let { path ->
                                lifecycleScope.launch {
                                    isProcessing = true
                                    currentTest = "Applying Video Filter"
                                    outputText += "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                                    outputText += "Applying scale filter to video...\n"
                                    
                                    val timestamp = System.currentTimeMillis()
                                    val outputPath = File(cacheDir, "filtered_${timestamp}.mp4").absolutePath
                                    
                                    outputText += "Input: ${File(path).name}\n"
                                    outputText += "Output: filtered_${timestamp}.mp4\n"
                                    outputText += "Filter: scale=640:480\n"
                                    
                                    // Use FFmpeg with video filter
                                    val command = "-i $path -vf scale=640:480 $outputPath"
                                    outputText += "Command: ffmpeg $command\n"
                                    
                                    val success = ffmpeg.execute(
                                        command,
                                        object : FFmpegHelper.FFmpegCallback {
                                            override fun onStart() {
                                                outputText += "Starting filter operation...\n"
                                            }
                                            
                                            override fun onProgress(prog: Float, time: Long) {
                                                progress = prog / 100f
                                            }
                                            
                                            override fun onOutput(line: String) {
                                                if (line.contains("filter") || 
                                                    line.contains("scale") ||
                                                    line.contains("frame=") ||
                                                    line.contains("Error")) {
                                                    outputText += "$line\n"
                                                }
                                            }
                                            
                                            override fun onSuccess(output: String?) {
                                                val outputFile = File(outputPath)
                                                if (outputFile.exists() && outputFile.length() > 0) {
                                                    val inputSize = File(path).length() / 1024
                                                    val outputSize = outputFile.length() / 1024
                                                    outputText += "✓ Filter applied successfully!\n"
                                                    outputText += "  Original size: ${inputSize}KB\n"
                                                    outputText += "  Filtered size: ${outputSize}KB\n"
                                                    outputText += "  Resolution: 640x480\n"
                                                    
                                                    // Save to Downloads
                                                    lifecycleScope.launch {
                                                        val savedPath = saveVideoToDownloads(outputFile)
                                                        if (savedPath != null) {
                                                            outputText += "✓ Saved to Downloads/filtered_${timestamp}.mp4\n"
                                                        } else {
                                                            outputText += "⚠️ Failed to save to Downloads\n"
                                                        }
                                                    }
                                                } else {
                                                    outputText += "✗ Filter failed: Output file not created\n"
                                                }
                                            }
                                            
                                            override fun onFailure(error: String) {
                                                outputText += "✗ Filter failed: $error\n"
                                            }
                                            
                                            override fun onFinish() {
                                                isProcessing = false
                                                currentTest = ""
                                                progress = 0f
                                            }
                                        }
                                    )
                                }
                            } ?: run {
                                outputText += "\n⚠️ Please select a video first!\n"
                            }
                        },
                        enabled = !isProcessing && testVideoPath != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Apply Filter (Scale 640x480)")
                    }
                    
                    // Test FFmpeg Version Button
                    ElevatedButton(
                        onClick = {
                            lifecycleScope.launch {
                                isProcessing = true
                                currentTest = "Testing FFmpeg"
                                outputText += "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                                outputText += "Testing FFmpeg installation...\n"
                                
                                // First try the simple test
                                val testSuccess = ffmpeg.testFFmpeg()
                                if (testSuccess) {
                                    outputText += "✓ FFmpeg basic test passed!\n"
                                    outputText += "Now testing with -version command...\n"
                                    
                                    // Then try the version command
                                    ffmpeg.execute(
                                        "-version",
                                        object : FFmpegHelper.FFmpegCallback {
                                            override fun onStart() {}
                                            override fun onProgress(progress: Float, time: Long) {}
                                            override fun onOutput(line: String) {
                                                if (line.contains("version")) {
                                                    outputText += line + "\n"
                                                }
                                            }
                                            override fun onSuccess(output: String?) {
                                                outputText += "✓ FFmpeg is working correctly!\n"
                                            }
                                            override fun onFailure(error: String) {
                                                outputText += "✗ FFmpeg -version command failed: $error\n"
                                                outputText += "Note: Basic test passed, so FFmpeg is installed.\n"
                                            }
                                            override fun onFinish() {
                                                isProcessing = false
                                                currentTest = ""
                                            }
                                        }
                                    )
                                } else {
                                    outputText += "✗ FFmpeg basic test failed!\n"
                                    outputText += "Please check:\n"
                                    outputText += "1. FFmpeg binaries are in assets/ffmpeg/*/libffmpeg.so\n"
                                    outputText += "2. Binaries are valid ELF executables\n"
                                    outputText += "3. Device architecture matches binary architecture\n"
                                    isProcessing = false
                                    currentTest = ""
                                }
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Test FFmpeg Version")
                    }
                    
                    // Check Available Encoders Button
                    ElevatedButton(
                        onClick = {
                            lifecycleScope.launch {
                                isProcessing = true
                                currentTest = "Checking Encoders"
                                outputText += "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                                outputText += "Checking available audio encoders...\n"
                                
                                ffmpeg.execute(
                                    "-encoders",
                                    object : FFmpegHelper.FFmpegCallback {
                                        override fun onStart() {}
                                        override fun onProgress(progress: Float, time: Long) {}
                                        override fun onOutput(line: String) {
                                            // Show only audio encoders
                                            if (line.contains("mp3") || line.contains("aac") || 
                                                line.contains("opus") || line.contains("vorbis") ||
                                                line.contains("flac") || line.contains("pcm")) {
                                                outputText += "$line\n"
                                            }
                                        }
                                        override fun onSuccess(output: String?) {
                                            outputText += "\n✓ Encoder check complete\n"
                                            outputText += "Note: If libmp3lame is missing, use AAC format instead\n"
                                        }
                                        override fun onFailure(error: String) {
                                            outputText += "✗ Failed to list encoders: $error\n"
                                        }
                                        override fun onFinish() {
                                            isProcessing = false
                                            currentTest = ""
                                        }
                                    }
                                )
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Check Available Encoders")
                    }
                    
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Text(
                        "Custom Command",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    // Custom Command Input
                    OutlinedTextField(
                        value = customCommand,
                        onValueChange = { customCommand = it },
                        label = { Text("FFmpeg Command (without 'ffmpeg')") },
                        placeholder = { Text("-i input.mp4 -c:v copy output.mp4") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        enabled = !isProcessing
                    )
                    
                    // Execute Custom Command Button
                    Button(
                        onClick = {
                            if (customCommand.isNotBlank()) {
                                lifecycleScope.launch {
                                    isProcessing = true
                                    currentTest = "Custom Command"
                                    outputText += "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                                    outputText += "Executing custom command:\n"
                                    outputText += "> ffmpeg $customCommand\n\n"
                                    
                                    // Replace placeholders if test video is selected
                                    var processedCommand = customCommand
                                    testVideoPath?.let { path ->
                                        processedCommand = processedCommand
                                            .replace("{input}", path)
                                            .replace("{input_path}", path)
                                    }
                                    
                                    // Replace output placeholder with cache path
                                    if (processedCommand.contains("{output}")) {
                                        val outputPath = File(cacheDir, "output_${System.currentTimeMillis()}.mp4").absolutePath
                                        processedCommand = processedCommand.replace("{output}", outputPath)
                                        processedCommand = processedCommand.replace("{output_path}", outputPath)
                                    }
                                    
                                    ffmpeg.execute(
                                        processedCommand,
                                        object : FFmpegHelper.FFmpegCallback {
                                            override fun onStart() {
                                                outputText += "Command started...\n"
                                            }
                                            
                                            override fun onProgress(prog: Float, time: Long) {
                                                progress = prog / 100f
                                            }
                                            
                                            override fun onOutput(line: String) {
                                                // Show important output lines
                                                if (line.contains("Stream") || 
                                                    line.contains("Duration") || 
                                                    line.contains("frame=") ||
                                                    line.contains("Error") ||
                                                    line.contains("Warning")) {
                                                    outputText += "$line\n"
                                                }
                                            }
                                            
                                            override fun onSuccess(output: String?) {
                                                outputText += "\n✓ Command executed successfully!\n"
                                            }
                                            
                                            override fun onFailure(error: String) {
                                                outputText += "\n✗ Command failed: $error\n"
                                            }
                                            
                                            override fun onFinish() {
                                                isProcessing = false
                                                currentTest = ""
                                                progress = 0f
                                            }
                                        }
                                    )
                                }
                            } else {
                                outputText += "\n⚠️ Please enter a command!\n"
                            }
                        },
                        enabled = !isProcessing && customCommand.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Text("Execute Custom Command")
                    }
                    
                    // Helper text
                    Text(
                        "Tip: Use {input} for selected video path, {output} for auto-generated output path",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                
                // Progress Indicator
                if (isProcessing) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = if (progress > 0) progress else 0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (progress > 0) {
                            Text(
                                "${(progress * 100).toInt()}%",
                                modifier = Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                
                // Output Display
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .heightIn(min = 200.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Output Log",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(
                                onClick = {
                                    outputText = "FFmpeg Test Results:\n"
                                    progress = 0f
                                }
                            ) {
                                Text("Clear")
                            }
                        }
                        
                        Text(
                            text = outputText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
    
    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        
        // Check for media permissions on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            // For Android 12 and below
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            
            // Write permission for Android 9 and below
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
        
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                1001
            )
        }
    }
    
    private fun copyVideoToCache(uri: Uri) {
        lifecycleScope.launch {
            try {
                outputText += "\nLoading video from: ${getFileName(uri)}\n"
                
                val inputStream = contentResolver.openInputStream(uri)
                val originalName = getFileName(uri)
                val fileName = if (originalName.endsWith(".mp4", true)) {
                    originalName
                } else {
                    "test_video_${System.currentTimeMillis()}.mp4"
                }
                val outputFile = File(cacheDir, fileName)
                
                var totalBytes = 0L
                inputStream?.use { input ->
                    outputFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytes: Int
                        while (input.read(buffer).also { bytes = it } != -1) {
                            output.write(buffer, 0, bytes)
                            totalBytes += bytes
                        }
                    }
                }
                
                testVideoPath = outputFile.absolutePath
                outputText += "✓ Video loaded: $fileName\n"
                outputText += "  Size: ${totalBytes / 1024}KB\n"
                outputText += "  Path: ${outputFile.absolutePath}\n"
            } catch (e: Exception) {
                outputText += "✗ Failed to load video: ${e.message}\n"
                e.printStackTrace()
            }
        }
    }
    
    private fun getFileName(uri: Uri): String {
        var result: String? = null
        
        // Try to get display name from content resolver
        if (uri.scheme == "content") {
            val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = it.getString(index)
                    }
                }
            }
        }
        
        // If no display name, try to get from path
        if (result == null) {
            result = uri.path?.let { path ->
                File(path).name
            }
        }
        
        return result ?: "video_${System.currentTimeMillis()}.mp4"
    }
    
    private suspend fun downloadTestVideo(onComplete: (String?) -> Unit) = withContext(Dispatchers.IO) {
        val videoUrl = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
        val fileName = "BigBuckBunny_${System.currentTimeMillis()}.mp4"
        
        withContext(Dispatchers.Main) {
            isProcessing = true
            currentTest = "Downloading Video"
            outputText += "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
            outputText += "Downloading Big Buck Bunny video...\n"
        }
        
        try {
            // First download to cache
            val cacheFile = File(cacheDir, fileName)
            val connection = URL(videoUrl).openConnection()
            connection.connect()
            
            val fileLength = connection.contentLength
            val input = connection.getInputStream()
            val output = cacheFile.outputStream()
            
            val buffer = ByteArray(4096)
            var total: Long = 0
            var count: Int
            
            while (input.read(buffer).also { count = it } != -1) {
                total += count
                if (fileLength > 0) {
                    val progressPercent = (total * 100 / fileLength).toFloat()
                    withContext(Dispatchers.Main) {
                        progress = progressPercent / 100f
                        if (progressPercent.toInt() % 10 == 0) {
                            outputText += "Downloaded: ${progressPercent.toInt()}%\n"
                        }
                    }
                }
                output.write(buffer, 0, count)
            }
            
            output.flush()
            output.close()
            input.close()
            
            // Save to Photos/Movies folder
            val savedPath = saveVideoToPhotos(cacheFile)
            
            withContext(Dispatchers.Main) {
                if (savedPath != null) {
                    outputText += "✓ Video downloaded successfully!\n"
                    outputText += "  Cache: ${cacheFile.absolutePath}\n"
                    outputText += "  Saved to: Photos/Movies/$fileName\n"
                    outputText += "  Size: ${cacheFile.length() / (1024 * 1024)}MB\n"
                    onComplete(cacheFile.absolutePath)
                } else {
                    outputText += "✗ Failed to save video to Photos\n"
                    outputText += "  Video available in cache: ${cacheFile.absolutePath}\n"
                    onComplete(cacheFile.absolutePath)
                }
                isProcessing = false
                currentTest = ""
                progress = 0f
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                outputText += "✗ Download failed: ${e.message}\n"
                isProcessing = false
                currentTest = ""
                progress = 0f
                onComplete(null)
            }
        }
    }
    
    private suspend fun saveVideoToPhotos(videoFile: File): String? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ using MediaStore
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = contentResolver.insert(collection, contentValues)
                
                uri?.let { videoUri ->
                    contentResolver.openOutputStream(videoUri)?.use { output ->
                        videoFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(videoUri, contentValues, null, null)
                    
                    return@withContext videoUri.toString()
                }
            } else {
                // Android 9 and below
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                if (!moviesDir.exists()) {
                    moviesDir.mkdirs()
                }
                
                val destFile = File(moviesDir, videoFile.name)
                videoFile.copyTo(destFile, overwrite = true)
                
                // Scan the file to make it appear in gallery
                MediaStore.Images.Media.insertImage(
                    contentResolver,
                    destFile.absolutePath,
                    videoFile.name,
                    "Downloaded from FFmpeg Test App"
                )
                
                return@withContext destFile.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }
    
    private suspend fun saveAudioToDownloads(audioFile: File): String? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ using MediaStore
                val mimeType = when {
                    audioFile.name.endsWith(".mp3", true) -> "audio/mpeg"
                    audioFile.name.endsWith(".aac", true) -> "audio/aac"
                    else -> "audio/*"
                }
                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, audioFile.name)
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val uri = contentResolver.insert(collection, contentValues)
                
                uri?.let { audioUri ->
                    contentResolver.openOutputStream(audioUri)?.use { output ->
                        audioFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    
                    contentValues.clear()
                    contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    contentResolver.update(audioUri, contentValues, null, null)
                    
                    return@withContext audioUri.toString()
                }
            } else {
                // Android 9 and below
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                
                val destFile = File(downloadsDir, audioFile.name)
                audioFile.copyTo(destFile, overwrite = true)
                
                return@withContext destFile.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }
    
    private suspend fun saveVideoToDownloads(videoFile: File): String? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ using MediaStore
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val uri = contentResolver.insert(collection, contentValues)
                
                uri?.let { videoUri ->
                    contentResolver.openOutputStream(videoUri)?.use { output ->
                        videoFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(videoUri, contentValues, null, null)
                    
                    return@withContext videoUri.toString()
                }
            } else {
                // Android 9 and below
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                
                val destFile = File(downloadsDir, videoFile.name)
                videoFile.copyTo(destFile, overwrite = true)
                
                return@withContext destFile.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }
}