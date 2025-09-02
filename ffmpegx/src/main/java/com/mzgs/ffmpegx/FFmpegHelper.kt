package com.mzgs.ffmpegx

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class FFmpegHelper(private val context: Context) {

    companion object {
        private const val TAG = "FFmpegHelper"
        
        fun cancelAllTasks() {
            FFmpegExecutor.cancelAll()
        }

        fun cancelTask(sessionId: Long): Boolean {
            return FFmpegExecutor.cancel(sessionId)
        }
    }

    interface FFmpegCallback {
        fun onStart()
        fun onProgress(progress: Float, time: Long)
        fun onOutput(line: String)
        fun onSuccess(output: String?)
        fun onFailure(error: String)
        fun onFinish()
    }

    suspend fun execute(
        command: String,
        callback: FFmpegCallback? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Execute command: $command")
        suspendCancellableCoroutine { continuation ->
            callback?.onStart()
            
            val outputBuilder = StringBuilder()
            val errorBuilder = StringBuilder()
            val progressParser = FFmpegProgressParser()
            var isCompleted = false // Track if already completed
            
            val executorCallback = object : FFmpegExecutor.ExecutorCallback {
                override fun onOutput(output: String) {
                    Log.v(TAG, "FFmpeg output: $output")
                    outputBuilder.append(output).append("\n")
                    callback?.onOutput(output)
                    
                    // Parse progress from output
                    val progress = progressParser.parseProgress(output)
                    progress?.let {
                        Log.d(TAG, "Progress: ${it.percentage}% at ${it.timeMs}ms")
                        callback?.onProgress(it.percentage, it.timeMs)
                    }
                }
                
                override fun onError(error: String) {
                    Log.w(TAG, "FFmpeg stderr: $error")
                    errorBuilder.append(error).append("\n")
                    
                    // FFmpeg outputs progress to stderr
                    val progress = progressParser.parseProgress(error)
                    progress?.let {
                        Log.d(TAG, "Progress from stderr: ${it.percentage}% at ${it.timeMs}ms")
                        callback?.onProgress(it.percentage, it.timeMs)
                    }
                }
                
                override fun onComplete(exitCode: Int) {
                    if (!isCompleted) {
                        isCompleted = true
                        Log.i(TAG, "FFmpeg process completed with exit code: $exitCode")
                        if (exitCode == 0) {
                            Log.i(TAG, "FFmpeg command executed successfully")
                            callback?.onSuccess(outputBuilder.toString())
                            if (continuation.isActive) {
                                continuation.resume(true)
                            }
                        } else {
                            val errorMsg = "Process exited with code $exitCode: ${errorBuilder}"
                            Log.e(TAG, "FFmpeg command failed: $errorMsg")
                            callback?.onFailure(errorMsg)
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                        callback?.onFinish()
                    }
                }
            }
            
            // Use the new native executor that handles Android version differences
            val sessionId = FFmpegNativeExecutor.executeFFmpeg(context, command, executorCallback)
            Log.d(TAG, "Started FFmpeg session: $sessionId")
            
            continuation.invokeOnCancellation {
                FFmpegExecutor.cancel(sessionId)
            }
            
            if (sessionId == -1L) {
                if (!isCompleted) {
                    isCompleted = true
                    Log.e(TAG, "Failed to start FFmpeg process - session ID is -1")
                    callback?.onFailure("Failed to start FFmpeg process")
                    callback?.onFinish()
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
        }
    }

    fun executeAsync(
        command: String,
        callback: FFmpegCallback? = null
    ): Long {
        Log.d(TAG, "Execute async command: $command")
        callback?.onStart()
        
        val outputBuilder = StringBuilder()
        val errorBuilder = StringBuilder()
        val progressParser = FFmpegProgressParser()
        
        val executorCallback = object : FFmpegExecutor.ExecutorCallback {
            override fun onOutput(output: String) {
                outputBuilder.append(output).append("\n")
                callback?.onOutput(output)
                
                val progress = progressParser.parseProgress(output)
                progress?.let {
                    callback?.onProgress(it.percentage, it.timeMs)
                }
            }
            
            override fun onError(error: String) {
                errorBuilder.append(error).append("\n")
                
                val progress = progressParser.parseProgress(error)
                progress?.let {
                    callback?.onProgress(it.percentage, it.timeMs)
                }
            }
            
            override fun onComplete(exitCode: Int) {
                if (exitCode == 0) {
                    callback?.onSuccess(outputBuilder.toString())
                } else {
                    callback?.onFailure("Process exited with code $exitCode: ${errorBuilder}")
                }
                callback?.onFinish()
            }
        }
        
        val sessionId = FFmpegNativeExecutor.executeFFmpeg(context, command, executorCallback)
        Log.i(TAG, "Started async FFmpeg session: $sessionId")
        return sessionId
    }

    fun getMediaInformation(path: String): MediaInformation? {
        val command = "-i \"$path\" -f null -"
        val outputBuilder = StringBuilder()
        val errorBuilder = StringBuilder()
        val latch = java.util.concurrent.CountDownLatch(1)
        
        val executorCallback = object : FFmpegExecutor.ExecutorCallback {
            override fun onOutput(output: String) {
                outputBuilder.append(output).append("\n")
            }
            
            override fun onError(error: String) {
                errorBuilder.append(error).append("\n")
            }
            
            override fun onComplete(exitCode: Int) {
                latch.countDown()
            }
        }
        
        FFmpegNativeExecutor.executeFFmpeg(context, command, executorCallback)
        
        // Wait for completion with timeout
        try {
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Media information retrieval interrupted", e)
        }
        
        // Parse the output to extract media information
        return parseMediaInformation(outputBuilder.toString() + errorBuilder.toString())
    }

    suspend fun getMediaInformationAsync(path: String): MediaInformation? = 
        withContext(Dispatchers.IO) {
            getMediaInformation(path)
        }

    private fun parseMediaInformation(output: String): MediaInformation? {
        if (output.isEmpty()) return null
        
        Log.d(TAG, "Parsing media information from output:\n$output")
        
        val mediaInfo = MediaInformation()
        val lines = output.split("\n")
        
        for (line in lines) {
            when {
                line.contains("Duration:") -> {
                    val durationMatch = Regex("Duration: (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{2})").find(line)
                    durationMatch?.let {
                        val hours = it.groupValues[1].toLong()
                        val minutes = it.groupValues[2].toLong()
                        val seconds = it.groupValues[3].toLong()
                        val milliseconds = it.groupValues[4].toLong() * 10
                        mediaInfo.duration = (hours * 3600 + minutes * 60 + seconds) * 1000 + milliseconds
                        Log.d(TAG, "Found duration: ${mediaInfo.duration}ms")
                    }
                    
                    val bitrateMatch = Regex("bitrate: (\\d+) kb/s").find(line)
                    bitrateMatch?.let {
                        mediaInfo.bitrate = it.groupValues[1].toLong() * 1000
                        Log.d(TAG, "Found bitrate: ${mediaInfo.bitrate} bps")
                    }
                }
                
                line.contains("Stream") && line.contains("Video:") -> {
                    val stream = VideoStream()
                    
                    // Updated regex to handle codec with extra info like (High)
                    val codecMatch = Regex("Video: ([^\\s(,]+)").find(line)
                    codecMatch?.let { 
                        stream.codec = it.groupValues[1]
                        Log.d(TAG, "Found video codec: ${stream.codec}")
                    }
                    
                    val resolutionMatch = Regex("(\\d+)x(\\d+)").find(line)
                    resolutionMatch?.let {
                        stream.width = it.groupValues[1].toInt()
                        stream.height = it.groupValues[2].toInt()
                        Log.d(TAG, "Found resolution: ${stream.width}x${stream.height}")
                    }
                    
                    val fpsMatch = Regex("(\\d+\\.?\\d*) fps").find(line)
                    fpsMatch?.let { 
                        stream.frameRate = it.groupValues[1].toDouble()
                        Log.d(TAG, "Found frame rate: ${stream.frameRate} fps")
                    }
                    
                    mediaInfo.videoStreams.add(stream)
                }
                
                line.contains("Stream") && line.contains("Audio:") -> {
                    val stream = AudioStream()
                    
                    val codecMatch = Regex("Audio: ([^\\s(,]+)").find(line)
                    codecMatch?.let { 
                        stream.codec = it.groupValues[1]
                        Log.d(TAG, "Found audio codec: ${stream.codec}")
                    }
                    
                    val sampleRateMatch = Regex("(\\d+) Hz").find(line)
                    sampleRateMatch?.let { 
                        stream.sampleRate = it.groupValues[1].toInt()
                        Log.d(TAG, "Found sample rate: ${stream.sampleRate} Hz")
                    }
                    
                    val channelsMatch = Regex("(mono|stereo|\\d+\\.\\d+)").find(line)
                    channelsMatch?.let {
                        stream.channels = when(it.groupValues[1]) {
                            "mono" -> 1
                            "stereo" -> 2
                            else -> it.groupValues[1].replace(".", "").toIntOrNull() ?: 2
                        }
                        Log.d(TAG, "Found channels: ${stream.channels}")
                    }
                    
                    mediaInfo.audioStreams.add(stream)
                }
            }
        }
        
        Log.d(TAG, "Parsed media info - Video streams: ${mediaInfo.videoStreams.size}, Audio streams: ${mediaInfo.audioStreams.size}")
        
        return if (mediaInfo.videoStreams.isNotEmpty() || mediaInfo.audioStreams.isNotEmpty()) {
            mediaInfo
        } else {
            Log.w(TAG, "No media streams found in output")
            null
        }
    }

    fun isVideoFile(path: String): Boolean {
        val mediaInfo = getMediaInformation(path)
        return mediaInfo?.videoStreams?.isNotEmpty() == true
    }

    fun isAudioFile(path: String): Boolean {
        val mediaInfo = getMediaInformation(path)
        return mediaInfo?.audioStreams?.isNotEmpty() == true && 
               mediaInfo.videoStreams.isEmpty()
    }

    fun getVideoDuration(path: String): Long {
        return getMediaInformation(path)?.duration ?: 0L
    }

    fun getVideoResolution(path: String): Pair<Int, Int>? {
        val mediaInfo = getMediaInformation(path)
        val videoStream = mediaInfo?.videoStreams?.firstOrNull()
        return videoStream?.let {
            Pair(it.width, it.height)
        }
    }

    fun getVideoCodec(path: String): String? {
        return getMediaInformation(path)?.videoStreams?.firstOrNull()?.codec
    }

    fun getAudioCodec(path: String): String? {
        return getMediaInformation(path)?.audioStreams?.firstOrNull()?.codec
    }

    fun getBitrate(path: String): Long {
        return getMediaInformation(path)?.bitrate ?: 0L
    }

    fun getFrameRate(path: String): Double {
        return getMediaInformation(path)?.videoStreams?.firstOrNull()?.frameRate ?: 0.0
    }
}

data class MediaInformation(
    var duration: Long = 0,
    var bitrate: Long = 0,
    val videoStreams: MutableList<VideoStream> = mutableListOf(),
    val audioStreams: MutableList<AudioStream> = mutableListOf()
)

data class VideoStream(
    var codec: String = "",
    var width: Int = 0,
    var height: Int = 0,
    var frameRate: Double = 0.0
)

data class AudioStream(
    var codec: String = "",
    var sampleRate: Int = 0,
    var channels: Int = 0
)