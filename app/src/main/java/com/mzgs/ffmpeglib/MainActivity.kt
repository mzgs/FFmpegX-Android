
package com.mzgs.ffmpeglib

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
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
import com.mzgs.ffmpegx.FFmpegHelper
import com.mzgs.ffmpegx.FFmpegInstaller
import com.mzgs.ffmpegx.FFmpegOperations
import com.mzgs.ffmpegx.FFmpegUtils
import com.mzgs.ffmpegx.FFmpegOperations.VideoQuality
import com.mzgs.ffmpegx.FFmpegOperations.AudioFormat
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
        enableEdgeToEdge()
        
        // Initialize FFmpeg
        ffmpeg = FFmpeg.initialize(this)
        
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
                        
                        ffmpeg.getVersion()?.let { version ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Version:")
                                Text(version)
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
                                    outputText += "Compressing video...\n"
                                    
                                    val outputPath = File(cacheDir, "compressed_${System.currentTimeMillis()}.mp4").absolutePath


                                    ffmpeg.operations().compressVideo(
                                        inputPath = path,
                                        outputPath = outputPath,
                                        quality = FFmpegOperations.VideoQuality.MEDIUM,
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
                                    outputText += "Extracting audio...\n"
                                    
                                    val outputPath = File(cacheDir, "audio_${System.currentTimeMillis()}.mp3").absolutePath
                                    
                                    ffmpeg.operations().extractAudio(
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
                                            
                                            override fun onOutput(line: String) {}
                                            
                                            override fun onSuccess(output: String?) {
                                                val outputSize = File(outputPath).length() / 1024
                                                outputText += "✓ Audio extracted!\n"
                                                outputText += "  Format: MP3\n"
                                                outputText += "  Size: ${outputSize}KB\n"
                                            }
                                            
                                            override fun onFailure(error: String) {
                                                outputText += "✗ Extraction failed: $error\n"
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
}