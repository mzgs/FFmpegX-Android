package com.mzgs.ffmpeglib

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mzgs.ffmpeglib.ui.theme.FfmpegLibTheme
import com.mzgs.ffmpegx.FFmpeg
import com.mzgs.ffmpegx.FFmpegInstaller
import com.mzgs.ffmpegx.FFmpegHelper
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.content.ContentValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Intent
import android.database.Cursor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.*

class MainActivity : ComponentActivity() {
    
    private lateinit var ffmpeg: FFmpeg
    private lateinit var exoPlayer: ExoPlayer
    private var inputVideoPath by mutableStateOf<String?>(null)
    private var outputVideoPath by mutableStateOf<String?>(null)
    private var isProcessing by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Select a video to begin")
    private var statusColor by mutableStateOf(Color.Gray)
    private var progress by mutableStateOf(0f)
    private var showCommandDialog by mutableStateOf(false)
    private var customCommand by mutableStateOf("")
    
    // Video picker
    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { 
            copyVideoToCache(it)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize FFmpeg
        ffmpeg = FFmpeg.initialize(this)
        
        // Initialize ExoPlayer
        exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
        
        // Check permissions
        checkPermissions()
        
        setContent {
            FfmpegLibTheme {
                FFmpegVideoEditor()
            }
        }
        
        // Install FFmpeg binaries on first launch
        lifecycleScope.launch {
            if (!ffmpeg.isInstalled()) {
                statusMessage = "Installing FFmpeg..."
                statusColor = Color.Blue
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        exoPlayer.release()
    }
    
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun FFmpegVideoEditor() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        Text(
                            "FFmpeg Video Editor",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = Color.White
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Video Player Section
                VideoPlayerSection(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                )
                
                // Status Section
                StatusSection(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                
                // Progress Bar
                if (isProcessing) {
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                }
                
                // Control Buttons
                ButtonGrid(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                )
            }
        }
        
        // Custom Command Dialog
        if (showCommandDialog) {
            CustomCommandDialog()
        }
    }
    
    @Composable
    fun VideoPlayerSection(modifier: Modifier = Modifier) {
        Card(
            modifier = modifier
                .padding(16.dp)
                .clip(RoundedCornerShape(12.dp)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (outputVideoPath != null || inputVideoPath != null) {
                    AndroidView(
                        factory = { context ->
                            PlayerView(context).apply {
                                player = exoPlayer
                                useController = true
                                controllerShowTimeoutMs = 3000
                                controllerAutoShow = false
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Filled.VideoLibrary,
                            contentDescription = "No Video",
                            tint = Color.Gray,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No video loaded",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
    
    @Composable
    fun StatusSection(modifier: Modifier = Modifier) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = statusColor.copy(alpha = 0.1f)
            ),
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (statusColor) {
                    Color.Green -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = statusColor)
                    Color.Red -> Icon(Icons.Filled.Error, contentDescription = null, tint = statusColor)
                    Color.Blue -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> Icon(Icons.Filled.Info, contentDescription = null, tint = statusColor)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = statusMessage,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
    
    @Composable
    fun ButtonGrid(modifier: Modifier = Modifier) {
        val buttonData = listOf(
            Triple(Icons.Filled.FileOpen, "Select Video", Color(0xFF4CAF50)) to ::selectVideo,
            Triple(Icons.Filled.ContentCut, "Trim (0-5s)", Color(0xFF2196F3)) to ::trimVideo,
            Triple(Icons.Filled.Transform, "Scale (640x480)", Color(0xFF9C27B0)) to ::scaleVideo,
            Triple(Icons.Filled.FilterAlt, "Apply Random Filter", Color(0xFFFF9800)) to ::applyRandomFilter,
            Triple(Icons.Filled.Compress, "Compress (CRF 28)", Color(0xFF009688)) to ::compressVideo,
            Triple(Icons.Filled.Speed, "Speed 2x", Color(0xFF3F51B5)) to ::speedUpVideo,
            Triple(Icons.Filled.FlipCameraAndroid, "Rotate 90°", Color(0xFF795548)) to ::rotateVideo,
            Triple(Icons.Filled.Terminal, "Custom Command", Color(0xFF607D8B)) to { showCommandDialog = true }
        )
        
        Column(modifier = modifier) {
            buttonData.chunked(2).forEachIndexed { index, rowButtons ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowButtons.forEach { (buttonInfo, action) ->
                        VideoOperationButton(
                            icon = buttonInfo.first,
                            text = buttonInfo.second,
                            color = buttonInfo.third,
                            onClick = action,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Fill empty space if odd number of buttons
                    if (rowButtons.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
    
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun VideoOperationButton(
        icon: ImageVector,
        text: String,
        color: Color,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Card(
            onClick = onClick,
            modifier = modifier.height(80.dp),
            colors = CardDefaults.cardColors(
                containerColor = color.copy(alpha = 0.1f)
            ),
            border = null,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = text,
                    tint = color,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = text,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
    
    @Composable
    fun CustomCommandDialog() {
        AlertDialog(
            onDismissRequest = { showCommandDialog = false },
            title = { Text("Custom FFmpeg Command") },
            text = {
                Column {
                    Text("Enter FFmpeg arguments (without -i input):", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customCommand,
                        onValueChange = { customCommand = it },
                        placeholder = { Text("e.g., -vf scale=720:480 -crf 23") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { 
                    executeCustomCommand()
                    showCommandDialog = false
                }) {
                    Text("Execute")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCommandDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Video operation functions
    private fun selectVideo() {
        videoPickerLauncher.launch("video/*")
    }
    
    private fun trimVideo() {
        if (inputVideoPath == null) {
            statusMessage = "Please select a video first"
            statusColor = Color.Red
            return
        }
        if (isProcessing) {
            statusMessage = "Another operation is in progress"
            statusColor = Color.Yellow
            return
        }
        
        executeFFmpegCommand("Trimming video...", "Trimmed successfully!") {
            val outputPath = getOutputPath("trimmed")
            "-i $inputVideoPath -ss 0 -t 5 -c:v libx264 -crf 23 $outputPath" to outputPath
        }
    }
    
    private fun scaleVideo() {
        if (inputVideoPath == null) {
            statusMessage = "Please select a video first"
            statusColor = Color.Red
            return
        }
        if (isProcessing) {
            statusMessage = "Another operation is in progress"
            statusColor = Color.Yellow
            return
        }
        
        executeFFmpegCommand("Scaling video...", "Scaled successfully!") {
            val outputPath = getOutputPath("scaled")
            "-i $inputVideoPath -vf scale=640:480 -c:a copy $outputPath" to outputPath
        }
    }
    
    private fun applyRandomFilter() {
        if (inputVideoPath == null) {
            statusMessage = "Please select a video first"
            statusColor = Color.Red
            return
        }
        if (isProcessing) {
            statusMessage = "Another operation is in progress"
            statusColor = Color.Yellow
            return
        }
        
        val filters = listOf(
            "hflip" to "Horizontal Flip",
            "vflip" to "Vertical Flip",
            "negate" to "Negative",
            "edgedetect" to "Edge Detect",
            "boxblur=5:1" to "Blur",
            "eq=brightness=0.2:saturation=1.5" to "Bright & Saturated"
        )
        val (filter, name) = filters.random()
        
        executeFFmpegCommand("Applying $name...", "$name applied!") {
            val outputPath = getOutputPath("filtered")
            "-i $inputVideoPath -vf $filter -c:a copy $outputPath" to outputPath
        }
    }
    
    private fun compressVideo() {
        if (inputVideoPath == null) {
            statusMessage = "Please select a video first"
            statusColor = Color.Red
            return
        }
        if (isProcessing) {
            statusMessage = "Another operation is in progress"
            statusColor = Color.Yellow
            return
        }
        
        executeFFmpegCommand("Compressing video...", "Compressed successfully!") {
            val outputPath = getOutputPath("compressed")
            "-i $inputVideoPath -c:v libx264 -crf 28 -preset fast -c:a copy $outputPath" to outputPath
        }
    }
    
    private fun speedUpVideo() {
        if (inputVideoPath == null) {
            statusMessage = "Please select a video first"
            statusColor = Color.Red
            return
        }
        if (isProcessing) {
            statusMessage = "Another operation is in progress"
            statusColor = Color.Yellow
            return
        }
        
        executeFFmpegCommand("Speeding up video...", "Speed changed successfully!") {
            val outputPath = getOutputPath("fast")
            "-i $inputVideoPath -vf setpts=0.5*PTS -af atempo=2.0 $outputPath" to outputPath
        }
    }
    
    private fun rotateVideo() {
        if (inputVideoPath == null) {
            statusMessage = "Please select a video first"
            statusColor = Color.Red
            return
        }
        if (isProcessing) {
            statusMessage = "Another operation is in progress"
            statusColor = Color.Yellow
            return
        }
        
        executeFFmpegCommand("Rotating video...", "Rotated successfully!") {
            val outputPath = getOutputPath("rotated")
            "-i $inputVideoPath -vf transpose=1 -c:a copy $outputPath" to outputPath
        }
    }
    
    private fun executeCustomCommand() {
        if (customCommand.isBlank()) return
        
        if (inputVideoPath == null) {
            statusMessage = "Please select a video first"
            statusColor = Color.Red
            return
        }
        if (isProcessing) {
            statusMessage = "Another operation is in progress"
            statusColor = Color.Yellow
            return
        }
        
        executeFFmpegCommand("Executing custom command...", "Command executed successfully!") {
            val outputPath = getOutputPath("custom")
            "-i $inputVideoPath $customCommand $outputPath" to outputPath
        }
    }
    
    private fun executeFFmpegCommand(
        processingMessage: String,
        successMessage: String,
        commandBuilder: () -> Pair<String, String>
    ) {
        lifecycleScope.launch {
            isProcessing = true
            statusMessage = processingMessage
            statusColor = Color.Blue
            progress = 0f
            
            val (command, outputPath) = commandBuilder()
            
            ffmpeg.execute(
                command,
                object : FFmpegHelper.FFmpegCallback {
                    override fun onStart() {}
                    
                    override fun onProgress(prog: Float, time: Long) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            progress = prog / 100f
                        }
                    }
                    
                    override fun onOutput(line: String) {}
                    
                    override fun onSuccess(output: String?) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            // Get video metadata
                            val videoInfo = getVideoMetadata(outputPath)
                            statusMessage = "$successMessage\n$videoInfo"
                            statusColor = Color.Green
                            outputVideoPath = outputPath
                            
                            // Auto-play the output video
                            playVideo(outputPath)
                        }
                    }
                    
                    override fun onFailure(error: String) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            statusMessage = "Failed: $error"
                            statusColor = Color.Red
                        }
                    }
                    
                    override fun onFinish() {
                        lifecycleScope.launch(Dispatchers.Main) {
                            isProcessing = false
                            progress = 0f
                        }
                    }
                }
            )
        }
    }
    
    private fun playVideo(videoPath: String) {
        val file = File(videoPath)
        if (file.exists()) {
            // Ensure ExoPlayer operations happen on main thread
            lifecycleScope.launch(Dispatchers.Main) {
                val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.play()
            }
        }
    }
    
    private fun getVideoMetadata(videoPath: String): String {
        return try {
            val file = File(videoPath)
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoPath)
            
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "0"
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "0"
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0
            
            retriever.release()
            
            val durationSeconds = duration / 1000.0
            val fileSizeKB = file.length() / 1024
            val fileSizeMB = fileSizeKB / 1024.0
            
            val sizeText = if (fileSizeMB >= 1) {
                String.format("%.1f MB", fileSizeMB)
            } else {
                "$fileSizeKB KB"
            }
            
            buildString {
                append("Duration: ${String.format("%.1f", durationSeconds)}s | ")
                append("Resolution: ${width}x${height} | ")
                append("Size: $sizeText")
                if (bitrate > 0) {
                    append(" | Bitrate: ${bitrate/1000} kbps")
                }
            }
        } catch (e: Exception) {
            ""
        }
    }
    
    private fun getOutputPath(suffix: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloadsDir, "${suffix}_$timestamp.mp4").absolutePath
    }
    
    private fun copyVideoToCache(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val timestamp = System.currentTimeMillis()
                val cacheFile = File(cacheDir, "input_video_$timestamp.mp4")
                
                contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    inputVideoPath = cacheFile.absolutePath
                    statusMessage = "Video loaded successfully"
                    statusColor = Color.Green
                    
                    // Play the input video
                    playVideo(cacheFile.absolutePath)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusMessage = "Failed to load video: ${e.message}"
                    statusColor = Color.Red
                }
            }
        }
    }
    
    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 100)
        }
    }
}