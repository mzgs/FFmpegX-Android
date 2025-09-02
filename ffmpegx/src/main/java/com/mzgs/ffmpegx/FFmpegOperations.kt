package com.mzgs.ffmpegx

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FFmpegOperations(context: Context) {
    
    private val ffmpegHelper = FFmpegHelper(context)
    
    suspend fun compressVideo(
        inputPath: String,
        outputPath: String,
        quality: VideoQuality = VideoQuality.MEDIUM,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val builder = FFmpegCommandBuilder()
            .input(inputPath)
            .overwriteOutput()
        
        // Use mpeg4 encoder which is available in our build
        when (quality) {
            VideoQuality.LOW -> {
                builder.videoCodec("mpeg4")
                    .videoBitrate("500k")
                    .customOption("-qscale:v", "5")
            }
            VideoQuality.MEDIUM -> {
                builder.videoCodec("mpeg4")
                    .videoBitrate("1500k")
                    .customOption("-qscale:v", "3")
            }
            VideoQuality.HIGH -> {
                builder.videoCodec("mpeg4")
                    .videoBitrate("3000k")
                    .customOption("-qscale:v", "2")
            }
            VideoQuality.VERY_HIGH -> {
                builder.videoCodec("mpeg4")
                    .videoBitrate("5000k")
                    .customOption("-qscale:v", "1")
            }
        }
        
        // Use AAC audio codec
        builder.audioCodec("aac")
            .audioBitrate("128k")
            .output(outputPath)
        
        return ffmpegHelper.execute(builder.build(), callback)
    }
    
    suspend fun extractAudio(
        inputPath: String,
        outputPath: String,
        audioFormat: AudioFormat = AudioFormat.MP3,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val builder = FFmpegCommandBuilder()
            .input(inputPath)
            .overwriteOutput()
        
        // Use standard FFmpeg approach - let FFmpeg choose encoder based on output format
        when (audioFormat) {
            AudioFormat.MP3 -> {
                // Standard MP3 extraction: -q:a 0 for best quality, -map a to map audio only
                builder.customOption("-q:a", "0")
                    .customOption("-map", "a")
            }
            AudioFormat.AAC -> {
                builder.customOption("-map", "a")
                    .audioCodec("aac")
                    .audioBitrate("192k")
            }
            AudioFormat.WAV -> {
                builder.customOption("-map", "a")
                    .audioCodec("pcm_s16le")
            }
            else -> {
                // For other formats, map audio only
                builder.customOption("-map", "a")
            }
        }
        
        builder.output(outputPath)
        
        return ffmpegHelper.execute(builder.build(), callback)
    }
    
    suspend fun convertVideo(
        inputPath: String,
        outputPath: String,
        outputFormat: VideoFormat,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val command = FFmpegCommandBuilder()
            .input(inputPath)
            .overwriteOutput()
            .videoCodec(outputFormat.videoCodec)
            .audioCodec(outputFormat.audioCodec)
            .output(outputPath)
            .build()
        
        return ffmpegHelper.execute(command, callback)
    }
    
    suspend fun trimVideo(
        inputPath: String,
        outputPath: String,
        startTimeSeconds: Double,
        durationSeconds: Double,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val command = FFmpegCommandBuilder()
            .input(inputPath)
            .overwriteOutput()
            .startTime(startTimeSeconds)
            .duration(durationSeconds)
            .copyAllCodecs()
            .output(outputPath)
            .build()
        
        return ffmpegHelper.execute(command, callback)
    }
    
    suspend fun mergeVideos(
        videoPaths: List<String>,
        outputPath: String,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val listFile = File.createTempFile("ffmpeg_list", ".txt")
        try {
            listFile.writeText(videoPaths.joinToString("\n") { "file '$it'" })
            
            val command = FFmpegCommandBuilder()
                .customOption("-f", "concat")
                .customOption("-safe", "0")
                .input(listFile.absolutePath)
                .overwriteOutput()
                .copyAllCodecs()
                .output(outputPath)
                .build()
            
            ffmpegHelper.execute(command, callback)
        } finally {
            listFile.delete()
        }
    }
    
    suspend fun resizeVideo(
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int,
        maintainAspectRatio: Boolean = true,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val scaleFilter = if (maintainAspectRatio) {
            "scale=$width:$height:force_original_aspect_ratio=decrease,pad=$width:$height:(ow-iw)/2:(oh-ih)/2"
        } else {
            "scale=$width:$height"
        }
        
        val command = FFmpegCommandBuilder()
            .input(inputPath)
            .overwriteOutput()
            .videoFilter(scaleFilter)
            .videoCodec("libx264")
            .audioCodec("copy")
            .output(outputPath)
            .build()
        
        return ffmpegHelper.execute(command, callback)
    }
    
    suspend fun rotateVideo(
        inputPath: String,
        outputPath: String,
        degrees: Int,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val transposeValue = when (degrees) {
            90 -> "1"
            180 -> "2,transpose=2"
            270 -> "2"
            else -> return false
        }
        
        val command = FFmpegCommandBuilder()
            .input(inputPath)
            .overwriteOutput()
            .videoFilter("transpose=$transposeValue")
            .audioCodec("copy")
            .output(outputPath)
            .build()
        
        return ffmpegHelper.execute(command, callback)
    }
    
    suspend fun addWatermark(
        videoPath: String,
        watermarkPath: String,
        outputPath: String,
        position: WatermarkPosition = WatermarkPosition.BOTTOM_RIGHT,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val overlay = when (position) {
            WatermarkPosition.TOP_LEFT -> "10:10"
            WatermarkPosition.TOP_RIGHT -> "main_w-overlay_w-10:10"
            WatermarkPosition.BOTTOM_LEFT -> "10:main_h-overlay_h-10"
            WatermarkPosition.BOTTOM_RIGHT -> "main_w-overlay_w-10:main_h-overlay_h-10"
            WatermarkPosition.CENTER -> "(main_w-overlay_w)/2:(main_h-overlay_h)/2"
        }
        
        val command = FFmpegCommandBuilder()
            .input(videoPath)
            .input(watermarkPath)
            .overwriteOutput()
            .complexFilter("[0:v][1:v]overlay=$overlay")
            .audioCodec("copy")
            .output(outputPath)
            .build()
        
        return ffmpegHelper.execute(command, callback)
    }
    
    suspend fun extractFrames(
        videoPath: String,
        outputPattern: String,
        fps: Int = 1,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val command = FFmpegCommandBuilder()
            .input(videoPath)
            .videoFilter("fps=$fps")
            .output(outputPattern)
            .build()
        
        return ffmpegHelper.execute(command, callback)
    }
    
    suspend fun createGif(
        inputPath: String,
        outputPath: String,
        width: Int = 320,
        fps: Int = 10,
        startTime: Double? = null,
        duration: Double? = null,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val builder = FFmpegCommandBuilder()
        
        startTime?.let { builder.startTime(it) }
        duration?.let { builder.duration(it) }
        
        builder.input(inputPath)
            .videoFilter("fps=$fps,scale=$width:-1:flags=lanczos")
            .output(outputPath)
        
        return ffmpegHelper.execute(builder.build(), callback)
    }
    
    suspend fun changeVideoSpeed(
        inputPath: String,
        outputPath: String,
        speed: Float,
        adjustAudio: Boolean = true,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val videoFilter = "setpts=${1.0f/speed}*PTS"
        val audioFilter = if (adjustAudio) "atempo=$speed" else null
        
        val builder = FFmpegCommandBuilder()
            .input(inputPath)
            .overwriteOutput()
            .videoFilter(videoFilter)
        
        audioFilter?.let { builder.audioFilter(it) }
        
        builder.output(outputPath)
        
        return ffmpegHelper.execute(builder.build(), callback)
    }
    
    suspend fun addSubtitles(
        videoPath: String,
        subtitlePath: String,
        outputPath: String,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val command = FFmpegCommandBuilder()
            .input(videoPath)
            .input(subtitlePath)
            .overwriteOutput()
            .videoCodec("copy")
            .audioCodec("copy")
            .customOption("-c:s", "mov_text")
            .output(outputPath)
            .build()
        
        return ffmpegHelper.execute(command, callback)
    }
    
    suspend fun reverseVideo(
        inputPath: String,
        outputPath: String,
        reverseAudio: Boolean = true,
        callback: FFmpegHelper.FFmpegCallback? = null
    ): Boolean {
        val builder = FFmpegCommandBuilder()
            .input(inputPath)
            .overwriteOutput()
            .videoFilter("reverse")
        
        if (reverseAudio) {
            builder.audioFilter("areverse")
        }
        
        builder.output(outputPath)
        
        return ffmpegHelper.execute(builder.build(), callback)
    }
    
    enum class VideoQuality(val preset: String, val crf: Int) {
        LOW("ultrafast", 28),
        MEDIUM("medium", 23),
        HIGH("slow", 18),
        VERY_HIGH("veryslow", 15)
    }
    
    enum class AudioFormat(val codec: String, val bitrate: String, val extension: String) {
        MP3("libmp3lame", "192k", "mp3"),
        AAC("aac", "192k", "aac"),
        WAV("pcm_s16le", "1411k", "wav"),
        FLAC("flac", "1411k", "flac"),
        OGG("libvorbis", "192k", "ogg")
    }
    
    enum class VideoFormat(val videoCodec: String, val audioCodec: String, val extension: String) {
        MP4("libx264", "aac", "mp4"),
        AVI("mpeg4", "mp3", "avi"),
        MKV("libx264", "aac", "mkv"),
        MOV("libx264", "aac", "mov"),
        WEBM("libvpx", "libvorbis", "webm"),
        FLV("flv", "mp3", "flv")
    }
    
    enum class WatermarkPosition {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        CENTER
    }
}